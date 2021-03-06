package is.hail.annotations

import java.io.{ObjectInputStream, ObjectOutputStream}

import com.esotericsoftware.kryo.io.{Input, Output}
import com.esotericsoftware.kryo.{Kryo, KryoSerializable}
import is.hail.expr._
import is.hail.utils._
import is.hail.variant.{AltAllele, Genotype, Locus, Variant}
import org.apache.spark.sql.Row

import scala.collection.mutable

object MemoryBuffer {
  def apply(sizeHint: Long = 128): MemoryBuffer = {
    new MemoryBuffer(Memory.malloc(sizeHint), sizeHint)
  }
}

final class MemoryBuffer(var mem: Long, var length: Long, var offset: Long = 0) extends KryoSerializable with Serializable {
  def size: Long = offset

  def copyFrom(other: MemoryBuffer, readStart: Long, writeStart: Long, n: Long) {
    assert(size <= length)
    assert(other.size <= other.length)
    assert(n >= 0)
    assert(readStart >= 0 && readStart + n <= other.size)
    assert(writeStart >= 0 && writeStart + n <= size)
    Memory.memcpy(mem + writeStart, other.mem + readStart, n)
  }

  def loadInt(off: Long): Int = {
    assert(size <= length)
    assert(off >= 0 && off + 4 <= size, s"tried to read int at $off from region with size $size")
    Memory.loadInt(mem + off)
  }

  def loadLong(off: Long): Long = {
    assert(size <= length)
    assert(off >= 0 && off + 8 <= size, s"tried to read long at $off from region with size $size")
    Memory.loadLong(mem + off)
  }

  def loadFloat(off: Long): Float = {
    assert(size <= length)
    assert(off >= 0 && off + 4 <= size, s"tried to read float at $off from region with size $size")
    Memory.loadFloat(mem + off)
  }

  def loadDouble(off: Long): Double = {
    assert(size <= length)
    assert(off >= 0 && off + 8 <= size, s"tried to read double at $off from region with size $size")
    Memory.loadDouble(mem + off)
  }

  def loadAddress(off: Long): Long = {
    assert(size <= length)
    assert(off >= 0 && off + 8 <= size, s"tried to read double at $off from region with size $size")
    Memory.loadAddress(mem + off)
  }

  def loadByte(off: Long): Byte = {
    assert(size <= length)
    assert(off >= 0 && off + 1 <= size, s"tried to read byte at $off from region of size $size")
    Memory.loadByte(mem + off)
  }

  def loadBytes(off: Long, n: Int): Array[Byte] = {
    assert(size <= length)
    assert(off >= 0 && off + n <= size, s"tried to read bytes of size $n at $off from region of size $size")
    val a = new Array[Byte](n)
    Memory.copyToArray(a, 0, mem + off, n)
    a
  }

  def loadBytes(off: Long, n: Long, dst: Array[Byte]) {
    assert(size <= length)
    assert(off >= 0 && off + n <= size, s"tried to read bytes of size $n at $off from region of size $size")
    assert(n <= dst.length)
    Memory.copyToArray(dst, 0, mem + off, n)
  }

  def storeInt(off: Long, i: Int) {
    assert(size <= length)
    assert(off >= 0 && off + 4 <= size, s"tried to store int at $off to region of size $size")
    Memory.storeInt(mem + off, i)
  }

  def storeLong(off: Long, l: Long) {
    assert(size <= length)
    assert(off >= 0 && off + 8 <= size, s"tried to store long at $off to region of size $size")
    Memory.storeLong(mem + off, l)
  }

  def storeFloat(off: Long, f: Float) {
    assert(size <= length)
    assert(off >= 0 && off + 4 <= size, s"tried to store float at $off to region of size $size")
    Memory.storeFloat(mem + off, f)
  }

  def storeDouble(off: Long, d: Double) {
    assert(size <= length)
    assert(off >= 0 && off + 8 <= size, s"tried to store double at $off to region of size $size")
    Memory.storeDouble(mem + off, d)
  }

  def storeAddress(off: Long, a: Long) {
    assert(size <= length)
    assert(off >= 0 && off + 8 <= size, s"tried to store address at $off to region of size $size")
    Memory.storeAddress(mem + off, a)
  }

  def storeByte(off: Long, b: Byte) {
    assert(size <= length)
    assert(off >= 0 && off + 1 <= size, s"tried to store byte at $off to region of size $size")
    Memory.storeByte(mem + off, b)
  }

  def storeBytes(off: Long, bytes: Array[Byte]) {
    storeBytes(off, bytes, 0, bytes.length)
  }

  def storeBytes(off: Long, bytes: Array[Byte], bytesOff: Long, n: Int) {
    assert(size <= length)
    assert(off >= 0 && off + n <= size, s"tried to store $n bytes at $off to region of size $size")
    assert(bytesOff + n <= bytes.length)
    Memory.copyFromArray(mem + off, bytes, bytesOff, n)
  }

  def ensure(n: Long) {
    val required = size + n
    if (length < required) {
      val newLength = (length * 2).max(required)
      mem = Memory.realloc(mem, newLength)
      length = newLength
    }
  }

  def align(alignment: Long) {
    assert(alignment > 0, s"invalid alignment: $alignment")
    assert((alignment & (alignment - 1)) == 0, s"invalid alignment: $alignment") // power of 2
    offset = (offset + (alignment - 1)) & ~(alignment - 1)
  }

  def allocate(n: Long): Long = {
    assert(n >= 0)
    val off = offset
    ensure(n)
    offset += n
    off
  }

  def alignAndAllocate(n: Long): Long = {
    align(n)
    allocate(n)
  }

  def loadBoolean(off: Long): Boolean = {
    val b = loadByte(off)
    assert(b == 1 || b == 0, s"invalid boolean byte: $b")
    b == 1
  }

  def loadBit(byteOff: Long, bitOff: Long): Boolean = {
    val b = byteOff + (bitOff >> 3)
    (loadByte(b) & (1 << (bitOff & 7))) != 0
  }

  def setBit(byteOff: Long, bitOff: Long) {
    val b = byteOff + (bitOff >> 3)
    storeByte(b,
      (loadByte(b) | (1 << (bitOff & 7))).toByte)
  }

  def clearBit(byteOff: Long, bitOff: Long) {
    val b = byteOff + (bitOff >> 3)
    storeByte(b,
      (loadByte(b) & ~(1 << (bitOff & 7))).toByte)
  }

  def storeBit(byteOff: Long, bitOff: Long, b: Boolean) {
    if (b)
      setBit(byteOff, bitOff)
    else
      clearBit(byteOff, bitOff)
  }

  def appendInt(i: Int) {
    storeInt(alignAndAllocate(4), i)
  }

  def appendLong(l: Long) {
    storeLong(alignAndAllocate(8), l)
  }

  def appendFloat(f: Float) {
    storeFloat(alignAndAllocate(4), f)
  }

  def appendDouble(d: Double) {
    storeDouble(alignAndAllocate(8), d)
  }

  def appendByte(b: Byte) {
    storeByte(allocate(1), b)
  }

  def appendBytes(bytes: Array[Byte]) {
    storeBytes(allocate(bytes.length), bytes)
  }

  def appendBytes(bytes: Array[Byte], bytesOff: Long, n: Int) {
    assert(bytesOff + n <= bytes.length)
    storeBytes(allocate(n), bytes, bytesOff, n)
  }

  def clear() {
    offset = 0
  }

  def copy(): MemoryBuffer = {
    val newMem = Memory.malloc(offset)
    Memory.memcpy(newMem, mem, offset)
    new MemoryBuffer(newMem, offset, offset)
  }

  def result(): MemoryBuffer = copy()

  override def finalize() {
    Memory.free(mem)
  }

  override def write(kryo: Kryo, output: Output) {
    output.writeLong(offset)

    assert(offset <= Int.MaxValue)
    val smallOffset = offset.toInt
    val a = new Array[Byte](smallOffset)

    Memory.memcpy(a, 0, mem, offset)

    output.write(a)
  }

  override def read(kryo: Kryo, input: Input) {
    offset = input.readLong()
    assert(offset <= Int.MaxValue)
    val smallOffset = offset.toInt
    val inMem = new Array[Byte](smallOffset)
    input.read(inMem)

    mem = Memory.malloc(offset)
    length = offset

    Memory.memcpy(mem, inMem, 0, offset)
  }

  private def writeObject(out: ObjectOutputStream) {
    out.writeLong(offset)

    assert(offset <= Int.MaxValue)
    val smallOffset = offset.toInt
    val a = new Array[Byte](smallOffset)

    Memory.memcpy(a, 0, mem, offset)

    out.write(a)
  }

  private def readObject(in: ObjectInputStream) {
    offset = in.readLong()
    assert(offset <= Int.MaxValue)
    val smallOffset = offset.toInt
    val inMem = new Array[Byte](smallOffset)
    in.read(inMem)

    mem = Memory.malloc(offset)
    length = offset

    Memory.memcpy(mem, inMem, 0, offset)
  }

  def visit(t: Type, off: Long, v: ValueVisitor) {
    t match {
      case TBoolean => v.visitBoolean(loadBoolean(off))
      case TInt32 => v.visitInt32(loadInt(off))
      case TInt64 => v.visitInt64(loadLong(off))
      case TFloat32 => v.visitFloat32(loadFloat(off))
      case TFloat64 => v.visitFloat64(loadDouble(off))
      case TString =>
        val boff = loadAddress(off)
        v.visitString(TString.loadString(this, boff))
      case TBinary =>
        val boff = loadAddress(off)
        val length = TBinary.loadLength(this, boff)
        val b = loadBytes(TBinary.bytesOffset(boff), length)
        v.visitBinary(b)
      case t: TContainer =>
        val aoff = loadAddress(off)
        val length = t.loadLength(this, aoff)
        v.enterArray(t, length)
        var i = 0
        while (i < length) {
          v.enterElement(i)
          if (t.isElementDefined(this, aoff, i))
            visit(t.elementType, t.elementOffset(aoff, length, i), v)
          else
            v.visitMissing(t.elementType)
          i += 1
        }
        v.leaveArray()
      case t: TStruct =>
        v.enterStruct(t)
        var i = 0
        while (i < t.size) {
          val f = t.fields(i)
          v.enterField(f)
          if (t.isFieldDefined(this, off, i))
            visit(f.typ, t.fieldOffset(off, i), v)
          else
            v.visitMissing(f.typ)
          v.leaveField()
          i += 1
        }
        v.leaveStruct()
      case t: ComplexType =>
        visit(t.representation, off, v)
    }
  }

  def pretty(t: Type, off: Long): String = {
    val v = new PrettyVisitor()
    visit(t, off, v)
    v.result()
  }
}

trait ValueVisitor {
  def visitMissing(t: Type): Unit

  def visitBoolean(b: Boolean): Unit

  def visitInt32(i: Int): Unit

  def visitInt64(l: Long): Unit

  def visitFloat32(f: Float): Unit

  def visitFloat64(d: Double): Unit

  def visitString(s: String): Unit

  def visitBinary(b: Array[Byte]): Unit

  def enterStruct(t: TStruct): Unit

  def enterField(f: Field): Unit

  def leaveField(): Unit

  def leaveStruct(): Unit

  def enterArray(t: TContainer, length: Int): Unit

  def leaveArray(): Unit

  def enterElement(i: Int): Unit

  def leaveElement(): Unit
}

final class PrettyVisitor extends ValueVisitor {
  val sb = new StringBuilder()

  def result(): String = sb.result()

  def visitMissing(t: Type) { sb.append("NA") }

  def visitBoolean(b: Boolean) { sb.append(b) }

  def visitInt32(i: Int) { sb.append(i) }

  def visitInt64(l: Long) { sb.append(l) }

  def visitFloat32(f: Float) { sb.append(f) }

  def visitFloat64(d: Double) { sb.append(d) }

  def visitBinary(a: Array[Byte]) { sb.append("bytes...") }

  def visitString(s: String) { sb.append(s) }

  def enterStruct(t: TStruct) { sb.append("{") }

  def enterField(f: Field) {
    if (f.index > 0)
      sb.append(",")
    sb.append(" ")
    sb.append(f.name)
    sb.append(": ")
  }

  def leaveField() {}

  def leaveStruct() { sb.append(" }") }

  def enterArray(t: TContainer, length: Int) {
    t match {
      case t: TSet =>
        sb.append("Set")
      case t: TDict =>
        sb.append("Dict")
      case _ =>
    }
    sb.append("[")
    sb.append(length)
    sb.append(";")
  }

  def leaveArray() { sb.append("]") }

  def enterElement(i: Int) {
    if (i > 0)
      sb.append(",")
    sb.append(" ")
  }

  def leaveElement() {}
}

case class RegionValue(region: MemoryBuffer, offset: Long) {
  def pretty(t: Type): String = region.pretty(t, offset)
}

class RegionValueBuilder(region: MemoryBuffer) {
  var start: Long = _
  var root: TStruct = _

  val typestk = new ArrayStack[Type]()
  val indexstk = new ArrayStack[Int]()
  val offsetstk = new ArrayStack[Long]()
  val elementsOffsetstk = new ArrayStack[Long]()

  def current(): (Type, Long) = {
    if (typestk.isEmpty)
      (root, start)
    else {
      val i = indexstk.top
      typestk.top match {
        case t: TStruct =>
          (t.fields(i).typ, offsetstk.top + t.byteOffsets(i))

        case t: TArray =>
          (t.elementType, elementsOffsetstk.top + i * UnsafeUtils.arrayElementSize(t.elementType))
      }
    }
  }

  def start(newRoot: TStruct) {
    assert(typestk.isEmpty && offsetstk.isEmpty && elementsOffsetstk.isEmpty && indexstk.isEmpty)

    root = newRoot.fundamentalType
    start = root.allocate(region)
  }

  def end(): Long = {
    assert(typestk.isEmpty && offsetstk.isEmpty && elementsOffsetstk.isEmpty && indexstk.isEmpty)

    start
  }

  def advance() {
    if (indexstk.nonEmpty)
      indexstk.push(indexstk.pop + 1)
  }

  def startStruct(init: Boolean = true) {
    current() match {
      case (t: TStruct, off) =>
        typestk.push(t)
        offsetstk.push(off)
        indexstk.push(0)

        if (init) {
          val nMissingBytes = (t.size + 7) / 8
          var i = 0
          while (i < nMissingBytes) {
            region.storeByte(off + i, 0)
            i += 1
          }
        }
    }
  }

  def endStruct() {
    typestk.top match {
      case t: TStruct =>
        typestk.pop()
        offsetstk.pop()
        val last = indexstk.pop()
        assert(last == t.size)

        advance()
    }
  }

  def startArray(length: Int, init: Boolean = true) {
    current() match {
      case (t: TArray, off) =>
        region.align(t.contentsAlignment)
        val aoff = region.allocate(t.contentsByteSize(length))
        region.storeAddress(off, aoff)

        typestk.push(t)
        elementsOffsetstk.push(aoff + t.elementsOffset(length))
        indexstk.push(0)
        offsetstk.push(aoff)

        if (init) {
          region.storeInt(aoff, length)

          val nMissingBytes = (length + 7) / 8
          var i = 0
          while (i < nMissingBytes) {
            region.storeByte(aoff + 4 + i, 0)
            i += 1
          }
        }
    }
  }

  def endArray() {
    typestk.top match {
      case t: TArray =>
        val aoff = offsetstk.top
        val length = t.loadLength(region, aoff)
        assert(length == indexstk.top)

        typestk.pop()
        offsetstk.pop()
        elementsOffsetstk.pop()
        indexstk.pop()

        advance()
    }
  }

  def setMissing() {
    val i = indexstk.top
    typestk.top match {
      case t: TStruct =>
        region.setBit(offsetstk.top, i)
      case t: TArray =>
        region.setBit(offsetstk.top + 4, i)
    }

    advance()
  }

  def addBoolean(b: Boolean) {
    current() match {
      case (TBoolean, off) =>
        region.storeByte(off, b.toByte)
        advance()
    }
  }

  def addInt(i: Int) {
    current() match {
      case (TInt32, off) =>
        region.storeInt(off, i)
        advance()
    }
  }

  def addLong(l: Long) {
    current() match {
      case (TInt64, off) =>
        region.storeLong(off, l)
        advance()
    }
  }

  def addFloat(f: Float) {
    current() match {
      case (TFloat32, off) =>
        region.storeFloat(off, f)
        advance()
    }
  }

  def addDouble(d: Double) {
    current() match {
      case (TFloat64, off) =>
        region.storeDouble(off, d)
        advance()
    }
  }

  def addBinary(bytes: Array[Byte]) {
    current() match {
      case (TBinary, off) =>
        region.align(4)
        val boff = region.offset
        region.storeAddress(off, boff)

        region.appendInt(bytes.length)
        region.appendBytes(bytes)
        advance()
    }
  }

  def addString(s: String) {
    addBinary(s.getBytes)
  }

  def addRow(t: TStruct, r: Row) {
    assert(r != null)

    startStruct()
    var i = 0
    while (i < t.size) {
      addAnnotation(t.fields(i).typ, r.get(i))
      i += 1
    }
    endStruct()
  }

  def fixupBinary(fromRegion: MemoryBuffer, fromBOff: Long): Long = {
    val length = TBinary.loadLength(fromRegion, fromBOff)
    val toBOff = TBinary.allocate(region, length)
    region.copyFrom(fromRegion, fromBOff, toBOff, TBinary.contentByteSize(length))
    toBOff
  }

  def requiresFixup(t: Type): Boolean = {
    t match {
      case t: TStruct => t.fields.exists(f => requiresFixup(f.typ))
      case _: TArray | TBinary => true
      case _ => false
    }
  }

  def fixupArray(t: TArray, fromRegion: MemoryBuffer, fromAOff: Long): Long = {
    val length = t.loadLength(fromRegion, fromAOff)
    val toAOff = t.allocate(region, length)

    region.copyFrom(fromRegion, fromAOff, toAOff, t.contentsByteSize(length))

    if (requiresFixup(t.elementType)) {
      var i = 0
      while (i < length) {
        if (t.isElementDefined(fromRegion, fromAOff, i)) {
          t.elementType match {
            case t2: TStruct =>
              fixupStruct(t2, t.elementOffset(toAOff, length, i), fromRegion, t.elementOffset(fromAOff, length, i))

            case t2: TArray =>
              val toAOff2 = fixupArray(t2, fromRegion, t.loadElement(fromRegion, fromAOff, length, i))
              region.storeAddress(t.elementOffset(toAOff, length, i), toAOff2)

            case TBinary =>
              val toBOff = fixupBinary(fromRegion, t.loadElement(fromRegion, fromAOff, length, i))
              region.storeAddress(t.elementOffset(toAOff, length, i), toBOff)

            case _ =>
          }
        }
        i += 1
      }
    }

    toAOff
  }

  def fixupStruct(t: TStruct, toOff: Long, fromRegion: MemoryBuffer, fromOff: Long) {
    var i = 0
    while (i < t.size) {
      if (t.isFieldDefined(fromRegion, fromOff, i)) {
        t.fields(i).typ match {
          case t2: TStruct =>
            fixupStruct(t2, t.fieldOffset(toOff, i), fromRegion, t.fieldOffset(fromOff, i))

          case TBinary =>
            val toBOff = fixupBinary(fromRegion, t.loadField(fromRegion, fromOff, i))
            region.storeAddress(t.fieldOffset(toOff, i), toBOff)

          case t2: TArray =>
            val toAOff = fixupArray(t2, fromRegion, t.loadField(fromRegion, fromOff, i))
            region.storeAddress(t.fieldOffset(toOff, i), toAOff)

          case _ =>
        }
      }
      i += 1
    }
  }

  def addRegionValue(t: TStruct, fromRegion: MemoryBuffer, fromOff: Long) {
    val (toT, toOff) = current()
    assert(toT == t.fundamentalType)

    region.copyFrom(fromRegion, fromOff, toOff, t.byteSize)

    fixupStruct(t.fundamentalType, toOff, fromRegion, fromOff)
    advance()
  }

  def addUnsafeRow(t: TStruct, ur: UnsafeRow) {
    addRegionValue(t, ur.region, ur.offset)
  }

  def addUnsafeArray(t: TArray, uis: UnsafeIndexedSeqAnnotation) {
    val (toT, toOff) = current()
    assert(toT == t.fundamentalType)

    val toBOff = fixupArray(t.fundamentalType, uis.region, uis.aoff)
    region.storeAddress(toOff, toBOff)

    advance()
  }

  def addAnnotation(t: Type, a: Annotation) {
    if (a == null)
      setMissing()
    else
      t match {
        case TBoolean => addBoolean(a.asInstanceOf[Boolean])
        case TInt32 => addInt(a.asInstanceOf[Int])
        case TInt64 => addLong(a.asInstanceOf[Long])
        case TFloat32 => addFloat(a.asInstanceOf[Float])
        case TFloat64 => addDouble(a.asInstanceOf[Double])
        case TString => addString(a.asInstanceOf[String])
        case TBinary => addBinary(a.asInstanceOf[Array[Byte]])

        case t: TArray =>
          a match {
            case uis: UnsafeIndexedSeqAnnotation =>
              addUnsafeArray(t, uis)

            case is: IndexedSeq[Annotation] =>
              startArray(is.length)
              var i = 0
              while (i < is.length) {
                addAnnotation(t.elementType, is(i))
                i += 1
              }
              endArray()
          }

        case t: TStruct =>
          a match {
            case ur: UnsafeRow =>
              addUnsafeRow(t, ur)
            case r: Row =>
              addRow(t, r)
          }

        case TSet(elementType) =>
          val s = a.asInstanceOf[Set[Annotation]]
            .toArray
            .sorted(elementType.ordering(true))
          startArray(s.length)
          s.foreach { x => addAnnotation(elementType, x) }
          endArray()

        case td: TDict =>
          val m = a.asInstanceOf[Map[Annotation, Annotation]]
            .map { case (k, v) => Row(k, v) }
            .toArray
            .sorted(td.elementType.ordering(true))
          startArray(m.size)
          m.foreach { case Row(k, v) =>
            startStruct()
            addAnnotation(td.keyType, k)
            addAnnotation(td.valueType, v)
            endStruct()
          }
          endArray()

        case t: TVariant =>
          val v = a.asInstanceOf[Variant]
          startStruct()
          addString(v.contig)
          addInt(v.start)
          addString(v.ref)
          startArray(v.altAlleles.length)
          var i = 0
          while (i < v.altAlleles.length) {
            addAnnotation(TAltAllele, v.altAlleles(i))
            i += 1
          }
          endArray()
          endStruct()

        case TAltAllele =>
          val aa = a.asInstanceOf[AltAllele]
          startStruct()
          addString(aa.ref)
          addString(aa.alt)
          endStruct()

        case TCall =>
          addInt(a.asInstanceOf[Int])

        case TGenotype =>
          val g = a.asInstanceOf[Genotype]
          startStruct()

          val unboxedGT = g._unboxedGT
          if (unboxedGT >= 0)
            addInt(unboxedGT)
          else
            setMissing()

          val unboxedAD = g._unboxedAD
          if (unboxedAD == null)
            setMissing()
          else {
            startArray(unboxedAD.length)
            var i = 0
            while (i < unboxedAD.length) {
              addInt(unboxedAD(i))
              i += 1
            }
            endArray()
          }

          val unboxedDP = g._unboxedDP
          if (unboxedDP >= 0)
            addInt(unboxedDP)
          else
            setMissing()

          val unboxedGQ = g._unboxedGQ
          if (unboxedGQ >= 0)
            addInt(unboxedGQ)
          else
            setMissing()

          val unboxedPX = g._unboxedPX
          if (unboxedPX == null)
            setMissing()
          else {
            startArray(unboxedPX.length)
            var i = 0
            while (i < unboxedPX.length) {
              addInt(unboxedPX(i))
              i += 1
            }
            endArray()
          }

          addBoolean(g._fakeRef)
          addBoolean(g._isLinearScale)
          endStruct()

        case t: TLocus =>
          val l = a.asInstanceOf[Locus]
          startStruct()
          addString(l.contig)
          addInt(l.position)
          endStruct()

        case t: TInterval =>
          val i = a.asInstanceOf[Interval[Locus]]
          startStruct()
          addAnnotation(TLocus(t.gr), i.start)
          addAnnotation(TLocus(t.gr), i.end)
          endStruct()
      }
  }

  def result(): RegionValue = RegionValue(region, start)
}
