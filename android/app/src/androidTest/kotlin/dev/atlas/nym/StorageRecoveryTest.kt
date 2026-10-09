package dev.atlas.nym

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dev.atlas.nym.core.*
import dev.atlas.nym.data.AppCrypto
import dev.atlas.nym.data.SqliteScanStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.io.StringWriter
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StorageRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val config = ScanConfig(mode = GenerationMode.SEQUENTIAL, length = 2, charset = "ab", limit = 4)
    @Test fun versionOneCooldownMigrationPreservesCheckpointAndCredentials() = runBlocking<Unit> {
        val name = "test-${UUID.randomUUID()}.db"
        val crypto = AppCrypto()
        val cfg = config.copy(proxies = listOf("socks5://user:secret@localhost:1080"))
        val deadline = System.currentTimeMillis() + 1_800_000
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY, config TEXT NOT NULL, mode TEXT NOT NULL, length INTEGER NOT NULL, status TEXT NOT NULL, cursor TEXT NOT NULL DEFAULT '0', created INTEGER NOT NULL, elapsed INTEGER NOT NULL DEFAULT 0, checked INTEGER NOT NULL DEFAULT 0, available INTEGER NOT NULL DEFAULT 0, errors INTEGER NOT NULL DEFAULT 0, requests INTEGER NOT NULL DEFAULT 0, current TEXT NOT NULL DEFAULT '', detail TEXT NOT NULL DEFAULT '')")
            db.execSQL("CREATE TABLE pending(session TEXT NOT NULL, username TEXT NOT NULL, ordinal TEXT NOT NULL, leased INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(session,username))")
            db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL("INSERT INTO sessions(id,config,mode,length,status,cursor,created,checked,available,requests) VALUES(?,?,?,?,?,?,?,?,?,?)",
                arrayOf("legacy", crypto.encode(Json.encodeToString(cfg)), cfg.mode.name, cfg.length, "COOLDOWN", "2", 0, 1, 1, 2))
            db.execSQL("INSERT INTO pending VALUES('legacy','ab','1',1)")
            db.execSQL("INSERT INTO metadata VALUES('cooldown',?)", arrayOf(deadline.toString()))
            db.version = 1
        }
        SqliteScanStore(context, crypto, name).use { store ->
            store.recover()
            val migrated = store.session("legacy")!!
            assertEquals(SessionStatus.PAUSED, migrated.status)
            assertEquals(NetworkStatus.RATE_LIMITED, migrated.networkStatus)
            assertEquals(cfg, migrated.config)
            assertEquals("2", migrated.cursor)
            assertEquals(1L, migrated.checked)
            assertEquals(1L, migrated.available)
            assertEquals(2L, migrated.requests)
            assertEquals(deadline, store.cooldownUntil())
            store.status("legacy", SessionStatus.RUNNING)
            assertEquals("ab", store.reserve("legacy", CandidateGenerator(cfg))!!.username)
        }
        context.deleteDatabase(name)
    }
    @Test fun atomicLate429PreservesStopAndMaximumDeadline() = runBlocking<Unit> {
        val name = "test-${UUID.randomUUID()}.db"
        SqliteScanStore(context, AppCrypto(), name).use { store ->
            val session = store.create(config)
            val deadline = System.currentTimeMillis() + 1_800_000
            for (status in listOf(SessionStatus.STOPPING, SessionStatus.STOPPED, SessionStatus.PAUSED)) {
                store.status(session.id, status)
                store.rateLimited(session.id, CheckResult("aa", CheckStatus.RATE_LIMITED, 429, 1_800_000), deadline)
                store.rateLimited(session.id, CheckResult("ab", CheckStatus.RATE_LIMITED, 429, 1000), deadline - 1000)
                assertEquals(status, store.session(session.id)!!.status)
                assertEquals(deadline, store.cooldownUntil())
            }
            assertEquals(6L, store.session(session.id)!!.requests)
            assertEquals(NetworkStatus.RATE_LIMITED, store.session(session.id)!!.networkStatus)
            store.status(session.id, SessionStatus.STOPPING)
            store.recover()
            assertEquals(SessionStatus.STOPPED, store.session(session.id)!!.status)
            assertEquals(deadline, store.cooldownUntil())
        }
        context.deleteDatabase(name)
    }
    @Test fun pendingCandidatesSurviveDatabaseReopenAndRecovery() = runBlocking<Unit> {
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
    @Test fun duplicateCompletionIsIdempotentAndLimitIncludesPending() = runBlocking<Unit> {
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
    @Test fun csvSearchFilteringAndCooldownSurviveClear() = runBlocking<Unit> {
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
    @Test fun dictionarySnapshotAndSeedSurviveRestart() = runBlocking<Unit> {
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
    @Test fun credentialsAreEncryptedInSqlite() = runBlocking<Unit> {
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
