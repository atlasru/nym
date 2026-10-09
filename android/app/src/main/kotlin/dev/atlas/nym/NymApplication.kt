package dev.atlas.nym

import android.app.Application
import dev.atlas.nym.core.CheckTransport
import dev.atlas.nym.core.HttpTransport
import dev.atlas.nym.core.ScanConfig
import dev.atlas.nym.data.AppCrypto
import dev.atlas.nym.data.SettingsStore
import dev.atlas.nym.data.SqliteScanStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import dev.atlas.nym.core.CheckResult
import dev.atlas.nym.core.NetworkStatus

class NymApplication : Application() {
    lateinit var graph: AppGraph
        private set
    override fun onCreate() { super.onCreate(); graph = AppGraph(this) }
}
class AppGraph(application: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val crypto = AppCrypto()
    val store = SqliteScanStore(application, crypto)
    val settings = SettingsStore(application, crypto)
    val ready = scope.async { store.recover() }
    val serviceSession = MutableStateFlow<String?>(null)
    val requestRate = MutableStateFlow(0.0)
    val activeRoute = MutableStateFlow("Direct")
    val networkStatus = MutableStateFlow(NetworkStatus.READY)
    var diagnosticEndpoint = "https://example.com/"
    val network = NetworkMonitor(application).flow.stateIn(scope, SharingStarted.WhileSubscribed(5000), NetworkRoute())
    // Instrumentation injects a loopback transport. No endpoint override in the product UI.
    var transportFactory: (ScanConfig) -> CheckTransport = { config ->
        NetworkAwareTransport(HttpTransport(config)) {
            if (!network.value.online) networkStatus.value = NetworkStatus.OFFLINE
            network.first { it.online }
            networkStatus.value = NetworkStatus.REQUESTING
        }
    }
}
class NetworkAwareTransport(val http: HttpTransport, private val awaitNetwork: suspend () -> Unit) : CheckTransport {
    override suspend fun check(username: String): CheckResult { awaitNetwork(); return http.check(username) }
    override fun cancel() = http.cancel()
    override fun close() = http.close()
}
