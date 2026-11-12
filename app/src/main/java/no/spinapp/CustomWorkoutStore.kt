package no.spinapp

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import no.spinapp.core.Workout
import no.spinapp.core.WorkoutJson
import java.io.File

/** Egne økter, lagret lokalt i én liten JSON-fil. */
object CustomWorkoutStore {
    private lateinit var file: File
    private val _workouts = MutableStateFlow<List<Workout>>(emptyList())
    val workouts: StateFlow<List<Workout>> = _workouts

    fun init(context: Context) {
        file = File(context.filesDir, "custom_workouts.json")
        _workouts.value = if (file.exists()) WorkoutJson.listFromJson(file.readText()) else emptyList()
    }

    fun newId() = "custom-" + System.currentTimeMillis()

    fun save(w: Workout) {
        val list = _workouts.value.toMutableList()
        val i = list.indexOfFirst { it.id == w.id }
        if (i >= 0) list[i] = w else list += w
        _workouts.value = list
        write()
    }

    fun delete(id: String) {
        _workouts.value = _workouts.value.filterNot { it.id == id }
        write()
    }

    fun isCustom(id: String) = id.startsWith("custom-")

    private fun write() {
        val list = _workouts.value
        if (list.isEmpty()) { if (file.exists()) file.delete(); return }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(WorkoutJson.listToJson(list))
        tmp.renameTo(file)
    }
}
