package no.spinapp.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Animasjonene: ett ark per animasjon med kvadratiske bilder på rad (32 × 32 i Pixel Adventure 1,
 * 24 × 24 for reven), figuren vendt mot høyre. Veggbildene har veggen på høyre side.
 * [frames] er standardantallet; appen leser det faktiske antallet fra arkene.
 */
enum class MascotAnim(val file: String, val frames: Int, val loop: Boolean) {
    IDLE("idle", 11, true),
    RUN("run", 12, true),
    JUMP("jump", 1, true),
    FALL("fall", 1, true),
    DOUBLE_JUMP("double_jump", 6, false),
    WALL("wall_jump", 5, true),
}

/**
 * Figurene (mappenavn, navn i appen og fart på bildene i forhold til Pixel Adventure, som er
 * laget for 20 bilder/s). Antall bilder per animasjon leses fra arkene (bredde / 32).
 */
enum class MascotCharacter(val folder: String, val label: String, val fpsScale: Double = 1.0) {
    NINJA_FROG("ninja_frog", "Frosk"),
    MASK_DUDE("mask_dude", "Maske"),
    PINK_MAN("pink_man", "Rosa"),
    VIRTUAL_GUY("virtual_guy", "Virtuell"),
    FOX("fox", "Rev", 0.5),
}

/**
 * Buen som hindring: en ring rundt ([cxFrac] × bredde, [cyFrac] × høyde fra toppen) med indre og
 * ytre radius som andel av boksens høyde. Vinkler som i Compose: 0° = klokka 3, med klokka.
 */
data class MascotArc(
    val cxFrac: Double = 0.5,
    val cyFrac: Double = 0.5,
    val rInFrac: Double,
    val rOutFrac: Double,
    val startDeg: Double,
    val sweepDeg: Double,
)

/**
 * Lar figuren bevege seg fritt i en boks (bredde [w] × høyde [h], målt i figurstørrelser).
 * Gulvet er bunnen av boksen. Tilstanden fra [SquirrelDirector] bestemmer hva den gjør:
 * - står: står stille og snur seg av og til
 * - løper: løper fram og tilbake, farten følger kadensen
 * - løper fort: løper fortere og hopper (noen ganger dobbelthopp)
 * - klatrer: løper til nærmeste vegg og klatrer opp; på toppen hopper den ut og lander på gulvet
 */
class MascotRunner(private val rnd: Random = Random.Default) {
    /** Antall bilder per animasjon for figuren som vises (fra arkene). */
    var frameCounts: Map<MascotAnim, Int> = emptyMap()
    /** Fart på bildene i forhold til standard (se [MascotCharacter.fpsScale]). */
    var fpsScale = 1.0
    /** Buen som figuren spretter av når den er i lufta, eller null. */
    var arc: MascotArc? = null
    private fun count(a: MascotAnim) = (frameCounts[a] ?: a.frames).coerceAtLeast(1)

    /** Venstre kant, i figurstørrelser fra venstre side av boksen. */
    var x = 0.0
        private set
    /** Høyde over gulvet, i figurstørrelser. */
    var y = 0.0
        private set
    var facingRight = true
        private set
    var anim = MascotAnim.IDLE
        private set
    var frame = 0
        private set
    var onWall = false
        private set

    private var vx = 0.0
    private var vy = 0.0
    private var frameMs = 0.0
    private var timer = 2.0
    private var doubled = false
    private var placed = false
    private var lastRpm = 0

    private val g = 32.0 // figurstørrelser per s²

    private var boxW = 1.0
    private var boxH = 1.0

    fun update(dtMs: Double, mode: SquirrelMode, rpm: Int?, w: Double, h: Double) {
        boxW = w; boxH = h
        val dt = (dtMs / 1000.0).coerceIn(0.0, 0.1)
        val maxX = (w - 1).coerceAtLeast(0.0)
        val maxY = (h - 1).coerceAtLeast(0.0)
        if (!placed) { x = maxX / 2; placed = true }
        val r = (rpm ?: 0).coerceIn(0, 160)
        lastRpm = r
        val run = 1.0 + 1.8 * r / 90.0 // figurstørrelser per sekund
        when {
            onWall -> wall(dt, mode, r, maxX, maxY, run)
            y > 0.0 || vy > 0.0 -> air(dt, mode, maxX, maxY)
            else -> ground(dt, mode, maxX, maxY, run)
        }
        x = x.coerceIn(0.0, maxX)
        y = y.coerceIn(0.0, maxY)
        advanceFrame(dtMs)
    }

    private fun setAnim(a: MascotAnim) {
        if (a != anim) { anim = a; frame = 0; frameMs = 0.0 }
    }

    private fun jumpSpeed(height: Double) = sqrt(2 * g * height)

    private fun ground(dt: Double, mode: SquirrelMode, maxX: Double, maxY: Double, run: Double) {
        vy = 0.0
        when (mode) {
            SquirrelMode.IDLE -> {
                vx = 0.0
                setAnim(MascotAnim.IDLE)
                timer -= dt
                if (timer <= 0) { facingRight = !facingRight; timer = 4.0 + rnd.nextDouble() * 5.0 }
            }
            SquirrelMode.RUN, SquirrelMode.SPRINT -> {
                val speed = run * if (mode == SquirrelMode.SPRINT) 1.5 else 1.0
                vx = if (facingRight) speed else -speed
                x += vx * dt
                if (x >= maxX) { x = maxX; facingRight = false }
                if (x <= 0.0) { x = 0.0; facingRight = true }
                setAnim(MascotAnim.RUN)
                if (mode == SquirrelMode.SPRINT) {
                    timer -= dt
                    if (timer <= 0) {
                        timer = 0.8 + rnd.nextDouble() * 1.4
                        val hJump = minOf(1.6, maxY)
                        if (hJump >= 0.3) { vy = jumpSpeed(hJump); doubled = false; setAnim(MascotAnim.JUMP) }
                    }
                } else if (timer > 2.5) timer = 2.5
            }
            SquirrelMode.CLIMB -> {
                // Til nærmeste vegg, så opp
                val toRight = x >= maxX / 2
                facingRight = toRight
                vx = if (toRight) run else -run
                x += vx * dt
                val atWall = (toRight && x >= maxX) || (!toRight && x <= 0.0)
                if (atWall && maxY >= 0.5) {
                    x = if (toRight) maxX else 0.0
                    onWall = true; vx = 0.0
                    setAnim(MascotAnim.WALL)
                } else setAnim(MascotAnim.RUN)
            }
        }
    }

    private fun air(dt: Double, mode: SquirrelMode, maxX: Double, maxY: Double) {
        val before = vy
        vy -= g * dt
        y += vy * dt
        x += vx * dt
        if (x >= maxX) { x = maxX; vx = -abs(vx); facingRight = false }
        if (x <= 0.0) { x = 0.0; vx = abs(vx); facingRight = true }
        if (y >= maxY) { y = maxY; if (vy > 0) vy = 0.0 }
        bounceOffArc()
        // På toppen av hoppet: noen ganger dobbelthopp når den løper fort
        if (before > 0 && vy <= 0 && mode == SquirrelMode.SPRINT && !doubled) {
            doubled = true
            if (rnd.nextDouble() < 0.45) { vy = jumpSpeed(minOf(1.0, maxY - y).coerceAtLeast(0.2)); setAnim(MascotAnim.DOUBLE_JUMP) }
        }
        // Klatring: tar tak i veggen den treffer på vei ned
        if (mode == SquirrelMode.CLIMB && vy <= 0 && y > 0.3 && (x <= 0.0 || x >= maxX)) {
            onWall = true; vx = 0.0; vy = 0.0
            facingRight = x >= maxX
            setAnim(MascotAnim.WALL)
            return
        }
        if (y <= 0.0 && vy <= 0) {
            y = 0.0; vy = 0.0; vx = 0.0
            setAnim(if (mode == SquirrelMode.IDLE) MascotAnim.IDLE else MascotAnim.RUN)
            return
        }
        if (anim == MascotAnim.DOUBLE_JUMP && frame < count(anim) - 1) return
        setAnim(if (vy > 0) MascotAnim.JUMP else MascotAnim.FALL)
    }

    private fun wall(dt: Double, mode: SquirrelMode, r: Int, maxX: Double, maxY: Double, run: Double) {
        val rightWall = x >= maxX / 2
        facingRight = rightWall // veggbildene har veggen til høyre; speiles på venstre vegg
        val away = if (rightWall) -1.0 else 1.0
        if (mode != SquirrelMode.CLIMB) {
            // Slipper taket og faller ned
            onWall = false; vy = 0.0; vx = away * run * 0.4
            facingRight = away > 0
            y = (y - 0.01).coerceAtLeast(0.0)
            setAnim(MascotAnim.FALL)
            return
        }
        setAnim(MascotAnim.WALL)
        y += (0.3 + 0.9 * r / 65.0) * dt
        if (y >= maxY) {
            // Over kanten: hopper ut fra veggen og lander på gulvet
            y = maxY; onWall = false; doubled = true
            vy = jumpSpeed(0.6); vx = away * run * 1.2
            facingRight = away > 0
            setAnim(MascotAnim.DOUBLE_JUMP)
        }
    }

    /**
     * Treffer figuren buen i lufta, spretter den av som fra en vegg: farten speiles om
     * buens normal (snur), og figuren skyves ut av buen.
     */
    private fun bounceOffArc() {
        val a = arc ?: return
        val rad = 0.38
        val fx = x + 0.5
        val fy = y + 0.5
        val cx = a.cxFrac * boxW
        val cy = boxH * (1 - a.cyFrac)
        val rIn = a.rInFrac * boxH
        val rOut = a.rOutFrac * boxH
        val dx = fx - cx
        val dy = fy - cy
        val d = hypot(dx, dy)
        if (d < 1e-6 || d <= rIn - rad || d >= rOut + rad) return
        // Vinkel på skjermen (y ned), 0–360
        val deg = (Math.toDegrees(atan2(-dy, dx)) + 360.0) % 360.0
        if ((deg - a.startDeg + 720.0) % 360.0 > a.sweepDeg) return
        val outside = d >= (rIn + rOut) / 2
        // Normal fra buen mot figuren
        val nx = if (outside) dx / d else -dx / d
        val ny = if (outside) dy / d else -dy / d
        val dot = vx * nx + vy * ny
        if (dot < 0) {
            vx -= 2 * dot * nx; vy -= 2 * dot * ny
            vx *= 0.9; vy *= 0.9
        }
        val target = if (outside) rOut + rad else rIn - rad
        x = cx + dx / d * target - 0.5
        y = (cy + dy / d * target - 0.5).coerceAtLeast(0.0)
        if (abs(vx) > 0.05) facingRight = vx > 0
    }

    /** Bilder per sekund. Pakken er laget for 20; løp og klatring følger kadensen. */
    fun fps(a: MascotAnim = anim, rpm: Int = lastRpm): Double = fpsScale * when (a) {
        MascotAnim.RUN -> (20.0 * rpm / 90.0).coerceIn(12.0, 32.0)
        MascotAnim.WALL -> (8.0 + 10.0 * rpm / 65.0).coerceIn(8.0, 24.0)
        else -> 20.0
    }

    private fun advanceFrame(dtMs: Double) {
        frameMs += dtMs
        val dur = 1000.0 / fps()
        val n = count(anim)
        if (frame >= n) frame = 0
        while (frameMs >= dur) {
            frameMs -= dur
            frame = if (frame < n - 1) frame + 1 else if (anim.loop) 0 else frame
        }
    }
}
