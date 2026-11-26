package com.example.obdanalyzer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.obdanalyzer.core.Conn
import com.example.obdanalyzer.core.ObdManager
import com.example.obdanalyzer.core.Orientation
import com.example.obdanalyzer.core.LayoutMode
import com.example.obdanalyzer.core.Updater
import com.example.obdanalyzer.core.TripSummary
import com.example.obdanalyzer.core.Vehicle
import com.example.obdanalyzer.core.Settings
import com.example.obdanalyzer.core.Live
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.shape.RoundedCornerShape
import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

private data class Tab(val label: String, val icon: ImageVector)
private val TABS = listOf(
    Tab("Accueil", Icons.Rounded.Home),
    Tab("Diagnostic", Icons.Rounded.Build),
    Tab("Trajets", Icons.Rounded.Map),
    Tab("Garage", Icons.Rounded.DirectionsCar),
)

@Composable
fun ObdRoot() {
    val l by ObdManager.live.collectAsStateWithLifecycle()
    val s by ObdManager.settings.collectAsStateWithLifecycle()
    val vehicles by ObdManager.vehicles.collectAsStateWithLifecycle()
    val trips by ObdManager.trips.collectAsStateWithLifecycle()
    val v = vehicles.firstOrNull { it.id == s.activeVehicleId } ?: vehicles.first()

    AppTheme(s.theme, s.accent) {
        val view = LocalView.current
        val ctx = LocalContext.current
        DisposableEffect(s.keepScreenOn) { view.keepScreenOn = s.keepScreenOn; onDispose { } }
        LaunchedEffect(s.orientation) {
            (ctx as? Activity)?.requestedOrientation = when (s.orientation) {
                Orientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                Orientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                Orientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var drive by rememberSaveable { mutableStateOf(false) }
        var settingsOpen by rememberSaveable { mutableStateOf(false) }
        var pricesOpen by rememberSaveable { mutableStateOf(false) }
        if (pricesOpen) FuelPricesDialog(v) { pricesOpen = false }
        val snack = remember { SnackbarHostState() }
        val upd by Updater.state.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) {
            // Vérification discrète au lancement, au plus une fois toutes les 6 heures
            if (s.autoUpdateCheck && System.currentTimeMillis() - upd.lastCheck > 6 * 3600_000L) {
                Updater.check(ctx, s.githubToken)
                val r = Updater.state.value
                if (r.available) snack.showSnackbar("Nouvelle version ${r.latest?.title ?: ""} disponible : Paramètres › Mises à jour")
            }
        }
        LaunchedEffect(l.message) {
            l.message?.let { snack.showSnackbar(it); ObdManager.consumeMessage() }
        }
        val connected = l.conn == Conn.CONNECTED || l.conn == Conn.RECONNECTING
        LaunchedEffect(connected) { if (!connected) drive = false }
        // Passage automatique en mode conduite au-dessus de la vitesse choisie
        val speed = l.values["speed"] ?: 0.0
        var autoArmed by remember { mutableStateOf(true) }
        LaunchedEffect(speed >= s.autoDriveKmh && s.autoDriveKmh > 0, speed < 5) {
            if (s.autoDriveKmh > 0 && speed >= s.autoDriveKmh && autoArmed && connected) { drive = true; autoArmed = false }
            if (speed < 5) autoArmed = true
        }

        // Verrouillage en roulant : seuls l'accueil et le mode conduite restent accessibles
        val locked = s.drivingLock && connected && speed > 10
        LaunchedEffect(locked) { if (locked) { tab = 0; settingsOpen = false } }

        val incoming by ObdManager.pendingImport.collectAsStateWithLifecycle()
        incoming?.let { json ->
            AlertDialog(
                onDismissRequest = { ObdManager.offerImport(null) },
                title = { Text("Données reçues d'un autre appareil") },
                text = {
                    Text(if (l.conn != Conn.IDLE) "Déconnectez d'abord le boîtier, puis rouvrez le fichier."
                    else "Fusionner : ajoute les trajets, pleins et véhicules reçus à ceux de cet appareil, en gardant vos réglages.\n\n" +
                        "Remplacer : efface les données de cet appareil et les remplace par celles reçues.")
                },
                confirmButton = {
                    TextButton(enabled = l.conn == Conn.IDLE, onClick = {
                        val ok = ObdManager.importBackup(json, merge = true)
                        ObdManager.toast(if (ok) "Données fusionnées." else "Fichier non reconnu.")
                    }) { Text("Fusionner") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { ObdManager.offerImport(null) }) { Text("Annuler") }
                        TextButton(enabled = l.conn == Conn.IDLE, onClick = {
                            val ok = ObdManager.importBackup(json, merge = false)
                            ObdManager.toast(if (ok) "Données remplacées." else "Fichier non reconnu.")
                        }) { Text("Remplacer", color = LocalObd.current.bad) }
                    }
                },
            )
        }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (drive) {
                DriveMode(l, v, s) { drive = false }
                return@Surface
            }
            if (settingsOpen) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    SettingsScreen(s, maxWidth >= 700.dp) { settingsOpen = false }
                }
                return@Surface
            }
            WallpaperLayer(s.wallpaper, speed = l.values["speed"], rpm = l.values["rpm"], rpmLimit = s.rpmLimit)
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val autoCar = maxWidth >= 700.dp && maxHeight < 700.dp && maxWidth > maxHeight
                val car = when (s.layoutMode) { LayoutMode.CAR -> true; LayoutMode.AUTO -> autoCar; else -> false }
                val wide = when (s.layoutMode) { LayoutMode.PHONE -> false; LayoutMode.TABLET, LayoutMode.CAR -> true; LayoutMode.AUTO -> maxWidth >= 700.dp }
                val content: @Composable (Modifier) -> Unit = { m ->
                    Column(m.fillMaxSize()) {
                        TopBar(l.conn, l.recording, l.recStart, v.name, l.device, wide, upd.available, locked, s.mediaButtons || s.spotifyButton,
                            onDrive = { drive = true }, onSettings = { settingsOpen = true }, onPrices = { pricesOpen = true })
                        Box(Modifier.weight(1f)) {
                            when (tab) {
                                0 -> HomeScreen(l, v, wide, car, locked) { if (!locked) tab = it }
                                1 -> DiagScreen(l, wide)
                                2 -> if (wide && com.example.obdanalyzer.AppFlavor.CAR) MoreScreen(trips, vehicles, v, s, l) { tab = 0 } else TripsScreen(trips, v, l, wide)
                                else -> GarageScreen(vehicles, v, s, l.stats.distanceKm, wide)
                            }
                        }
                    }
                }
                if (wide && !com.example.obdanalyzer.AppFlavor.CAR) {
                    Row(Modifier.fillMaxSize().systemBarsPadding()) {
                        NavigationRail(containerColor = Color.Transparent) {
                            Spacer(Modifier.height(12.dp))
                            TABS.forEachIndexed { i, t ->
                                NavigationRailItem(selected = tab == i, onClick = { tab = i }, enabled = !locked || i == 0,
                                    icon = { TabIcon(t, i == 1 && !l.dtcs.isNullOrEmpty()) }, label = { Text(t.label) })
                            }
                        }
                        VerticalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
                        content(Modifier.weight(1f))
                    }
                } else if (wide) {
                    // SimCarDiag Auto : une seule barre de 5 gros boutons, faciles à toucher en roulant
                    Row(Modifier.fillMaxSize().systemBarsPadding()) {
                        CarRail(
                            items = listOf(
                                RailItem("Accueil", Icons.Rounded.Speed, tab == 0, enabled = true) { tab = 0 },
                                RailItem("Conduite", Icons.Rounded.Fullscreen, false, enabled = connected) { drive = true },
                                RailItem("Diagnostic", Icons.Rounded.Build, tab == 1, enabled = !locked, dot = !l.dtcs.isNullOrEmpty()) { tab = 1 },
                                RailItem("Carburant", Icons.Rounded.LocalGasStation, false, enabled = !locked) { pricesOpen = true },
                                RailItem("Plus", Icons.Rounded.Menu, tab >= 2, enabled = !locked, dot = upd.available) { tab = 2 },
                            ))
                        VerticalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
                        content(Modifier.weight(1f))
                    }
                } else {
                    Scaffold(
                        bottomBar = {
                            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                                TABS.forEachIndexed { i, t ->
                                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, enabled = !locked || i == 0,
                                        icon = { TabIcon(t, i == 1 && !l.dtcs.isNullOrEmpty()) }, label = { Text(t.label) })
                                }
                            }
                        },
                        snackbarHost = { SnackbarHost(snack) },
                        containerColor = Color.Transparent,
                    ) { pad -> content(Modifier.padding(pad)) }
                }
                if (wide) SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(24.dp))
            }
        }
    }
}

@Composable
private fun TabIcon(t: Tab, dot: Boolean) {
    BadgedBox(badge = { if (dot) Badge(containerColor = LocalObd.current.bad) }) { Icon(t.icon, t.label) }
}

@Composable
private fun TopBar(conn: Conn, rec: Boolean, recStart: Long, vehicle: String, device: String?, wide: Boolean, updateDot: Boolean,
                   locked: Boolean, media: Boolean, onDrive: () -> Unit, onSettings: () -> Unit, onPrices: () -> Unit) {
    val obd = LocalObd.current
    var ask by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = if (wide) 8.dp else 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(vehicle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            val (txt, c) = when {
                rec && conn == Conn.CONNECTED -> {
                    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); now = System.currentTimeMillis() } }
                    val t = (now - recStart) / 1000
                    "Enregistrement · ${t / 60}:${"%02d".format(t % 60)}" to obd.bad
                }
                locked -> "En roulant : commandes limitées" to obd.ok
                conn == Conn.CONNECTED -> "Connecté · ${device ?: "OBD"}" to obd.ok
                conn == Conn.RECONNECTING -> "Reconnexion en cours…" to obd.warn
                conn == Conn.CONNECTING -> "Connexion…" to obd.warn
                else -> "Non connecté" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                Text(txt, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (media && wide) { MediaBar(size = 48.dp); Spacer(Modifier.width(8.dp)) }
        // horloge (la barre d'Android est masquée en plein écran)
        Text(clockText(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
        if (conn == Conn.CONNECTED || conn == Conn.RECONNECTING) {
            if (!(wide && com.example.obdanalyzer.AppFlavor.CAR)) FilledTonalButton(onClick = onDrive) { Icon(Icons.Rounded.Fullscreen, null); Spacer(Modifier.width(6.dp)); Text("Conduite") }
            if (!locked) IconButton(onClick = { ask = true }) { Icon(Icons.Rounded.PowerSettingsNew, "Déconnecter") }
        } else if (conn == Conn.CONNECTING) {
            IconButton(onClick = { ObdManager.disconnect() }) { Icon(Icons.Rounded.Close, "Annuler") }
        }
        // raccourci : stations les moins chères autour de soi (désactivé en roulant)
        val car = wide && com.example.obdanalyzer.AppFlavor.CAR
        if (!car) IconButton(onClick = onPrices, enabled = !locked) { Icon(Icons.Rounded.LocalGasStation, "Carburant le moins cher") }
        if (!car) IconButton(onClick = onSettings, enabled = !locked) {
            BadgedBox(badge = { if (updateDot) Badge(containerColor = MaterialTheme.colorScheme.primary) }) { Icon(Icons.Rounded.Settings, "Paramètres") }
        }
    }
    // téléphone : les boutons musique ont leur propre ligne sous la barre du haut
    if (media && !wide) MediaBar(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), size = 44.dp)
    if (ask) AlertDialog(
        onDismissRequest = { ask = false },
        title = { Text("Terminer le trajet ?") },
        text = { Text("L'appli se déconnecte du boîtier et enregistre ce trajet dans l'historique.") },
        confirmButton = { TextButton(onClick = { ask = false; ObdManager.disconnect() }) { Text("Terminer") } },
        dismissButton = { TextButton(onClick = { ask = false }) { Text("Continuer") } },
    )
}


private class RailItem(val label: String, val icon: ImageVector, val selected: Boolean, val enabled: Boolean,
                       val dot: Boolean = false, val onClick: () -> Unit)

/** Barre latérale de l'autoradio : 5 grosses tuiles qui se partagent toute la hauteur de l'écran. */
@Composable
private fun CarRail(items: List<RailItem>) {
    Column(Modifier.fillMaxHeight().width(112.dp).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { it ->
            val bg = if (it.selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
            val fg = if (it.selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            Surface(onClick = it.onClick, enabled = it.enabled, shape = RoundedCornerShape(20.dp), color = bg,
                modifier = Modifier.weight(1f).fillMaxWidth().alpha(if (it.enabled) 1f else 0.4f)) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    BadgedBox(badge = { if (it.dot) Badge(containerColor = LocalObd.current.bad) }) {
                        Icon(it.icon, null, Modifier.size(34.dp), tint = fg)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(it.label, color = fg, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
    }
}

/** « Plus » : tout ce qui sert rarement en roulant, regroupé en un seul endroit. */
@Composable
private fun MoreScreen(trips: List<TripSummary>, vehicles: List<Vehicle>, v: Vehicle, s: Settings, l: Live, onBack: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Trajets" to Icons.Rounded.Map, "Véhicule" to Icons.Rounded.DirectionsCar, "Réglages" to Icons.Rounded.Settings).forEachIndexed { i, (n, ic) ->
                FilterChip(selected = page == i, onClick = { page = i }, label = { Text(n, style = MaterialTheme.typography.titleSmall) },
                    leadingIcon = { Icon(ic, null) }, modifier = Modifier.height(44.dp))
            }
        }
        Box(Modifier.weight(1f)) {
            when (page) {
                0 -> TripsScreen(trips, v, l, true)
                1 -> GarageScreen(vehicles, v, s, l.stats.distanceKm, true)
                else -> SettingsScreen(s, true, onBack)
            }
        }
    }
}
