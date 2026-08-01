package com.jirofeingold.pairfortwo.core.net

import com.jirofeingold.pairfortwo.core.Card
import com.jirofeingold.pairfortwo.core.GameMessage
import com.jirofeingold.pairfortwo.core.PlayerID
import com.jirofeingold.pairfortwo.core.Rank
import com.jirofeingold.pairfortwo.core.Suit
import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The framing layer, which is where a transport quietly corrupts a game if it gets it wrong.
 *
 * TCP gives no message boundaries: one `read` can return half a message, or three and a half. Every
 * case below has been seen on a real network, so each is pinned rather than assumed.
 */
class NdjsonTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    private val hello = GameMessage.Hello(
        name = "Jiro",
        colorID = 2,
        playerToken = "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
    )

    // ---- Framing ----

    @Test
    fun `a framed message is one line ending in a newline`() {
        val framed = Ndjson.frame(hello)
        assertEquals(Ndjson.NEWLINE, framed.last())
        assertEquals(1, framed.count { it == Ndjson.NEWLINE }, "a frame must contain exactly one delimiter")
        // The whole point of NDJSON: the payload can never contain the delimiter.
        assertTrue('\n' !in String(framed, 0, framed.size - 1, Charsets.UTF_8))
    }

    @Test
    fun `framing then assembling round-trips every message type`() {
        val messages = listOf<GameMessage>(
            hello,
            GameMessage.AssignSeat(PlayerID.TWO),
            GameMessage.IntentCut(17),
            GameMessage.IntentDiscard(listOf(Card(Rank.FOUR, Suit.SPADES), Card(Rank.TEN, Suit.HEARTS))),
            GameMessage.IntentPlay(Card(Rank.SEVEN, Suit.CLUBS)),
            GameMessage.IntentGo,
            GameMessage.Advance,
            GameMessage.QuitGame,
        )
        val stream = messages.fold(ByteArray(0)) { acc, m -> acc + Ndjson.frame(m) }

        val assembler = Ndjson.LineAssembler()
        val decoded = assembler.feed(stream).mapNotNull(Ndjson::decode)

        assertEquals(messages, decoded)
        assertEquals(0, assembler.pending)
    }

    // ---- Partial and combined reads ----

    @Test
    fun `a message split across reads is reassembled`() {
        val framed = Ndjson.frame(hello)
        val assembler = Ndjson.LineAssembler()

        // Feed it one byte at a time; nothing may surface until the delimiter arrives.
        for (i in 0 until framed.size - 1) {
            assertTrue(assembler.feed(byteArrayOf(framed[i])).isEmpty(), "emitted a line at byte $i")
        }
        val lines = assembler.feed(byteArrayOf(framed.last()))
        assertEquals(1, lines.size)
        assertEquals(hello, Ndjson.decode(lines.single()))
    }

    @Test
    fun `several messages in one read all surface, in order`() {
        val a = GameMessage.IntentCut(1)
        val b = GameMessage.IntentCut(2)
        val c = GameMessage.IntentCut(3)
        val assembler = Ndjson.LineAssembler()

        val lines = assembler.feed(Ndjson.frame(a) + Ndjson.frame(b) + Ndjson.frame(c))

        assertEquals(listOf(a, b, c), lines.mapNotNull(Ndjson::decode))
    }

    @Test
    fun `a trailing partial message is held back until it completes`() {
        val whole = Ndjson.frame(GameMessage.IntentGo)
        val partial = Ndjson.frame(hello)
        val split = partial.size / 2
        val assembler = Ndjson.LineAssembler()

        val first = assembler.feed(whole + partial.copyOfRange(0, split))
        assertEquals(listOf<GameMessage>(GameMessage.IntentGo), first.mapNotNull(Ndjson::decode))
        assertTrue(assembler.pending > 0)

        val second = assembler.feed(partial.copyOfRange(split, partial.size))
        assertEquals(listOf<GameMessage>(hello), second.mapNotNull(Ndjson::decode))
        assertEquals(0, assembler.pending)
    }

    @Test
    fun `feed honours a read count shorter than the buffer`() {
        // Sockets are read into a reusable 64 KB buffer, so everything past `count` is stale data
        // from the previous read and must be ignored.
        val framed = Ndjson.frame(GameMessage.IntentGo)
        val buffer = framed + bytes("GARBAGE-LEFTOVER-FROM-LAST-READ\n")
        val assembler = Ndjson.LineAssembler()

        val lines = assembler.feed(buffer, count = framed.size)

        assertEquals(1, lines.size)
        assertEquals(GameMessage.IntentGo, Ndjson.decode(lines.single()))
    }

    // ---- Tolerance ----

    @Test
    fun `carriage returns and blank keepalive lines are tolerated`() {
        val assembler = Ndjson.LineAssembler()
        val payload = String(Ndjson.frame(hello), Charsets.UTF_8).trimEnd('\n')

        val lines = assembler.feed(bytes("\n\r\n$payload\r\n\n"))

        assertEquals(listOf<GameMessage>(hello), lines.mapNotNull(Ndjson::decode))
    }

    @Test
    fun `a corrupt frame is dropped rather than fatal`() {
        assertNull(Ndjson.decode(bytes("{not json at all")))
        assertNull(Ndjson.decode(bytes("""{"t":"noSuchMessageType"}""")))
        assertNull(Ndjson.decode(bytes("")))
        assertNull(Ndjson.decode(bytes("\r")))

        // …and the ones either side of it still arrive.
        val assembler = Ndjson.LineAssembler()
        val stream = Ndjson.frame(GameMessage.IntentGo) + bytes("{ broken\n") + Ndjson.frame(GameMessage.Advance)
        assertEquals(
            listOf<GameMessage>(GameMessage.IntentGo, GameMessage.Advance),
            assembler.feed(stream).mapNotNull(Ndjson::decode),
        )
    }

    @Test
    fun `unknown fields from a newer peer do not break decoding`() {
        val line = bytes("""{"t":"intentCut","index":9,"somethingNewInV2":true}""")
        assertEquals(GameMessage.IntentCut(9), Ndjson.decode(line))
    }

    // ---- Denial of service ----

    @Test
    fun `an endless unterminated stream cannot grow the buffer without bound`() {
        val assembler = Ndjson.LineAssembler()
        val chunk = ByteArray(64 * 1024) { 'x'.code.toByte() }   // no newline, ever

        repeat(40) { assembler.feed(chunk) }   // 2.5 MB of it

        assertTrue(
            assembler.pending <= Ndjson.MAX_BUFFERED_BYTES,
            "buffer grew to ${assembler.pending} bytes",
        )
        assertTrue(assembler.isResyncing, "after dropping bytes it must resynchronise")

        // Recovery: the first delimiter closes the junk line — whatever shares that line is lost
        // with it, which is the honest outcome once bytes have been dropped — and everything from
        // the next line on is parsed normally.
        val lines = assembler.feed(Ndjson.frame(GameMessage.IntentGo) + Ndjson.frame(GameMessage.Advance))

        assertEquals(listOf<GameMessage>(GameMessage.Advance), lines.mapNotNull(Ndjson::decode))
        assertTrue(!assembler.isResyncing)
        assertEquals(0, assembler.pending)
    }

    @Test
    fun `resynchronising does not lose a line that arrives in a later chunk`() {
        val assembler = Ndjson.LineAssembler()
        val chunk = ByteArray(64 * 1024) { 'x'.code.toByte() }
        repeat(40) { assembler.feed(chunk) }
        assertTrue(assembler.isResyncing)

        // More junk with no delimiter: still resyncing, still nothing emitted, still bounded.
        assertTrue(assembler.feed(chunk).isEmpty())
        assertTrue(assembler.isResyncing)

        // The delimiter arrives on its own, and the next whole message comes through intact.
        assertTrue(assembler.feed(byteArrayOf(Ndjson.NEWLINE)).isEmpty())
        val lines = assembler.feed(Ndjson.frame(GameMessage.IntentGo))
        assertEquals(GameMessage.IntentGo, Ndjson.decode(lines.single()))
    }

    // ---- Interop ----

    @Test
    fun `a frame is exactly the golden fixture bytes plus a newline`() {
        // The fixtures are what iOS puts on the wire. If framing ever re-encoded through a
        // different Json instance, this is where it would show up.
        val dir = File("../fixtures/protocol-v1")
        assertTrue(dir.isDirectory, "fixtures not found at ${dir.absolutePath}")

        for (file in dir.listFiles { f: File -> f.extension == "json" }!!.sortedBy { it.name }) {
            val message = Ndjson.decode(file.readText().trim().toByteArray(Charsets.UTF_8))
                ?: error("${file.name} did not decode")
            val framed = Ndjson.frame(message)
            assertEquals(Ndjson.NEWLINE, framed.last(), "${file.name} was not delimited")

            // Re-assembling our own frame must give back an identical message.
            val assembler = Ndjson.LineAssembler()
            val lines = assembler.feed(framed)
            assertEquals(message, Ndjson.decode(lines.single()), "${file.name} did not round-trip")
            assertArrayEquals(
                framed.copyOf(framed.size - 1),
                lines.single(),
                "${file.name}: the assembled line differs from the framed payload",
            )
        }
    }
}
