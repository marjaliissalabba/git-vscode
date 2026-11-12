package no.spinapp.core

import kotlin.math.max
import kotlin.math.min

/** Gråtonebilde, piksler 0..255, radvis. */
class GrayImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(pixels.size == width * height) { "Feil antall piksler" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]
}

/** Normalisert rektangel (0..1) relativt til et overordnet område. */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top

    /** Rektangelet [inner], gitt relativt til dette, uttrykt i samme koordinater som dette. */
    fun sub(inner: NormRect) = NormRect(
        left + inner.left * width,
        top + inner.top * height,
        left + inner.right * width,
        top + inner.bottom * height,
    )
}

enum class FieldKind { INTEGER, DECIMAL }

data class ConsoleField(val key: String, val label: String, val rect: NormRect, val kind: FieldKind)

/**
 * Feltene på Stages-konsollet (SC-serien), relativt til LCD-glasset.
 * Venstre kolonne er nåverdi, høyre er snitt. Vi leser nåverdiene.
 */
object StagesConsoleLayout {
    val WATTS = ConsoleField("watts", "Watt", NormRect(0.08f, 0.222f, 0.40f, 0.328f), FieldKind.INTEGER)
    val RPM = ConsoleField("rpm", "RPM", NormRect(0.08f, 0.336f, 0.40f, 0.444f), FieldKind.INTEGER)
    val SPEED = ConsoleField("speed", "Fart", NormRect(0.08f, 0.454f, 0.37f, 0.558f), FieldKind.DECIMAL)

    val fields = listOf(WATTS, RPM, SPEED)

    /** Forholdet bredde/høyde på LCD-glasset. */
    const val ASPECT = 0.50f
}

data class DecodeResult(val text: String, val confidence: Float) {
    val isValid get() = text.isNotEmpty() && '?' !in text

    fun asInt(): Int? = if (isValid) text.filter { it.isDigit() }.toIntOrNull() else null
    /** Stages viser alltid én desimal, og punktumet leses ikke. 271 -> 27.1 */
    fun asTenths(): Double? = asInt()?.let { it / 10.0 }
}

/**
 * Leser 7-segment-tall (mørke segmenter på lys LCD-bakgrunn) fra et utsnitt.
 *
 * Fremgangsmåte: Otsu-terskel, fjern rammelinjer, del i siffer med
 * kolonneprojeksjon, og sjekk de sju segmentområdene i hvert siffer.
 */
object SevenSegmentDecoder {
    // Segmentbiter: a=1, b=2, c=4, d=8, e=16, f=32, g=64
    private val PATTERNS: Map<Int, Char> = mapOf(
        0b0111111 to '0',
        0b0000110 to '1',
        0b1011011 to '2',
        0b1001111 to '3',
        0b1100110 to '4',
        0b1101101 to '5',
        0b1111101 to '6',
        0b1111100 to '6', // 6 uten topp
        0b0000111 to '7',
        0b0100111 to '7', // 7 med f
        0b1111111 to '8',
        0b1101111 to '9',
        0b1100111 to '9', // 9 uten bunn
    )

    fun otsuThreshold(img: GrayImage): Int {
        val hist = IntArray(256)
        for (p in img.pixels) hist[p.coerceIn(0, 255)]++
        val total = img.pixels.size
        var sum = 0.0
        for (i in 0..255) sum += i * hist[i].toDouble()
        var sumB = 0.0
        var wB = 0
        var best = 0.0
        var threshold = 127
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t * hist[t].toDouble()
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                threshold = t
            }
        }
        return threshold
    }

    /**
     * Leser et høyrejustert tall. Tallene på LCD-en har fast sifferbredde, så vi
     * finner høyre kant og prøver ulike sifferbredder, og velger den tolkningen
     * som forklarer mest av blekket med sikrest segmenter.
     */
    fun decode(img: GrayImage, maxDigits: Int = 4): DecodeResult {
        val w = img.width
        val h = img.height
        if (w < 8 || h < 8) return DecodeResult("", 0f)
        var minV = 255
        var maxV = 0
        for (p in img.pixels) { minV = min(minV, p); maxV = max(maxV, p) }
        if (maxV - minV < 40) return DecodeResult("", 0f)

        val thr = otsuThreshold(img)
        val ink = BooleanArray(w * h) { img.pixels[it] <= thr }
        removeBorders(ink, w, h)

        // Høyden på tallene
        var rowTop = -1
        var rowBottom = -1
        for (y in 0 until h) {
            var any = false
            for (x in 0 until w) if (ink[y * w + x]) { any = true; break }
            if (any) { if (rowTop < 0) rowTop = y; rowBottom = y }
        }
        if (rowTop < 0) return DecodeResult("", 0f)
        val digitH = rowBottom - rowTop + 1
        if (digitH < 7) return DecodeResult("", 0f)
        fun inkAt(x: Int, y: Int) = x in 0 until w && y in 0 until h && ink[y * w + x]

        // Høyre kant = siste kolonne med et vertikalt segment
        var right = -1
        for (x in w - 1 downTo 0) {
            var c = 0
            for (y in rowTop..rowBottom) if (ink[y * w + x]) c++
            if (c >= digitH * 0.28) { right = x; break }
        }
        if (right < 0) return DecodeResult("", 0f)
        // Ta med en eventuell utstikkende kant av horisontale segmenter
        while (right + 1 < w && (rowTop..rowBottom).any { ink[it * w + right + 1] }) right++

        var totalInk = 0
        for (y in rowTop..rowBottom) for (x in 0..right) if (ink[y * w + x]) totalInk++
        if (totalInk == 0) return DecodeResult("", 0f)

        var best = DecodeResult("", 0f)
        var bestScore = -10f
        var pitchRatio = 0.40f
        while (pitchRatio <= 0.92f) {
            for (widthFrac in floatArrayOf(0.68f, 0.76f, 0.84f)) {
                val pitch = pitchRatio * digitH
                val cellW = max(3, (pitch * widthFrac).toInt())
                val sb = StringBuilder()
                var conf = 0f
                var covered = 0
                var bad = false
                for (k in 0 until maxDigits) {
                    val cr = (right - k * pitch).toInt()
                    val cl = cr - cellW + 1
                    if (cr < 0) break
                    var cellInk = 0
                    for (y in rowTop..rowBottom) for (x in max(0, cl)..cr) if (ink[y * w + x]) cellInk++
                    if (cellInk < digitH * 0.5) break // tom celle: tallet er slutt
                    val (ch, score) = classifyDigit(::inkAt, cl, rowTop, cellW, digitH)
                    if (ch == '?') bad = true
                    sb.append(ch)
                    conf += score
                    covered += cellInk
                }
                if (sb.isEmpty()) continue
                val meanConf = conf / sb.length
                val coverage = covered.toFloat() / totalInk
                val score = meanConf + coverage * 1.5f - (if (bad) 1f else 0f)
                if (score > bestScore) {
                    bestScore = score
                    best = DecodeResult(sb.reverse().toString(), if (bad) 0f else meanConf * min(1f, coverage))
                }
            }
            pitchRatio += 0.02f
        }
        return best
    }

    /** Fjerner rammelinjer: hele rader/kolonner og tynne linjer som går ut av utsnittet. */
    private fun removeBorders(ink: BooleanArray, w: Int, h: Int) {
        for (y in 0 until h) {
            var c = 0
            for (x in 0 until w) if (ink[y * w + x]) c++
            if (c > w * 0.75) for (x in 0 until w) ink[y * w + x] = false
        }
        for (x in 0 until w) {
            var c = 0
            for (y in 0 until h) if (ink[y * w + x]) c++
            if (c > h * 0.92) for (y in 0 until h) ink[y * w + x] = false
        }
        // Sammenhengende komponenter (8-naboskap)
        val label = IntArray(w * h) { -1 }
        val stack = IntArray(w * h)
        var next = 0
        for (start in 0 until w * h) {
            if (!ink[start] || label[start] >= 0) continue
            var sp = 0
            stack[sp++] = start
            label[start] = next
            var minX = w; var maxX = -1; var minY = h; var maxY = -1; var area = 0
            val members = ArrayList<Int>()
            while (sp > 0) {
                val p = stack[--sp]
                members += p
                area++
                val px = p % w
                val py = p / w
                minX = min(minX, px); maxX = max(maxX, px); minY = min(minY, py); maxY = max(maxY, py)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = px + dx
                    val ny = py + dy
                    if (nx in 0 until w && ny in 0 until h) {
                        val q = ny * w + nx
                        if (ink[q] && label[q] < 0) { label[q] = next; stack[sp++] = q }
                    }
                }
            }
            next++
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            val touchesSide = minX == 0 || maxX == w - 1
            val touchesTopBottom = minY == 0 || maxY == h - 1
            val lineLike = bh <= max(3, (h * 0.12).toInt()) && bw > w * 0.25
            val remove = area < 3 ||
                (touchesSide && (lineLike || bh > h * 0.9 || bw > bh * 3)) ||
                (touchesTopBottom && lineLike)
            if (remove) for (m in members) ink[m] = false
        }
    }

    private fun classifyDigit(inkAt: (Int, Int) -> Boolean, x0: Int, y0: Int, bw: Int, bh: Int): Pair<Char, Float> {
        fun px(f: Float) = (x0 + f * (bw - 1)).toInt().coerceIn(x0, x0 + bw - 1)
        fun py(f: Float) = (y0 + f * (bh - 1)).toInt().coerceIn(y0, y0 + bh - 1)

        // Horisontalt segment: andel kolonner i [xa,xb] som har blekk i [ya,yb]
        fun hSeg(ya: Float, yb: Float): Float {
            val xs = px(0.30f)..px(0.70f)
            var hit = 0
            for (x in xs) {
                for (y in py(ya)..py(yb)) if (inkAt(x, y)) { hit++; break }
            }
            return hit.toFloat() / xs.count()
        }

        // Vertikalt segment: andel rader i [ya,yb] som har blekk i [xa,xb]
        fun vSeg(xa: Float, xb: Float, ya: Float, yb: Float): Float {
            val ys = py(ya)..py(yb)
            var hit = 0
            for (y in ys) {
                for (x in px(xa)..px(xb)) if (inkAt(x, y)) { hit++; break }
            }
            return hit.toFloat() / ys.count()
        }

        val seg = floatArrayOf(
            hSeg(0.00f, 0.16f),                 // a
            vSeg(0.68f, 1.00f, 0.18f, 0.40f),   // b
            vSeg(0.68f, 1.00f, 0.60f, 0.82f),   // c
            hSeg(0.84f, 1.00f),                 // d
            vSeg(0.00f, 0.32f, 0.60f, 0.82f),   // e
            vSeg(0.00f, 0.32f, 0.18f, 0.40f),   // f
            hSeg(0.40f, 0.60f),                 // g
        )
        var bits = 0
        var margin = 1f
        for (i in seg.indices) {
            if (seg[i] >= 0.5f) bits = bits or (1 shl i)
            margin = min(margin, kotlin.math.abs(seg[i] - 0.5f) * 2f)
        }
        PATTERNS[bits]?.let { return it to (0.5f + margin / 2f) }
        // Nærmeste mønster med én bit forskjell
        val near = PATTERNS.entries.filter { Integer.bitCount(it.key xor bits) == 1 }.map { it.value }.distinct()
        if (near.size == 1) return near[0] to 0.4f
        return '?' to 0f
    }

    /** Parser et tidsfelt som "0:45:22" til sekunder. */
    fun parseTime(text: String): Int? {
        val digits = text.filter { it.isDigit() }
        if (digits.length < 3) return null
        val s = digits.takeLast(2).toInt()
        val m = digits.dropLast(2).takeLast(2).toInt()
        val hrs = digits.dropLast(4).ifEmpty { "0" }.toInt()
        if (s > 59 || m > 59) return null
        return hrs * 3600 + m * 60 + s
    }
}

/**
 * Glatter avlesninger fra kamera: forkaster umulige hopp og krever at en ny verdi
 * er sett to ganger før den godtas når hoppet er stort.
 */
class ReadingFilter(private val maxJump: Int, private val minValue: Int = 0, private val maxValue: Int) {
    private var current: Int? = null
    private var candidate: Int? = null

    fun accept(value: Int?): Int? {
        if (value == null || value < minValue || value > maxValue) return current
        val cur = current
        if (cur == null || kotlin.math.abs(value - cur) <= maxJump) {
            current = value
            candidate = null
        } else if (candidate != null && kotlin.math.abs(candidate!! - value) <= maxJump / 2 + 1) {
            current = value
            candidate = null
        } else {
            candidate = value
        }
        return current
    }

    fun reset() { current = null; candidate = null }
}
