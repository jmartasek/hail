package is.hail.io

import java.io._
import is.hail.annotations._
import is.hail.asm4s._
import is.hail.backend.ExecuteContext
import is.hail.expr.ir.{EmitClassBuilder, EmitFunctionBuilder}
import is.hail.types.encoded._
import is.hail.types.physical._
import is.hail.types.virtual._

object TypedCodecSpec {
  def apply(pt: PType, bufferSpec: BufferSpec): TypedCodecSpec = {
    val eType = EType.defaultFromPType(pt)
    TypedCodecSpec(eType, pt.virtualType, bufferSpec)
  }
}

final case class TypedCodecSpec(_eType: EType, _vType: Type, _bufferSpec: BufferSpec) extends AbstractTypedCodecSpec {
  def encodedType: EType = _eType
  def encodedVirtualType: Type = _vType

  def buildEncoder(ctx: ExecuteContext, t: PType): (OutputStream) => Encoder = {
    val bufferToEncoder = encodedType.buildEncoder(ctx, t)
    out: OutputStream => bufferToEncoder(_bufferSpec.buildOutputBuffer(out))
  }

  def decodedPType(requestedType: Type): PType = {
    encodedType.decodedPType(requestedType)
  }

  def buildDecoder(ctx: ExecuteContext, requestedType: Type): (PType, (InputStream) => Decoder) = {
    val (rt, bufferToDecoder) = encodedType.buildDecoder(ctx, requestedType)
    (rt, (in: InputStream) => bufferToDecoder(_bufferSpec.buildInputBuffer(in)))
  }

  def buildStructDecoder(ctx: ExecuteContext, requestedType: TStruct): (PStruct, (InputStream) => Decoder) = {
    val (pType: PStruct, makeDec) = buildDecoder(ctx, requestedType)
    pType -> makeDec
  }

  def buildCodeInputBuffer(is: Code[InputStream]): Code[InputBuffer] = _bufferSpec.buildCodeInputBuffer(is)

  def buildCodeOutputBuffer(os: Code[OutputStream]): Code[OutputBuffer] = _bufferSpec.buildCodeOutputBuffer(os)
}
