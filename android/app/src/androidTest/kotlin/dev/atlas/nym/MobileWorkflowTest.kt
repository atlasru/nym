package dev.atlas.nym

import android.app.NotificationManager
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Process
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.atlas.nym.core.*
import dev.atlas.nym.data.Preferences
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class MobileWorkflowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private lateinit var server: MockWebServer
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val graph get() = (rule.activity.application as NymApplication).graph
    private val model get() = rule.activity.model
    @Before fun prepare() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val available = JSONObject(request.body.readUtf8()).getString("username").contains("a")
                return MockResponse().setBody("{\"taken\":${!available}}")
            }
        }
        server.start()
        runBlocking { graph.ready.await(); if (graph.serviceSession.value == null) graph.store.clearHistory() }
        graph.transportFactory = { HttpTransport(it, server.url("/check").toString()) }
        rule.waitUntil(10_000) { model.loaded.value }
        rule.runOnUiThread { model.update(Preferences(scan = ScanConfig(mode = GenerationMode.SEQUENTIAL, length = 3, charset = "abcd", limit = 32, intervalMs = 250, jitterMs = 0, workers = 2))) }
        if (Build.VERSION.SDK_INT >= 33) device.executeShellCommand("pm grant dev.atlas.nym android.permission.POST_NOTIFICATIONS")
    }
    @After fun cleanup() {
        val id = graph.serviceSession.value
        if (id != null) {
            ScanService.send(rule.activity, ScanService.STOP, id)
            rule.waitUntil(10_000) { graph.serviceSession.value == null }
        }
        server.shutdown()
    }
    private fun start(waitRunning: Boolean = true) {
        rule.onNodeWithTag("nav_Home").performClick()
        rule.onNodeWithTag("start").performClick()
        if (waitRunning) rule.waitUntil(10_000) { graph.serviceSession.value != null }
    }
    private fun screenshot(name: String) {
        rule.waitForIdle()
        Thread.sleep(300) // Let SurfaceFlinger present the Compose frame before capture.
        val directory = File(rule.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }
    @Test fun navigationStateRestorationAndLandscape() {
        for (screen in listOf("Results", "Sessions", "Settings", "Home")) {
            rule.onNodeWithTag("nav_$screen").performClick()
            rule.onNodeWithTag("screen_$screen").assertIsDisplayed()
        }
        rule.onNodeWithTag("nav_Settings").performClick()
        rule.activityRule.scenario.recreate()
        rule.onNodeWithTag("screen_Settings").assertIsDisplayed()
        device.setOrientationLeft()
        rule.onNodeWithTag("nav_Results").performClick()
        rule.onNodeWithTag("screen_Results").assertIsDisplayed()
        device.setOrientationNatural()
    }
    @Test fun foregroundServiceContinuesInBackgroundAndNotificationControlsWork() {
        rule.runOnUiThread { model.update(model.preferences.value.copy(scan = model.preferences.value.scan.copy(length = 4, charset = "abcde", limit = 200))) }
        start()
        rule.waitUntil(10_000) { server.requestCount >= 2 }
        val before = server.requestCount
        device.pressHome()
        rule.waitUntil(10_000) { server.requestCount >= before + 3 }
        val manager = rule.activity.getSystemService(NotificationManager::class.java)
        val notification = manager.activeNotifications.first { it.id == ScanService.NOTIFICATION }.notification
        val services = device.executeShellCommand("dumpsys activity services dev.atlas.nym")
        assertTrue("Service is not foreground: $services", services.contains("isForeground=true"))
        notification.actions.first { it.title.toString() == "Pause" }.actionIntent.send()
        rule.waitUntil(10_000) { graph.serviceSession.value == null }
        val session = runBlocking { graph.store.sessions().first() }
        rule.waitUntil(10_000) { runBlocking { graph.store.session(session.id)!!.status == SessionStatus.PAUSED } }
        val pausedCount = server.requestCount
        Thread.sleep(750)
        assertEquals(pausedCount, server.requestCount)
        manager.activeNotifications.first { it.id == ScanService.NOTIFICATION }.notification.actions.first { it.title.toString() == "Resume" }.actionIntent.send()
        rule.waitUntil(10_000) { server.requestCount > pausedCount }
        manager.activeNotifications.first { it.id == ScanService.NOTIFICATION }.notification.actions.first { it.title.toString() == "Stop" }.actionIntent.send()
        rule.waitUntil(10_000) { graph.serviceSession.value == null }
        rule.waitUntil(10_000) { runBlocking { graph.store.session(session.id)!!.status == SessionStatus.STOPPED } }
    }
    @Test fun pauseResumePersistResultsAndCaptureRealScreens() {
        start()
        rule.waitUntil(10_000) { server.requestCount >= 6 }
        screenshot("01-home-running")
        rule.onNodeWithTag("pause").performClick()
        rule.waitUntil(10_000) { graph.serviceSession.value == null }
        val session = runBlocking { graph.store.sessions().first() }
        rule.waitUntil(10_000) { runBlocking { graph.store.session(session.id)!!.status == SessionStatus.PAUSED } }
        screenshot("02-home-paused")
        rule.activityRule.scenario.recreate()
        rule.onNodeWithTag("nav_Results").performClick()
        rule.waitUntil(10_000) { !model.results.value.loading && model.results.value.items.isNotEmpty() }
        rule.onNodeWithTag("result_list").assertIsDisplayed()
        screenshot("03-results")
        rule.onNodeWithTag("result_search").performTextInput("aaa")
        rule.onNode(hasText("aaa") and !hasTestTag("result_search")).assertExists()
        rule.onNodeWithTag("result_search").performTextClearance()
        rule.onNodeWithTag("nav_Sessions").performClick()
        screenshot("04-sessions")
        rule.onNodeWithTag("nav_Settings").performClick()
        screenshot("05-settings")
        rule.onNodeWithTag("nav_Home").performClick()
        rule.onNodeWithTag("resume").performClick()
        rule.waitUntil(20_000) { runBlocking { graph.store.session(session.id)?.status == SessionStatus.COMPLETED } }
        val items = runBlocking { graph.store.results("", null, false, 100, session.id) }
        assertEquals(32, items.size)
        assertEquals(32, items.map { it.result.username }.distinct().size)
        assertTrue(server.requestCount in 32..34)
    }
    @Test fun server429StopsAllWorkersAndPersistsCooldown() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(429).setHeader("Retry-After", "2").setBody("{\"retry_after\":2}")
        }
        start(false)
        rule.waitUntil(10_000) { server.requestCount > 0 }
        rule.waitUntil(10_000) { graph.serviceSession.value == null }
        val session = runBlocking { graph.store.sessions().first() }
        assertEquals(SessionStatus.COOLDOWN, session.status)
        assertEquals(1, server.requestCount)
        assertTrue(runBlocking { graph.store.cooldownUntil() } > System.currentTimeMillis())
        rule.onNodeWithTag("resume").assertIsNotEnabled()
        Thread.sleep(2200)
    }
    @Test fun measuredCpuAndMemoryOnEmulator() {
        val idleCpu = Process.getElapsedCpuTime()
        val idleStart = android.os.SystemClock.elapsedRealtime()
        Thread.sleep(2000)
        val idleUsed = Process.getElapsedCpuTime() - idleCpu
        val cpu = Process.getElapsedCpuTime(); val startTime = android.os.SystemClock.elapsedRealtime()
        start()
        rule.waitUntil(15_000) { server.requestCount >= 8 }
        val activeCpu = Process.getElapsedCpuTime() - cpu
        val elapsed = android.os.SystemClock.elapsedRealtime() - startTime
        val info = android.os.Debug.MemoryInfo().apply { android.os.Debug.getMemoryInfo(this) }
        val directory = File(rule.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, "performance.txt").writeText("API=${Build.VERSION.SDK_INT}\nABI=${Build.SUPPORTED_ABIS.joinToString()}\nidle_cpu_ms=$idleUsed\nidle_elapsed_ms=${android.os.SystemClock.elapsedRealtime() - idleStart - elapsed}\nactive_cpu_ms=$activeCpu\nactive_elapsed_ms=$elapsed\nrequests=${server.requestCount}\ntotal_pss_kib=${info.totalPss}\nMock network, emulator measurements; not a physical-device throughput or battery claim.\n")
    }
}
