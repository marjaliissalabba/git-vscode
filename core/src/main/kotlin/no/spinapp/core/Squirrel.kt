package no.spinapp.core

/**
 * Ekornet ved måleren. Målingene (watt/FTP og rpm) velger tilstand; overganger
 * mellom løping og klatring spilles én gang, de andre klippene looper.
 * Startverdiene er foreløpige og kan justeres under Profil → Ekornet.
 */
data class SquirrelThresholds(
    /** Står og puster under denne kadensen (eller når økta står på pause). */
    val idleRpm: Int = 20,
    /** Oppover (klatring) = mye kraft per tråkk: minst [climbPct] % av FTP og høyst [climbMaxRpm] rpm. */
    val climbPct: Int = 75,
    val climbMaxRpm: Int = 75,
    /** Klatringen slutter under [climbExitPct] % av FTP eller over [climbExitRpm] rpm. */
    val climbExitPct: Int = 70,
    val climbExitRpm: Int = 82,
    /** Hoppende løp = høy kadens: minst [sprintRpm] rpm, eller minst [sprintAltPct] % av FTP med minst [sprintAltRpm] rpm. */
    val sprintRpm: Int = 100,
    val sprintAltPct: Int = 120,
    val sprintAltRpm: Int = 90,
    /** Hoppende løp slutter under [sprintExitRpm] rpm og under [sprintExitPct] % av FTP. */
    val sprintExitRpm: Int = 95,
    val sprintExitPct: Int = 115,
    /** Reaksjonstid i sekunder: målingene jevnes ut og en ny tilstand må holde seg så lenge før ekornet bytter. */
    val delaySec: Int = 3,
)

enum class SquirrelMode { IDLE, RUN, SPRINT, CLIMB }

/** Klipp i spritepakken. Mappenavnet i assets er [folder]. */
enum class SquirrelClip(val folder: String, val loop: Boolean, val frames: Int = 6) {
    IDLE("idle", true),
    RUN("run", true),
    SPRINT("sprint_bouncy", true),
    CLIMB("climb_run_vertical", true),
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

        if (paused || r < t.idleRpm) {
            mode = SquirrelMode.IDLE
            return mode
        }
        val climbEnter = pct >= t.climbPct && r <= t.climbMaxRpm
        val climbExit = pct < t.climbExitPct || r > t.climbExitRpm
        val sprintEnter = r >= t.sprintRpm || (pct >= t.sprintAltPct && r >= t.sprintAltRpm)
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
 * Gjør ekornet rolig: jevner ut watt og rpm over [SquirrelThresholds.delaySec] sekunder,
 * krever at en ny tilstand holder seg like lenge før den tas i bruk, og at hver tilstand
 * vises en stund. Pause gir «står og puster» med en gang.
 */
class SquirrelDirector(thresholds: SquirrelThresholds = SquirrelThresholds()) {
    val machine = SquirrelStateMachine(thresholds)
    var thresholds: SquirrelThresholds
        get() = machine.thresholds
        set(v) { machine.thresholds = v }

    var mode = SquirrelMode.IDLE
        private set
    private var emaPct: Double? = null
    private var emaRpm: Double? = null
    private var candidate = SquirrelMode.IDLE
    private var candidateMs = 0.0
    private var inModeMs = 0.0

    /** Rpm for farten: utjevnet og rundet til nærmeste 5. */
    val animRpm: Int get() = ((emaRpm ?: 0.0) / 5.0).let { kotlin.math.round(it).toInt() * 5 }

    fun update(dtMs: Double, watts: Int?, rpm: Int?, ftp: Int, paused: Boolean): SquirrelMode {
        val delayMs = thresholds.delaySec.coerceAtLeast(0) * 1000.0
        val pct = if (ftp > 0 && watts != null) watts * 100.0 / ftp else 0.0
        val r = (rpm ?: 0).toDouble()
        // Eksponentiell utjevning med tidskonstant = reaksjonstiden
        val a = if (delayMs <= 0) 1.0 else (dtMs / delayMs).coerceIn(0.0, 1.0)
        emaPct = emaPct?.let { it + (pct - it) * a } ?: pct
        emaRpm = emaRpm?.let { it + (r - it) * a } ?: r
        inModeMs += dtMs

        if (paused) {
            mode = SquirrelMode.IDLE
            machine.update(0, 0, ftp, paused = true)
            candidate = mode; candidateMs = 0.0; inModeMs = 0.0
            return mode
        }
        val wanted = machine.update((emaPct!! * ftp / 100.0).toInt(), emaRpm!!.toInt(), ftp, paused = false)
        if (wanted == mode) {
            candidate = mode; candidateMs = 0.0
        } else {
            if (wanted != candidate) { candidate = wanted; candidateMs = 0.0 }
            candidateMs += dtMs
            if (candidateMs >= delayMs && inModeMs >= delayMs) {
                mode = wanted; inModeMs = 0.0; candidateMs = 0.0
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

    /**
     * Varighet per bilde i ms. Løp, hoppende løp og klatring går én runde per pedalomdreining,
     * så farten følger kadensen: 60 rpm ≈ 167 ms, 90 rpm ≈ 111 ms, 116 rpm ≈ 86 ms per bilde.
     */
    fun frameDurationMs(c: SquirrelClip, rpm: Int?): Double = when (c) {
        SquirrelClip.IDLE -> 250.0
        SquirrelClip.RUN, SquirrelClip.SPRINT, SquirrelClip.CLIMB -> {
            val r = (rpm ?: 60).coerceIn(30, 160)
            (60000.0 / (r * c.frames)).coerceIn(50.0, 250.0)
        }
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
