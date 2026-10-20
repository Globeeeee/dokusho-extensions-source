package eu.kanade.tachiyomi.extension.en.wuxiaworld

import java.io.ByteArrayOutputStream
import java.io.IOException

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

/**
 * Minimal protobuf reader. Proto3 omits false and zero values, so a missing field reads as 0 or false.
 * Malformed input (truncated data, over-long varints, bad lengths or wire types) throws an [IOException].
 */
internal class ProtoMessage(bytes: ByteArray) {
    private val fields = HashMap<Int, MutableList<Any>>()

    init {
        var i = 0
        fun malformed(reason: String): Nothing = throw IOException("Malformed WuxiaWorld response: $reason")
        fun readVarint(): Long {
            var result = 0L
            for (shift in 0 until 64 step 7) {
                if (i >= bytes.size) malformed("truncated varint")
                val b = bytes[i++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b < 0x80) return result
            }
            malformed("varint longer than 10 bytes")
        }
        fun take(length: Long): ByteArray {
            if (length < 0 || length > bytes.size - i) malformed("length $length out of bounds")
            val end = i + length.toInt()
            return bytes.copyOfRange(i, end).also { i = end }
        }
        while (i < bytes.size) {
            val key = readVarint()
            val field = key ushr 3
            if (field < 1 || field > MAX_FIELD) malformed("field number $field")
            val value: Any = when ((key and 7).toInt()) {
                0 -> readVarint()
                1 -> take(8)
                2 -> take(readVarint())
                5 -> take(4)
                else -> malformed("unsupported wire type in field $field")
            }
            fields.getOrPut(field.toInt()) { mutableListOf() } += value
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

    private companion object {
        const val MAX_FIELD = (1L shl 29) - 1
    }
}
