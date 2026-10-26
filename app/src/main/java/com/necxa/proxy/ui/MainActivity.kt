package com.necxa.proxy.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.necxa.proxy.core.FilterMode
import com.necxa.proxy.core.FormState
import com.necxa.proxy.core.Profile
import com.necxa.proxy.core.ProxyType
import com.necxa.proxy.vpn.*
import kotlinx.coroutines.delay

// Minimal theme: light text on dark, or dark text on light. Thin (Light) font
// for headings, regular for body. No emojis, no gradients, no clutter.
private val LightScheme = lightColorScheme(
    background = Color.White, surface = Color(0xFFF7F7F5), onBackground = Color(0xFF111111),
    onSurface = Color(0xFF111111), primary = Color(0xFF111111), onPrimary = Color.White,
    outline = Color(0xFFE5E5E5), surfaceVariant = Color(0xFFF0F0EE),
)
private val DarkScheme = darkColorScheme(
    background = Color.Black, surface = Color(0xFF161616), onBackground = Color.White,
    onSurface = Color.White, primary = Color.White, onPrimary = Color.Black,
    outline = Color(0xFF2A2A2A), surfaceVariant = Color(0xFF1E1E1E),
)
private val ThinTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.Light, fontSize = 32.sp),
    displayMedium = TextStyle(fontWeight = FontWeight.Light, fontSize = 28.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Light, fontSize = 22.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Light, fontSize = 13.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp),
)

@Composable
fun NeroTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = ThinTypography, content = content)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: ProxyViewModel = viewModel()
            val dark by vm.darkMode.collectAsState()
            NeroTheme(dark) { Surface(Modifier.fillMaxSize()) { App(vm) } }
        }
    }
}

enum class Tab { HOME, PROFILES, LOGS, SETTINGS }

private fun startVpn(ctx: Context) {
    ContextCompat.startForegroundService(ctx, Intent(ctx, NeroVpnService::class.java).setAction(NeroVpnService.ACTION_START))
}
private fun stopVpn(ctx: Context) {
    ctx.startService(Intent(ctx, NeroVpnService::class.java).setAction(NeroVpnService.ACTION_STOP))
}
private fun launchPrepare(ctx: Context, l: ManagedActivityResultLauncher<Intent, ActivityResult>) {
    val i = VpnService.prepare(ctx)
    if (i == null) startVpn(ctx) else l.launch(i)
}
private fun fmtBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}
private fun fmtDur(ms: Long): String { val s = ms / 1000; return "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) }

@Composable
fun App(vm: ProxyViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == Tab.HOME, { tab = Tab.HOME }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
                NavigationBarItem(tab == Tab.PROFILES, { tab = Tab.PROFILES }, icon = { Icon(Icons.Default.List, null) }, label = { Text("Profiles") })
                NavigationBarItem(tab == Tab.LOGS, { tab = Tab.LOGS }, icon = { Icon(Icons.Default.Info, null) }, label = { Text("Logs") })
                NavigationBarItem(tab == Tab.SETTINGS, { tab = Tab.SETTINGS }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                Tab.HOME -> HomeScreen(vm) { tab = Tab.PROFILES }
                Tab.PROFILES -> ProfilesScreen(vm)
                Tab.LOGS -> LogsScreen()
                Tab.SETTINGS -> SettingsScreen(vm)
            }
        }
    }
}

@Composable
fun HomeScreen(vm: ProxyViewModel, goProfiles: () -> Unit) {
    val profiles by vm.profiles.collectAsState()
    val activeId by vm.activeId.collectAsState()
    val active = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
    val s by VpnState.flow.collectAsState()
    val steps by vm.steps.collectAsState()
    val testing by vm.testing.collectAsState()
    val ctx = LocalContext.current
    val isOn = s.status == VpnStatus.CONNECTED || s.status == VpnStatus.RECONNECTING
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isOn) { if (isOn) while (true) { now = System.currentTimeMillis(); delay(1000) } }

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) startVpn(ctx)
        else VpnState.update { it.copy(status = VpnStatus.ERROR, message = "VPN permission was denied. NERO Proxy cannot work without it.") }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { launchPrepare(ctx, vpnLauncher) }

    val onConnect: () -> Unit = {
        when (s.status) {
            VpnStatus.CONNECTED, VpnStatus.RECONNECTING -> stopVpn(ctx)
            VpnStatus.DISCONNECTED, VpnStatus.ERROR ->
                if (active == null) goProfiles()
                else if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else launchPrepare(ctx, vpnLauncher)
            else -> {}
        }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("NERO PROXY", style = MaterialTheme.typography.titleLarge, letterSpacing = 3.sp)
        Text(s.status.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.displayMedium)
        Text(
            if (active != null) "${active.displayName()}  ·  ${active.type} · ${active.host}:${active.port}" + if (active.tls) " · TLS" else ""
            else "No proxy profile yet",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
        if (isOn) Text(fmtDur(now - s.startedAt), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
        Button(onClick = onConnect, enabled = s.status != VpnStatus.CONNECTING && s.status != VpnStatus.STOPPING,
            modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Text(when (s.status) {
                VpnStatus.CONNECTED, VpnStatus.RECONNECTING -> "Disconnect"
                VpnStatus.CONNECTING -> "Connecting…"
                VpnStatus.STOPPING -> "Stopping…"
                else -> if (active != null) "Connect" else "Add a profile"
            }, fontSize = 18.sp)
        }
        s.message?.let { m -> Card { Text(m, Modifier.padding(14.dp)) } }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Upload", fmtBytes(s.upBytes), "${fmtBytes(s.upSpeed)}/s", Modifier.weight(1f))
            StatCard("Download", fmtBytes(s.downBytes), "${fmtBytes(s.downSpeed)}/s", Modifier.weight(1f))
        }
        Text("Proxy latency: " + (s.latencyMs?.let { "$it ms" } ?: "—") + "   (TCP connect to proxy)",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), fontSize = 13.sp)
        if (s.status == VpnStatus.CONNECTED)
            Text(if (s.forwardingVerified) "Forwarding verified: traffic is flowing through the proxy."
            else "Connected. Waiting for first traffic to verify forwarding.",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), fontSize = 13.sp)
        Card { Text("TCP only. UDP, QUIC and IPv6 are blocked, never sent directly. DNS is resolved by your proxy.",
            Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), fontSize = 12.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.testActive() }, enabled = active != null && !testing) {
                Text(if (testing) "Testing…" else "Test active profile")
            }
            OutlinedButton(onClick = goProfiles) { Text("Profiles") }
        }
        steps.forEach {
            Text((if (it.ok) "✓ " else "✗ ") + it.name, fontWeight = FontWeight.Normal)
            Text(it.detail, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun StatCard(title: String, total: String, speed: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), fontSize = 12.sp)
            Text(total, fontSize = 20.sp, fontWeight = FontWeight.Light)
            Text(speed, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), fontSize = 12.sp)
        }
    }
}

@Composable
fun ProfilesScreen(vm: ProxyViewModel) {
    val profiles by vm.profiles.collectAsState()
    val activeId by vm.activeId.collectAsState()
    var editing by remember { mutableStateOf<Profile?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Profile?>(null) }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Profiles", style = MaterialTheme.typography.titleLarge)
            Button(onClick = { vm.newForm(); adding = true }) { Text("Add") }
        }
        Text("Unlimited profiles. Tap Use to make one active. The active profile is the one Connect uses.",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), fontSize = 13.sp)
        if (profiles.isEmpty()) Text("No profiles yet. Add your first proxy above.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
            items(profiles, key = { it.id }) { p ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(p.displayName(), fontWeight = FontWeight.Normal)
                            if (p.id == activeId) Text("ACTIVE", fontSize = 12.sp, fontWeight = FontWeight.Normal)
                        }
                        Text("${p.type} · ${p.host}:${p.port}" + if (p.tls) " · TLS" else "" + if (!p.username.isNullOrEmpty()) " · auth" else "",
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (p.id != activeId) OutlinedButton(onClick = { vm.setActive(p.id) }) { Text("Use") }
                            OutlinedButton(onClick = { vm.editForm(p); editing = p }) { Text("Edit") }
                            TextButton(onClick = { deleting = p }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }

    if (adding || editing != null) {
        ProfileEditor(vm, editing) { editing = null; adding = false }
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete profile?") },
            text = { Text("Delete \"${p.displayName()}\"? This cannot be undone.") },
            confirmButton = { TextButton(onClick = { vm.deleteProfile(p.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ProfileEditor(vm: ProxyViewModel, existing: Profile?, close: () -> Unit) {
    val f by vm.form.collectAsState()
    val errs by vm.errors.collectAsState()
    val steps by vm.steps.collectAsState()
    val testing by vm.testing.collectAsState()
    var show by remember { mutableStateOf(false) }
    fun set(n: FormState) = vm.update(n)
    AlertDialog(
        onDismissRequest = close,
        title = { Text(if (existing != null) "Edit profile" else "New profile") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(f.name, { set(f.copy(name = it)) }, label = { Text("Profile name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProxyType.values().forEach { t ->
                        if (f.type == t) Button(onClick = { set(f.copy(type = t)) }) { Text(if (t == ProxyType.HTTP) "HTTP" else "SOCKS5") }
                        else OutlinedButton(onClick = { set(f.copy(type = t)) }) { Text(if (t == ProxyType.HTTP) "HTTP" else "SOCKS5") }
                    }
                }
                OutlinedTextField(f.host, { set(f.copy(host = it)) }, label = { Text("Server host / IP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(f.port, { set(f.copy(port = it.filter(Char::isDigit).take(5))) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(f.username, { set(f.copy(username = it)) }, label = { Text("Username (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(f.password, { set(f.copy(password = it)) }, label = { Text("Password (optional)") }, singleLine = true,
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") } },
                    modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(f.tls, { set(f.copy(tls = it)) })
                    Spacer(Modifier.width(8.dp))
                    Text("TLS to proxy (encrypts the connection to the proxy itself)")
                }
                OutlinedTextField(f.testUrl, { set(f.copy(testUrl = it)) }, label = { Text("HTTPS test endpoint") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                errs.forEach { Text(it, color = Color(0xFFFF8A8A), fontSize = 13.sp) }
                steps.forEach {
                    Text((if (it.ok) "✓ " else "✗ ") + it.name)
                    Text(it.detail, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.testForm() }, enabled = !testing) { Text(if (testing) "Testing…" else "Test") }
                Button(onClick = { if (vm.saveProfile(existing?.id)) close() }) { Text("Save") }
            }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

@Composable
fun LogsScreen() {
    val lines by LogBuffer.flow.collectAsState()
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Logs", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { LogBuffer.clear() }) { Text("Clear") }
        }
        Text("Live connection log. Passwords and visited sites are never logged.",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), fontSize = 13.sp)
        if (lines.isEmpty()) Text("No log entries yet.")
        LazyColumn { items(lines.reversed()) { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f), modifier = Modifier.padding(vertical = 2.dp)) } }
    }
}

@Composable
fun SettingsScreen(vm: ProxyViewModel) {
    val dark by vm.darkMode.collectAsState()
    val mode by vm.filterMode.collectAsState()
    val pkgs by vm.filterPackages.collectAsState()
    val apps by vm.apps.collectAsState()
    val appsLoading by vm.appsLoading.collectAsState()
    val importResult by vm.importResult.collectAsState()
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            try { ctx.contentResolver.openOutputStream(uri)?.use { it.write(vm.exportJson().toByteArray()) } } catch (_: Exception) {}
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                if (text.isNotBlank()) vm.importJsonText(text)
            } catch (_: Exception) { vm.importJsonText("") }
        }
    }

    LaunchedEffect(Unit) { vm.loadApps() }

    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        Text("Appearance", fontWeight = FontWeight.Normal)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(dark, { vm.toggleDark(it) })
            Spacer(Modifier.width(8.dp))
            Text("Dark mode")
        }

        Text("Profiles", fontWeight = FontWeight.Normal)
        Text("Exported files contain passwords in clear text. Keep an exported file private, like any password file.",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { exportLauncher.launch("nero-proxy-profiles.json") }) { Text("Export profiles") }
            OutlinedButton(onClick = { importLauncher.launch("*/*") }) { Text("Import profiles") }
        }
        importResult?.let { Text(it, fontSize = 13.sp) }

        Text("App filter", fontWeight = FontWeight.Normal)
        Text("All apps: every app uses the VPN. Exclude: selected apps bypass it. Include only: only selected apps use it.",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), fontSize = 13.sp)
        FilterMode.values().forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(mode == m, { vm.setFilter(m) })
                Text(when (m) { FilterMode.ALL -> "All apps"; FilterMode.EXCLUDE -> "Exclude selected apps"; FilterMode.INCLUDE -> "Include only selected apps" })
            }
        }
        if (mode != FilterMode.ALL) {
            if (appsLoading) Text("Loading apps…", fontSize = 13.sp)
            else if (apps.isEmpty()) Text("Could not list apps on this device.", fontSize = 13.sp)
            else {
                OutlinedTextField(query, { query = it }, label = { Text("Search apps") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val shown = apps.filter { it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }.take(200)
                shown.forEach { a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(pkgs.contains(a.packageName), { vm.toggleFilterApp(a.packageName) })
                        Column {
                            Text(a.label, fontSize = 14.sp)
                            Text(a.packageName, fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                        }
                    }
                }
                if (shown.size == 200) Text("Showing first 200 matches. Narrow the search to see more.", fontSize = 12.sp)
            }
        }

        Text("Quick Settings tile", fontWeight = FontWeight.Normal)
        Text("Add Nero Proxy to Quick Settings from the notification shade (edit tiles) to connect or disconnect with one tap. If VPN permission was never granted, the tile opens the app first.",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), fontSize = 13.sp)

        Text("About", fontWeight = FontWeight.Normal)
        Text("NERO Proxy 1.0.0 — free, no ads, no accounts, no servers of its own. Your proxy settings stay on this device; the password is encrypted with an Android Keystore key. Logs stay in memory and never contain passwords or visited sites. HTTPS is never intercepted or decrypted. TCP only; UDP, QUIC and IPv6 are dropped, fail-closed. Android does not guarantee complete leak protection and this app does not promise anonymity.",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), fontSize = 13.sp)
        Text("Open-source licenses: hev-socks5-tunnel (heiher) – MIT, with bundled lwIP (BSD), libyaml (MIT), hev-task-system (MIT). AndroidX, Jetpack Compose, Kotlin, kotlinx.coroutines – Apache 2.0.",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), fontSize = 13.sp)
    }
}
