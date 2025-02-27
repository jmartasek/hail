package is.hail.types.physical.stypes.concrete

import is.hail.annotations.Region
import is.hail.asm4s.{BooleanInfo, Code, LongInfo, Settable, SettableBuilder, TypeInfo, Value}
import is.hail.expr.ir.orderings.CodeOrdering
import is.hail.expr.ir.{EmitCodeBuilder, IEmitCode}
import is.hail.types.physical.stypes.interfaces.{SInterval, SIntervalCode, SIntervalValue}
import is.hail.types.physical.stypes._
import is.hail.types.physical.{PInterval, PType}
import is.hail.types.virtual.Type
import is.hail.utils.FastIndexedSeq


final case class SIntervalPointer(pType: PInterval) extends SInterval {
  require(!pType.required)

  override def _coerceOrCopy(cb: EmitCodeBuilder, region: Value[Region], value: SValue, deepCopy: Boolean): SValue =
    value match {
      case value: SIntervalValue =>
        new SIntervalPointerValue(this, pType.store(cb, region, value, deepCopy), value.includesStart(), value.includesEnd())
    }


  override def castRename(t: Type): SType = SIntervalPointer(pType.deepRename(t).asInstanceOf[PInterval])

  override lazy val virtualType: Type = pType.virtualType

  override def settableTupleTypes(): IndexedSeq[TypeInfo[_]] = FastIndexedSeq(LongInfo, BooleanInfo, BooleanInfo)

  override def fromSettables(settables: IndexedSeq[Settable[_]]): SIntervalPointerSettable = {
    val IndexedSeq(a: Settable[Long@unchecked], includesStart: Settable[Boolean@unchecked], includesEnd: Settable[Boolean@unchecked]) = settables
    assert(a.ti == LongInfo)
    assert(includesStart.ti == BooleanInfo)
    assert(includesEnd.ti == BooleanInfo)
    new SIntervalPointerSettable(this, a, includesStart, includesEnd)
  }

  override def fromValues(values: IndexedSeq[Value[_]]): SIntervalPointerValue = {
    val IndexedSeq(a: Value[Long@unchecked], includesStart: Value[Boolean@unchecked], includesEnd: Value[Boolean@unchecked]) = values
    assert(a.ti == LongInfo)
    assert(includesStart.ti == BooleanInfo)
    assert(includesEnd.ti == BooleanInfo)
    new SIntervalPointerValue(this, a, includesStart, includesEnd)
  }

  override def pointType: SType = pType.pointType.sType
  override def pointEmitType: EmitType = EmitType(pType.pointType.sType, pType.pointType.required)

  override def storageType(): PType = pType

  override def copiedType: SType = SIntervalPointer(pType.copiedType.asInstanceOf[PInterval])

  override def containsPointers: Boolean = pType.containsPointers
}

class SIntervalPointerValue(
  val st: SIntervalPointer,
  val a: Value[Long],
  val includesStart: Value[Boolean],
  val includesEnd: Value[Boolean]
) extends SIntervalValue {
  override def get: SIntervalPointerCode = new SIntervalPointerCode(st, a)

  override lazy val valueTuple: IndexedSeq[Value[_]] = FastIndexedSeq(a, includesStart, includesEnd)

  val pt: PInterval = st.pType

  override def loadStart(cb: EmitCodeBuilder): IEmitCode =
    IEmitCode(cb,
      !pt.startDefined(cb, a),
      pt.pointType.loadCheapSCode(cb, pt.loadStart(a)))

  override def startDefined(cb: EmitCodeBuilder): Value[Boolean] =
    pt.startDefined(cb, a)

  override def loadEnd(cb: EmitCodeBuilder): IEmitCode =
    IEmitCode(cb,
      !pt.endDefined(cb, a),
      pt.pointType.loadCheapSCode(cb, pt.loadEnd(a)))

  override def endDefined(cb: EmitCodeBuilder): Value[Boolean] =
    pt.endDefined(cb, a)

  override def isEmpty(cb: EmitCodeBuilder): Value[Boolean] = {
    val gt = cb.emb.ecb.getOrderingFunction(st.pointType, CodeOrdering.Gt())
    val gteq = cb.emb.ecb.getOrderingFunction(st.pointType, CodeOrdering.Gteq())

    val start = cb.memoize(loadStart(cb), "start")
    val end = cb.memoize(loadEnd(cb), "end")
    val empty = cb.newLocal("is_empty", includesStart)
    cb.ifx(empty,
      cb.ifx(includesEnd,
        cb.assign(empty, gt(cb, start, end)),
        cb.assign(empty, gteq(cb, start, end))))
    empty
  }
}

object SIntervalPointerSettable {
  def apply(sb: SettableBuilder, st: SIntervalPointer, name: String): SIntervalPointerSettable = {
    new SIntervalPointerSettable(st,
      sb.newSettable[Long](s"${ name }_a"),
      sb.newSettable[Boolean](s"${ name }_includes_start"),
      sb.newSettable[Boolean](s"${ name }_includes_end"))
  }
}

final class SIntervalPointerSettable(
  st: SIntervalPointer,
  override val a: Settable[Long],
  override val includesStart: Settable[Boolean],
  override val includesEnd: Settable[Boolean]
) extends SIntervalPointerValue(st, a, includesStart, includesEnd) with SSettable {
  override def settableTuple(): IndexedSeq[Settable[_]] = FastIndexedSeq(a, includesStart, includesEnd)

  override def store(cb: EmitCodeBuilder, pc: SCode): Unit = {
    cb.assign(a, pc.asInstanceOf[SIntervalPointerCode].a)
    cb.assign(includesStart, pt.includesStart(a.load()))
    cb.assign(includesEnd, pt.includesEnd(a.load()))
  }
}

class SIntervalPointerCode(val st: SIntervalPointer, val a: Code[Long]) extends SIntervalCode {
  val pt = st.pType

  def code: Code[_] = a

  def codeIncludesStart(): Code[Boolean] = pt.includesStart(a)

  def codeIncludesEnd(): Code[Boolean] = pt.includesEnd(a)

  def memoize(cb: EmitCodeBuilder, name: String, sb: SettableBuilder): SIntervalPointerValue = {
    val s = SIntervalPointerSettable(sb, st, name)
    s.store(cb, this)
    s
  }

  def memoize(cb: EmitCodeBuilder, name: String): SIntervalPointerValue = memoize(cb, name, cb.localBuilder)

  def memoizeField(cb: EmitCodeBuilder, name: String): SIntervalPointerValue = memoize(cb, name, cb.fieldBuilder)
}
