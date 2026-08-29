package com.automatelinux.wakeUp.alarm

/**
 * A wake-up is a sequence, not a sound.
 *
 * Every stage adds a channel and takes something away from you, and **the ladder never goes
 * back down**. Starting at full blast is what trains the blind lunge for the phone: if the
 * first ten seconds are always unbearable, the hand learns to kill it before the brain is
 * involved. Starting gently and getting worse means the cheapest way out is to actually wake
 * up — which is the only behaviour this app is trying to buy.
 *
 * [atSeconds] is measured from the first sound. [volumeFraction] is of the alarm stream's
 * maximum, and [gainMb] is LoudnessEnhancer gain on top of that — the stage past the slider.
 */
data class Stage(
    val atSeconds: Int,
    val volumeFraction: Float,
    val gainMb: Int,
    val vibrate: Boolean,
    val flash: Boolean,
    val flashPeriodMs: Long,
    val note: String,
)

object Escalation {
    /**
     * The default ladder. Two minutes from a voice in a quiet room to every channel at once.
     *
     * Stage 0 is deliberately soft: it is Claude talking, at a third of the stream, with no
     * gain. If that is enough on a good morning you are awake without ever having been
     * assaulted, and the rest of the ladder never happens.
     */
    val DEFAULT = listOf(
        Stage(0, 0.35f, 0, vibrate = false, flash = false, flashPeriodMs = 0, note = "voice, quiet"),
        Stage(20, 0.60f, 400, vibrate = true, flash = false, flashPeriodMs = 0, note = "sound joins, buzz"),
        Stage(45, 1.00f, 1200, vibrate = true, flash = true, flashPeriodMs = 700, note = "full stream, strobe"),
        Stage(80, 1.00f, 2200, vibrate = true, flash = true, flashPeriodMs = 450, note = "gain past the slider"),
        Stage(130, 1.00f, 3200, vibrate = true, flash = true, flashPeriodMs = 220, note = "everything, hard"),
    )

    /** The stage in force [elapsedSeconds] after the alarm started. */
    fun stageAt(elapsedSeconds: Long, ladder: List<Stage> = DEFAULT): Stage =
        ladder.last { it.atSeconds <= elapsedSeconds }

    /** The next stage boundary after [elapsedSeconds], or null once the top is reached. */
    fun nextBoundary(elapsedSeconds: Long, ladder: List<Stage> = DEFAULT): Int? =
        ladder.firstOrNull { it.atSeconds > elapsedSeconds }?.atSeconds
}
