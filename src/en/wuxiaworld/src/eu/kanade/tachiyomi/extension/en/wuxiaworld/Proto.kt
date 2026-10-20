package eu.kanade.tachiyomi.extension.en.wuxiaworld

import java.io.ByteArrayOutputStream

/** Minimal protobuf writer for WuxiaWorld's gRPC-web requests. */
internal class ProtoWriter {
    private val out = ByteArrayOutputStream()

    private fun varint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    private fun tag(field: Int, wireType: Int) = varint(((field shl 3) or wireType).toLong())

    /** Negative int32 values are sign-extended to 64 bits, as protobuf expects. */
    fun int(field: Int, value: Int) = apply {
        tag(field, 0)
        varint(value.toLong())
    }

    fun string(field: Int, value: String) = bytes(field, value.toByteArray())

    fun message(field: Int, build: ProtoWriter.() -> Unit) = bytes(field, ProtoWriter().apply(build).toByteArray())

    private fun bytes(field: Int, value: ByteArray) = apply {
        tag(field, 2)
        varint(value.size.toLong())
        out.write(value)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Minimal protobuf reader. Proto3 omits false and zero values, so a missing field reads as 0 or false. */
internal class ProtoMessage(bytes: ByteArray) {
    private val fields = HashMap<Int, MutableList<Any>>()

    init {
        var i = 0
        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = bytes[i++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b < 0x80) return result
                shift += 7
            }
        }
        while (i < bytes.size) {
            val key = readVarint()
            val field = (key ushr 3).toInt()
            val value: Any = when ((key and 7).toInt()) {
                0 -> readVarint()
                1 -> bytes.copyOfRange(i, i + 8).also { i += 8 }
                2 -> {
                    val length = readVarint().toInt()
                    bytes.copyOfRange(i, i + length).also { i += length }
                }
                5 -> bytes.copyOfRange(i, i + 4).also { i += 4 }
                else -> error("Unsupported wire type in field $field")
            }
            fields.getOrPut(field) { mutableListOf() } += value
        }
    }

    fun long(field: Int): Long = fields[field]?.firstOrNull() as? Long ?: 0L

    fun bool(field: Int): Boolean = long(field) != 0L

    fun string(field: Int): String? = (fields[field]?.firstOrNull() as? ByteArray)?.decodeToString()

    fun strings(field: Int): List<String> = fields[field].orEmpty().mapNotNull { (it as? ByteArray)?.decodeToString() }

    fun message(field: Int): ProtoMessage? = (fields[field]?.firstOrNull() as? ByteArray)?.let(::ProtoMessage)

    fun messages(field: Int): List<ProtoMessage> = fields[field].orEmpty().mapNotNull { (it as? ByteArray)?.let(::ProtoMessage) }

    /** Value of a google.protobuf wrapper message (StringValue and friends) stored in [field]. */
    fun wrappedString(field: Int): String? = message(field)?.string(1)
}
