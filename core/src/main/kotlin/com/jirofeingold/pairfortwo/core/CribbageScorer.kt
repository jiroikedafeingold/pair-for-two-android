package com.jirofeingold.pairfortwo.core

/**
 * Port of the iOS app's `CribbageScorer.swift`.
 *
 * Pure functions, no Android dependencies. Detects every count; it never decides whether points
 * are *taken* — in [ScoringMode.FEEDBACK] and [ScoringMode.OFF] that stays manual.
 *
 * Faithfulness matters more than elegance here. The host is the sole referee (PLAN.md §0.3), so
 * the two platforms never score the same hand at the same moment — but each must score
 * *identically*, or a game would play differently depending on who hosts. `ScorerFixtureTest`
 * asserts that against several thousand hands scored by the Swift implementation, including the
 * `detail` strings, since those are shown to the player during the show.
 */
object CribbageScorer {

    // ---- Hand / crib scoring (the show) ----

    /**
     * Scores a 4-card [hand] (or the 4-card crib) together with the cut [starter].
     *
     * @param isCrib the crib may only score a 5-card flush (all five the same suit), never a
     *   4-card flush.
     */
    fun handScore(hand: List<Card>, starter: Card, isCrib: Boolean): List<ScoreFlag> {
        val all = hand + starter
        return buildList {
            addAll(fifteens(all))
            addAll(pairs(all))
            addAll(runs(all))
            addAll(flush(hand, starter, isCrib))
            addAll(nobs(hand, starter))
        }
    }

    /**
     * A human-readable breakdown of a hand/crib show score using proper cribbage terminology —
     * "double run", "pair royal", "run of five", etc. — one line per scoring element.
     *
     * The point totals match [handScore]; this is a presentation of the same count for the
     * "check my count" view.
     */
    fun handBreakdown(hand: List<Card>, starter: Card, isCrib: Boolean): List<ScoreFlag> {
        val all = hand + starter
        val flags = mutableListOf<ScoreFlag>()

        // Fifteens (each distinct subset summing to 15 is 2 points).
        val n15 = fifteens(all).size
        if (n15 > 0) {
            flags += ScoreFlag(
                ScoreFlag.Kind.FIFTEEN,
                points = n15 * 2,
                detail = if (n15 == 1) "Fifteen" else "${numberWord(n15).capitalizedFirst()} fifteens",
            )
        }

        // Runs, folding any in-run duplicates into the run's name (double/triple/double-double run).
        val counts = all.groupingBy { it.orderValue }.eachCount()
        val distinct = counts.keys.sorted()
        val consumedByRun = mutableSetOf<Int>()
        var i = 0
        while (i < distinct.size) {
            var end = i
            while (end + 1 < distinct.size && distinct[end + 1] == distinct[end] + 1) end += 1
            val length = end - i + 1
            if (length >= 3) {
                val blockRanks = distinct.subList(i, end + 1)
                val multiplicity = blockRanks.fold(1) { acc, r -> acc * (counts[r] ?: 1) }
                val inRunPairPts = blockRanks.sumOf { pairPoints(counts[it] ?: 1) }
                flags += ScoreFlag(
                    ScoreFlag.Kind.RUN,
                    points = multiplicity * length + inRunPairPts,
                    detail = runName(multiplicity = multiplicity, length = length),
                )
                consumedByRun += blockRanks
            }
            i = end + 1
        }

        // Pairs / trips / quads for ranks not already folded into a run.
        for (rank in distinct) {
            if (rank in consumedByRun) continue
            val c = counts[rank] ?: 0
            if (c >= 2) flags += ScoreFlag(ScoreFlag.Kind.PAIR, points = pairPoints(c), detail = pairName(c))
        }

        flags += flush(hand, starter, isCrib)
        flags += nobs(hand, starter)
        return flags
    }

    private fun pairPoints(count: Int): Int = when (count) {
        2 -> 2
        3 -> 6
        4 -> 12
        else -> 0
    }

    private fun pairName(count: Int): String = when (count) {
        2 -> "Pair"
        3 -> "Pair royal"
        4 -> "Double pair royal"
        else -> "Pair"
    }

    private fun runName(multiplicity: Int, length: Int): String = when (multiplicity) {
        1 -> "Run of ${numberWord(length)}"
        2 -> if (length == 3) "Double run" else "Double run of ${numberWord(length)}"
        3 -> if (length == 3) "Triple run" else "Triple run of ${numberWord(length)}"
        else -> if (length == 3) "Double double run" else "Double double run of ${numberWord(length)}"
    }

    private val numberWords =
        listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")

    private fun numberWord(n: Int): String =
        if (n >= 0 && n < numberWords.size) numberWords[n] else n.toString()

    /** Matches Swift's `String.capitalized` for these single lowercase words. */
    private fun String.capitalizedFirst(): String = replaceFirstChar(Char::uppercaseChar)

    // ---- Pegging scoring (the play) ----

    /**
     * Scores the card just laid onto the pegging pile. [pile] is the current run of play since
     * the last reset (a go or a 31) and **includes** [justPlayed] as its final element.
     *
     * Detects fifteens, thirty-ones, pairs/trips/quads, and runs. `go` and `lastCard` depend on
     * turn/hand context and are emitted by the engine, not here.
     */
    fun peggingScore(pile: List<Card>, justPlayed: Card): List<ScoreFlag> {
        val flags = mutableListOf<ScoreFlag>()
        val count = pile.sumOf { it.countingValue }

        if (count == 15) flags += ScoreFlag(ScoreFlag.Kind.FIFTEEN, points = 2, detail = "Fifteen 2")
        if (count == 31) flags += ScoreFlag(ScoreFlag.Kind.THIRTY_ONE, points = 2, detail = "31 for 2")

        // Pairs: how many trailing cards share the rank of the card just played.
        var sameRank = 0
        for (card in pile.asReversed()) {
            if (card.rank == justPlayed.rank) sameRank += 1 else break
        }
        when (sameRank) {
            4 -> flags += ScoreFlag(ScoreFlag.Kind.PAIR, points = 12, detail = "Double pair royal")
            3 -> flags += ScoreFlag(ScoreFlag.Kind.PAIR, points = 6, detail = "Pair royal")
            2 -> flags += ScoreFlag(ScoreFlag.Kind.PAIR, points = 2, detail = "Pair")
        }

        // Runs: the longest trailing window (length >= 3) that forms consecutive distinct ranks.
        // Order within the window does not matter in cribbage pegging.
        var runLength = 0
        var window = pile.size
        while (window >= 3) {
            val tail = pile.takeLast(window)
            val values = tail.map { it.orderValue }.toSet()
            if (values.size == tail.size && values.max() - values.min() == tail.size - 1) {
                runLength = window
                break
            }
            window -= 1
        }
        if (runLength >= 3) {
            flags += ScoreFlag(ScoreFlag.Kind.RUN, points = runLength, detail = "Run of $runLength")
        }

        return flags
    }

    // ---- Legality helpers ----

    /**
     * Cards from [hand] that may legally be played on a pile at the given running [count]
     * (i.e. would not push the count past 31).
     */
    fun legalPlays(hand: List<Card>, count: Int): List<Card> =
        hand.filter { count + it.countingValue <= 31 }

    /** A player must say "go" when they hold cards but none can be played without exceeding 31. */
    fun mustSayGo(hand: List<Card>, count: Int): Boolean =
        hand.isNotEmpty() && legalPlays(hand, count).isEmpty()

    /** "His heels" / "his nibs": if the cut starter is a Jack, the dealer pegs 2 immediately. */
    fun isHisHeels(starter: Card): Boolean = starter.rank == Rank.JACK

    // ---- Private scoring primitives ----

    /** One flag per distinct subset of cards summing to 15 (2 points each). */
    private fun fifteens(cards: List<Card>): List<ScoreFlag> {
        val flags = mutableListOf<ScoreFlag>()
        val values = cards.map { it.countingValue }
        val n = values.size
        // Enumerate every non-empty subset via a bitmask.
        for (mask in 1 until (1 shl n)) {
            var sum = 0
            for (i in 0 until n) {
                if (mask and (1 shl i) != 0) sum += values[i]
            }
            if (sum == 15) flags += ScoreFlag(ScoreFlag.Kind.FIFTEEN, points = 2, detail = "Fifteen 2")
        }
        return flags
    }

    /**
     * One flag per unordered pair of same-rank cards (2 points each). Naturally yields 2/6/12 for
     * pairs / trips / quads.
     */
    private fun pairs(cards: List<Card>): List<ScoreFlag> {
        val flags = mutableListOf<ScoreFlag>()
        for (i in cards.indices) {
            for (j in (i + 1) until cards.size) {
                if (cards[i].rank == cards[j].rank) {
                    flags += ScoreFlag(ScoreFlag.Kind.PAIR, points = 2, detail = "Pair")
                }
            }
        }
        return flags
    }

    /**
     * Runs in the show: find each maximal block of consecutive ranks (length >= 3) and emit one
     * `run` flag per distinct run instance (accounting for duplicate ranks — double/triple runs).
     */
    private fun runs(cards: List<Card>): List<ScoreFlag> {
        val counts = cards.groupingBy { it.orderValue }.eachCount()
        val distinct = counts.keys.sorted()

        val flags = mutableListOf<ScoreFlag>()
        var index = 0
        while (index < distinct.size) {
            // Extend a consecutive block.
            var end = index
            while (end + 1 < distinct.size && distinct[end + 1] == distinct[end] + 1) end += 1
            val blockLength = end - index + 1
            if (blockLength >= 3) {
                // Multiplicity = product of the per-rank counts in the block.
                val multiplicity = (index..end).fold(1) { acc, k -> acc * (counts[distinct[k]] ?: 1) }
                repeat(multiplicity) {
                    flags += ScoreFlag(ScoreFlag.Kind.RUN, points = blockLength, detail = "Run of $blockLength")
                }
            }
            index = end + 1
        }
        return flags
    }

    /**
     * Flush: 4 matching hand cards score 4 (5 if the starter matches too). The crib scores only a
     * full 5-card flush.
     */
    private fun flush(hand: List<Card>, starter: Card, isCrib: Boolean): List<ScoreFlag> {
        val suit = hand.firstOrNull()?.suit ?: return emptyList()
        if (!hand.all { it.suit == suit }) return emptyList()
        if (starter.suit == suit) {
            return listOf(ScoreFlag(ScoreFlag.Kind.FLUSH, points = 5, detail = "Flush of 5"))
        }
        if (isCrib) return emptyList() // no 4-card flush in the crib
        return listOf(ScoreFlag(ScoreFlag.Kind.FLUSH, points = 4, detail = "Flush of 4"))
    }

    /** His Nobs: a Jack held in hand whose suit matches the starter scores 1. */
    private fun nobs(hand: List<Card>, starter: Card): List<ScoreFlag> =
        if (hand.any { it.rank == Rank.JACK && it.suit == starter.suit }) {
            listOf(ScoreFlag(ScoreFlag.Kind.NOBS, points = 1, detail = "His Nobs"))
        } else {
            emptyList()
        }
}
