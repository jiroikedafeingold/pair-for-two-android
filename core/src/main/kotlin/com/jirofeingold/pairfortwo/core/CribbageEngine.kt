package com.jirofeingold.pairfortwo.core

/**
 * The host-authoritative referee — port of the iOS `CribbageEngine`.
 *
 * Pure: it validates *intents* and mutates the canonical [GameState], advancing phases. It never
 * auto-applies points unless the game is in [ScoringMode.AUTO]; otherwise scoring stays manual
 * (flag-only) and it merely *surfaces* every scoring opportunity in [GameState.activeFlags].
 *
 * Every handler returns `false` for an illegal or out-of-turn intent — the host simply ignores it
 * — and `true` when the intent was applied. That return value is part of the contract and is
 * asserted by `EngineFixtureTest` against the iOS engine's answers, because "silently ignored" and
 * "applied" are the difference between the two devices agreeing on whose turn it is.
 */
object CribbageEngine {

    // ---- Lifecycle ----

    /** Move from [GamePhase.CONNECTING] into the opening cut-for-deal. */
    fun begin(s: GameState) {
        s.phase = GamePhase.CUT_FOR_DEAL
        s.cutForDeal = emptyMap()
        s.activeFlags = emptyList()
    }

    // ---- Cut for deal ----

    /**
     * Each player cuts once; the **lower** card wins the deal, and therefore the first crib. A tie
     * triggers a reshuffle and recut. Both cut cards stay on the table so each player sees the
     * other's — dealing then happens on the next [advance] (the "Deal" tap), not automatically.
     */
    fun cutForDeal(s: GameState, player: PlayerID, index: Int): Boolean {
        if (s.phase != GamePhase.CUT_FOR_DEAL || s.cutForDeal[player] != null) return false

        // Two players can never cut the same physical card. Each device generates its cut index
        // independently and they can collide, so if this draw matches the opponent's, take a
        // different position of the deck. A genuine same-rank tie with *different* cards still
        // recuts, below.
        var card = s.deck.cardAtCut(index)
        val other = s.cutForDeal[player.opponent]
        if (other != null && card == other) {
            card = s.deck.cardAtCut(index + 26)
        }
        s.cutForDeal = s.cutForDeal + (player to card)

        val a = s.cutForDeal[PlayerID.ONE] ?: return true
        val b = s.cutForDeal[PlayerID.TWO] ?: return true
        if (a.orderValue == b.orderValue) {
            // Genuine tie (two different cards, same rank) — reshuffle and recut.
            s.cutForDeal = emptyMap()
            s.seed = s.seed + 0x1111_1111uL
            s.deck = Deck.shuffled(seed = s.seed)
        } else {
            // Lower card deals and takes the crib. Hold here so the result is visible; the next
            // `advance` deals.
            s.dealer = if (a.orderValue < b.orderValue) PlayerID.ONE else PlayerID.TWO
        }
        return true
    }

    /** True once both players have cut for deal and the dealer is decided (result is on show). */
    fun cutForDealDecided(s: GameState): Boolean =
        s.phase == GamePhase.CUT_FOR_DEAL && s.cutForDeal.size == 2

    // ---- Deal ----

    /**
     * Shuffle a fresh deck for the hand and deal 6 to each player, leaving the remaining 40 for
     * the starter cut. Advances to [GamePhase.DISCARD_TO_CRIB].
     */
    fun dealNewHand(s: GameState) {
        s.handNumber += 1
        val deck = Deck.shuffled(seed = s.seed + (s.handNumber.toULong() * 0x9E37_79B9uL))
        val (handOne, afterOne) = deck.deal(6)
        val (handTwo, afterTwo) = afterOne.deal(6)
        s.hands = mapOf(PlayerID.ONE to handOne, PlayerID.TWO to handTwo)
        s.deck = afterTwo
        s.crib = emptyList()
        s.discarded = emptySet()
        s.starter = null
        s.starterCutIndex = null
        s.starterCutLifted = false
        s.playSequence = emptyList()
        s.lapCards = emptyList()
        s.goPlayers = emptySet()
        s.lastToPlay = null
        s.whoseTurn = null
        s.activeFlags = emptyList()
        s.phase = GamePhase.DISCARD_TO_CRIB
    }

    // ---- Discard to crib ----

    /**
     * A player lays 2 cards into the crib. When both have discarded, the game moves to the manual
     * starter cut.
     */
    fun discard(s: GameState, player: PlayerID, cards: List<Card>): Boolean {
        if (s.phase != GamePhase.DISCARD_TO_CRIB || player in s.discarded || cards.size != 2) return false
        val hand = s.hands[player] ?: return false
        if (!cards.all { it in hand }) return false

        s.hands = s.hands + (player to hand.filterNot { it in cards })
        s.crib = s.crib + cards
        s.discarded = s.discarded + player

        if (s.discarded.size == 2) {
            s.phase = GamePhase.CUT_STARTER
            s.starterCutIndex = null
            s.starterCutLifted = false
            s.starter = null
            s.activeFlags = emptyList()
        }
        return true
    }

    // ---- Starter cut (manual, two-step — mirrors an in-person cut) ----

    /**
     * Step 1: the pone (non-dealer) lifts a portion of the deck to the side, choosing the cut
     * depth. Records the cut index; the card stays hidden until the dealer reveals it.
     */
    fun liftStarterCut(s: GameState, player: PlayerID, index: Int): Boolean {
        if (s.phase != GamePhase.CUT_STARTER || s.starterCutLifted || player != s.pone) return false
        s.starterCutIndex = index
        s.starterCutLifted = true
        return true
    }

    /**
     * Step 2: the dealer turns up the card at the cut depth as the starter, then pegging begins.
     * A Jack starter flags "His Heels" (2 for the dealer), surfaced so the dealer can peg it.
     */
    fun revealStarter(s: GameState, player: PlayerID): Boolean {
        if (s.phase != GamePhase.CUT_STARTER || !s.starterCutLifted || player != s.dealer) return false
        val index = s.starterCutIndex ?: ((s.seed % 47uL).toInt() + s.handNumber * 13 + 7)
        val starter = s.deck.cardAtCut(index)
        s.starter = starter
        s.activeFlags = if (CribbageScorer.isHisHeels(starter)) {
            listOf(ScoreFlag(ScoreFlag.Kind.HIS_HEELS, points = 2, detail = "His Heels — 2 for dealer"))
        } else {
            emptyList()
        }

        s.phase = GamePhase.PEGGING
        s.lapCards = emptyList()
        s.playSequence = emptyList()
        s.goPlayers = emptySet()
        s.lastToPlay = null
        s.whoseTurn = s.pone            // pone leads the play
        autoScore(s, to = s.dealer)     // his heels, if any
        return true
    }

    // ---- Pegging — play a card ----

    fun play(s: GameState, player: PlayerID, card: Card): Boolean {
        if (s.phase != GamePhase.PEGGING || s.whoseTurn != player) return false
        if (card !in s.unplayed(player)) return false
        if (s.runningCount + card.countingValue > 31) return false

        s.playSequence = s.playSequence + PlayedCard(card = card, player = player)
        s.lapCards = s.lapCards + card
        s.lastToPlay = player

        val flags = CribbageScorer.peggingScore(pile = s.lapCards, justPlayed = card)
        val count = s.runningCount
        val done = s.allCardsPlayed

        if (count == 31) {
            s.activeFlags = flags       // "31 for 2" is already included
            notePegEvent(s, PegEvent(PegEvent.Kind.THIRTY_ONE, scorer = player, points = flags.totalPoints))
            if (done) finishPegging(s) else resetLap(s, nextLeadPreferring = player.opponent)
            autoScore(s, to = player)
            return true
        }

        if (done) {
            s.activeFlags = flags + ScoreFlag(ScoreFlag.Kind.LAST_CARD, points = 1, detail = "Last card")
            finishPegging(s)
            autoScore(s, to = player)
            return true
        }

        s.activeFlags = flags
        // Turn passes to the opponent unless they've gone or are out of cards, in which case you
        // continue laying.
        val opp = player.opponent
        val oppInPlay = opp !in s.goPlayers && s.unplayed(opp).isNotEmpty()
        s.whoseTurn = if (oppInPlay) opp else player
        autoScore(s, to = player)
        return true
    }

    /** Record a go/31 alert so the other device can nudge the scorer to take the point(s). */
    private fun notePegEvent(s: GameState, event: PegEvent) {
        s.pegEventTick += 1
        s.lastPegEvent = event
    }

    /** In automatic mode, immediately claim the just-surfaced flags for [player]. */
    private fun autoScore(s: GameState, to: PlayerID) {
        if (s.scoringMode != ScoringMode.AUTO) return
        val points = s.activeFlags.totalPoints
        if (points > 0) claim(s, player = to, amount = points)
    }

    // ---- Pegging — say "go" ----

    fun go(s: GameState, player: PlayerID): Boolean {
        if (s.phase != GamePhase.PEGGING || s.whoseTurn != player) return false
        // A player may only say "go" with no legal play available.
        if (CribbageScorer.legalPlays(s.unplayed(player), s.runningCount).isNotEmpty()) return false

        s.goPlayers = s.goPlayers + player
        val opp = player.opponent
        val oppCanPlay = opp !in s.goPlayers &&
            CribbageScorer.legalPlays(s.unplayed(opp), s.runningCount).isNotEmpty()

        if (oppCanPlay) {
            s.whoseTurn = opp   // opponent keeps laying until they also can't
            // Notify the opponent that a "go" was said and the play has passed to them. `points: 0`
            // distinguishes this "your turn" nudge from the go point awarded when the lap ends.
            notePegEvent(s, PegEvent(PegEvent.Kind.GO, scorer = player, points = 0))
            return true
        }

        // Neither can add — the lap ends. Last player to lay a card pegs 1 for the go.
        s.activeFlags = listOf(ScoreFlag(ScoreFlag.Kind.GO, points = 1, detail = "Go"))
        val goScorer = s.lastToPlay ?: player
        notePegEvent(s, PegEvent(PegEvent.Kind.GO, scorer = goScorer, points = 1))
        if (s.allCardsPlayed) {
            finishPegging(s)
        } else {
            resetLap(s, nextLeadPreferring = goScorer.opponent)
        }
        autoScore(s, to = goScorer)
        return true
    }

    /**
     * Clears the current lap and hands the lead to [nextLeadPreferring], or to the other player if
     * they are out of cards. If both are out, pegging is finished.
     */
    private fun resetLap(s: GameState, nextLeadPreferring: PlayerID) {
        s.lapCards = emptyList()
        s.goPlayers = emptySet()
        if (s.unplayed(nextLeadPreferring).isNotEmpty()) {
            s.whoseTurn = nextLeadPreferring
        } else if (s.unplayed(nextLeadPreferring.opponent).isNotEmpty()) {
            s.whoseTurn = nextLeadPreferring.opponent
        } else {
            finishPegging(s)
        }
    }

    /** Pegging is over. Keep the final flags visible for claiming; the show starts on [advance]. */
    private fun finishPegging(s: GameState) {
        s.whoseTurn = null
        s.lapCards = emptyList()
        s.goPlayers = emptySet()
    }

    // ---- The show ----

    private fun beginShow(s: GameState, phase: GamePhase) {
        s.phase = phase
        val starter = s.starter
        if (starter == null) {
            s.activeFlags = emptyList()
            return
        }
        when (phase) {
            GamePhase.SHOW_PONE -> {
                s.activeFlags = CribbageScorer.handScore(s.hands[s.pone] ?: emptyList(), starter, isCrib = false)
                autoScore(s, to = s.pone)
            }
            GamePhase.SHOW_DEALER -> {
                s.activeFlags = CribbageScorer.handScore(s.hands[s.dealer] ?: emptyList(), starter, isCrib = false)
                autoScore(s, to = s.dealer)
            }
            GamePhase.SHOW_CRIB -> {
                s.activeFlags = CribbageScorer.handScore(s.crib, starter, isCrib = true)
                autoScore(s, to = s.dealer)
            }
            else -> s.activeFlags = emptyList()
        }
    }

    // ---- Manual scoring ----

    /** Apply a manual claim from the slider. Reaching 121 wins the game immediately. */
    fun claim(s: GameState, player: PlayerID, amount: Int): Boolean {
        if (s.phase == GamePhase.GAME_OVER || amount <= 0) return false
        s.scores = s.scores + (player to ((s.scores[player] ?: 0) + amount))
        s.claimHistory = s.claimHistory + Claim(player = player, amount = amount, phase = s.phase)
        s.claimTick += 1
        if ((s.scores[player] ?: 0) >= 121) {
            s.winner = player
            s.whoseTurn = null
            s.phase = GamePhase.GAME_OVER
        }
        return true
    }

    /** Undo a player's most recent claim, restoring the pre-win phase if it had ended the game. */
    fun undo(s: GameState, player: PlayerID): Boolean {
        val idx = s.claimHistory.indexOfLast { it.player == player }
        if (idx < 0) return false
        val claim = s.claimHistory[idx]
        s.claimHistory = s.claimHistory.filterIndexed { i, _ -> i != idx }
        s.scores = s.scores + (player to ((s.scores[player] ?: 0) - claim.amount))
        if (s.winner == player && (s.scores[player] ?: 0) < 121) {
            s.winner = null
            if (s.phase == GamePhase.GAME_OVER) s.phase = claim.phase
        }
        return true
    }

    // ---- Advancing steps ("Continue") ----

    /** Advance the show sub-phases, finish pegging into the show, and start the next hand. */
    fun advance(s: GameState): Boolean = when {
        s.phase == GamePhase.CUT_FOR_DEAL && cutForDealDecided(s) -> { dealNewHand(s); true }
        s.phase == GamePhase.PEGGING && s.whoseTurn == null -> { beginShow(s, GamePhase.SHOW_PONE); true }
        s.phase == GamePhase.SHOW_PONE -> { beginShow(s, GamePhase.SHOW_DEALER); true }
        s.phase == GamePhase.SHOW_DEALER -> { beginShow(s, GamePhase.SHOW_CRIB); true }
        s.phase == GamePhase.SHOW_CRIB -> {
            s.phase = GamePhase.HAND_COMPLETE
            s.activeFlags = emptyList()
            true
        }
        s.phase == GamePhase.HAND_COMPLETE -> {
            s.dealer = s.dealer.opponent   // deal passes to the former pone
            s.cutForDeal = emptyMap()
            dealNewHand(s)
            true
        }
        else -> false
    }

    // ---- Play again ----

    /**
     * Reset scores and start a fresh game, keeping names, colours and scoring mode. No cut for
     * deal — the player who was *not* the dealer last game deals first, and we go straight to it.
     *
     * Unlike every other handler this **returns a replacement state** rather than mutating in
     * place: the Swift assigns a whole fresh struct over `inout s`, and a new game genuinely is a
     * new state rather than an edit of the old one. Call it as `state = CribbageEngine.playAgain(state)`.
     */
    fun playAgain(s: GameState): GameState {
        val nextDealer = s.dealer.opponent
        val fresh = GameState.newMatch(
            matchID = s.matchID,
            seed = s.seed + 0x7777_7777uL,
            names = s.names,
            colorIDs = s.colorIDs,
            scoringMode = s.scoringMode,
        )
        fresh.dealer = nextDealer
        dealNewHand(fresh)   // straight to discardToCrib, no cut-for-deal
        return fresh
    }
}
