package dev.atlas.nym.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.atlas.nym.core.Candidate
import dev.atlas.nym.core.CandidateGenerator
import dev.atlas.nym.core.CheckResult
import dev.atlas.nym.core.CheckStatus
import dev.atlas.nym.core.ScanConfig
import dev.atlas.nym.core.ScanStore
import dev.atlas.nym.core.Session
import dev.atlas.nym.core.SessionStatus
import dev.atlas.nym.core.NetworkStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.Writer
import java.util.UUID

data class StoredResult(val id: Long, val sessionId: String, val result: CheckResult)

class SqliteScanStore(context: Context, private val crypto: AppCrypto, name: String = "nym.db") : ScanStore, AutoCloseable {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val configs = object : LinkedHashMap<String, ScanConfig>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ScanConfig>?) = size > 8
    }
    val changes = MutableStateFlow(0L)
    private val helper = object : SQLiteOpenHelper(context, name, null, 2) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY, config TEXT NOT NULL, mode TEXT NOT NULL, length INTEGER NOT NULL, status TEXT NOT NULL, cursor TEXT NOT NULL DEFAULT '0', created INTEGER NOT NULL, elapsed INTEGER NOT NULL DEFAULT 0, checked INTEGER NOT NULL DEFAULT 0, available INTEGER NOT NULL DEFAULT 0, errors INTEGER NOT NULL DEFAULT 0, requests INTEGER NOT NULL DEFAULT 0, current TEXT NOT NULL DEFAULT '', detail TEXT NOT NULL DEFAULT '')")
            db.execSQL("CREATE TABLE pending(session TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, username TEXT NOT NULL, ordinal TEXT NOT NULL, leased INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(session,username))")
            db.execSQL("CREATE TABLE results(id INTEGER PRIMARY KEY AUTOINCREMENT, session TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, username TEXT NOT NULL, status TEXT NOT NULL, checked_at INTEGER NOT NULL, http INTEGER, retry INTEGER, detail TEXT NOT NULL, route TEXT NOT NULL, latency INTEGER NOT NULL, UNIQUE(session,username))")
            db.execSQL("CREATE INDEX results_status_time ON results(status,checked_at DESC)")
            db.execSQL("CREATE INDEX results_username ON results(username)")
            db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL("ALTER TABLE sessions ADD COLUMN network TEXT NOT NULL DEFAULT 'READY'")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion == 1 && newVersion == 2) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN network TEXT NOT NULL DEFAULT 'READY'")
                db.execSQL("UPDATE sessions SET status='PAUSED', network='RATE_LIMITED' WHERE status='COOLDOWN'")
            } else error("Unsupported database migration $oldVersion → $newVersion")
        }
    }.apply { setWriteAheadLoggingEnabled(true) }

    private suspend fun <T> access(write: Boolean = false, block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            val db = helper.writableDatabase
            val value = if (write) {
                db.beginTransaction()
                try { block(db).also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
            } else block(db)
            if (write) changes.value += 1
            value
        }
    }
    suspend fun recover() = access(true) { db ->
        db.execSQL("UPDATE sessions SET status='INTERRUPTED', detail='Process ended. Checkpoint is ready to resume.' WHERE status='RUNNING'")
        db.execSQL("UPDATE sessions SET status='STOPPED', detail='Stopped. Checkpoint is available.' WHERE status='STOPPING'")
        db.execSQL("UPDATE sessions SET status='PAUSED', network='RATE_LIMITED' WHERE status='COOLDOWN'")
        db.execSQL("UPDATE sessions SET network='READY' WHERE network IN ('REQUESTING','OFFLINE')")
        db.execSQL("UPDATE pending SET leased=0")
    }
    suspend fun create(config: ScanConfig): Session = access(true) { db ->
        config.validate()
        val session = Session(UUID.randomUUID().toString(), config, SessionStatus.PAUSED)
        db.insertOrThrow("sessions", null, ContentValues().apply {
            put("id", session.id); put("config", crypto.encode(json.encodeToString(config)))
            put("mode", config.mode.name); put("length", config.length)
            put("status", session.status.name); put("created", session.createdAt)
        })
        session
    }
    override suspend fun session(id: String): Session? = access { db -> load(db, id) }
    suspend fun sessions(): List<Session> = access { db ->
        db.rawQuery("SELECT id,mode,length,status,cursor,created,elapsed,checked,available,errors,requests,current,detail,network FROM sessions ORDER BY created DESC LIMIT 200", null).use { cursor -> buildList { while (cursor.moveToNext()) add(readSession(cursor, false)) } }
    }
    private fun load(db: SQLiteDatabase, id: String): Session? = db.rawQuery("SELECT * FROM sessions WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) readSession(it) else null }
    private fun readSession(cursor: Cursor, full: Boolean = true) = Session(
        cursor.text("id"), if (full) configs.getOrPut(cursor.text("id")) { json.decodeFromString<ScanConfig>(crypto.decode(cursor.text("config"))) }
        else ScanConfig(mode = dev.atlas.nym.core.GenerationMode.valueOf(cursor.text("mode")), length = cursor.number("length").toInt()), SessionStatus.valueOf(cursor.text("status")),
        cursor.text("cursor"), cursor.number("created"), cursor.number("elapsed"), cursor.number("checked"), cursor.number("available"),
        cursor.number("errors"), cursor.number("requests"), cursor.text("current"), cursor.text("detail"), NetworkStatus.valueOf(cursor.text("network")))

    override suspend fun reserve(id: String, generator: CandidateGenerator): Candidate? = access(true) { db ->
        val session = load(db, id) ?: return@access null
        if (session.status != SessionStatus.RUNNING) return@access null
        val existing = db.rawQuery("SELECT username,ordinal FROM pending WHERE session=? AND leased=0 ORDER BY rowid LIMIT 1", arrayOf(id)).use {
            if (it.moveToFirst()) Candidate(it.getString(0), it.getString(1)) else null
        }
        val candidate = existing ?: run {
            val pending = db.rawQuery("SELECT COUNT(*) FROM pending WHERE session=?", arrayOf(id)).use { it.moveToFirst(); it.getLong(0) }
            if (session.checked + pending >= session.config.limit) return@access null
            val next = generator.at(session.cursor) ?: return@access null
            db.insertOrThrow("pending", null, ContentValues().apply { put("session", id); put("username", next.username); put("ordinal", next.ordinal) })
            db.execSQL("UPDATE sessions SET cursor=? WHERE id=?", arrayOf((next.ordinal.toBigInteger() + java.math.BigInteger.ONE).toString(), id))
            next
        }
        db.execSQL("UPDATE pending SET leased=1 WHERE session=? AND username=?", arrayOf(id, candidate.username))
        db.execSQL("UPDATE sessions SET current=? WHERE id=?", arrayOf(candidate.username, id))
        candidate
    }

    override suspend fun complete(id: String, candidate: Candidate, result: CheckResult) = access(true) { db ->
        val values = ContentValues().apply {
            put("session", id); put("username", result.username); put("status", result.status.name); put("checked_at", result.checkedAt)
            if (result.httpStatus == null) putNull("http") else put("http", result.httpStatus)
            if (result.retryAfterMs == null) putNull("retry") else put("retry", result.retryAfterMs)
            put("detail", result.detail); put("route", result.route); put("latency", result.latencyMs)
        }
        val row = db.insertWithOnConflict("results", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        if (row != -1L) db.execSQL("UPDATE sessions SET checked=checked+1, available=available+? WHERE id=?", arrayOf(if (result.status == CheckStatus.AVAILABLE) 1 else 0, id))
        db.delete("pending", "session=? AND username=?", arrayOf(id, candidate.username))
        Unit
    }
    override suspend fun recordAttempt(id: String, result: CheckResult) = access(true) { db ->
        val error = if (result.status in setOf(CheckStatus.UNKNOWN, CheckStatus.NETWORK_ERROR, CheckStatus.RATE_LIMITED)) 1 else 0
        db.execSQL("UPDATE sessions SET requests=requests+1, errors=errors+?, detail=? WHERE id=?", arrayOf(error, result.detail, id))
    }
    override suspend fun status(id: String, status: SessionStatus, detail: String) = access(true) { db ->
        db.execSQL("UPDATE sessions SET status=?, detail=? WHERE id=?", arrayOf(status.name, detail, id))
    }
    override suspend fun network(id: String, status: NetworkStatus) = access(true) { db ->
        db.execSQL("UPDATE sessions SET network=? WHERE id=?", arrayOf(status.name, id))
    }
    override suspend fun rateLimited(id: String, result: CheckResult, until: Long) = access(true) { db ->
        saveCooldown(db, until)
        db.execSQL("UPDATE sessions SET requests=requests+1, errors=errors+1, network='RATE_LIMITED' WHERE id=?", arrayOf(id))
        // A late response must retain the server restriction without undoing Stop/Pause.
        db.execSQL("UPDATE sessions SET status='PAUSED', detail=? WHERE id=? AND status='RUNNING'", arrayOf(result.detail, id))
    }
    override suspend fun releasePending(id: String) = access(true) { db -> db.execSQL("UPDATE pending SET leased=0 WHERE session=?", arrayOf(id)) }
    override suspend fun cooldownUntil(): Long = access { db ->
        db.rawQuery("SELECT value FROM metadata WHERE key='cooldown'", null).use { if (it.moveToFirst()) it.getString(0).toLongOrNull() ?: 0 else 0 }
    }
    override suspend fun setCooldown(until: Long) = access(true) { db ->
        saveCooldown(db, until)
    }
    private fun saveCooldown(db: SQLiteDatabase, until: Long) {
        val old = db.rawQuery("SELECT value FROM metadata WHERE key='cooldown'", null).use { if (it.moveToFirst()) it.getString(0).toLongOrNull() ?: 0 else 0 }
        db.insertWithOnConflict("metadata", null, ContentValues().apply { put("key", "cooldown"); put("value", maxOf(old, until).toString()) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    override suspend fun addElapsed(id: String, milliseconds: Long) = access(true) { db -> db.execSQL("UPDATE sessions SET elapsed=elapsed+? WHERE id=?", arrayOf(milliseconds.coerceAtLeast(0), id)) }
    suspend fun results(search: String, status: CheckStatus?, ascending: Boolean, limit: Int, sessionId: String? = null): List<StoredResult> = access { db ->
        val (where, args) = filter(search, status, sessionId)
        val order = if (ascending) "username ASC, id DESC" else "checked_at DESC, id DESC"
        db.rawQuery("SELECT * FROM results WHERE $where ORDER BY $order LIMIT ?", (args + limit.coerceIn(1, 10_000).toString()).toTypedArray()).use {
            buildList { while (it.moveToNext()) add(readResult(it)) }
        }
    }
    suspend fun resultCount(): Long = access { db -> db.rawQuery("SELECT COUNT(*) FROM results", null).use { it.moveToFirst(); it.getLong(0) } }
    suspend fun export(writer: Writer, search: String, status: CheckStatus?, ascending: Boolean, sessionId: String? = null) = access { db ->
        val (where, args) = filter(search, status, sessionId)
        val order = if (ascending) "username ASC, id DESC" else "checked_at DESC, id DESC"
        writer.write("session,username,status,checked_at,http_status,retry_after_ms,route,latency_ms,detail\r\n")
        db.rawQuery("SELECT * FROM results WHERE $where ORDER BY $order", args.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                val item = readResult(cursor); val result = item.result
                writer.write(listOf(item.sessionId, result.username, result.status.name.lowercase(), java.time.Instant.ofEpochMilli(result.checkedAt).toString(),
                    result.httpStatus?.toString().orEmpty(), result.retryAfterMs?.toString().orEmpty(), result.route, result.latencyMs.toString(), result.detail)
                    .joinToString(",") { csv(it) } + "\r\n")
            }
        }
        writer.flush()
    }
    private fun filter(search: String, status: CheckStatus?, session: String?): Pair<String, List<String>> {
        val escaped = search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val args = mutableListOf("%$escaped%")
        var where = "username LIKE ? ESCAPE '\\'"
        if (status == CheckStatus.UNKNOWN) where += " AND status IN ('UNKNOWN','NETWORK_ERROR')"
        else if (status != null) { where += " AND status=?"; args += status.name }
        if (session != null) { where += " AND session=?"; args += session }
        return where to args
    }
    private fun readResult(c: Cursor) = StoredResult(c.number("id"), c.text("session"), CheckResult(c.text("username"), CheckStatus.valueOf(c.text("status")),
        c.optionalNumber("http")?.toInt(), c.optionalNumber("retry"), c.text("detail"), c.text("route"), c.number("latency"), c.number("checked_at")))
    suspend fun clearHistory() = access(true) { db ->
        check(db.rawQuery("SELECT 1 FROM sessions WHERE status IN ('RUNNING','STOPPING') LIMIT 1", null).use { !it.moveToFirst() }) { "Pause the active session first" }
        db.delete("sessions", null, null)
        configs.clear()
        // Server cooldown metadata intentionally survives clearing results.
        Unit
    }
    override fun close() = helper.close()
    companion object {
        fun csv(value: String): String {
            val safe = if (value.firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'$value" else value
            return "\"${safe.replace("\"", "\"\"")}\""
        }
    }
}
private fun Cursor.text(name: String): String = getString(getColumnIndexOrThrow(name))
private fun Cursor.number(name: String): Long = getLong(getColumnIndexOrThrow(name))
private fun Cursor.optionalNumber(name: String): Long? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }
