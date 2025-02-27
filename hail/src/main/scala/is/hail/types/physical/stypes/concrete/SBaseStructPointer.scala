package is.hail.types.physical.stypes.concrete

import is.hail.annotations.Region
import is.hail.asm4s._
import is.hail.expr.ir.{EmitCodeBuilder, IEmitCode}
import is.hail.types.physical.stypes.interfaces.{SBaseStruct, SBaseStructCode, SBaseStructSettable, SBaseStructValue}
import is.hail.types.physical.stypes.{EmitType, SCode, SType, SValue}
import is.hail.types.physical.{PBaseStruct, PType}
import is.hail.types.virtual.{TBaseStruct, Type}
import is.hail.utils.FastIndexedSeq


final case class SBaseStructPointer(pType: PBaseStruct) extends SBaseStruct {
  require(!pType.required)
  override def size: Int = pType.size

  override lazy val virtualType: TBaseStruct = pType.virtualType.asInstanceOf[TBaseStruct]

  override def castRename(t: Type): SType = SBaseStructPointer(pType.deepRename(t).asInstanceOf[PBaseStruct])

  override def fieldIdx(fieldName: String): Int = pType.fieldIdx(fieldName)

  override def _coerceOrCopy(cb: EmitCodeBuilder, region: Value[Region], value: SValue, deepCopy: Boolean): SValue =
    new SBaseStructPointerValue(this, pType.store(cb, region, value, deepCopy))

  override def settableTupleTypes(): IndexedSeq[TypeInfo[_]] = FastIndexedSeq(LongInfo)

  override def fromSettables(settables: IndexedSeq[Settable[_]]): SBaseStructPointerSettable = {
    val IndexedSeq(a: Settable[Long@unchecked]) = settables
    assert(a.ti == LongInfo)
    new SBaseStructPointerSettable(this, a)
  }

  override def fromValues(values: IndexedSeq[Value[_]]): SBaseStructPointerValue = {
    val IndexedSeq(a: Value[Long@unchecked]) = values
    assert(a.ti == LongInfo)
    new SBaseStructPointerValue(this, a)
  }

  def canonicalPType(): PType = pType

  override val fieldTypes: IndexedSeq[SType] = pType.types.map(_.sType)
  override val fieldEmitTypes: IndexedSeq[EmitType] = pType.types.map(t => EmitType(t.sType, t.required))

  override def containsPointers: Boolean = pType.containsPointers

  override def storageType(): PType = pType

  override def copiedType: SType = SBaseStructPointer(pType.copiedType.asInstanceOf[PBaseStruct])
}

class SBaseStructPointerValue(
  val st: SBaseStructPointer,
  val a: Value[Long]
) extends SBaseStructValue {
  val pt: PBaseStruct = st.pType

  override def get: SBaseStructPointerCode = new SBaseStructPointerCode(st, a)

  override lazy val valueTuple: IndexedSeq[Value[_]] = FastIndexedSeq(a)

  override def loadField(cb: EmitCodeBuilder, fieldIdx: Int): IEmitCode = {
    IEmitCode(cb,
      pt.isFieldMissing(cb, a, fieldIdx),
      pt.fields(fieldIdx).typ.loadCheapSCode(cb, pt.loadField(a, fieldIdx)))
  }

  override def isFieldMissing(cb: EmitCodeBuilder, fieldIdx: Int): Value[Boolean] = {
    pt.isFieldMissing(cb, a, fieldIdx)
  }
}

object SBaseStructPointerSettable {
  def apply(sb: SettableBuilder, st: SBaseStructPointer, name: String): SBaseStructPointerSettable = {
    new SBaseStructPointerSettable(st, sb.newSettable(name))
  }
}

final class SBaseStructPointerSettable(
  st: SBaseStructPointer,
  override val a: Settable[Long]
) extends SBaseStructPointerValue(st, a) with SBaseStructSettable {
  override def settableTuple(): IndexedSeq[Settable[_]] = FastIndexedSeq(a)

  override def store(cb: EmitCodeBuilder, pv: SCode): Unit = {
    cb.assign(a, pv.asInstanceOf[SBaseStructPointerCode].a)
  }
}

class SBaseStructPointerCode(val st: SBaseStructPointer, val a: Code[Long]) extends SBaseStructCode {
  val pt: PBaseStruct = st.pType

  def code: Code[_] = a

  def memoize(cb: EmitCodeBuilder, name: String, sb: SettableBuilder): SBaseStructPointerValue = {
    val s = SBaseStructPointerSettable(sb, st, name)
    s.store(cb, this)
    s
  }

  def memoize(cb: EmitCodeBuilder, name: String): SBaseStructPointerValue = memoize(cb, name, cb.localBuilder)

  def memoizeField(cb: EmitCodeBuilder, name: String): SBaseStructPointerValue = memoize(cb, name, cb.fieldBuilder)
}
