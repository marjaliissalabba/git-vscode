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

/**
 * Klipp i spritepakken (mappenavn = navnet i animations.json). Løkkene har 8 bilder,
 * overgangene 6 og finnes for alle par av tilstander.
 */
enum class SquirrelClip(
    val folder: String,
    val loop: Boolean,
    val frames: Int,
    val from: SquirrelMode? = null,
    val to: SquirrelMode? = null,
) {
    IDLE("idle", true, 8),
    SLOW_RUN("slow_run", true, 8),
    FAST_RUN("fast_run", true, 8),
    CLIMB("climb", true, 8),
    IDLE_TO_SLOW_RUN("idle_to_slow_run", false, 6, SquirrelMode.IDLE, SquirrelMode.RUN),
    SLOW_RUN_TO_IDLE("slow_run_to_idle", false, 6, SquirrelMode.RUN, SquirrelMode.IDLE),
    IDLE_TO_FAST_RUN("idle_to_fast_run", false, 6, SquirrelMode.IDLE, SquirrelMode.SPRINT),
    FAST_RUN_TO_IDLE("fast_run_to_idle", false, 6, SquirrelMode.SPRINT, SquirrelMode.IDLE),
    SLOW_RUN_TO_FAST_RUN("slow_run_to_fast_run", false, 6, SquirrelMode.RUN, SquirrelMode.SPRINT),
    FAST_RUN_TO_SLOW_RUN("fast_run_to_slow_run", false, 6, SquirrelMode.SPRINT, SquirrelMode.RUN),
    IDLE_TO_CLIMB("idle_to_climb", false, 6, SquirrelMode.IDLE, SquirrelMode.CLIMB),
    CLIMB_TO_IDLE("climb_to_idle", false, 6, SquirrelMode.CLIMB, SquirrelMode.IDLE),
    SLOW_RUN_TO_CLIMB("slow_run_to_climb", false, 6, SquirrelMode.RUN, SquirrelMode.CLIMB),
    CLIMB_TO_SLOW_RUN("climb_to_slow_run", false, 6, SquirrelMode.CLIMB, SquirrelMode.RUN),
    FAST_RUN_TO_CLIMB("fast_run_to_climb", false, 6, SquirrelMode.SPRINT, SquirrelMode.CLIMB),
    CLIMB_TO_FAST_RUN("climb_to_fast_run", false, 6, SquirrelMode.CLIMB, SquirrelMode.SPRINT);

    companion object {
        fun loopFor(m: SquirrelMode) = when (m) {
            SquirrelMode.IDLE -> IDLE
            SquirrelMode.RUN -> SLOW_RUN
            SquirrelMode.SPRINT -> FAST_RUN
            SquirrelMode.CLIMB -> CLIMB
        }

        fun transition(from: SquirrelMode, to: SquirrelMode): SquirrelClip? =
            entries.firstOrNull { !it.loop && it.from == from && it.to == to }
    }
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
 * Spiller av klippene: looper tilstandens klipp, og spiller riktig overgang én gang
 * ved hvert tilstandsbytte. En overgang spilles alltid ferdig; deretter velges neste
 * overgang fra tilstanden den endte i.
 */
class SquirrelPlayer(
    /**
     * Varighet i ms per bilde fra Ekorn Sprite-studio (timing.json). For hvile og overganger
     * brukes tallene direkte; for løp og klatring er de rytme (relative lengder), mens
     * kadensen styrer den samlede farten.
     */
    var timings: Map<SquirrelClip, DoubleArray> = emptyMap(),
) {
    var clip = SquirrelClip.IDLE
        private set
    var frame = 0
        private set
    private var shownMode = SquirrelMode.IDLE
    private var elapsedMs = 0.0
    private var curDurMs = 1.0

    /** Hvor langt (0–1) det viste bildet har kommet mot neste. Brukes til myk blanding. */
    val progress: Double get() = (elapsedMs / curDurMs).coerceIn(0.0, 1.0)

    /**
     * Bildet som kommer etter det viste innen samme klipp, eller null når klippet er
     * en overgang som er på siste bilde (da kommer et annet klipp, og blanding ville gitt hakk).
     */
    val nextFrame: Int? get() = when {
        frame < clip.frames - 1 -> frame + 1
        clip.loop -> 0
        else -> null
    }

    /**
     * Varighet per bilde i ms. Løkkene følger kadensen ut fra spritepakkens anbefalte fart:
     * rolig løp 10 bilder/s ved 85 rpm, raskt løp 16 bilder/s ved 105 rpm (≈ 18 ved 116 rpm),
     * klatring 10 bilder/s ved 65 rpm. Hvile 6 bilder/s, overganger 14 bilder/s.
     */
    fun frameDurationMs(c: SquirrelClip, rpm: Int?, frame: Int = 0): Double {
        val base = baseDurationMs(c, rpm)
        val t = timings[c] ?: return base
        if (t.isEmpty()) return base
        val d = t[frame.coerceIn(0, t.lastIndex)]
        return when (c) {
            SquirrelClip.SLOW_RUN, SquirrelClip.FAST_RUN, SquirrelClip.CLIMB -> base * d / t.average()
            else -> d
        }.coerceAtLeast(10.0)
    }

    private fun baseDurationMs(c: SquirrelClip, rpm: Int?): Double {
        val r = (rpm ?: 0).toDouble()
        val fps = when (c) {
            SquirrelClip.IDLE -> 6.0
            SquirrelClip.SLOW_RUN -> (10.0 * r / 85.0).coerceIn(5.0, 16.0)
            SquirrelClip.FAST_RUN -> (16.0 * r / 105.0).coerceIn(10.0, 24.0)
            SquirrelClip.CLIMB -> (10.0 * r / 65.0).coerceIn(6.0, 14.0)
            else -> 14.0
        }
        return 1000.0 / fps
    }

    private fun start(c: SquirrelClip) {
        clip = c
        frame = 0
        elapsedMs = 0.0
    }

    private fun goTowards(target: SquirrelMode) {
        start(SquirrelClip.transition(shownMode, target) ?: SquirrelClip.loopFor(target))
        shownMode = target
    }

    /** Går fram [dtMs] millisekunder. Returnerer klipp og bilde som skal vises. */
    fun advance(dtMs: Double, target: SquirrelMode, rpm: Int?): Pair<SquirrelClip, Int> {
        if (clip.loop && target != shownMode) goTowards(target)

        elapsedMs += dtMs
        var dur = frameDurationMs(clip, rpm, frame)
        while (elapsedMs >= dur) {
            elapsedMs -= dur
            if (frame < clip.frames - 1) {
                frame++
            } else if (clip.loop) {
                frame = 0
            } else {
                val landed = clip.to ?: shownMode
                shownMode = landed
                if (target != landed) goTowards(target) else start(SquirrelClip.loopFor(landed))
            }
            dur = frameDurationMs(clip, rpm, frame)
        }
        curDurMs = dur
        return clip to frame
    }
}
