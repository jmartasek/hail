package is.hail.types.physical.stypes.interfaces

import is.hail.asm4s.Code.invokeStatic1
import is.hail.asm4s.{Code, Value}
import is.hail.expr.ir.EmitCodeBuilder
import is.hail.types.physical.stypes.primitives.SInt32Value
import is.hail.types.physical.stypes.{SCode, SType, SValue}
import is.hail.types.{RPrimitive, TypeWithRequiredness}

trait SBinary extends SType {
  override def _typeWithRequiredness: TypeWithRequiredness = RPrimitive()
}

trait SBinaryValue extends SValue {
  override def get: SBinaryCode

  def loadLength(cb: EmitCodeBuilder): Value[Int]

  def loadBytes(cb: EmitCodeBuilder): Value[Array[Byte]]

  def loadByte(cb: EmitCodeBuilder, i: Code[Int]): Value[Byte]

  override def hash(cb: EmitCodeBuilder): SInt32Value =
    new SInt32Value(cb.memoize(invokeStatic1[java.util.Arrays, Array[Byte], Int]("hashCode", loadBytes(cb))))
}

trait SBinaryCode extends SCode {
  def loadLength(): Code[Int]

  def loadBytes(): Code[Array[Byte]]

  def memoize(cb: EmitCodeBuilder, name: String): SBinaryValue

  def memoizeField(cb: EmitCodeBuilder, name: String): SBinaryValue
}

