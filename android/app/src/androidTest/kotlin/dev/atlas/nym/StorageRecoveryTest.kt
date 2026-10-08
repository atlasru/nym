package dev.atlas.nym

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import dev.atlas.nym.core.*
import dev.atlas.nym.data.AppCrypto
import dev.atlas.nym.data.SqliteScanStore
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.io.StringWriter
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StorageRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val config = ScanConfig(mode = GenerationMode.SEQUENTIAL, length = 2, charset = "ab", limit = 4)
    @Test fun pendingCandidatesSurviveDatabaseReopenAndRecovery() = runBlocking {
        val name = "test-${UUID.randomUUID()}.db"
        var store = SqliteScanStore(context, AppCrypto(), name)
        val session = store.create(config)
        store.status(session.id, SessionStatus.RUNNING)
        val generator = CandidateGenerator(config)
        val a = store.reserve(session.id, generator)!!
        val b = store.reserve(session.id, generator)!!
        assertEquals("aa", a.username); assertEquals("ab", b.username)
        store.complete(session.id, a, CheckResult(a.username, CheckStatus.AVAILABLE, 200))
        store.setCooldown(System.currentTimeMillis() + 120_000)
        store.close()
        store = SqliteScanStore(context, AppCrypto(), name)
        store.recover()
        assertEquals(SessionStatus.INTERRUPTED, store.session(session.id)!!.status)
        assertEquals("2", store.session(session.id)!!.cursor)
        assertTrue(store.cooldownUntil() > System.currentTimeMillis())
        store.status(session.id, SessionStatus.RUNNING)
        val resumed = store.reserve(session.id, generator)!!
        assertEquals(b, resumed)
        store.complete(session.id, resumed, CheckResult(resumed.username, CheckStatus.UNAVAILABLE, 200))
        assertEquals("ba", store.reserve(session.id, generator)!!.username)
        assertEquals(2L, store.session(session.id)!!.checked)
        assertEquals(1L, store.session(session.id)!!.available)
        store.close(); context.deleteDatabase(name)
    }
    @Test fun duplicateCompletionIsIdempotentAndLimitIncludesPending() = runBlocking {
        val name = "test-${UUID.randomUUID()}.db"
        SqliteScanStore(context, AppCrypto(), name).use { store ->
            val session = store.create(config.copy(limit = 1))
            store.status(session.id, SessionStatus.RUNNING)
            val generator = CandidateGenerator(config)
            val candidate = store.reserve(session.id, generator)!!
            assertNull(store.reserve(session.id, generator))
            val result = CheckResult(candidate.username, CheckStatus.AVAILABLE, 200)
            store.complete(session.id, candidate, result); store.complete(session.id, candidate, result)
            assertEquals(1L, store.session(session.id)!!.checked)
            assertEquals(1L, store.resultCount())
            assertNull(store.reserve(session.id, generator))
        }
        context.deleteDatabase(name)
    }
    @Test fun csvSearchFilteringAndCooldownSurviveClear() = runBlocking {
        val name = "test-${UUID.randomUUID()}.db"
        SqliteScanStore(context, AppCrypto(), name).use { store ->
            val session = store.create(config)
            store.status(session.id, SessionStatus.RUNNING)
            val candidate = store.reserve(session.id, CandidateGenerator(config))!!
            store.complete(session.id, candidate, CheckResult("aa", CheckStatus.AVAILABLE, 200, detail = "a,b\n\"quoted\""))
            assertEquals(1, store.results("aa", CheckStatus.AVAILABLE, true, 200).size)
            assertEquals(0, store.results("_", null, false, 200).size)
            val output = StringWriter()
            store.export(output, "", CheckStatus.AVAILABLE, false)
            assertTrue(output.toString().contains("\"a,b\n\"\"quoted\"\"\""))
            assertEquals("\"'=command\"", SqliteScanStore.csv("=command"))
            val until = System.currentTimeMillis() + 60_000
            store.setCooldown(until)
            store.status(session.id, SessionStatus.STOPPED)
            store.clearHistory()
            assertEquals(0L, store.resultCount())
            assertEquals(until, store.cooldownUntil())
        }
        context.deleteDatabase(name)
    }
    @Test fun dictionarySnapshotAndSeedSurviveRestart() = runBlocking {
        val name = "test-${UUID.randomUUID()}.db"
        val cfg = config.copy(mode = GenerationMode.DICTIONARY, dictionary = "ALPHA\nalpha\nbeta\ngamma", seed = -999)
        var store = SqliteScanStore(context, AppCrypto(), name)
        val session = store.create(cfg)
        store.status(session.id, SessionStatus.RUNNING)
        val candidate = store.reserve(session.id, CandidateGenerator(cfg))!!
        store.complete(session.id, candidate, CheckResult(candidate.username, CheckStatus.AVAILABLE, 200))
        store.close()
        store = SqliteScanStore(context, AppCrypto(), name)
        store.recover()
        val restored = store.session(session.id)!!
        assertEquals(cfg, restored.config)
        store.status(session.id, SessionStatus.RUNNING)
        assertEquals("beta", store.reserve(session.id, CandidateGenerator(restored.config))!!.username)
        store.close(); context.deleteDatabase(name)
    }
    @Test fun credentialsAreEncryptedInSqlite() = runBlocking {
        val name = "test-${UUID.randomUUID()}.db"
        SqliteScanStore(context, AppCrypto(), name).use { store ->
            val cfg = config.copy(proxyEnabled = true, proxies = listOf("http://user:secret-test-password@localhost:8080"))
            val session = store.create(cfg)
            assertEquals(cfg, store.session(session.id)!!.config)
            val bytes = context.getDatabasePath(name).readBytes().toString(Charsets.ISO_8859_1) + context.getDatabasePath("$name-wal").readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(bytes.contains("secret-test-password"))
        }
        context.deleteDatabase(name)
    }
}
