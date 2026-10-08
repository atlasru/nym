package dev.atlas.nym

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.atlas.nym.core.ScanEngine
import dev.atlas.nym.core.Session
import dev.atlas.nym.core.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ScanService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Mutex()
    private val graph get() = (application as NymApplication).graph
    private var scan: Job? = null
    private var activeId: String? = null
    @Volatile private var endingByCommand = false
    private val notifications get() = getSystemService(NotificationManager::class.java)
    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Checking sessions", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: STOP
        val id = intent?.getStringExtra("session")
        if (action == START) {
            ServiceCompat.startForeground(this, NOTIFICATION, notification(null, true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
        scope.launch {
            commands.withLock {
                graph.ready.await()
                when (action) {
                    START -> {
                        if (scan?.isActive == true) return@withLock
                        if (id == null) { finishService(); return@withLock }
                        val session = graph.store.session(id)
                        if (session == null || session.status == SessionStatus.COMPLETED) { finishService(); return@withLock }
                        if (graph.store.cooldownUntil() > System.currentTimeMillis()) {
                            graph.store.status(id, SessionStatus.COOLDOWN, "Server cooldown is still active")
                            postFinished(graph.store.session(id))
                            finishService()
                            return@withLock
                        }
                        activeId = id
                        endingByCommand = false
                        graph.serviceSession.value = id
                        scan = scope.launch { runSession(id) }
                    }
                    PAUSE, STOP -> {
                        val target = activeId ?: id
                        endingByCommand = true
                        scan?.cancelAndJoin()
                        if (target != null) {
                            val status = graph.store.session(target)?.status
                            if (status != SessionStatus.COMPLETED && status != SessionStatus.COOLDOWN) {
                                graph.store.status(target, if (action == PAUSE) SessionStatus.PAUSED else SessionStatus.STOPPED,
                                    if (action == PAUSE) "Checkpoint saved" else "Stopped. Checkpoint is available.")
                            }
                            postFinished(graph.store.session(target))
                        }
                        graph.serviceSession.value = null
                        graph.requestRate.value = 0.0
                        finishService()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }
    private suspend fun runSession(id: String) {
        var transport: dev.atlas.nym.core.CheckTransport? = null
        var last = SystemClock.elapsedRealtime()
        val rates = ArrayDeque<Pair<Long, Long>>()
        rates.addLast(last to (graph.store.session(id)?.requests ?: 0L))
        val ticker = scope.launch {
            while (true) {
                delay(1000)
                val now = SystemClock.elapsedRealtime()
                graph.store.addElapsed(id, now - last)
                last = now
                val session = graph.store.session(id)
                graph.activeRoute.value = when (val client = transport) {
                    is dev.atlas.nym.core.HttpTransport -> client.activeRoute
                    is NetworkAwareTransport -> client.http.activeRoute
                    else -> "Direct"
                }
                if (session != null) {
                    rates.addLast(now to session.requests)
                    while (rates.size > 1 && now - rates.first().first > 5000) rates.removeFirst()
                    val first = rates.first()
                    graph.requestRate.value = if (now > first.first) (session.requests - first.second).toDouble() * 1000 / (now - first.first) else 0.0
                    notifications.notify(NOTIFICATION, notification(session, true))
                }
            }
        }
        try {
            val config = requireNotNull(graph.store.session(id)).config
            transport = graph.transportFactory(config)
            ScanEngine(graph.store, transport).run(id)
        } catch (canceled: CancellationException) { throw canceled }
        catch (failure: Exception) { graph.store.status(id, SessionStatus.ERROR, "${failure.javaClass.simpleName}: ${failure.message}") }
        finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                ticker.cancelAndJoin()
                graph.store.addElapsed(id, (SystemClock.elapsedRealtime() - last).coerceAtLeast(0))
                // Pause/Stop saves its final state before stopping the service.
                // stopSelf here would let onDestroy cancel the waiting command.
                if (!endingByCommand) {
                    if (graph.store.session(id)?.status == SessionStatus.RUNNING) graph.store.status(id, SessionStatus.INTERRUPTED, "Checkpoint saved")
                    graph.serviceSession.value = null
                    graph.requestRate.value = 0.0
                    postFinished(graph.store.session(id))
                    finishService()
                }
            }
        }
    }
    private fun postFinished(session: Session?) {
        stopForeground(STOP_FOREGROUND_DETACH)
        if (session != null) notifications.notify(NOTIFICATION, notification(session, false))
    }
    private fun finishService() { stopForeground(STOP_FOREGROUND_DETACH); stopSelf() }
    private fun notification(session: Session?, running: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = session?.let { "${it.checked} checked · ${it.available} available · ${it.current.ifBlank { "Ready" }}" } ?: "Preparing session…"
        val builder = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_nym).setContentTitle("Nym · ${if (running) "Checking" else session?.status?.name?.lowercase() ?: "Ready"}")
            .setContentText(text).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(running).setSilent(true)
        val id = session?.id ?: activeId
        if (running) builder.addAction(0, "Pause", action(PAUSE, id))
        else if (session?.status != SessionStatus.COMPLETED) builder.addAction(0, "Resume", action(START, id))
        builder.addAction(0, "Stop", action(STOP, id))
        return builder.build()
    }
    private fun action(action: String, id: String?): PendingIntent {
        val intent = Intent(this, ScanService::class.java).setAction(action).putExtra("session", id)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (action == START) PendingIntent.getForegroundService(this, action.hashCode(), intent, flags)
            else PendingIntent.getService(this, action.hashCode(), intent, flags)
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        endingByCommand = true
        scan?.cancel()
        activeId?.let { id -> graph.scope.launch { graph.store.status(id, SessionStatus.INTERRUPTED, "Android foreground-service time limit reached. Resume from the app.") } }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() { scope.cancel(); graph.serviceSession.value = null; graph.requestRate.value = 0.0; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val START = "nym.START"
        const val PAUSE = "nym.PAUSE"
        const val STOP = "nym.STOP"
        const val CHANNEL = "nym-checking"
        const val NOTIFICATION = 1001
        fun send(context: Context, action: String, id: String?) {
            val intent = Intent(context, ScanService::class.java).setAction(action).putExtra("session", id)
            if (action == START) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
        }
    }
}
