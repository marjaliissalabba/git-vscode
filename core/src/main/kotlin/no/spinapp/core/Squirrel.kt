package no.spinapp.core

/**
 * Ekornet ved måleren. Målingene (watt/FTP og rpm) velger tilstand; overganger
 * mellom løping og klatring spilles én gang, de andre klippene looper.
 * Startverdiene kommer fra spritepakkens control_rules.json og kan justeres.
 */
data class SquirrelThresholds(
    /** Står og puster under denne kadensen … */
    val idleRpm: Int = 20,
    /** … og under denne andelen av FTP. */
    val idlePct: Int = 20,
    /** Sprint: rpm ≥ [sprintRpm] og ≥ [sprintPct] % av FTP … */
    val sprintRpm: Int = 85,
    val sprintPct: Int = 35,
    /** … eller ≥ [sprintAltPct] % av FTP og rpm ≥ [sprintAltRpm]. */
    val sprintAltPct: Int = 100,
    val sprintAltRpm: Int = 65,
    /** Sprint slutter når rpm < [sprintExitRpm] og under [sprintExitPct] %. */
    val sprintExitRpm: Int = 78,
    val sprintExitPct: Int = 90,
    /** Klatring: ≥ [climbPct] % av FTP og rpm ≤ [climbMaxRpm]. */
    val climbPct: Int = 85,
    val climbMaxRpm: Int = 65,
    /** Klatring slutter når under [climbExitPct] % eller rpm > [climbExitRpm]. */
    val climbExitPct: Int = 78,
    val climbExitRpm: Int = 72,
)

enum class SquirrelMode { IDLE, RUN, SPRINT, CLIMB }

/** Klipp i spritepakken. Mappenavnet i assets er [folder]. */
enum class SquirrelClip(val folder: String, val loop: Boolean, val frames: Int = 6) {
    IDLE("idle", true),
    RUN("run", true),
    SPRINT("sprint_bouncy", true),
    CLIMB("climb_loop", true),
    RUN_TO_CLIMB("run_to_climb", false),
    CLIMB_TO_RUN("climb_over_crest_to_run", false),
}

/** Velger tilstand ut fra målingene, med hysterese så ekornet ikke blafrer mellom tilstander. */
class SquirrelStateMachine(var thresholds: SquirrelThresholds = SquirrelThresholds()) {
    var mode = SquirrelMode.IDLE
        private set

    fun update(watts: Int?, rpm: Int?, ftp: Int, paused: Boolean): SquirrelMode {
        val t = thresholds
        val r = rpm ?: 0
        val pct = if (ftp > 0 && watts != null) watts * 100.0 / ftp else 0.0

        if (paused || (r < t.idleRpm && pct < t.idlePct)) {
            mode = SquirrelMode.IDLE
            return mode
        }
        val climbEnter = pct >= t.climbPct && r <= t.climbMaxRpm
        val climbExit = pct < t.climbExitPct || r > t.climbExitRpm
        val sprintEnter = (r >= t.sprintRpm && pct >= t.sprintPct) || (pct >= t.sprintAltPct && r >= t.sprintAltRpm)
        val sprintExit = r < t.sprintExitRpm && pct < t.sprintExitPct

        mode = when (mode) {
            SquirrelMode.CLIMB -> when {
                !climbExit -> SquirrelMode.CLIMB
                sprintEnter -> SquirrelMode.SPRINT
                else -> SquirrelMode.RUN
            }
            SquirrelMode.SPRINT -> when {
                climbEnter -> SquirrelMode.CLIMB
                !sprintExit -> SquirrelMode.SPRINT
                else -> SquirrelMode.RUN
            }
            else -> when {
                climbEnter -> SquirrelMode.CLIMB
                sprintEnter -> SquirrelMode.SPRINT
                else -> SquirrelMode.RUN
            }
        }
        return mode
    }
}

/**
 * Spiller av klippene: looper tilstandens klipp, og legger inn overgang én gang
 * når man går inn i eller ut av klatring.
 */
class SquirrelPlayer {
    var clip = SquirrelClip.IDLE
        private set
    var frame = 0
        private set
    private var shownMode = SquirrelMode.IDLE
    private var elapsedMs = 0.0

    /** Varighet per bilde i ms. Løping følger kadensen (30–110 rpm → 160–80 ms). */
    fun frameDurationMs(c: SquirrelClip, rpm: Int?): Double = when (c) {
        SquirrelClip.IDLE -> 250.0
        SquirrelClip.RUN -> {
            val r = (rpm ?: 30).coerceIn(30, 110)
            160.0 - (r - 30) / 80.0 * 80.0
        }
        SquirrelClip.SPRINT -> 85.0
        SquirrelClip.CLIMB -> 130.0
        SquirrelClip.RUN_TO_CLIMB, SquirrelClip.CLIMB_TO_RUN -> 120.0
    }

    private fun loopFor(m: SquirrelMode) = when (m) {
        SquirrelMode.IDLE -> SquirrelClip.IDLE
        SquirrelMode.RUN -> SquirrelClip.RUN
        SquirrelMode.SPRINT -> SquirrelClip.SPRINT
        SquirrelMode.CLIMB -> SquirrelClip.CLIMB
    }

    private fun start(c: SquirrelClip) {
        clip = c
        frame = 0
        elapsedMs = 0.0
    }

    /** Velg neste klipp mot ønsket tilstand (eventuelt via en overgang). */
    private fun goTowards(target: SquirrelMode) {
        val from = shownMode
        when {
            target == SquirrelMode.CLIMB && (from == SquirrelMode.RUN || from == SquirrelMode.SPRINT) -> start(SquirrelClip.RUN_TO_CLIMB)
            from == SquirrelMode.CLIMB && (target == SquirrelMode.RUN || target == SquirrelMode.SPRINT) -> start(SquirrelClip.CLIMB_TO_RUN)
            else -> start(loopFor(target))
        }
        shownMode = target
    }

    /** Går fram [dtMs] millisekunder. Returnerer klipp og bilde som skal vises. */
    fun advance(dtMs: Double, target: SquirrelMode, rpm: Int?): Pair<SquirrelClip, Int> {
        val inTransition = !clip.loop
        if (!inTransition && target != shownMode) goTowards(target)

        elapsedMs += dtMs
        var dur = frameDurationMs(clip, rpm)
        while (elapsedMs >= dur) {
            elapsedMs -= dur
            if (frame < clip.frames - 1) {
                frame++
            } else if (clip.loop) {
                frame = 0
            } else {
                // Overgangen er ferdig: gå til tilstandens loop, eller videre hvis målet har endret seg
                val landed = when (clip) {
                    SquirrelClip.RUN_TO_CLIMB -> SquirrelMode.CLIMB
                    else -> SquirrelMode.RUN
                }
                shownMode = landed
                if (target != landed) goTowards(target) else start(loopFor(landed))
            }
            dur = frameDurationMs(clip, rpm)
        }
        return clip to frame
    }
}
