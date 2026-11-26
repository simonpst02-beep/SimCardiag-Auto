package com.example.obdanalyzer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.obdanalyzer.core.CustomPid
import com.example.obdanalyzer.core.Fuel
import com.example.obdanalyzer.core.MaintItem
import com.example.obdanalyzer.core.ObdManager
import com.example.obdanalyzer.core.Settings
import com.example.obdanalyzer.core.ThemeMode
import com.example.obdanalyzer.core.LayoutMode
import com.example.obdanalyzer.core.Accent
import com.example.obdanalyzer.core.Wallpaper
import com.example.obdanalyzer.core.Orientation
import com.example.obdanalyzer.core.PollRate
import com.example.obdanalyzer.core.ConsUnit
import com.example.obdanalyzer.core.TempUnit
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.example.obdanalyzer.core.Vehicle
import com.example.obdanalyzer.core.WidgetCfg
import com.example.obdanalyzer.core.Presets
import com.example.obdanalyzer.core.status
import com.example.obdanalyzer.obd.Expr
import com.example.obdanalyzer.obd.TorqueImport
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.example.obdanalyzer.core.Conn
import kotlin.math.roundToInt

@Composable
fun GarageScreen(vehicles: List<Vehicle>, active: Vehicle, s: Settings, liveKm: Double, wide: Boolean) {
    val left: @Composable ColumnScope.() -> Unit = {
        VehiclesCard(vehicles, active)
        CustomPidsCard(active)
    }
    val right: @Composable ColumnScope.() -> Unit = {
        MaintenanceCard(active, liveKm)
    }
    TwoColumns(wide, left, right)
}

/** Écran Paramètres, ouvert par la roue dentée en haut à droite. */
@Composable
fun SettingsScreen(s: Settings, wide: Boolean, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Retour") }
            Text("Paramètres", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        }
        Box(Modifier.weight(1f)) {
            TwoColumns(wide,
                left = { UpdatesCard(s); AppearanceCard(s); DashboardCard(wide); CarCard(s); DrivingCard(s) },
                right = { SettingsCard(s); AlertsCard(s); DriveModeCard(s); DataCard(s); BackupCard(); AboutCard() })
        }
    }
}

@Composable
private fun TwoColumns(wide: Boolean, left: @Composable ColumnScope.() -> Unit, right: @Composable ColumnScope.() -> Unit) {
    if (wide) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = left)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = right)
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            left(); right()
        }
    }
}

// ---------------------------------------------------------------- véhicules

@Composable
private fun VehiclesCard(vehicles: List<Vehicle>, active: Vehicle) {
    var editing by remember { mutableStateOf<Vehicle?>(null) }
    var adding by remember { mutableStateOf(false) }
    SectionCard(Icons.Rounded.DirectionsCar, LocalObd.current.speed, "Mes véhicules") {
        vehicles.forEach { v ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = v.id == active.id, onClick = { ObdManager.selectVehicle(v.id) })
                Column(Modifier.weight(1f)) {
                    Text(v.name, fontWeight = FontWeight.Bold)
                    Text("${v.fuel.label} · ${ObdManager.fmt(v.odometerKm)} km${v.vin?.let { " · VIN …${it.takeLast(6)}" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton(onClick = { editing = v }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Modifier")
                }
            }
        }
        TextButton(onClick = { adding = true }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Ajouter un véhicule") }
        Text("Chaque véhicule a son tableau de bord, ses trajets et son entretien. Il est reconnu automatiquement par son VIN au branchement.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    editing?.let { v -> VehicleDialog(v, canDelete = vehicles.size > 1, onDismiss = { editing = null }) }
    if (adding) VehicleDialog(null, false, onDismiss = { adding = false })
}

@Composable
private fun VehicleDialog(v: Vehicle?, canDelete: Boolean, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(v?.name ?: "") }
    var fuel by remember { mutableStateOf(v?.fuel ?: Fuel.ESSENCE) }
    var tank by remember { mutableStateOf(v?.tankL?.roundToInt()?.toString() ?: "50") }
    var odo by remember { mutableStateOf(v?.odometerKm?.roundToInt()?.toString() ?: "0") }
    var cc by remember { mutableStateOf(v?.engineL?.toString()?.replace('.', ',') ?: "1,4") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (v == null) "Nouveau véhicule" else "Modifier le véhicule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nom") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Fuel.entries.forEach { f -> FilterChip(selected = fuel == f, onClick = { fuel = f }, label = { Text(f.label) }) }
                }
                OutlinedTextField(tank, { tank = it.filter(Char::isDigit) }, label = { Text("Réservoir (litres)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(cc, { cc = it.filter { c -> c.isDigit() || c == ',' || c == '.' } }, label = { Text("Cylindrée (litres)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = { Text("Ex. 1,3 pour une Corsa 1.3 CDTI, 2,0 pour un D2") })
                OutlinedTextField(odo, { odo = it.filter(Char::isDigit) }, label = { Text("Kilométrage au compteur") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text("Mis à jour automatiquement après chaque trajet") })
                if (v != null && canDelete) TextButton(onClick = { ObdManager.deleteVehicle(v.id); onDismiss() }) {
                    Text("Supprimer ce véhicule", color = LocalObd.current.bad)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val t = tank.toDoubleOrNull() ?: 50.0
                val o = odo.toDoubleOrNull() ?: 0.0
                val e = cc.replace(',', '.').toDoubleOrNull()?.coerceIn(0.6, 7.0) ?: 1.4
                if (v == null) {
                    val nv = ObdManager.addVehicle(name.trim(), fuel)
                    ObdManager.updateVehicle(nv.id) { it.copy(tankL = t, odometerKm = o, engineL = e, maintenance = it.maintenance.map { m -> m.copy(lastKm = o) }) }
                    ObdManager.selectVehicle(nv.id)
                } else {
                    ObdManager.updateVehicle(v.id) { it.copy(name = name.trim(), fuel = fuel, tankL = t, odometerKm = o, engineL = e) }
                }
                onDismiss()
            }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

// ---------------------------------------------------------------- entretien

@Composable
private fun MaintenanceCard(v: Vehicle, liveKm: Double) {
    val obd = LocalObd.current
    var adding by remember { mutableStateOf(false) }
    val odo = v.odometerKm + liveKm
    val now = System.currentTimeMillis()
    SectionCard(Icons.Rounded.EventNote, obd.warn, "Carnet d'entretien") {
        Text("Compteur : ${ObdManager.fmt(odo)} km", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        v.maintenance.forEach { m ->
            val st = m.status(odo, now)
            val c = when { st.overdue -> obd.bad; st.soon -> obd.warn; else -> obd.ok }
            val frac = listOfNotNull(
                m.everyKm?.let { ((odo - m.lastKm) / it).toFloat() },
                m.everyMonths?.let { ((now - m.lastDate).toFloat() / (it * 30f * 86_400_000f)) },
            ).maxOrNull()?.coerceIn(0f, 1f) ?: 0f
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name, fontWeight = FontWeight.SemiBold)
                        Text(buildList {
                            st.kmLeft?.let { add(if (it <= 0) "dépassé de ${(-it).roundToInt()} km" else "dans ${it.roundToInt()} km") }
                            st.daysLeft?.let { add(if (it <= 0) "échéance passée" else "dans $it jours") }
                        }.joinToString(" ou "), style = MaterialTheme.typography.bodySmall, color = c)
                    }
                    TextButton(onClick = {
                        ObdManager.updateVehicle(v.id) { veh ->
                            veh.copy(maintenance = veh.maintenance.map { if (it.id == m.id) it.copy(lastKm = odo, lastDate = System.currentTimeMillis()) else it })
                        }
                    }) { Text("Fait") }
                    IconButton(onClick = {
                        ObdManager.updateVehicle(v.id) { veh -> veh.copy(maintenance = veh.maintenance.filterNot { it.id == m.id }) }
                    }) { Icon(Icons.Rounded.Close, "Supprimer", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                LinearProgressIndicator(progress = { frac }, color = c, trackColor = obd.track, modifier = Modifier.fillMaxWidth())
            }
        }
        TextButton(onClick = { adding = true }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Ajouter un rappel") }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var km by remember { mutableStateOf("") }
        var months by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Nouveau rappel d'entretien") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Nom (ex. Pneus, Courroie)") }, singleLine = true)
                    OutlinedTextField(km, { km = it.filter(Char::isDigit) }, label = { Text("Tous les … km") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(months, { months = it.filter(Char::isDigit) }, label = { Text("Ou tous les … mois") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Text("Le compteur part d'aujourd'hui. Touchez « Fait » après chaque entretien.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && (km.isNotEmpty() || months.isNotEmpty()), onClick = {
                    val item = MaintItem(name = name.trim(), everyKm = km.toIntOrNull(), everyMonths = months.toIntOrNull(),
                        lastKm = odo, lastDate = System.currentTimeMillis())
                    ObdManager.updateVehicle(v.id) { it.copy(maintenance = it.maintenance + item) }
                    adding = false
                }) { Text("Ajouter") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Annuler") } },
        )
    }
}

// ---------------------------------------------------------------- capteurs personnalisés

@Composable
private fun CustomPidsCard(v: Vehicle) {
    var adding by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    SectionCard(Icons.Rounded.Memory, LocalObd.current.custom, "Données constructeur") {
        Text("Ajoutez des mesures propres à votre voiture (turbo, filtre à particules, boîte…) en saisissant la commande et la formule trouvées pour votre modèle. Elles deviennent disponibles comme widgets.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        v.customPids.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, fontWeight = FontWeight.SemiBold)
                    Text("${if (c.header.isNotBlank()) "ATSH${c.header} · " else ""}${c.command} · ${c.formula} ${c.unit}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = {
                    ObdManager.updateVehicle(v.id) { veh ->
                        veh.copy(customPids = veh.customPids.filterNot { it.id == c.id }, layout = veh.layout.filterNot { it.metric == c.key })
                    }
                }) { Icon(Icons.Rounded.Close, "Supprimer") }
            }
        }
        Row {
            TextButton(onClick = { adding = true }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Ajouter une mesure") }
            TextButton(onClick = { importing = true }) { Icon(Icons.Rounded.FileUpload, null); Spacer(Modifier.width(6.dp)); Text("Importer une liste") }
        }
    }
    if (importing) ImportPidsDialog(onDismiss = { importing = false }) { list ->
        ObdManager.updateVehicle(v.id) { it.copy(customPids = it.customPids + list) }
        importing = false
    }
    if (adding) CustomPidDialog(onDismiss = { adding = false }) { c ->
        ObdManager.updateVehicle(v.id) { it.copy(customPids = it.customPids + c) }
        adding = false
    }
}

@Composable
private fun CustomPidDialog(onDismiss: () -> Unit, onSave: (CustomPid) -> Unit) {
    var name by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }
    var header by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var formula by remember { mutableStateOf("") }
    var max by remember { mutableStateOf("100") }
    val valid = Expr.compile(formula) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouvelle mesure") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Exemples standard : température du catalyseur → 013C, formule (A*256+B)/10-40, unité °C. Pression atmosphérique → 0133, formule A, unité kPa.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name, { name = it }, label = { Text("Nom") }, singleLine = true)
                OutlinedTextField(command, { command = it.uppercase().filter { ch -> ch.isLetterOrDigit() } }, label = { Text("Commande (ex. 013C ou 221940)") }, singleLine = true)
                OutlinedTextField(header, { header = it.uppercase().filter { ch -> ch.isLetterOrDigit() } }, label = { Text("En-tête calculateur (facultatif, ex. 7E0)") }, singleLine = true)
                OutlinedTextField(formula, { formula = it }, label = { Text("Formule avec A, B, C, D") }, singleLine = true,
                    isError = formula.isNotEmpty() && !valid,
                    supportingText = { if (formula.isNotEmpty() && !valid) Text("Formule invalide") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(unit, { unit = it }, label = { Text("Unité") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(max, { max = it.filter { ch -> ch.isDigit() || ch == '.' } }, label = { Text("Maximum") }, singleLine = true,
                        modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && command.length >= 4 && valid, onClick = {
                onSave(CustomPid(name = name.trim(), unit = unit.trim(), header = header, command = command, formula = formula,
                    max = max.toDoubleOrNull() ?: 100.0))
            }) { Text("Ajouter") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

// ---------------------------------------------------------------- réglages

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DriveModeCard(s: Settings) {
    SectionCard(Icons.Rounded.Fullscreen, LocalObd.current.engine, "Mode conduite") {
        Text("Thème", fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            com.example.obdanalyzer.core.DriveTheme.entries.forEach { t ->
                val on = s.driveTheme == t
                Surface(onClick = { ObdManager.updateSettings { it.copy(driveTheme = t, driveAccent = 0) } },
                    shape = RoundedCornerShape(14.dp), border = BorderStroke(if (on) 3.dp else 1.dp, if (on) Color(t.accent) else MaterialTheme.colorScheme.outlineVariant)) {
                    Box(Modifier.background(Brush.radialGradient(listOf(Color(t.bgCenter), Color(t.bgEdge))))) {
                    if (t.carbon) CarbonWeave(Modifier.matchParentSize())
                    Column(Modifier.padding(10.dp).width(110.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(t.low, t.mid, t.high).forEach { c -> Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(50)).background(Color(c))) }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(t.label, color = Color(t.text), fontWeight = FontWeight.Bold, maxLines = 1)
                        Box(Modifier.padding(top = 4.dp).width(40.dp).height(4.dp).background(Color(t.accent)))
                    }
                    }
                }
            }
        }
        Text("Fond", fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val stl = driveStyle(s)
            com.example.obdanalyzer.core.DriveBg.entries.forEach { b ->
                val on = s.driveBg == b
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(96.dp, 56.dp).clip(RoundedCornerShape(12.dp))
                        .background(Brush.radialGradient(listOf(stl.bgCenter, stl.bgEdge)))
                        .border(if (on) 3.dp else 1.dp, if (on) stl.accent else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .clickable { ObdManager.updateSettings { it.copy(driveBg = b) } }) {
                        DriveBackground(b, stl, Modifier.matchParentSize())
                    }
                    Text(b.label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text("Police des chiffres", fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.example.obdanalyzer.core.DriveFont.entries.forEach { f ->
                val st = driveStyle(s.copy(driveFont = f))
                FilterChip(selected = s.driveFont == f, onClick = { ObdManager.updateSettings { it.copy(driveFont = f) } },
                    label = { Text("88 · ${f.label}", fontFamily = st.family, fontWeight = st.weight,
                        fontStyle = if (st.italic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal) })
            }
        }
        Text("Couleur principale", fontWeight = FontWeight.SemiBold)
        val colors = listOf(0L to "Du thème", 0xFFE10600 to "Rouge", 0xFFFF7A00 to "Orange", 0xFFFFD23F to "Jaune", 0xFF39FF14 to "Vert",
            0xFF2EC7FF to "Bleu", 0xFF8A7DFF to "Violet", 0xFFFF3EC8 to "Rose", 0xFFFFFFFF to "Blanc")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            colors.forEach { (c, n) ->
                val on = s.driveAccent == c
                val shown = if (c == 0L) Color(s.driveTheme.accent) else Color(c)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(shown)
                        .border(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .clickable { ObdManager.updateSettings { it.copy(driveAccent = c) } })
                    Text(n, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        SwitchRow("Assombrir la nuit", "De 20 h à 7 h, l'écran du mode conduite est atténué pour ne pas éblouir", s.nightDim) { on ->
            ObdManager.updateSettings { it.copy(nightDim = on) }
        }
        SwitchRow("Conseil moteur froid", "Sous 60 °C : bandeau « évitez les hauts régimes » et passage des vitesses conseillé 500 tr/min plus tôt", s.coldAdvice) { on ->
            ObdManager.updateSettings { it.copy(coldAdvice = on) }
        }
        val gears = ObdManager.activeVehicle().gearRatios.size
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (gears >= 2) "Rapport engagé : $gears rapports appris" else "Rapport engagé : apprentissage en cours (roulez quelques km en passant les vitesses)",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (gears > 0) TextButton(onClick = { ObdManager.resetGears() }) { Text("Réapprendre") }
        }
        Text("Mesures à droite du compte-tours", fontWeight = FontWeight.SemiBold)
        Text("Choisissez jusqu'à 8 mesures affichées en grand pendant la conduite (${s.driveMetrics.size}/8). Plus il y en a, plus les chiffres sont petits.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ObdManager.metricDefs().forEach { d ->
                val on = d.key in s.driveMetrics
                FilterChip(selected = on, enabled = on || s.driveMetrics.size < 8, onClick = {
                    ObdManager.updateSettings { st -> st.copy(driveMetrics = if (on) st.driveMetrics - d.key else st.driveMetrics + d.key) }
                }, label = { Text(d.name) })
            }
        }
    }
}

@Composable
private fun AlertsCard(s: Settings) {
    SectionCard(Icons.Rounded.NotificationsActive, LocalObd.current.bad, "Alertes") {
        SwitchRow("Son", "Bip quand une alerte apparaît", s.sound) { v -> ObdManager.updateSettings { it.copy(sound = v) } }
        SwitchRow("Vibration", "Le téléphone vibre en cas d'alerte", s.vibration) { v -> ObdManager.updateSettings { it.copy(vibration = v) } }
        SwitchRow("Alertes vocales", "Une voix lit l'alerte (« Température moteur élevée »…) au lieu du bip", s.voiceAlerts) { v ->
            ObdManager.updateSettings { it.copy(voiceAlerts = v) }
        }
        SwitchRow("Témoin de passage de vitesse", "Bande de voyants en mode conduite : verte au ralenti, orange puis rouge en montant, elle clignote au régime de passage", s.shiftLight) { v ->
            ObdManager.updateSettings { it.copy(shiftLight = v) }
        }
        if (s.shiftLight) {
            val cur = if (s.shiftRpm > 0) s.shiftRpm else 0
            Text("Régime de passage : " + (if (cur == 0) "automatique (2 500 tr/min en diesel, 3 000 en essence)" else "${ObdManager.fmt(cur.toDouble())} tr/min"),
                style = MaterialTheme.typography.bodySmall)
            Slider(value = (if (cur == 0) 1500 else cur).toFloat(), onValueChange = { x ->
                val r = (x / 100).roundToInt() * 100
                ObdManager.updateSettings { it.copy(shiftRpm = if (r <= 1500) 0 else r) }
            }, valueRange = 1500f..6500f, steps = 49)
            Text("Tout à gauche = automatique. Conduite économique : 2 000 à 2 500 tr/min en diesel.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SwitchRow("Zones de danger", "Annonce les zones de radars fixes (300 m en ville, 2 km sur route, 4 km sur autoroute), sans leur position exacte, comme Coyote ou Waze. Besoin du GPS ; liste officielle téléchargée une fois par mois.", s.dangerZones) { v ->
            ObdManager.updateSettings { it.copy(dangerZones = v) }
        }
        if (s.dangerZones) {
            val ctxZ = LocalContext.current
            val scopeZ = rememberCoroutineScope()
            var status by remember { mutableStateOf("Chargement…") }
            LaunchedEffect(Unit) { com.example.obdanalyzer.core.DangerZones.load(ctxZ); status = ObdManager.zoneStatus() }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { status = "Téléchargement…"; scopeZ.launch { com.example.obdanalyzer.core.DangerZones.load(ctxZ, force = true); status = ObdManager.zoneStatus() } }) { Text("Mettre à jour") }
            }
            Text("Fonctionne aussi sans le boîtier, avec le GPS de l'appareil. Annonce à l'entrée de chaque zone (son ou voix selon vos réglages d'alerte).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SwitchRow("Rappels d'entretien par notification", "Une fois par jour, même appli fermée : vidange, contrôle technique, assurance…", s.maintNotif) { v ->
            ObdManager.updateSettings { it.copy(maintNotif = v) }
        }
        SwitchRow("Limiter les commandes en roulant", "Au-dessus de 10 km/h : seul l'accueil reste accessible, pas de personnalisation ni de réglages", s.drivingLock) { v ->
            ObdManager.updateSettings { it.copy(drivingLock = v) }
        }
        Text("Alerte régime : ${s.rpmLimit} tr/min", fontWeight = FontWeight.SemiBold)
        Slider(value = s.rpmLimit.toFloat(), onValueChange = { v -> ObdManager.updateSettings { it.copy(rpmLimit = (v / 250).roundToInt() * 250) } },
            valueRange = 3000f..7000f, steps = 15)
        Text("Alerte température moteur : ${s.tempLimit} °C", fontWeight = FontWeight.SemiBold)
        Slider(value = s.tempLimit.toFloat(), onValueChange = { v -> ObdManager.updateSettings { it.copy(tempLimit = v.roundToInt()) } },
            valueRange = 95f..120f, steps = 24)
        Text(if (s.speedAlertKmh == 0) "Alerte de vitesse : désactivée" else "Alerte de vitesse : ${s.speedAlertKmh} km/h", fontWeight = FontWeight.SemiBold)
        Slider(value = s.speedAlertKmh.toFloat(), onValueChange = { v -> ObdManager.updateSettings { it.copy(speedAlertKmh = (v / 10).roundToInt() * 10) } },
            valueRange = 0f..200f, steps = 19)
        Text("Alerte réserve : sous ${s.reserveKm} km d'autonomie", fontWeight = FontWeight.SemiBold)
        Slider(value = s.reserveKm.toFloat(), onValueChange = { v -> ObdManager.updateSettings { it.copy(reserveKm = (v / 10).roundToInt() * 10) } },
            valueRange = 20f..150f, steps = 12)
        Text("Batterie faible au repos : sous ${"%.1f".format(s.batteryLowV)} V", fontWeight = FontWeight.SemiBold)
        Slider(value = s.batteryLowV.toFloat(), onValueChange = { v -> ObdManager.updateSettings { it.copy(batteryLowV = (v * 10).roundToInt() / 10.0) } },
            valueRange = 11.5f..12.6f, steps = 10)
        Text("Rappel de pause", fontWeight = FontWeight.SemiBold)
        ChipRow(listOf(0 to "Jamais", 90 to "1 h 30", 120 to "2 h", 180 to "3 h"), s.breakMinutes) { m -> ObdManager.updateSettings { it.copy(breakMinutes = m) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsCard(s: Settings) {
    SectionCard(Icons.Rounded.Bluetooth, MaterialTheme.colorScheme.primary, "Connexion et suivi") {
        SwitchRow("Connexion comme avant (méthode d'hier soir)",
            "Revient à la façon de dialoguer de la version 2.0.35 : protocole non figé, dialogue avec toute la voiture, délais longs. Utile pour comparer si la nouvelle méthode décroche. Prend effet à la prochaine connexion.",
            s.legacyLink) { v -> ObdManager.updateSettings { it.copy(legacyLink = v) } }
        SwitchRow("Reconnexion automatique", "Se reconnecte seul au boîtier après une coupure et à l'ouverture de l'appli", s.autoReconnect) { v ->
            ObdManager.updateSettings { it.copy(autoReconnect = v) }
        }
        SwitchRow("Surveiller les défauts en roulant", "Relit les codes chaque minute et prévient dès qu'un défaut apparaît", s.monitorDtc) { v ->
            ObdManager.updateSettings { it.copy(monitorDtc = v) }
        }
        SwitchRow("Tracer les trajets sur une carte", "Utilise le GPS de l'appareil pendant les trajets", s.gps) { v ->
            ObdManager.updateSettings { it.copy(gps = v) }
        }
        SwitchRow("Distance de secours par GPS", "Compte les km avec le GPS quand la voiture ne donne pas sa vitesse ou pendant une reconnexion", s.gpsDistance) { v ->
            ObdManager.updateSettings { it.copy(gpsDistance = v) }
        }
        val av = ObdManager.vehicles.value.firstOrNull { it.id == s.activeVehicleId }
        val ecart = av?.takeIf { it.speedSampleKm >= 10 }?.let { (1 / it.speedFactor - 1) * 100 }
        SwitchRow("Corriger avec le GPS",
            (if (ecart == null) "Mesure de votre compteur en cours : roulez une dizaine de km au-dessus de 40 km/h. "
            else "Votre compteur indique ${if (ecart >= 0) "+" else ""}${ObdManager.fmt(ecart)} % par rapport au GPS (sur ${ObdManager.fmt(av.speedSampleKm)} km). ") +
                "Activé, la vitesse, les km et la consommation sont corrigés de cet écart.", s.gpsCorrect) { v ->
            ObdManager.updateSettings { it.copy(gpsCorrect = v) }
        }
        JournalButton()

    }
}

private val SPECIAL_NAMES = mapOf("stats" to "Bilan du trajet", "eco" to "Éco-conduite", "dtc" to "Codes défaut",
    "rec" to "Enregistrement CSV", "shift" to "Témoin de passage")
private val KIND_NAMES = mapOf("val" to "Valeur", "gauge" to "Jauge", "chart" to "Courbe")

/** Modifier le tableau de bord de l'accueil depuis les réglages : modèle, ordre, taille, ajout et retrait des widgets. */
@Composable
private fun DashboardCard(wide: Boolean) {
    val vehicles by ObdManager.vehicles.collectAsStateWithLifecycle()
    val st by ObdManager.settings.collectAsStateWithLifecycle()
    val v = vehicles.firstOrNull { it.id == st.activeVehicleId } ?: vehicles.firstOrNull() ?: return
    val layout = v.layout
    var adding by remember { mutableStateOf(false) }
    fun save(list: List<WidgetCfg>) = ObdManager.updateActive { it.copy(layout = list, preset = "Personnalisé") }
    SectionCard(Icons.Rounded.Dashboard, MaterialTheme.colorScheme.primary, "Tableau de bord · ${v.name}") {
        Text("Partir d'un modèle", fontWeight = FontWeight.SemiBold)
        ChipRow(Presets.ALL.keys.map { it to it }, v.preset) { name -> ObdManager.updateActive { it.copy(layout = Presets.get(name), preset = name) } }
        SwitchRow("État du véhicule", "Carte en haut de l'accueil : santé du moteur, alertes, prochain entretien", st.showHealth) { on ->
            ObdManager.updateSettings { it.copy(showHealth = on) }
        }
        Text("Widgets affichés (dans l'ordre)", fontWeight = FontWeight.SemiBold)
        if (layout.isEmpty()) Text("Aucun widget : l'accueil est vide.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        layout.forEachIndexed { i, w ->
            val def = ObdManager.metric(w.metric)
            val name = def?.name ?: SPECIAL_NAMES[w.kind] ?: w.kind
            val sub = buildString {
                KIND_NAMES[w.kind]?.let { append(it); append(" · ") }
                append(if (w.wide) "large" else "petit")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { save(layout.toMutableList().apply { add(i - 1, removeAt(i)) }) }, enabled = i > 0) {
                    Icon(Icons.Rounded.KeyboardArrowUp, "Monter")
                }
                IconButton(onClick = { save(layout.toMutableList().apply { add(i + 1, removeAt(i)) }) }, enabled = i < layout.size - 1) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "Descendre")
                }
                if (w.kind == "val" || w.kind == "gauge") IconButton(onClick = { save(layout.toMutableList().apply { set(i, w.copy(wide = !w.wide)) }) }) {
                    Icon(Icons.Rounded.SwapHoriz, "Changer la taille")
                }
                IconButton(onClick = { save(layout.toMutableList().apply { removeAt(i) }) }) {
                    Icon(Icons.Rounded.Close, "Retirer", tint = LocalObd.current.bad)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { adding = true }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Ajouter un widget") }
            TextButton(onClick = { ObdManager.updateActive { it.copy(layout = Presets.get(Presets.DEFAULT), preset = Presets.DEFAULT) } }) { Text("Réinitialiser") }
        }
        Text("Chaque véhicule a son propre tableau de bord. Les changements s'appliquent tout de suite à l'accueil.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (adding) AddWidgetSheet(layout, wide, onAdd = { save(layout + it); adding = false }, onDismiss = { adding = false })
}

@Composable
private fun CarCard(s: Settings) {
    val ctx = LocalContext.current
    var overlayOk by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(ctx)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { overlayOk = android.provider.Settings.canDrawOverlays(ctx); onPauseOrDispose { } }
    SectionCard(Icons.Rounded.DirectionsCar, LocalObd.current.speed, "Autoradio") {
        Text("Disposition de l'accueil", fontWeight = FontWeight.SemiBold)
        ChipRow(LayoutMode.entries.map { it to it.label }, s.layoutMode) { m -> ObdManager.updateSettings { it.copy(layoutMode = m) } }
        Text("« Autoradio » remplit tout l'écran avec les widgets. En automatique, il est choisi sur les écrans larges et peu hauts (1024 × 600…).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SwitchRow("Boutons musique", "Volume −, précédent, lecture/pause, suivant et volume + en haut de l'écran et en mode conduite (radio, Spotify, YouTube Music…)", s.mediaButtons) { v ->
            ObdManager.updateSettings { it.copy(mediaButtons = v) }
        }
        SwitchRow("Bouton Spotify", "Bouton vert en haut de l'écran et en mode conduite qui ouvre Spotify (ou sa page dans le Play Store s'il n'est pas installé)", s.spotifyButton) { v ->
            ObdManager.updateSettings { it.copy(spotifyButton = v) }
        }
        SwitchRow("Plein écran", "Masque les barres d'Android (glisser depuis le bord pour les revoir)", s.fullscreen) { v ->
            ObdManager.updateSettings { it.copy(fullscreen = v) }
        }
        SwitchRow("Démarrer avec l'appareil", "Ouvre l'appli et se connecte au boîtier quand l'autoradio s'allume", s.autoStartBoot) { v ->
            ObdManager.updateSettings { it.copy(autoStartBoot = v) }
        }
        if (s.autoStartBoot && !overlayOk) {
            Text("Pour que l'appli puisse s'ouvrir seule, Android demande l'autorisation « Afficher par-dessus les autres applis ».",
                style = MaterialTheme.typography.bodySmall, color = LocalObd.current.warn)
            OutlinedButton(onClick = {
                runCatching {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + ctx.packageName)))
                }
            }) { Text("Donner l'autorisation") }
        }
        SwitchRow("Connexion dès que le boîtier est détecté", "Lance la connexion OBD quand le boîtier mémorisé se connecte en Bluetooth", s.autoConnectBt) { v ->
            ObdManager.updateSettings { it.copy(autoConnectBt = v) }
        }
        Text("Certains autoradios coupent l'alimentation sans vraiment éteindre Android : dans ce cas l'appli se reconnecte au réveil grâce à la reconnexion automatique.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ImportPidsDialog(onDismiss: () -> Unit, onImport: (List<CustomPid>) -> Unit) {
    var text by remember { mutableStateOf("") }
    val res = remember(text) { TorqueImport.parse(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Importer des mesures") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Collez une liste au format CSV de Torque Pro (Name, ShortName, ModeAndPID, Equation, Min, Max, Units, Header), " +
                    "telle qu'on en trouve sur les forums pour chaque modèle. Vérifiez qu'elle correspond bien à votre moteur.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp), label = { Text("Liste CSV") })
                if (text.isNotBlank()) Text("${res.pids.size} mesure(s) reconnue(s)" + if (res.skipped > 0) ", ${res.skipped} ligne(s) ignorée(s)" else "",
                    fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = { TextButton(enabled = res.pids.isNotEmpty(), onClick = { onImport(res.pids) }) { Text("Importer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun BackupCard() {
    val ctx = LocalContext.current
    var msg by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) msg = runCatching {
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(ObdManager.exportBackup().toByteArray()) }
            "Sauvegarde enregistrée."
        }.getOrElse { "Échec de la sauvegarde : ${it.message}" }
    }
    val load = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) msg = runCatching {
            val txt = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            if (ObdManager.importBackup(txt)) "Données restaurées." else "Fichier non reconnu, ou boîtier encore connecté."
        }.getOrElse { "Échec de la restauration : ${it.message}" }
    }
    val merge = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) msg = runCatching {
            val txt = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            if (ObdManager.importBackup(txt, merge = true)) "Données fusionnées." else "Fichier non reconnu, ou boîtier encore connecté."
        }.getOrElse { "Échec de la fusion : ${it.message}" }
    }
    SectionCard(Icons.Rounded.Backup, MaterialTheme.colorScheme.primary, "Sauvegarde et autres appareils") {
        Text("Enregistrez vos véhicules, trajets, pleins et réglages dans un fichier (Drive, mail…), puis restaurez-le sur la tablette ou un nouveau téléphone.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { save.launch("simcardiag-sauvegarde.json") }) { Text("Sauvegarder") }
            OutlinedButton(onClick = { load.launch(arrayOf("application/json", "*/*")) }, enabled = ObdManager.live.value.conn == Conn.IDLE) { Text("Restaurer") }
        }
        Text("Synchroniser téléphone ↔ autoradio", fontWeight = FontWeight.SemiBold)
        Text("Envoyez vos données à l'autre appareil (Bluetooth, Quick Share, mail…). Là-bas, ouvrez le fichier avec SimCarDiag et choisissez « Fusionner » : " +
            "les trajets et pleins des deux appareils sont réunis, les réglages de chacun sont conservés.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                runCatching { shareFile(ctx, ObdManager.syncFile(), "application/json", "Envoyer vers l'autre appareil") }
                    .onFailure { msg = "Envoi impossible : ${it.message}" }
            }) { Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(6.dp)); Text("Envoyer") }
            OutlinedButton(onClick = { merge.launch(arrayOf("application/json", "*/*")) }, enabled = ObdManager.live.value.conn == Conn.IDLE) { Text("Fusionner un fichier") }
        }
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold) }
        ReportButton()
        Text("Astuce : ajoutez le widget « SimCarDiag » sur l'écran d'accueil (appui long › Widgets) pour voir l'autonomie et le prochain entretien.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(items: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (v, n) -> FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(n) }) }
    }
}

@Composable
private fun AppearanceCard(s: Settings) {
    SectionCard(Icons.Rounded.Palette, MaterialTheme.colorScheme.primary, "Apparence") {
        Text("Fond d'écran", fontWeight = FontWeight.SemiBold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Wallpaper.entries.forEach { w ->
                val sel = s.wallpaper == w
                Column(Modifier.width(76.dp).clickable {
                    // les fonds carbone sportifs vont avec un thème sombre et leur couleur
                    ObdManager.updateSettings {
                        when (w) {
                            Wallpaper.CARBON_RED, Wallpaper.CARBON_RED_LIVE -> it.copy(wallpaper = w, accent = Accent.RED, theme = ThemeMode.DARK)
                            Wallpaper.CARBON_GREEN, Wallpaper.CARBON_GREEN_LIVE -> it.copy(wallpaper = w, accent = Accent.NEON, theme = ThemeMode.DARK)
                            else -> it.copy(wallpaper = w)
                        }
                    }
                },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(0.62f).clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.background)
                        .border(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(14.dp))) {
                        WallpaperLayer(w, Modifier.fillMaxSize())
                    }
                    Text(w.label, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                }
            }
        }
        Text("Couleur d'accent", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Accent.entries.forEach { a ->
                val sel = s.accent == a
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color(a.argb))
                    .border(if (sel) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    .clickable { ObdManager.updateSettings { it.copy(accent = a) } }, contentAlignment = Alignment.Center) {
                    if (sel) Icon(Icons.Rounded.Check, a.label, tint = Color(0xFF101318))
                }
            }
        }
        Text("Thème", fontWeight = FontWeight.SemiBold)
        ChipRow(ThemeMode.entries.map { it to it.label }, s.theme) { m -> ObdManager.updateSettings { it.copy(theme = m) } }
        Text("Orientation de l'écran", fontWeight = FontWeight.SemiBold)
        ChipRow(Orientation.entries.map { it to it.label }, s.orientation) { o -> ObdManager.updateSettings { it.copy(orientation = o) } }
        SwitchRow("Garder l'écran allumé", "Pendant que l'appli est ouverte", s.keepScreenOn) { v -> ObdManager.updateSettings { it.copy(keepScreenOn = v) } }
        Text("Unités", fontWeight = FontWeight.SemiBold)
        ChipRow(ConsUnit.entries.map { it to it.label }, s.consUnit) { u -> ObdManager.updateSettings { it.copy(consUnit = u) } }
        ChipRow(TempUnit.entries.map { it to it.label }, s.tempUnit) { u -> ObdManager.updateSettings { it.copy(tempUnit = u) } }
    }
}

@Composable
private fun DrivingCard(s: Settings) {
    SectionCard(Icons.Rounded.DirectionsCar, LocalObd.current.speed, "Conduite") {
        Text(if (s.autoDriveKmh == 0) "Mode conduite automatique : désactivé" else "Mode conduite automatique au-dessus de ${s.autoDriveKmh} km/h",
            fontWeight = FontWeight.SemiBold)
        Slider(value = s.autoDriveKmh.toFloat(), onValueChange = { v -> ObdManager.updateSettings { it.copy(autoDriveKmh = (v / 10).roundToInt() * 10) } },
            valueRange = 0f..130f, steps = 12)
        SwitchRow("Enregistrer le détail CSV automatiquement", "Dès la connexion au boîtier", s.autoRecord) { v -> ObdManager.updateSettings { it.copy(autoRecord = v) } }
        Text("Fréquence de lecture", fontWeight = FontWeight.SemiBold)
        ChipRow(PollRate.entries.map { it to it.label }, s.pollRate) { r -> ObdManager.updateSettings { it.copy(pollRate = r) } }
        Text("« Rapide » rend les jauges plus fluides, « Économie » ménage la batterie du téléphone.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SwitchRow("Valeurs dans la notification", "Vitesse et température visibles écran verrouillé", s.notifValues) { v ->
            ObdManager.updateSettings { it.copy(notifValues = v) }
        }
    }
}

@Composable
private fun DataCard(s: Settings) {
    var price by remember(s.fuelPrice) { mutableStateOf(String.format(java.util.Locale.FRANCE, "%.3f", s.fuelPrice)) }
    var wipe by remember { mutableStateOf(false) }
    SectionCard(Icons.Rounded.Storage, LocalObd.current.energy, "Carburant et données") {
        OutlinedTextField(price, { t ->
            price = t
            t.replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.5..5.0 }?.let { p -> ObdManager.updateSettings { it.copy(fuelPrice = p) } }
        }, label = { Text("Prix du litre (€)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            supportingText = { Text("Sert à estimer le coût de chaque trajet") })
        Text("Conserver les trajets", fontWeight = FontWeight.SemiBold)
        ChipRow(listOf(0 to "Toujours", 365 to "1 an", 90 to "3 mois"), s.keepTripsDays) { d ->
            ObdManager.updateSettings { it.copy(keepTripsDays = d) }; ObdManager.pruneTrips()
        }
        if (s.lastDevice != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Boîtier mémorisé : ${s.lastDevice}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { ObdManager.forgetDevice() }) { Text("Oublier") }
        }
        TextButton(onClick = { wipe = true }, enabled = ObdManager.live.value.conn == com.example.obdanalyzer.core.Conn.IDLE) {
            Icon(Icons.Rounded.DeleteForever, null, tint = LocalObd.current.bad); Spacer(Modifier.width(6.dp))
            Text("Effacer toutes les données", color = LocalObd.current.bad)
        }
    }
    if (wipe) AlertDialog(
        onDismissRequest = { wipe = false },
        title = { Text("Tout effacer ?") },
        text = { Text("Véhicules, trajets, pleins, chronos et réglages seront supprimés de ce téléphone. Pensez à faire une sauvegarde avant.") },
        confirmButton = { TextButton(onClick = { wipe = false; ObdManager.wipeAll() }) { Text("Tout effacer", color = LocalObd.current.bad) } },
        dismissButton = { TextButton(onClick = { wipe = false }) { Text("Annuler") } },
    )
}

@Composable
private fun AboutCard() {
    val ctx = LocalContext.current
    val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
    SectionCard(Icons.Rounded.Info, MaterialTheme.colorScheme.onSurfaceVariant, "À propos") {
        KeyValue("SimCarDiag", "version $version")
        Text("Fonds de carte © contributeurs OpenStreetMap. Les informations affichées viennent des calculateurs via la prise OBD et ne remplacent pas un diagnostic professionnel.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UpdatesCard(s: Settings) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val u by com.example.obdanalyzer.core.Updater.state.collectAsStateWithLifecycle()
    var showToken by remember { mutableStateOf(false) }
    var token by remember(s.githubToken) { mutableStateOf(s.githubToken) }
    val up = com.example.obdanalyzer.core.Updater
    SectionCard(Icons.Rounded.SystemUpdate, MaterialTheme.colorScheme.primary, "Mises à jour") {
        KeyValue("Version installée", "${up.currentName(ctx)} (n° ${up.currentBuild(ctx)})")
        when {
            u.checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp); Text("Recherche sur GitHub…")
            }
            u.progress != null -> {
                Text("Téléchargement de ${u.latest?.title}…", fontWeight = FontWeight.SemiBold)
                LinearProgressIndicator(progress = { u.progress ?: 0f }, Modifier.fillMaxWidth(), trackColor = LocalObd.current.track)
            }
            u.available && u.latest != null -> {
                val r = u.latest!!
                Text("Nouvelle version : ${r.title}", fontWeight = FontWeight.Bold, color = LocalObd.current.ok)
                Text("Publiée le ${r.date} · ${"%.1f".format(r.size / 1_048_576.0)} Mo", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (r.notes.isNotBlank()) Text(r.notes.take(600), style = MaterialTheme.typography.bodySmall)
                Button(onClick = { scope.launch { up.downloadAndInstall(ctx, s.githubToken) } }) {
                    Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(6.dp)); Text("Télécharger et installer")
                }
                Text("Vos véhicules, trajets et réglages sont conservés.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            u.latest != null -> Text("Vous avez la dernière version.", color = LocalObd.current.ok, fontWeight = FontWeight.SemiBold)
        }
        u.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalObd.current.warn) }
        if (!u.checking && u.progress == null) OutlinedButton(onClick = { scope.launch { up.check(ctx, s.githubToken) } }) {
            Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Rechercher une mise à jour")
        }
        if (u.lastCheck > 0) Text("Dernière vérification : ${java.text.SimpleDateFormat("d MMM à HH:mm", java.util.Locale.FRANCE).format(java.util.Date(u.lastCheck))}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SwitchRow("Vérifier au démarrage", "Au plus toutes les 6 heures, si internet est disponible", s.autoUpdateCheck) { v ->
            ObdManager.updateSettings { it.copy(autoUpdateCheck = v) }
        }
        TextButton(onClick = { showToken = !showToken }, contentPadding = PaddingValues(0.dp)) {
            Text(if (showToken) "Masquer l'accès au dépôt privé" else "Dépôt GitHub privé ?")
        }
        if (showToken) {
            Text("Si le dépôt « ${up.REPO} » est privé, créez sur GitHub un jeton à accès fin (Settings › Developer settings › Fine-grained tokens), " +
                "limité à ce dépôt, avec la permission « Contents : Read-only », puis collez-le ici. Il reste sur ce téléphone.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(token, { token = it.trim() }, label = { Text("Jeton GitHub") }, singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { ObdManager.updateSettings { it.copy(githubToken = token) }; scope.launch { up.check(ctx, token) } }) { Text("Enregistrer et vérifier") }
                if (s.githubToken.isNotEmpty()) TextButton(onClick = { token = ""; ObdManager.updateSettings { it.copy(githubToken = "") } }) { Text("Supprimer") }
            }
        }
    }
}
