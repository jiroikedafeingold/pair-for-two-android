package com.jirofeingold.pairfortwo.core.net

import com.jirofeingold.pairfortwo.core.GameMessage
import com.jirofeingold.pairfortwo.core.PairWireJson

/**
 * Newline-delimited JSON framing — the wire format both apps speak over TCP (PROTOCOL.md).
 *
 * One message per line. Encoded JSON never contains a raw newline, so this needs no length prefix,
 * has no endianness to argue about, and can be watched with `nc` while debugging a live game.
 */
object Ndjson {

    const val NEWLINE: Byte = '\n'.code.toByte()
    private const val CARRIAGE_RETURN: Byte = '\r'.code.toByte()

    /**
     * A peer that opens a socket and then streams bytes without ever sending a newline must not be
     * able to grow our buffer without bound. iOS uses the same 1 MB ceiling; a real message is a
     * couple of kilobytes at most, so anything approaching this is either broken or hostile.
     */
    const val MAX_BUFFERED_BYTES = 1 shl 20

    /** Encodes [message] as protocol v1 and appends the delimiter. */
    fun frame(message: GameMessage): ByteArray =
        (PairWireJson.encodeToString(GameMessage.serializer(), message) + "\n").toByteArray(Charsets.UTF_8)

    /**
     * Reassembles whole lines from a TCP stream that splits wherever it likes.
     *
     * A single `read` can return half a message, three messages, or three and a half — this is the
     * piece that makes that invisible to everything above it. Not thread-safe; it belongs to one
     * connection's reader.
     */
    class LineAssembler {
        private var buffer = ByteArray(0)
        private var resyncing = false

        /** Feeds [count] bytes from [chunk] and returns every complete line it now holds. */
        fun feed(chunk: ByteArray, count: Int = chunk.size): List<ByteArray> {
            var incoming = chunk.copyOf(count)

            // Having dropped bytes, we no longer know where the current line began, so anything up
            // to the next delimiter is the tail of a line we can't parse anyway. Discarding it
            // deliberately beats handing up a "line" that is half junk and half a real message.
            if (resyncing) {
                val nl = incoming.indexOfFrom(NEWLINE, 0)
                if (nl < 0) return emptyList()
                incoming = incoming.copyOfRange(nl + 1, incoming.size)
                resyncing = false
            }

            buffer += incoming
            val lines = mutableListOf<ByteArray>()
            var start = 0
            while (true) {
                val idx = buffer.indexOfFrom(NEWLINE, start)
                if (idx < 0) break
                lines += buffer.copyOfRange(start, idx)
                start = idx + 1
            }
            buffer = if (start == 0) buffer else buffer.copyOfRange(start, buffer.size)

            // Only an *unterminated* run can be discarded — dropping the tail after emitting the
            // complete lines above keeps a legitimate burst intact.
            if (buffer.size > MAX_BUFFERED_BYTES) {
                buffer = ByteArray(0)
                resyncing = true
            }
            return lines
        }

        /** Bytes held back waiting for a delimiter. Exposed for tests and diagnostics. */
        val pending: Int get() = buffer.size

        /** True while discarding to the next delimiter after an over-long line was dropped. */
        val isResyncing: Boolean get() = resyncing

        fun reset() {
            buffer = ByteArray(0)
            resyncing = false
        }

        private fun ByteArray.indexOfFrom(byte: Byte, from: Int): Int {
            for (i in from until size) if (this[i] == byte) return i
            return -1
        }
    }

    /**
     * Decodes one assembled line, or null if it carries nothing usable.
     *
     * Tolerates `\r\n` and blank keepalive lines, and treats a corrupt frame as droppable rather
     * than fatal: one unparseable message must not end a game that is otherwise fine.
     */
    fun decode(line: ByteArray): GameMessage? {
        var end = line.size
        if (end > 0 && line[end - 1] == CARRIAGE_RETURN) end -= 1
        if (end == 0) return null
        return try {
            PairWireJson.decodeFromString(GameMessage.serializer(), String(line, 0, end, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }
}
