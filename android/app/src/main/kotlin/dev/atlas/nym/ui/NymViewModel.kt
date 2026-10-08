@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.atlas.nym.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.atlas.nym.NetworkMonitor
import dev.atlas.nym.NetworkRoute
import dev.atlas.nym.NymApplication
import dev.atlas.nym.ScanService
import dev.atlas.nym.core.CheckStatus
import dev.atlas.nym.core.HttpTransport
import dev.atlas.nym.core.Session
import dev.atlas.nym.core.SessionStatus
import dev.atlas.nym.data.Preferences
import dev.atlas.nym.data.StoredResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ResultQuery(val search: String = "", val status: CheckStatus? = CheckStatus.AVAILABLE, val ascending: Boolean = false, val limit: Int = 200, val sessionId: String? = null)
class NymViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    val graph = (application as NymApplication).graph
    val preferences = MutableStateFlow(Preferences())
    val loaded = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val diagnostics = MutableStateFlow<List<String>>(emptyList())
    val query = MutableStateFlow(ResultQuery())
    val selectedId = saved.getStateFlow<String?>("session", null)
    val destination = saved.getStateFlow("destination", "Home")
    val network = graph.network
    val sessions: StateFlow<List<Session>> = graph.store.changes.mapLatest { graph.ready.await(); graph.store.sessions() }
        .catch { message.value = "Cannot open history: ${it.message}"; emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val current = combine(sessions, selectedId) { items, id -> (items.find { it.id == id } ?: items.firstOrNull())?.let { graph.store.session(it.id) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val results: StateFlow<List<StoredResult>> = combine(query, graph.store.changes) { query, _ -> query }.mapLatest {
        graph.ready.await(); graph.store.results(it.search, it.status, it.ascending, it.limit, it.sessionId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val cooldown = MutableStateFlow(0L)
    val clock = flow {
        while (true) { emit(System.currentTimeMillis()); delay(1000) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), System.currentTimeMillis())
    private var save: Job? = null
    init {
        viewModelScope.launch {
            graph.settings.flow.catch { message.value = "Cannot load settings: ${it.message}"; emit(Preferences()) }.collect {
                if (!loaded.value) { preferences.value = it; loaded.value = true }
            }
        }
        viewModelScope.launch {
            graph.store.changes.collect { cooldown.value = graph.store.cooldownUntil() }
        }
    }
    fun navigate(destination: String) { saved["destination"] = destination }
    fun update(preferences: Preferences) {
        this.preferences.value = preferences
        save?.cancel()
        save = viewModelScope.launch {
            delay(400)
            if (runCatching { preferences.scan.validate() }.isSuccess) {
                runCatching { graph.settings.save(preferences) }.onFailure { message.value = "Settings were not saved: ${it.message}" }
            }
        }
    }
    fun saveSettings() = work {
        graph.settings.save(preferences.value)
        message.value = "Settings saved"
    }
    fun start() = work {
        check(graph.serviceSession.value == null) { "Pause the current session first" }
        check(graph.store.cooldownUntil() <= System.currentTimeMillis()) { "Server cooldown is still active" }
        preferences.value.scan.validate()
        graph.settings.save(preferences.value)
        val session = graph.store.create(preferences.value.scan)
        saved["session"] = session.id
        navigate("Home")
        ScanService.send(getApplication(), ScanService.START, session.id)
    }
    fun resume(session: Session) = work {
        check(graph.serviceSession.value == null) { "Pause the current session first" }
        check(graph.store.cooldownUntil() <= System.currentTimeMillis()) { "Server cooldown is still active" }
        saved["session"] = session.id
        navigate("Home")
        ScanService.send(getApplication(), ScanService.START, session.id)
    }
    fun pause(checkpoint: Boolean = false) {
        current.value?.let { ScanService.send(getApplication(), ScanService.PAUSE, it.id) }
        if (checkpoint) message.value = "Saving checkpoint…"
    }
    fun stop() { current.value?.let { ScanService.send(getApplication(), ScanService.STOP, it.id) } }
    fun clear() = work { check(graph.serviceSession.value == null) { "Pause the active session first" }; graph.store.clearHistory(); saved["session"] = null; message.value = "History cleared" }
    fun sessionResults(session: Session) { query.value = query.value.copy(sessionId = session.id, status = null); navigate("Results") }
    fun importDictionary(uri: Uri) = work {
        val text = withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 2_000_000) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                val bytes = output.toByteArray()
                require(bytes.size <= 2_000_000) { "Dictionary exceeds 2 MB" }
                bytes.toString(Charsets.UTF_8)
            } ?: error("Cannot read dictionary")
        }
        update(preferences.value.copy(scan = preferences.value.scan.copy(dictionary = text)))
        message.value = "Dictionary imported"
    }
    fun export(uri: Uri) = work {
        val filter = query.value
        withContext(Dispatchers.IO) {
            val stream = getApplication<Application>().contentResolver.openOutputStream(uri) ?: error("Cannot open export file")
            stream.bufferedWriter().use { graph.store.export(it, filter.search, filter.status, filter.ascending, filter.sessionId) }
        }
        message.value = "CSV exported"
    }
    fun diagnose() = work {
        check(graph.serviceSession.value == null) { "Pause checking before testing routes" }
        check(graph.store.cooldownUntil() <= System.currentTimeMillis()) { "Diagnostics wait for server cooldown too" }
        preferences.value.scan.validate()
        val transport = HttpTransport(preferences.value.scan)
        try { diagnostics.value = transport.diagnose { graph.store.setCooldown(System.currentTimeMillis() + it) } } finally { transport.close() }
    }
    fun work(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { graph.ready.await(); block() } catch (failure: Exception) { message.value = failure.message ?: failure.javaClass.simpleName }
            finally { busy.value = false }
        }
    }
}
