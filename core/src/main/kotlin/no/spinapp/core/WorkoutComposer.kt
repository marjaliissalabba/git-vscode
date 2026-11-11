package no.spinapp.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToInt

/** Oppvarming som legges foran alle økter, med valgfri lengde. */
object Warmup {
    val OPTIONS_MIN = listOf(0, 5, 8, 10, 15)

    fun build(minutes: Int): List<Segment> {
        if (minutes <= 0) return emptyList()
        val total = minutes * 60
        if (minutes < 4) return listOf(Segment("Oppvarming", total, 45, 60, 80, 95, isWarmup = true))
        val a = (total * 0.5).roundToInt()
        val b = (total * 0.3).roundToInt()
        val c = total - a - b
        return listOf(
            Segment("Oppvarming", a, 45, 55, 80, 90, isWarmup = true),
            Segment("Oppvarming", b, 56, 65, 85, 95, isWarmup = true),
            Segment("Oppvarming – rask fot", c, 60, 70, 95, 105, isWarmup = true),
        )
    }
}

object WorkoutComposer {
    /** Korteste lengde et intervall kan skaleres ned til. */
    const val MIN_SEGMENT_SEC = 15

    /** Avrund til nærmeste 5 sekunder. */
    private fun round5(sec: Double) = max(MIN_SEGMENT_SEC, (sec / 5.0).roundToInt() * 5)

    /**
     * Skalerer alle intervaller (og ståperioder) så hoveddelen varer ca. [targetMinutes].
     * Oppvarming i [workout] tas ikke med.
     */
    fun scale(workout: Workout, targetMinutes: Int): Workout {
        val main = workout.segments.filter { !it.isWarmup }
        val current = main.sumOf { it.durationSec }
        if (current == 0 || targetMinutes * 60 == current) return workout.copy(segments = main)
        val f = targetMinutes * 60.0 / current
        val scaled = main.map { s ->
            val d = round5(s.durationSec * f)
            s.copy(
                durationSec = d,
                stand = s.stand.map { w ->
                    val from = (w.fromSec * f).roundToInt().coerceIn(0, d)
                    val to = (w.toSec * f).roundToInt().coerceIn(from, d)
                    StandWindow(from, to)
                }.filter { it.lengthSec >= 5 },
            )
        }
        return workout.copy(segments = scaled)
    }

    /** Ferdig økt: oppvarming + (eventuelt skalert) hoveddel. */
    fun compose(workout: Workout, warmupMinutes: Int, targetMinutes: Int? = null): Workout {
        val main = if (targetMinutes != null) scale(workout, targetMinutes).segments
        else workout.segments.filter { !it.isWarmup }
        return workout.copy(segments = Warmup.build(warmupMinutes) + main)
    }

    fun mainMinutes(workout: Workout) = workout.segments.filter { !it.isWarmup }.sumOf { it.durationSec } / 60

    /** Valgbare lengder: 50–150 % av originalen i steg på 5 min. */
    fun lengthOptions(workout: Workout): List<Int> {
        val m = mainMinutes(workout).coerceAtLeast(5)
        val lo = max(5, ((m * 0.5) / 5).roundToInt() * 5)
        val hi = ((m * 1.5) / 5).roundToInt() * 5
        return (lo..hi step 5).toList().let { if (m in it) it else (it + m).sorted() }
    }
}

/** Status for stå opp-signaler i et intervall. */
data class StandCue(
    /** Står vi nå? */
    val standing: Boolean,
    /** Sekunder igjen av ståperioden (når [standing]). */
    val standLeftSec: Int?,
    /** Sekunder til neste ståperiode starter (bare de siste 10 sekundene før). */
    val standInSec: Int?,
)

object StandCues {
    const val WARN_SEC = 10

    fun at(segment: Segment?, secondsIn: Int): StandCue {
        if (segment == null) return StandCue(false, null, null)
        val now = segment.stand.firstOrNull { secondsIn >= it.fromSec && secondsIn < it.toSec }
        if (now != null) return StandCue(true, now.toSec - secondsIn, null)
        val next = segment.stand.filter { it.fromSec > secondsIn }.minByOrNull { it.fromSec }
        val inSec = next?.let { it.fromSec - secondsIn }?.takeIf { it <= WARN_SEC }
        return StandCue(false, null, inSec)
    }
}

/** Lagring av egne økter som JSON. */
object WorkoutJson {
    fun toJson(w: Workout): JSONObject = JSONObject()
        .put("id", w.id)
        .put("name", w.name)
        .put("description", w.description)
        .put("segments", JSONArray().apply {
            w.segments.filter { !it.isWarmup }.forEach { s ->
                put(JSONObject()
                    .put("n", s.name).put("d", s.durationSec)
                    .put("pl", s.powerLowPct).put("ph", s.powerHighPct)
                    .put("cl", s.cadenceLow).put("ch", s.cadenceHigh)
                    .put("ftp", s.isFtpTest)
                    .put("cd", s.isCooldown)
                    .put("st", JSONArray().apply { s.stand.forEach { put(JSONArray().put(it.fromSec).put(it.toSec)) } }))
            }
        })

    fun fromJson(j: JSONObject): Workout? = runCatching {
        val arr = j.getJSONArray("segments")
        val segs = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val st = o.optJSONArray("st") ?: JSONArray()
            Segment(
                name = o.optString("n", "Intervall"),
                durationSec = o.getInt("d").coerceAtLeast(5),
                powerLowPct = o.getInt("pl"),
                powerHighPct = o.getInt("ph"),
                cadenceLow = o.getInt("cl"),
                cadenceHigh = o.getInt("ch"),
                isFtpTest = o.optBoolean("ftp", false),
                isCooldown = o.optBoolean("cd", false),
                stand = (0 until st.length()).map { k ->
                    val p = st.getJSONArray(k)
                    StandWindow(p.getInt(0), p.getInt(1))
                },
            )
        }
        Workout(j.getString("id"), j.optString("name", "Min økt"), j.optString("description", ""), segs)
    }.getOrNull()

    fun listToJson(list: List<Workout>): String = JSONArray().apply { list.forEach { put(toJson(it)) } }.toString()

    fun listFromJson(text: String): List<Workout> = runCatching {
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull { fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())
}
