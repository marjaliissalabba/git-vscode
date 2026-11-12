package no.spinapp

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import no.spinapp.core.HistoryPolicy
import no.spinapp.core.Retention
import no.spinapp.core.RideRecord
import java.io.File

/**
 * Lagrer øktene lokalt i én liten fil (én linje per økt). Ingenting sendes noe sted.
 * Gamle økter slettes automatisk etter valgt lagringstid.
 */
object HistoryStore {
    private lateinit var file: File
    private val _records = MutableStateFlow<List<RideRecord>>(emptyList())
    val records: StateFlow<List<RideRecord>> = _records

    fun init(context: Context) {
        file = File(context.filesDir, "history.jsonl")
        val loaded = if (file.exists()) file.readLines().mapNotNull { RideRecord.fromJson(it) } else emptyList()
        _records.value = loaded
        prune()
    }

    fun add(record: RideRecord) {
        if (!HistoryPolicy.shouldStore(record)) return
        _records.value = _records.value + record
        prune(forceWrite = true)
    }

    fun delete(record: RideRecord) {
        _records.value = _records.value - record
        write()
    }

    fun deleteAll() {
        _records.value = emptyList()
        if (file.exists()) file.delete()
    }

    /** Bruker valgt lagringstid; skriver bare til disk hvis noe faktisk endres. */
    fun prune(forceWrite: Boolean = false) {
        val before = _records.value
        val after = HistoryPolicy.prune(before, System.currentTimeMillis() / 1000, AppState.retention)
        _records.value = after
        if (forceWrite || after.size != before.size) write()
    }

    fun sizeBytes(): Long = if (file.exists()) file.length() else 0

    private fun write() {
        val list = _records.value
        if (list.isEmpty()) {
            if (file.exists()) file.delete()
            return
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(list.joinToString("\n") { it.toJson() })
        tmp.renameTo(file)
    }

    fun setRetention(r: Retention) {
        AppState.retention = r
        prune()
    }
}
