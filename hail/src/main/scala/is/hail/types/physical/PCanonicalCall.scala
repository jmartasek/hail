package is.hail.types.physical

import is.hail.annotations.{Annotation, Region, UnsafeOrdering}
import is.hail.asm4s._
import is.hail.expr.ir.orderings.CodeOrdering
import is.hail.expr.ir.{EmitCodeBuilder, EmitMethodBuilder}
import is.hail.types.physical.stypes.{SCode, SValue}
import is.hail.types.physical.stypes.concrete.{SCanonicalCall, SCanonicalCallCode, SCanonicalCallValue}
import is.hail.types.physical.stypes.interfaces.SCall
import is.hail.utils._

final case class PCanonicalCall(required: Boolean = false) extends PCall {
  def _asIdent = "call"

  override def copiedType: PType = this

  override def _pretty(sb: StringBuilder, indent: Int, compact: Boolean): Unit = sb.append("PCCall")

  val representation: PInt32 = PInt32(required)

  def byteSize: Long = representation.byteSize
  override def alignment: Long = representation.alignment

  override def unsafeOrdering(): UnsafeOrdering = representation.unsafeOrdering() // this was a terrible idea

  def setRequired(required: Boolean) = if (required == this.required) this else PCanonicalCall(required)

  override def unstagedStoreAtAddress(addr: Long, region: Region, srcPType: PType, srcAddress: Long, deepCopy: Boolean): Unit = {
    srcPType match {
      case pt: PCanonicalCall =>
        representation.unstagedStoreAtAddress(addr, region, pt.representation, srcAddress, deepCopy)
    }
  }

  override def containsPointers: Boolean = representation.containsPointers

  def _copyFromAddress(region: Region, srcPType: PType, srcAddress: Long, deepCopy: Boolean): Long = {
    srcPType match {
      case pt: PCanonicalCall => representation._copyFromAddress(region, pt.representation, srcAddress, deepCopy)
    }
  }

  def sType: SCall = SCanonicalCall

  def loadCheapSCode(cb: EmitCodeBuilder, addr: Code[Long]): SCanonicalCallValue =
    new SCanonicalCallCode(Region.loadInt(addr)).memoize(cb, "loadCheapSCode")

  def loadCheapSCodeField(cb: EmitCodeBuilder, addr: Code[Long]): SCanonicalCallValue =
    new SCanonicalCallCode(Region.loadInt(addr)).memoizeField(cb, "loadCheapSCodeField")

  def store(cb: EmitCodeBuilder, region: Value[Region], value: SValue, deepCopy: Boolean): Value[Long] = {
    value.st match {
      case SCanonicalCall =>
        val newAddr = cb.memoize(region.allocate(representation.alignment, representation.byteSize))
        storeAtAddress(cb, newAddr, region, value, deepCopy)
        newAddr
    }
  }

  def storeAtAddress(cb: EmitCodeBuilder, addr: Code[Long], region: Value[Region], value: SValue, deepCopy: Boolean): Unit = {
    cb += Region.storeInt(addr, value.asCall.canonicalCall(cb))
  }

  def loadFromNested(addr: Code[Long]): Code[Long] = representation.loadFromNested(addr)

  override def unstagedLoadFromNested(addr: Long): Long = representation.unstagedLoadFromNested(addr)

  override def unstagedStoreJavaObject(annotation: Annotation, region: Region): Long = {
    representation.unstagedStoreJavaObject(annotation, region)
  }

  override def unstagedStoreJavaObjectAtAddress(addr: Long, annotation: Annotation, region: Region): Unit = {
    representation.unstagedStoreJavaObjectAtAddress(addr, annotation, region)
  }
}
