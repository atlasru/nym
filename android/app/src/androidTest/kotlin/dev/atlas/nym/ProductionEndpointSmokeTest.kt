package dev.atlas.nym

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.nym.core.CheckStatus
import dev.atlas.nym.core.HttpTransport
import dev.atlas.nym.core.ScanConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit opt-in: exactly one availability request, with no retry or mutation. */
@RunWith(AndroidJUnit4::class)
class ProductionEndpointSmokeTest {
    @Test fun oneLegitimateRequestClassifiesTheServerResponse() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveSmoke") == "true")
        val username = "nym_mobile_${System.currentTimeMillis()}"
        val application = ApplicationProvider.getApplicationContext<NymApplication>()
        val result = HttpTransport(ScanConfig(retries = 0)).use { it.check(username) }
        val directory = File(application.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, "production-smoke.txt").writeText("API=${Build.VERSION.SDK_INT}\nrequests=1\nusername=$username\nhttp_status=${result.httpStatus}\nclassification=${result.status}\nretry_after_ms=${result.retryAfterMs}\nlatency_ms=${result.latencyMs}\ndetail=${result.detail}\n")
        if (result.status == CheckStatus.RATE_LIMITED) {
            application.graph.ready.await()
            application.graph.store.setCooldown(System.currentTimeMillis() + (result.retryAfterMs ?: 60_000))
        }
        assertNotNull("No HTTP response: ${result.detail}", result.httpStatus)
        if (result.httpStatus == 200) assertTrue(result.status == CheckStatus.AVAILABLE || result.status == CheckStatus.UNAVAILABLE)
        if (result.httpStatus == 429) assertTrue((result.retryAfterMs ?: 0) >= 1000)
    }
}
