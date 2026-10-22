package com.github.florent37.assets_audio_player

import com.github.florent37.assets_audio_player.playerimplem.PlayerImplem

/**
 * Runs one prepared or opened playback attempt of a player; owner guards and the single reply live here.
 * No android.* import: the JVM suite drives this class through a fake port.
 */
class PlaybackAttemptSession(
    private val playerId: String,
    private val bound: PreparationBound,
    private val port: Port,
    private val boundMs: Long,
) {
    fun interface Cancellable {
        fun cancel()
    }

    interface OpenReply {
        fun success()

        fun error(code: String, message: String?)
    }

    interface Port {
        fun showPreparedCard(playing: Boolean)

        fun publishPreparedPlaybackState(playing: Boolean)

        /** The player whose media card is visible now. */
        fun cardOwnerPlayerId(): String?

        /** Removes the visible media card. */
        fun hideCard()

        /** The wrapper adopts this exact player as its current transport; called once per accepted open. */
        fun publishTransport(player: PlayerImplem)

        /** The wrapper clears and releases this exact accepted transport. */
        fun releaseTransport(player: PlayerImplem)

        /** Tells Dart this player's accepted transport was released by the bound's expiry. */
        fun notifyTransportExpired()

        fun playTransport()

        fun pauseTransport()

        fun acknowledgeForeground()

        fun schedule(delayMs: Long, action: () -> Unit): Cancellable

        fun log(line: String)
    }

    private var generation: Int? = null
    private var pendingReply: OpenReply? = null
    private var pendingAttempt: Int? = null
    private var attemptCounter: Int = 0
    private var expiry: Cancellable? = null
    private var heldPlayer: PlayerImplem? = null
    private var cardVisible: Boolean = false

    /** Fresh generation, card shown playing, bound armed. */
    fun prepare() {
        expiry?.cancel()
        expiry = null
        heldPlayer = null
        val gen = bound.prepare()
        generation = gen
        cardVisible = true
        showPreparedCard(playing = true)
        port.log("prepare gen=$gen player=$playerId")
        expiry = port.schedule(boundMs) { expireAt(gen) }
    }

    /** Keeps the bound; settles a still-unanswered previous open, then holds the one reply of this open. */
    fun openStarted(reply: OpenReply): Int {
        val superseded = takePendingReply()
        if (superseded != null) {
            // The previous open keeps its exactly-one answer; its late result hits the stale-attempt guards below.
            port.log("open answered gen=${generation ?: "none"} result=superseded")
            superseded.error("OPEN_SUPERSEDED", "a newer open took the reply slot")
        }
        attemptCounter += 1
        pendingReply = reply
        pendingAttempt = attemptCounter
        return attemptCounter
    }

    /** False: cancelled or expired; stops and releases player. */
    fun openSucceeded(attempt: Int, player: PlayerImplem): Boolean {
        if (attempt != pendingAttempt) {
            // The pending open was already answered; a late player is released without a second reply.
            player.stop()
            player.release()
            port.log("late player released gen=${generation ?: attempt}")
            return false
        }
        val gen = generation
        if (gen != null && !bound.mayStart(gen)) {
            player.stop()
            player.release()
            port.log("late player released gen=$gen")
            return false
        }
        val reply = takePendingReply() ?: return false
        heldPlayer = player
        port.publishTransport(player)
        port.log("open answered gen=${gen ?: attempt} result=success")
        reply.success()
        return true
    }

    /** Answers once with the error; hides the card if this player owns it. */
    fun openFailed(attempt: Int, error: Throwable) {
        if (attempt != pendingAttempt) {
            return
        }
        val reply = takePendingReply() ?: return
        port.log("open answered gen=${generation ?: attempt} result=error")
        reply.error("OPEN_ERROR", error.message)
        removeOwnedTerminalState()
    }

    /** Internal stop inside open: keeps the bound. */
    fun replacement() {
        heldPlayer?.let(port::releaseTransport)
        heldPlayer = null
    }

    /** Answers a pending open once (OPEN_CANCELLED); owner-guarded hide. */
    fun terminalStop(cause: String) {
        expiry?.cancel()
        expiry = null
        val gen = generation
        gen?.let { bound.terminal(it) }
        generation = null
        val reply = takePendingReply()
        if (reply != null) {
            port.log("open answered gen=${gen ?: "none"} result=cancelled")
            reply.error("OPEN_CANCELLED", "the attempt was stopped before the open completed")
        }
        heldPlayer?.let(port::releaseTransport)
        heldPlayer = null
        removeOwnedTerminalState()
        port.log("release cause=$cause")
    }

    /** Disarms the bound. */
    fun playbackStarted() {
        val gen = generation ?: return
        bound.playbackStarted(gen)
    }

    /** Logs and applies one foreground acknowledgement. */
    fun onServiceForeground() {
        generation?.let { gen: Int -> port.log("foreground ack gen=$gen") }
        port.acknowledgeForeground()
    }

    /** Dispatches Play through the prepared card or the opened transport, never both. */
    fun play() {
        if (!preparedPlayPause(playing = true)) {
            port.playTransport()
        }
    }

    /** Dispatches Pause through the prepared card or the opened transport, never both. */
    fun pause() {
        if (!preparedPlayPause(playing = false)) {
            port.pauseTransport()
        }
    }

    /** True when only the prepared card changes (no media player yet). */
    fun preparedPlayPause(playing: Boolean): Boolean {
        if (heldPlayer != null || !cardVisible) {
            return false
        }
        showPreparedCard(playing)
        return true
    }

    /** Consumes the one pending reply; null when nothing is pending, so every path answers at most once. */
    private fun takePendingReply(): OpenReply? {
        val reply = pendingReply ?: return null
        pendingReply = null
        pendingAttempt = null
        return reply
    }

    private fun removeOwnedTerminalState() {
        // Another player may own the card by now; only the prepared owner may remove its card and state.
        if (port.cardOwnerPlayerId() == playerId) {
            port.hideCard()
            cardVisible = false
            port.publishPreparedPlaybackState(playing = false)
        }
    }

    private fun showPreparedCard(playing: Boolean) {
        port.showPreparedCard(playing)
        port.publishPreparedPlaybackState(playing)
    }

    private fun expireAt(gen: Int) {
        if (gen != generation) {
            return
        }
        if (!bound.expire(gen)) {
            return
        }
        val reply = takePendingReply()
        if (reply != null) {
            port.log("open answered gen=$gen result=timeout")
            reply.error("OPEN_TIMEOUT", "the open did not complete within the bound")
        }
        var released = false
        heldPlayer?.let { player: PlayerImplem ->
            port.releaseTransport(player)
            heldPlayer = null
            released = true
            // The accepted transport is gone: Dart must not keep treating the playlist as playable.
            port.notifyTransportExpired()
            port.log("release cause=expiry")
        }
        removeOwnedTerminalState()
        generation = null
        port.log("prepare expired gen=$gen released=$released")
    }
}
