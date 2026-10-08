@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package dev.atlas.nym.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.atlas.nym.NetworkRoute
import dev.atlas.nym.core.*
import dev.atlas.nym.data.Preferences
import dev.atlas.nym.data.StoredResult
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Dark = darkColorScheme(
    primary = Color(0xFFB5C6EE), onPrimary = Color(0xFF17253F), primaryContainer = Color(0xFF25314A),
    background = Color(0xFF090B10), surface = Color(0xFF101319), surfaceContainer = Color(0xFF151922),
    surfaceVariant = Color(0xFF1B2029), onBackground = Color(0xFFEBEEF5), onSurface = Color(0xFFEBEEF5),
    onSurfaceVariant = Color(0xFF9099AA), outline = Color(0xFF333C4D), outlineVariant = Color(0xFF232A36))
private val Light = lightColorScheme(primary = Color(0xFF405D93), background = Color(0xFFF5F6FA), surface = Color.White, surfaceContainer = Color(0xFFEBEEF5))
private val destinations = listOf("Home" to NymIcons.Home, "Results" to NymIcons.AlternateEmail,
    "Sessions" to NymIcons.History, "Settings" to NymIcons.Tune)

@Composable fun NymApp(vm: NymViewModel) {
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    val dark = preferences.theme == "dark" || (preferences.theme == "system" && isSystemInDarkTheme())
    val destination by vm.destination.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) vm.message.value = "Notification permission denied. Android can still run the service; controls remain in the app."
    }
    val dictionaryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importDictionary) }
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let(vm::export) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.message.value = null } }
    SideEffect {
        (context as? Activity)?.let { activity ->
            WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
            }
        }
    }
    BackHandler(destination != "Home") { vm.navigate("Home") }
    MaterialTheme(colorScheme = if (dark) Dark else Light) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    destinations.forEach { (label, icon) ->
                        NavigationBarItem(selected = destination == label, onClick = { vm.navigate(label) },
                            icon = { Icon(icon, contentDescription = null) }, label = { Text(label) },
                            modifier = Modifier.testTag("nav_$label"),
                            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer))
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
                if (!loaded) CircularProgressIndicator(Modifier.align(Alignment.Center))
                else AnimatedContent(destination, transitionSpec = {
                    fadeIn(tween(if (preferences.reducedMotion) 0 else 160)) togetherWith fadeOut(tween(if (preferences.reducedMotion) 0 else 100))
                }, label = "navigation", modifier = Modifier.widthIn(max = 840.dp).fillMaxSize()) { tab ->
                    when (tab) {
                        "Home" -> Home(vm, preferences.reducedMotion, busy, onStart = {
                            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            vm.start()
                        })
                        "Results" -> Results(vm) { csvPicker.launch("Nym_results.csv") }
                        "Sessions" -> Sessions(vm)
                        "Settings" -> Settings(vm) { dictionaryPicker.launch(arrayOf("text/plain", "text/*", "application/octet-stream")) }
                    }
                }
            }
        }
    }
}

@Composable private fun Header(title: String, caption: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.invoke()
    }
}

@Composable private fun Home(vm: NymViewModel, reduced: Boolean, busy: Boolean, onStart: () -> Unit) {
    val session by vm.current.collectAsStateWithLifecycle()
    val service by vm.graph.serviceSession.collectAsStateWithLifecycle()
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    val network by vm.network.collectAsStateWithLifecycle()
    val rate by vm.graph.requestRate.collectAsStateWithLifecycle()
    val activeRoute by vm.graph.activeRoute.collectAsStateWithLifecycle()
    val until by vm.cooldown.collectAsStateWithLifecycle()
    val now by vm.clock.collectAsStateWithLifecycle()
    val remaining = (until - now).coerceAtLeast(0)
    val running = service != null
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().testTag("screen_Home")) {
        Header("Nym", "USERNAME CHECKER · 0.1.0") {
            IconButton(onClick = { vm.navigate("Settings") }) { Icon(NymIcons.Tune, "Configure scan") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            RouteLine(network, session?.config ?: preferences.scan)
            if (running) Text("Active connection · $activeRoute", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)))
                        Spacer(Modifier.width(8.dp))
                        Text(if (remaining > 0) "Server cooldown · ${duration(remaining)}" else statusLabel(session?.status),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("session_status"))
                    }
                    Column {
                        Text("CURRENT CANDIDATE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(session?.current?.ifBlank { "—" } ?: "—", style = MaterialTheme.typography.headlineLarge,
                            fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("candidate"))
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Counter("Checked", session?.checked ?: 0, reduced, Modifier.weight(1f))
                        Counter("Available", session?.available ?: 0, reduced, Modifier.weight(1f))
                        Counter("Errors", session?.errors ?: 0, reduced, Modifier.weight(1f))
                    }
                    LinearProgressIndicator(progress = { if (session == null) 0f else (session!!.checked.toFloat() / session!!.config.limit).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(3.dp), color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(String.format(Locale.ROOT, "%.2f req/s", if (running) rate else 0.0), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(duration(session?.elapsedMs ?: 0), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            if (!session?.detail.isNullOrBlank()) {
                Text(session!!.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("session_detail"))
            }
            val config = session?.takeIf { running }?.config ?: preferences.scan
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("GENERATOR", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().combinedClickable(onClick = { vm.navigate("Settings") }), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${modeLabel(config.mode)} · ${if (config.mode == GenerationMode.PATTERN) config.pattern else "${config.length} characters"}", style = MaterialTheme.typography.titleMedium)
                        Text("${config.limit} checks · ${config.intervalMs} ms interval · ${config.workers} worker${if (config.workers == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(NymIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (session == null) Text("Configure a generator, then start. Results and checkpoints stay on this device.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else if (!running && session?.status != SessionStatus.COMPLETED) Text("Resume continues the saved candidates and cursor. A new session starts a separate check history.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { vm.pause() }, modifier = Modifier.weight(1f).height(52.dp).testTag("pause")) { Icon(NymIcons.Pause, null); Spacer(Modifier.width(8.dp)); Text("Pause") }
                    OutlinedButton(onClick = { confirmStop = true }, modifier = Modifier.weight(1f).height(52.dp).testTag("stop")) { Icon(NymIcons.Stop, null); Spacer(Modifier.width(8.dp)); Text("Stop") }
                }
                TextButton(onClick = { vm.pause(true) }, modifier = Modifier.fillMaxWidth().testTag("checkpoint")) { Text("Save checkpoint and pause") }
            } else {
                if (session != null && session?.status != SessionStatus.COMPLETED) {
                    Button(onClick = { session?.let(vm::resume) }, enabled = remaining == 0L && !busy,
                        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("resume")) { Icon(NymIcons.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Resume session") }
                    TextButton(onClick = onStart, enabled = remaining == 0L && !busy, modifier = Modifier.fillMaxWidth().testTag("new_session")) { Text("Start a new session") }
                } else Button(onClick = onStart, enabled = remaining == 0L && !busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp).testTag("start")) { Icon(NymIcons.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Start checking") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    if (confirmStop) Confirm("Stop this session?", "The checkpoint remains available for resume.", "Stop", { confirmStop = false; vm.stop() }, { confirmStop = false })
}

@Composable private fun Counter(label: String, value: Long, reduced: Boolean, modifier: Modifier) {
    Column(modifier) {
        AnimatedContent(value, transitionSpec = { fadeIn(tween(if (reduced) 0 else 140)) togetherWith fadeOut(tween(if (reduced) 0 else 80)) }, label = label) {
            Text(it.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium, modifier = Modifier.testTag("count_$label"))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun RouteLine(network: NetworkRoute, config: ScanConfig) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(if (network.vpn) NymIcons.VpnLock else NymIcons.Wifi, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            Text(network.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (config.proxyEnabled) Text("Proxy routing · ${config.proxies.size} configured · follows system VPN policy", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable private fun Results(vm: NymViewModel, onExport: () -> Unit) {
    val results by vm.results.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var details by remember { mutableStateOf<StoredResult?>(null) }
    Column(Modifier.fillMaxSize().testTag("screen_Results")) {
        Header("Results", if (query.sessionId == null) "ALL SESSIONS" else "SELECTED SESSION") {
            IconButton(onClick = onExport) { Icon(NymIcons.FileDownload, "Export CSV") }
        }
        OutlinedTextField(query.search, { vm.query.value = query.copy(search = it, limit = 200) }, label = { Text("Search usernames") },
            leadingIcon = { Icon(NymIcons.Search, null) }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp).testTag("result_search"))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Available" to CheckStatus.AVAILABLE, "Unavailable" to CheckStatus.UNAVAILABLE, "Errors" to CheckStatus.UNKNOWN, "All" to null).forEach { (label, status) ->
                FilterChip(selected = query.status == status, onClick = { vm.query.value = query.copy(status = status, limit = 200) }, label = { Text(label) }, modifier = Modifier.testTag("filter_$label"))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${results.size} shown", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (query.sessionId != null) TextButton(onClick = { vm.query.value = query.copy(sessionId = null) }) { Text("All sessions") }
            TextButton(onClick = { vm.query.value = query.copy(ascending = !query.ascending) }) { Icon(NymIcons.Sort, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(if (query.ascending) "A–Z" else "Newest") }
        }
        if (results.isEmpty()) Empty(NymIcons.AlternateEmail, "No matching results", "Completed checks appear here. Availability is a snapshot, not a reservation.", Modifier.weight(1f))
        else LazyColumn(Modifier.weight(1f).testTag("result_list"), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(results, key = { it.id }) { item ->
                Row(Modifier.fillMaxWidth().combinedClickable(onClick = { details = item }, onLongClick = {
                    clipboard.setText(AnnotatedString(item.result.username)); vm.message.value = "Username copied"
                }).padding(start = 22.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.result.username, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                        Text("${resultLabel(item.result.status)} · ${timestamp(item.result.checkedAt)}", style = MaterialTheme.typography.labelSmall,
                            color = if (item.result.status == CheckStatus.AVAILABLE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { clipboard.setText(AnnotatedString(item.result.username)); vm.message.value = "Username copied" }) { Icon(NymIcons.ContentCopy, "Copy ${item.result.username}", Modifier.size(20.dp)) }
                }
                HorizontalDivider(Modifier.padding(horizontal = 22.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (results.size >= query.limit) item { TextButton(onClick = { vm.query.value = query.copy(limit = (query.limit + 200).coerceAtMost(10_000)) }, modifier = Modifier.fillMaxWidth()) { Text("Load more") } }
        }
    }
    details?.let { item ->
        AlertDialog(onDismissRequest = { details = null }, title = { Text(item.result.username, fontFamily = FontFamily.Monospace) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${resultLabel(item.result.status)} · ${timestamp(item.result.checkedAt)}")
                Text("HTTP ${item.result.httpStatus ?: "—"} · ${item.result.latencyMs} ms")
                Text(item.result.route)
                if (item.result.detail.isNotBlank()) Text(item.result.detail)
                Text("Session ${item.sessionId.take(8)}", style = MaterialTheme.typography.labelSmall)
            } }, confirmButton = { TextButton(onClick = { details = null }) { Text("Close") } },
            dismissButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(item.result.username)); vm.message.value = "Username copied" }) { Text("Copy") } })
    }
}

@Composable private fun Sessions(vm: NymViewModel) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val active by vm.graph.serviceSession.collectAsStateWithLifecycle()
    val until by vm.cooldown.collectAsStateWithLifecycle()
    val now by vm.clock.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().testTag("screen_Sessions")) {
        Header("Sessions", "CHECKPOINTS & HISTORY")
        if (sessions.isEmpty()) Empty(NymIcons.History, "No sessions yet", "Start checking to create the first session. Checkpoints are saved automatically.", Modifier.weight(1f))
        else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(sessions, key = { it.id }) { session ->
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(modeLabel(session.config.mode), style = MaterialTheme.typography.titleMedium)
                            Text(statusLabel(session.status), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(timestamp(session.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${session.checked} checked · ${session.available} available · ${duration(session.elapsedMs)}", style = MaterialTheme.typography.bodyMedium)
                        if (session.detail.isNotBlank()) Text(session.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { vm.sessionResults(session) }) { Text("Results") }
                            if (session.status != SessionStatus.COMPLETED && session.status != SessionStatus.RUNNING) {
                                TextButton(onClick = { vm.resume(session) }, enabled = active == null && until <= now) { Text("Resume") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun Settings(vm: NymViewModel, importDictionary: () -> Unit) {
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val config = prefs.scan
    val busy by vm.busy.collectAsStateWithLifecycle()
    val diagnostics by vm.diagnostics.collectAsStateWithLifecycle()
    val network by vm.network.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var proxyToRemove by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().testTag("screen_Settings")) {
        Header("Settings", "NEXT SESSION DEFAULTS") {
            TextButton(onClick = { vm.saveSettings() }, enabled = !busy) { Text("Save") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Group("Generator", NymIcons.Shuffle, initiallyExpanded = true) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GenerationMode.entries.forEach { mode -> FilterChip(config.mode == mode, onClick = { vm.update(prefs.copy(scan = config.copy(mode = mode))) }, label = { Text(modeLabel(mode)) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field("Length · 2–32", config.length.toString(), { it.toIntOrNull()?.let { number -> vm.update(prefs.copy(scan = config.copy(length = number))) } }, Modifier.weight(1f), KeyboardType.Number, "setting_length")
                    Field("Check limit", config.limit.toString(), { it.toLongOrNull()?.let { number -> vm.update(prefs.copy(scan = config.copy(limit = number))) } }, Modifier.weight(1f), KeyboardType.Number, "setting_limit")
                }
                Field("Character set", config.charset, { vm.update(prefs.copy(scan = config.copy(charset = it))) }, tag = "setting_charset")
                if (config.mode == GenerationMode.PATTERN) Field("Pattern · @ letter, # digit, * charset", config.pattern, { vm.update(prefs.copy(scan = config.copy(pattern = it))) }, tag = "setting_pattern")
                if (config.mode == GenerationMode.RANDOM) Field("Random seed", config.seed.toString(), { it.toLongOrNull()?.let { number -> vm.update(prefs.copy(scan = config.copy(seed = number))) } }, keyboard = KeyboardType.Number, tag = "setting_seed")
                if (config.mode == GenerationMode.DICTIONARY) {
                    if (config.dictionary.length < 16_000) Field("Dictionary · one username per line", config.dictionary, { vm.update(prefs.copy(scan = config.copy(dictionary = it))) }, singleLine = false, tag = "setting_dictionary")
                    TextButton(onClick = importDictionary) { Icon(NymIcons.UploadFile, null); Spacer(Modifier.width(8.dp)); Text("Import UTF-8 dictionary") }
                    Text("${remember(config.dictionary) { CandidateGenerator.dictionaryWords(config.dictionary).size }} unique valid names · immutable session snapshot", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val preview = remember(config.mode, config.length, config.charset, config.pattern, config.seed, config.dictionary) {
                    runCatching { config.validate(); CandidateGenerator(config).let { generator -> (0..2).mapNotNull { generator.at(it.toString())?.username } } }.getOrDefault(emptyList())
                }
                if (preview.isNotEmpty()) Text("Preview: ${preview.joinToString(" · ")}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Group("Network", NymIcons.Wifi) {
                Field("Request interval · ms", config.intervalMs.toString(), { it.toLongOrNull()?.let { number -> vm.update(prefs.copy(scan = config.copy(intervalMs = number))) } }, keyboard = KeyboardType.Number, tag = "setting_interval")
                Field("Additional jitter · ms", config.jitterMs.toString(), { it.toLongOrNull()?.let { number -> vm.update(prefs.copy(scan = config.copy(jitterMs = number))) } }, keyboard = KeyboardType.Number)
                Field("Timeout · seconds", config.timeoutSeconds.toString(), { it.toLongOrNull()?.let { number -> vm.update(prefs.copy(scan = config.copy(timeoutSeconds = number))) } }, keyboard = KeyboardType.Number)
                Text("All workers and proxies share one request cadence. HTTP 429 pauses every route until the server cooldown ends. Resume is manual.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Group("Proxies", NymIcons.Router) {
                Toggle("Use proxy routing", config.proxyEnabled) { vm.update(prefs.copy(scan = config.copy(proxyEnabled = it))) }
                Toggle("Allow direct fallback on network failure", config.fallbackDirect) { vm.update(prefs.copy(scan = config.copy(fallbackDirect = it))) }
                var proxyInput by remember { mutableStateOf("") }
                var show by remember { mutableStateOf(false) }
                OutlinedTextField(proxyInput, { proxyInput = it }, label = { Text("scheme://user:pass@host:port") }, singleLine = true,
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { show = !show }) { Icon(if (show) NymIcons.VisibilityOff else NymIcons.Visibility, "Toggle proxy visibility") } },
                    modifier = Modifier.fillMaxWidth().testTag("proxy_input"), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                TextButton(onClick = {
                    runCatching { ProxySpec.parse(proxyInput) }.onSuccess {
                        vm.update(prefs.copy(scan = config.copy(proxies = (config.proxies + proxyInput.trim()).distinct())))
                        proxyInput = ""
                    }.onFailure { vm.message.value = it.message }
                }, modifier = Modifier.testTag("proxy_add")) { Icon(NymIcons.Add, null); Spacer(Modifier.width(8.dp)); Text("Add proxy") }
                config.proxies.forEach { raw ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(runCatching { ProxySpec.parse(raw).display }.getOrDefault("Invalid proxy"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = { proxyToRemove = raw }) { Icon(NymIcons.DeleteOutline, "Remove proxy") }
                    }
                }
                Text("HTTP, HTTPS and authenticated SOCKS5 supported. A proxy follows Android's system routing; it does not automatically bypass your VPN.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Group("Performance", NymIcons.Speed) {
                Field("Concurrent workers · 1–4", config.workers.toString(), { it.toIntOrNull()?.let { number -> vm.update(prefs.copy(scan = config.copy(workers = number))) } }, keyboard = KeyboardType.Number)
                Field("Network retries · 0–3", config.retries.toString(), { it.toIntOrNull()?.let { number -> vm.update(prefs.copy(scan = config.copy(retries = number))) } }, keyboard = KeyboardType.Number)
                Text("One request warms up the route. Concurrency starts only after a confirmed response. Retries use bounded backoff.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Group("Storage", NymIcons.Storage) {
                Text("Results, pending candidates and generator cursor are saved together. CSV export uses the filters on Results.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { confirmClear = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Icon(NymIcons.DeleteOutline, null); Spacer(Modifier.width(8.dp)); Text("Clear results and session history") }
            }
            Group("Appearance", NymIcons.DarkMode) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("dark", "light", "system").forEach { theme -> FilterChip(prefs.theme == theme, onClick = { vm.update(prefs.copy(theme = theme)) }, label = { Text(theme.replaceFirstChar { it.uppercase() }) }) }
                }
                Toggle("Reduce motion", prefs.reducedMotion) { vm.update(prefs.copy(reducedMotion = it)) }
            }
            Group("Diagnostics", NymIcons.Info) {
                RouteLine(network, config)
                Text("To keep the phone VPN active while using another route: open your VPN's split-tunneling settings, exclude Nym (dev.atlas.nym), then reconnect. Only the VPN app controls its exclusion list. Always-on VPN with “Block connections without VPN” may block excluded apps.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS)) }.onFailure { vm.message.value = "VPN settings unavailable" } }) { Text("Open Android VPN settings") }
                TextButton(onClick = { vm.diagnose() }, enabled = !busy, modifier = Modifier.testTag("diagnose")) { Icon(NymIcons.NetworkCheck, null); Spacer(Modifier.width(8.dp)); Text("Test configured routes") }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                diagnostics.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                Text("Background checking uses a foreground notification. Android 15+ may stop data-sync services after six background hours. Force-stop, battery restrictions and system termination can interrupt a session; reopen Nym to resume its checkpoint.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    if (confirmClear) Confirm("Clear all history?", "This deletes results and saved sessions. The server cooldown remains enforced.", "Clear", { confirmClear = false; vm.clear() }, { confirmClear = false })
    proxyToRemove?.let { raw -> Confirm("Remove proxy?", runCatching { ProxySpec.parse(raw).display }.getOrDefault("Selected proxy"), "Remove", {
        proxyToRemove = null
        val list = config.proxies - raw
        vm.update(prefs.copy(scan = config.copy(proxies = list, proxyEnabled = config.proxyEnabled && list.isNotEmpty())))
    }, { proxyToRemove = null }) }
}

@Composable private fun Group(title: String, icon: ImageVector, initiallyExpanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            Row(Modifier.fillMaxWidth().combinedClickable(onClick = { expanded = !expanded }).heightIn(min = 58.dp).padding(horizontal = 16.dp).testTag("group_$title"), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(if (expanded) NymIcons.ExpandLess else NymIcons.ExpandMore, "${if (expanded) "Collapse" else "Expand"} $title")
            }
            AnimatedVisibility(expanded) { Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
        }
    }
}
@Composable private fun Field(label: String, value: String, change: (String) -> Unit, modifier: Modifier = Modifier, keyboard: KeyboardType = KeyboardType.Text, tag: String = label, singleLine: Boolean = true) {
    var text by rememberSaveable { mutableStateOf(value) }
    LaunchedEffect(value) { if (text != value && text.isNotBlank()) text = value }
    OutlinedTextField(text, { text = it; change(it) }, label = { Text(label) }, singleLine = singleLine, maxLines = if (singleLine) 1 else 5,
        shape = RoundedCornerShape(12.dp), keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = modifier.fillMaxWidth().testTag(tag))
}
@Composable private fun Toggle(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Spacer(Modifier.width(8.dp)); Switch(checked, change)
    }
}
@Composable private fun Empty(icon: ImageVector, title: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp)); Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp)); Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun Confirm(title: String, text: String, action: String, confirm: () -> Unit, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = confirm) { Text(action) } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
private fun statusLabel(status: SessionStatus?) = when (status) {
    null -> "Ready to check"; SessionStatus.RUNNING -> "Checking"; SessionStatus.PAUSED -> "Paused"; SessionStatus.STOPPED -> "Stopped"
    SessionStatus.COMPLETED -> "Completed"; SessionStatus.COOLDOWN -> "Server cooldown"; SessionStatus.INTERRUPTED -> "Interrupted"; SessionStatus.ERROR -> "Needs attention"
}
private fun modeLabel(mode: GenerationMode) = mode.name.lowercase().replaceFirstChar { it.uppercase() }
private fun resultLabel(status: CheckStatus) = status.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
private fun duration(ms: Long): String {
    val seconds = ms / 1000
    return if (seconds >= 3600) String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)
        else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}
private fun timestamp(ms: Long) = DateTimeFormatter.ofPattern("dd MMM · HH:mm", Locale.getDefault()).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
