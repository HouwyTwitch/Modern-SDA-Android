package com.houwytwitch.modernsda.domain.steam

import java.io.ByteArrayOutputStream

/**
 * Minimal protobuf encoder/decoder for Steam IAuthenticationService API messages.
 */
object ProtoUtils {

    // ── Encoding ────────────────────────────────────────────────────────────

    fun encodeVarint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7F).toInt())
        return out.toByteArray()
    }

    private fun tag(fieldNum: Int, wireType: Int): ByteArray =
        encodeVarint(((fieldNum.toLong() shl 3) or wireType.toLong()))

    fun encodeString(fieldNum: Int, value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return concat(tag(fieldNum, 2), encodeVarint(bytes.size.toLong()), bytes)
    }

    fun encodeBytes(fieldNum: Int, value: ByteArray): ByteArray =
        concat(tag(fieldNum, 2), encodeVarint(value.size.toLong()), value)

    fun encodeVarintField(fieldNum: Int, value: Long): ByteArray =
        concat(tag(fieldNum, 0), encodeVarint(value))

    /** Encode a fixed64 field (wire type 1): 8 bytes little-endian. Used for proto `fixed64` fields. */
    fun encodeFixed64(fieldNum: Int, value: Long): ByteArray {
        val bytes = ByteArray(8)
        var v = value
        for (i in 0..7) { bytes[i] = (v and 0xFF).toByte(); v = v ushr 8 }
        return concat(tag(fieldNum, 1), bytes)
    }

    fun encodeMessage(fieldNum: Int, content: ByteArray): ByteArray =
        encodeBytes(fieldNum, content)

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (p in parts) out.write(p)
        return out.toByteArray()
    }

    // ── Decoding ─────────────────────────────────────────────────────────────

    class Reader(private val data: ByteArray) {
        private var pos = 0

        fun hasMore(): Boolean = pos < data.size

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (pos < data.size) {
                val b = data[pos++].toLong() and 0xFF
                result = result or ((b and 0x7F) shl shift)
                if ((b and 0x80) == 0L) break
                shift += 7
            }
            return result
        }

        fun readBytes(): ByteArray {
            val len = readVarint().toInt()
            val result = data.copyOfRange(pos, pos + len)
            pos += len
            return result
        }

        fun readString(): String = readBytes().toString(Charsets.UTF_8)

        /** Returns (fieldNumber, wireType) or null at end. */
        fun nextTag(): Pair<Int, Int>? {
            if (!hasMore()) return null
            val raw = readVarint()
            return Pair((raw ushr 3).toInt(), (raw and 7L).toInt())
        }

        fun skip(wireType: Int) {
            when (wireType) {
                0 -> readVarint()
                1 -> { pos += 8 }
                2 -> readBytes()
                5 -> { pos += 4 }
            }
        }
    }
}
