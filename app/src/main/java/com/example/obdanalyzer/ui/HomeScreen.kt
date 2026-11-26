package com.example.obdanalyzer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.obdanalyzer.core.Conn
import com.example.obdanalyzer.core.Level
import com.example.obdanalyzer.core.Live
import com.example.obdanalyzer.core.ObdManager
import com.example.obdanalyzer.core.Presets
import com.example.obdanalyzer.core.Vehicle
import com.example.obdanalyzer.core.WidgetCfg

@Composable
fun HomeScreen(l: Live, v: Vehicle, wide: Boolean, car: Boolean = false, locked: Boolean = false, onTab: (Int) -> Unit) {
    if (l.conn == Conn.IDLE || l.conn == Conn.CONNECTING) {
        ConnectPanel(l, wide)
        return
    }
    var carEdit by rememberSaveable { mutableStateOf(false) }
    val showHealth = ObdManager.settings.collectAsState().value.showHealth
    if (locked) carEdit = false
    if (car && !carEdit) {
        CarDashboard(l, v, locked, showHealth, onTab) { carEdit = true }
        return
    }
    if (car) {
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { carEdit = false }, Modifier.padding(start = 8.dp)) {
                Icon(Icons.Rounded.ChevronLeft, null); Text("Retour à l'affichage autoradio")
            }
            Box(Modifier.weight(1f)) { Dashboard(l, v, cols = 4, wide = true, locked = false, onTab = onTab, header = null) }
        }
        return
    }
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 16.dp, top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (showHealth) HealthCard(l, v, onTab)
                DeviceCard(l, v)
            }
            Dashboard(l, v, cols = 4, wide = true, locked = locked, onTab = onTab, header = null)
        }
    } else {
        Dashboard(l, v, cols = 2, wide = false, locked = locked, onTab = onTab, header = if (showHealth) ({ HealthCard(l, v, onTab) }) else null)
    }
}

/**
 * Affichage autoradio (écran large et peu haut, ex. 1024 × 600) : les widgets remplissent toute la hauteur,
 * sans zone vide en bas. Lignes de 4 cases (un widget large en prend 2) ; au-delà de 3 lignes, on fait défiler.
 */
@Composable
private fun CarDashboard(l: Live, v: Vehicle, locked: Boolean, showHealth: Boolean, onTab: (Int) -> Unit, onEdit: () -> Unit) {
    val rows = remember(v.layout) {
        val out = mutableListOf<MutableList<WidgetCfg>>()
        var used = 4
        for (w in v.layout.filter { it.kind != "rec" }) {   // l'enregistrement passe en bouton dans la barre du haut
            val span = if (w.wide) 2 else 1
            if (used + span > 4) { out += mutableListOf<WidgetCfg>(); used = 0 }
            out.last() += w; used += span
        }
        out
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)) {
        val gap = 10.dp
        val bar = 40.dp
        val n = rows.size.coerceAtLeast(1)
        val shownRows = minOf(n, 3)
        val rowH = ((maxHeight - bar - gap * shownRows) / shownRows).coerceAtLeast(120.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(gap)) {
            Row(Modifier.fillMaxWidth().height(bar), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showHealth) CarHealthChip(l, v, Modifier.weight(1f)) { onTab(1) } else Spacer(Modifier.weight(1f))
                if (v.layout.any { it.kind == "rec" }) {
                    if (l.recording) Button(onClick = { ObdManager.toggleRecording() }, colors = ButtonDefaults.buttonColors(containerColor = LocalObd.current.bad)) {
                        Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(6.dp)); Text("Arrêter")
                    } else FilledTonalButton(onClick = { ObdManager.toggleRecording() }) {
                        Icon(Icons.Rounded.FiberManualRecord, null); Spacer(Modifier.width(6.dp)); Text("Enregistrer")
                    }
                }
                if (!locked) FilledTonalButton(onClick = onEdit) { Icon(Icons.Rounded.Tune, null); Spacer(Modifier.width(6.dp)); Text("Personnaliser") }
            }
            if (rows.isEmpty()) EmptyState(Icons.Rounded.Dashboard, MaterialTheme.colorScheme.primary, "Aucun widget", "Touchez Personnaliser pour en ajouter.")
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth().height(rowH), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    var used = 0
                    row.forEach { w ->
                        val span = if (w.wide) 2 else 1
                        used += span
                        Card(Modifier.weight(span.toFloat()).fillMaxHeight(), shape = CardShape,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                            Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
                                WidgetContent(w, l, v, onTab)
                            }
                        }
                    }
                    if (used < 4) Spacer(Modifier.weight((4 - used).toFloat()))
                }
            }
        }
    }
}

/** Bandeau d'état compact pour l'autoradio : alertes ou « tout va bien ». */
@Composable
private fun CarHealthChip(l: Live, v: Vehicle, modifier: Modifier, onClick: () -> Unit) {
    val alert = l.alerts.firstOrNull { it.level != Level.OK }
    val dtc = l.dtcs?.size ?: 0
    val (txt, c) = when {
        alert != null -> alert.text to levelColor(alert.level)
        dtc > 0 -> "$dtc code(s) défaut : touchez pour voir" to LocalObd.current.warn
        else -> "Tout va bien · ${v.name}" to LocalObd.current.ok
    }
    Surface(onClick = onClick, modifier = modifier.fillMaxHeight(), shape = RoundedCornerShape(50), color = c.copy(alpha = 0.18f)) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(c))
            Text(txt, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------- tableau de bord

@Composable
private fun Dashboard(l: Live, v: Vehicle, cols: Int, wide: Boolean, locked: Boolean, onTab: (Int) -> Unit, header: (@Composable () -> Unit)?) {
    var edit by rememberSaveable { mutableStateOf(false) }
    if (locked) edit = false
    var adding by remember { mutableStateOf(false) }
    val layout = v.layout
    fun save(list: List<WidgetCfg>) = ObdManager.updateActive { it.copy(layout = list, preset = "Personnalisé") }

    LazyVerticalGrid(
        GridCells.Fixed(cols),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (edit) "Personnaliser" else "Mon tableau de bord", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (edit) Button(onClick = { edit = false }) { Icon(Icons.Rounded.Check, null); Spacer(Modifier.width(6.dp)); Text("Terminé") }
                    else if (!locked) FilledTonalButton(onClick = { edit = true }) { Icon(Icons.Rounded.Tune, null); Spacer(Modifier.width(6.dp)); Text("Personnaliser") }
                }
                AnimatedVisibility(edit) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Partez d'un modèle, puis ajustez chaque widget.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Presets.ALL.keys.forEach { name ->
                                FilterChip(selected = v.preset == name, onClick = {
                                    ObdManager.updateActive { it.copy(layout = Presets.get(name), preset = name) }
                                }, label = { Text(name) })
                            }
                        }
                    }
                }
            }
        }
        itemsIndexed(layout, span = { _, w -> GridItemSpan(if (w.wide) minOf(2, maxLineSpan) else 1) }) { i, w ->
            WidgetCard(w, l, v, edit, i, layout.size, onTab,
                onMove = { d -> val j = i + d; if (j in layout.indices) save(layout.toMutableList().apply { add(j, removeAt(i)) }) },
                onResize = { save(layout.toMutableList().apply { set(i, w.copy(wide = !w.wide)) }) },
                onRemove = { save(layout.toMutableList().apply { removeAt(i) }) })
        }
        if (edit || layout.isEmpty()) item(span = { GridItemSpan(minOf(2, maxLineSpan)) }) {
            OutlinedButton(onClick = { adding = true }, Modifier.fillMaxWidth().height(64.dp), shape = CardShape) {
                Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Ajouter un widget")
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                if (v.layout.any { it.metric == "cons" } && l.values["fuelrate"] == null)
                    "* consommation estimée (${v.fuel.label.lowercase()}, ${ObdManager.fmt(v.engineL)} L)" +
                        (if (v.consFactor != 1.0) ", recalée sur vos pleins ×${"%.2f".format(v.consFactor)}" else " : faites 2 pleins complets pour la recaler")
                else "",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (adding) AddWidgetSheet(layout, wide, onAdd = { save(layout + it); adding = false }, onDismiss = { adding = false })
}

@Composable
private fun WidgetCard(
    w: WidgetCfg, l: Live, v: Vehicle, edit: Boolean, i: Int, n: Int, onTab: (Int) -> Unit,
    onMove: (Int) -> Unit, onResize: () -> Unit, onRemove: () -> Unit,
) {
    val bare = w.kind == "rec" && !edit
    val body: @Composable ColumnScope.() -> Unit = {
        if (edit) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SmallIcon(Icons.Rounded.ChevronLeft, "Déplacer avant", i > 0) { onMove(-1) }
            SmallIcon(Icons.Rounded.ChevronRight, "Déplacer après", i < n - 1) { onMove(1) }
            if (w.kind == "val" || w.kind == "gauge") SmallIcon(Icons.Rounded.SwapHoriz, "Changer la taille", true, onClick = onResize)
            SmallIcon(Icons.Rounded.Close, "Retirer", true, tint = LocalObd.current.bad, onClick = onRemove)
        }
        WidgetContent(w, l, v, onTab)
    }
    if (bare) Column(content = body)
    else Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp).fillMaxWidth().heightIn(min = 90.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = body)
    }
}

@Composable
private fun SmallIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, enabled: Boolean,
                      tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp).padding(2.dp)) {
        Icon(icon, desc, tint = if (enabled) tint else tint.copy(alpha = 0.3f), modifier = Modifier.size(20.dp))
    }
}

// ---------------------------------------------------------------- état du véhicule

@Composable
fun HealthCard(l: Live, v: Vehicle, onTab: (Int) -> Unit) {
    val h = health(l, v)
    val c = levelColor(h.level)
    val base = MaterialTheme.colorScheme.surfaceContainer
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(c.copy(alpha = 0.22f), base, base)))
            .padding(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("État du véhicule", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(if (h.level == Level.OK) Icons.Rounded.CheckCircle else Icons.Rounded.Warning, null, tint = c)
                        Text(h.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = c)
                    }
                }
                Icon(Icons.Rounded.DirectionsCar, null, tint = c, modifier = Modifier.size(64.dp))
            }
            Text(h.text, style = MaterialTheme.typography.bodyMedium)
            nextMaintenance(v, l.stats.distanceKm)?.let {
                Row(Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.EventNote, null, tint = LocalObd.current.warn, modifier = Modifier.size(20.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onTab(3) }) { Text("Voir") }
                }
            }
            if (l.dtcs == null && l.conn == Conn.CONNECTED) {
                Button(onClick = { ObdManager.readDiagnostics() }, enabled = !l.diagLoading) {
                    Icon(Icons.Rounded.Build, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(if (l.diagLoading) "Lecture…" else "Vérifier les codes défaut")
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(l: Live, v: Vehicle) {
    Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WidgetTitle(Icons.Rounded.Bluetooth, LocalObd.current.engine, "Boîtier OBD")
            KeyValue("Modèle", l.device ?: "—")
            KeyValue("Protocole", l.protocol.ifEmpty { "—" })
            KeyValue("Capteurs", "${l.supported.size} détectés")
            KeyValue("Lecture", if (l.multi) "Groupée (rapide)" else if (l.isStn) "Puce STN" else "Standard")
            KeyValue("Véhicule", v.name)
            l.vin?.let { KeyValue("VIN", it) }
            JournalButton()
        }
    }
}

@Composable
fun KeyValue(k: String, v: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------- connexion

private val STEPS = listOf("Connexion au boîtier", "Réveil de la puce OBDLink", "Recherche du langage de la voiture", "Détection des capteurs")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectPanel(l: Live, wide: Boolean) {
    var devices by remember { mutableStateOf(ObdManager.pairedDevices()) }
    val btOn = ObdManager.bluetoothOn()
    val left: @Composable ColumnScope.() -> Unit = {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.SettingsInputComponent, LocalObd.current.engine, 76)
            Icon(Icons.Rounded.Bluetooth, null, tint = LocalObd.current.ok, modifier = Modifier.padding(horizontal = 18.dp).size(36.dp))
            IconBadge(Icons.Rounded.PhoneAndroid, LocalObd.current.ok, 76)
        }
        if (l.conn == Conn.CONNECTING) {
            Text("On fait connaissance…", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            Text("Quelques secondes, le temps que le boîtier parle à votre voiture.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("Connectons votre voiture", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            listOf(
                "Branchez l'OBDLink MX+ sur la prise OBD, souvent sous le volant.",
                "Mettez le contact, inutile de démarrer.",
                "Appuyez sur le bouton Connect du boîtier, puis appairez-le dans les réglages Bluetooth dans les 2 minutes.",
            ).forEachIndexed { i, s ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(s, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    val right: @Composable ColumnScope.() -> Unit = {
        if (l.conn == Conn.CONNECTING) {
            STEPS.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    when {
                        i < l.step -> Icon(Icons.Rounded.CheckCircle, null, tint = LocalObd.current.ok, modifier = Modifier.size(30.dp))
                        i == l.step -> CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
                        else -> Box(Modifier.size(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh))
                    }
                    Text(s, color = if (i <= l.step) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            var open by remember { mutableStateOf(false) }
            TextButton(onClick = { open = !open }) { Text(if (open) "Masquer les détails techniques" else "Détails techniques") }
            if (open) Text(l.log.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            l.error?.let { ErrorBox(it); JournalButton() }
            val parked by ObdManager.vehicles.collectAsState()
            parked.firstOrNull { it.id == ObdManager.settings.value.activeVehicleId }?.let { ParkCard(it) }
            // Type de boîtier : Bluetooth, Wi-Fi ou câble USB
            val last = ObdManager.settings.value.lastDevice ?: ""
            var link by rememberSaveable { mutableIntStateOf(when { com.example.obdanalyzer.obd.Links.isWifi(last) -> 1; com.example.obdanalyzer.obd.Links.isUsb(last) -> 2; else -> 0 }) }
            Text("Type de boîtier", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Bluetooth" to Icons.Rounded.Bluetooth, "Wi-Fi" to Icons.Rounded.Wifi, "Câble USB" to Icons.Rounded.Usb).forEachIndexed { i, (n, ic) ->
                    SegmentedButton(selected = link == i, onClick = { link = i; ObdManager.clearError() }, shape = SegmentedButtonDefaults.itemShape(i, 3),
                        icon = { Icon(ic, null) }) { Text(n) }
                }
            }
            when (link) {
                1 -> WifiConnect(last)
                2 -> UsbConnect()
                else -> BluetoothList(devices, btOn) { devices = ObdManager.pairedDevices() }
            }
        }
    }
    ConnectLayout(wide, left, right)
}

@Composable
private fun WifiConnect(last: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val parts = last.removePrefix("wifi:").split(":")
    var host by rememberSaveable { mutableStateOf(if (last.startsWith("wifi:")) parts.getOrElse(0) { "192.168.0.10" } else "192.168.0.10") }
    var port by rememberSaveable { mutableStateOf(if (last.startsWith("wifi:")) parts.getOrElse(1) { "35000" } else "35000") }
    Text("1. Dans les réglages Wi-Fi de l'autoradio, connectez-vous au réseau du boîtier (souvent « OBDLink MX… » ou « WiFi_OBDII »). " +
        "Si Android dit que ce réseau n'a pas Internet, choisissez de rester connecté.\n2. Touchez Se connecter.",
        style = MaterialTheme.typography.bodyMedium)
    OutlinedButton(onClick = { runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) } }) {
        Icon(Icons.Rounded.Wifi, null); Spacer(Modifier.width(6.dp)); Text("Ouvrir les réglages Wi-Fi")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(host, { host = it.trim() }, label = { Text("Adresse") }, singleLine = true, modifier = Modifier.weight(2f))
        OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f))
    }
    Text("La plupart des boîtiers : 192.168.0.10, port 35000.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button(onClick = { ObdManager.connect(com.example.obdanalyzer.obd.Links.wifiAddress(host.ifBlank { "192.168.0.10" }, port.toIntOrNull() ?: 35000)) },
        modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Se connecter en Wi-Fi") }
}

@Composable
private fun UsbConnect() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var found by remember { mutableIntStateOf(com.example.obdanalyzer.obd.Links.usbDevices(ctx).size) }
    Text("Branchez le câble du boîtier sur une prise USB de l'autoradio, puis touchez Se connecter. " +
        "Android vous demandera d'autoriser l'accès au câble : acceptez (cochez « toujours » si proposé).",
        style = MaterialTheme.typography.bodyMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (found > 0) "Câble détecté" else "Aucun câble détecté pour l'instant",
            color = if (found > 0) LocalObd.current.ok else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        TextButton(onClick = { found = com.example.obdanalyzer.obd.Links.usbDevices(ctx).size }) { Icon(Icons.Rounded.Refresh, null); Text("Actualiser") }
    }
    Button(onClick = { ObdManager.connect("usb:auto") }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Se connecter par câble USB") }
}

@Composable
private fun BluetoothList(devices: List<com.example.obdanalyzer.core.DeviceInfo>, btOn: Boolean, onRefresh: () -> Unit) {
    run {
        run {
            if (!btOn) ErrorBox("Le Bluetooth est désactivé. Activez-le puis touchez Actualiser.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Mes appareils Bluetooth", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { onRefresh(); ObdManager.clearError() }) {
                    Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(4.dp)); Text("Actualiser")
                }
            }
            if (devices.isEmpty() && btOn) Text("Aucun appareil appairé pour l'instant.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            devices.forEach { d ->
                Card(onClick = {
                    if (d.isObd) ObdManager.connect(d.address)
                    else ObdManager.connect(d.address)
                }, shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconBadge(Icons.Rounded.Bluetooth, if (d.isObd) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f)) {
                            Text(d.name, fontWeight = FontWeight.Bold)
                            Text(if (d.isObd) "Boîtier OBD" else d.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (d.isObd) AssistChip(onClick = { ObdManager.connect(d.address) }, label = { Text("Recommandé") })
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectLayout(wide: Boolean, left: @Composable ColumnScope.() -> Unit, right: @Composable ColumnScope.() -> Unit) {
    if (wide) {
        Row(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp), content = left)
            Column(Modifier.weight(1.2f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = right)
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            left(); right()
        }
    }
}

@Composable
fun ErrorBox(text: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(LocalObd.current.bad.copy(alpha = 0.14f)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Rounded.Warning, null, tint = LocalObd.current.bad)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
