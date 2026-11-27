package com.example.obdanalyzer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.obdanalyzer.core.Conn
import com.example.obdanalyzer.core.Live
import com.example.obdanalyzer.core.ObdManager
import com.example.obdanalyzer.obd.Dtc
import com.example.obdanalyzer.obd.Severity
import com.example.obdanalyzer.obd.Vin
import java.util.Calendar

@Composable
fun DiagScreen(l: Live, wide: Boolean) {
    var sub by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = sub == 0, onClick = { sub = 0 }, label = { Text("Vue d'ensemble") },
                leadingIcon = { Icon(Icons.Rounded.Dashboard, null, Modifier.size(18.dp)) })
            FilterChip(selected = sub == 1, onClick = { sub = 1 }, label = { Text("Par calculateur") },
                leadingIcon = { Icon(Icons.Rounded.Memory, null, Modifier.size(18.dp)) })
            FilterChip(selected = sub == 2, onClick = { sub = 2 }, label = { Text("Batterie") },
                leadingIcon = { Icon(Icons.Rounded.BatteryFull, null, Modifier.size(18.dp)) })
            FilterChip(selected = sub == 3, onClick = { sub = 3 }, label = { Text("FAP") },
                leadingIcon = { Icon(Icons.Rounded.FilterAlt, null, Modifier.size(18.dp)) })
        }
        Box(Modifier.weight(1f)) {
            when (sub) {
                0 -> DiagOverview(l, wide)
                1 -> EcuScreen(l, wide)
                2 -> BatteryScreen(l, wide)
                else -> DpfScreen(l, wide)
            }
        }
    }
}

@Composable
private fun DiagOverview(l: Live, wide: Boolean) {
    val obd = LocalObd.current
    if (l.conn != Conn.CONNECTED && l.conn != Conn.RECONNECTING) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            EmptyState(Icons.Rounded.BluetoothDisabled, MaterialTheme.colorScheme.primary, "Boîtier non connecté",
                "Connectez l'OBDLink depuis l'accueil pour lire les codes défaut et préparer le contrôle technique.")
        }
        return
    }
    if (l.diagLoading && l.dtcs == null) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            CircularProgressIndicator(Modifier.size(56.dp), strokeWidth = 5.dp)
            Spacer(Modifier.height(16.dp))
            Text("Lecture des calculateurs…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Codes défaut, tests antipollution, contexte du défaut.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val dtcs = l.dtcs
    if (dtcs == null) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            EmptyState(Icons.Rounded.Search, MaterialTheme.colorScheme.primary, "Vérifions votre voiture",
                "On demande au moteur s'il a enregistré des défauts et si les tests antipollution sont prêts pour le contrôle technique. Rien n'est modifié.") {
                Button(onClick = { ObdManager.readDiagnostics() }, Modifier.padding(top = 6.dp)) { Text("Lancer le diagnostic") }
            }
        }
        return
    }

    var confirm by remember { mutableStateOf(false) }
    val summary: @Composable ColumnScope.() -> Unit = {
        if (dtcs.isEmpty()) EmptyState(Icons.Rounded.CheckCircle, obd.ok, "Aucun problème détecté", "Le calculateur moteur n'a enregistré aucun défaut.")
        else EmptyState(Icons.Rounded.Warning, if (dtcs.any { it.severity == Severity.HIGH }) obd.bad else obd.warn,
            "${dtcs.size} défaut${if (dtcs.size > 1) "s" else ""} à faire vérifier",
            if (dtcs.any { it.severity == Severity.HIGH }) "Au moins un défaut est sérieux : roulez calmement et voyez un garage rapidement."
            else "Rien d'urgent : vous pouvez rouler, mais prévoyez un passage au garage.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            FilledTonalButton(onClick = { ObdManager.readDiagnostics() }, enabled = !l.diagLoading) {
                Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(6.dp)); Text(if (l.diagLoading) "Lecture…" else "Relancer")
            }
            if (dtcs.isNotEmpty() || l.pending.isNotEmpty()) TextButton(onClick = { confirm = true }) {
                Icon(Icons.Rounded.Delete, null); Spacer(Modifier.width(6.dp)); Text("Effacer les codes")
            }
        }
        ReportButton(Modifier.align(Alignment.CenterHorizontally))
        EngineWatchCard()
        l.readiness?.let { r -> ReadinessCard(r) }
        l.vin?.let { vin ->
            SectionCard(Icons.Rounded.DirectionsCar, obd.speed, "Identification") {
                KeyValue("VIN", vin)
                KeyValue("Véhicule", Vin.describe(vin, Calendar.getInstance().get(Calendar.YEAR)))
            }
        }
    }
    val details: @Composable ColumnScope.() -> Unit = {
        dtcs.forEach { DtcCard(it) }
        if (l.pending.isNotEmpty()) {
            SectionCard(Icons.Rounded.HourglassTop, obd.warn, "En attente de confirmation") {
                Text("Ces défauts sont apparus une fois mais n'allument pas encore le voyant. À surveiller.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                l.pending.forEach { Text("${it.code} · ${it.title}", style = MaterialTheme.typography.bodyMedium) }
            }
        }
        l.freeze?.let { f ->
            SectionCard(Icons.Rounded.PhotoCamera, obd.engine, "Contexte du défaut") {
                Text("État du moteur au moment où ${f.dtc ?: "le défaut"} est apparu. Utile pour le garagiste.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                f.values.forEach { (k, v) -> KeyValue(k, v) }
            }
        }
    }
    if (wide) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.width(360.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = summary)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = details)
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            summary(); details()
        }
    }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Effacer les codes défaut ?") },
        text = {
            Text("Le voyant moteur s'éteindra, mais le défaut reviendra si la panne n'est pas réparée. " +
                "Les tests antipollution seront remis à zéro : évitez de le faire juste avant un contrôle technique.")
        },
        confirmButton = { TextButton(onClick = { confirm = false; ObdManager.clearDtcs() }) { Text("Effacer", color = obd.bad) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annuler") } },
    )
}

@Composable
private fun DtcCard(d: Dtc) {
    val obd = LocalObd.current
    val c = when (d.severity) { Severity.HIGH -> obd.bad; Severity.MEDIUM -> obd.warn; Severity.LOW -> obd.ok }
    Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(d.code, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text("Gravité ${d.severity.label}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = c,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 3.dp))
            }
            Text(d.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.background).padding(12.dp)) {
                Text("Que faire ?", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(d.advice, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ReadinessCard(r: com.example.obdanalyzer.obd.Readiness) {
    val obd = LocalObd.current
    val ready = r.ready && !r.mil
    SectionCard(Icons.Rounded.FactCheck, if (ready) obd.ok else obd.warn, "Prêt pour le contrôle technique ?") {
        Text(
            when {
                r.mil -> "Non : le voyant moteur est allumé. Il faut d'abord réparer le défaut."
                ready -> "Oui, tous les tests antipollution sont terminés."
                else -> "Pas encore : ${r.pending.size} test(s) en cours. Roulez 50 à 100 km en ville et sur route, puis revérifiez."
            },
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = if (ready) obd.ok else obd.warn
        )
        r.monitors.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (m.complete) Icons.Rounded.CheckCircle else Icons.Rounded.HourglassTop, null,
                    tint = if (m.complete) obd.ok else obd.warn, modifier = Modifier.size(18.dp))
                Text(m.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(if (m.complete) "Prêt" else "En cours", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("Moteur ${if (r.diesel) "diesel" else "essence"} · ${r.dtcCount} code(s) mémorisé(s)",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionCard(icon: androidx.compose.ui.graphics.vector.ImageVector, color: androidx.compose.ui.graphics.Color, title: String,
                content: @Composable ColumnScope.() -> Unit) {
    Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WidgetTitle(icon, color, title)
            content()
        }
    }
}


/** Surveillance moteur : thermostat, turbo, vanne EGR, ralenti, comparés aux habitudes de la voiture. */
@Composable
fun EngineWatchCard() {
    val obd = LocalObd.current
    val notes by com.example.obdanalyzer.core.EngineWatch.notes.collectAsState()
    var relearn by remember { mutableStateOf(false) }
    SectionCard(Icons.Rounded.MonitorHeart, if (notes.isEmpty()) obd.ok else obd.warn, "Surveillance moteur") {
        if (notes.isEmpty()) Text("Rien d'anormal. Pendant que vous roulez, l'appli surveille le thermostat, le turbo, la vanne EGR et la régularité du ralenti, " +
            "en comparant la voiture à ses propres habitudes (apprises sur les premiers trajets).", style = MaterialTheme.typography.bodySmall)
        val fmt = java.text.SimpleDateFormat("d MMM", java.util.Locale.FRANCE)
        notes.forEach { n ->
            Column {
                Text("${n.title} · ${fmt.format(java.util.Date(n.at))}", fontWeight = FontWeight.SemiBold, color = obd.warn)
                Text(n.text, style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (notes.isNotEmpty()) TextButton(onClick = { com.example.obdanalyzer.core.EngineWatch.clear() }) { Text("Effacer") }
            TextButton(onClick = { relearn = true }) { Text("Réapprendre après réparation") }
        }
    }
    if (relearn) AlertDialog(
        onDismissRequest = { relearn = false },
        title = { Text("Tout réapprendre ?") },
        text = { Text("À faire après une réparation (turbo, vanne EGR, thermostat, injecteur…) : l'appli oublie les valeurs habituelles et les réapprend sur les prochains trajets.") },
        confirmButton = { TextButton(onClick = { relearn = false; com.example.obdanalyzer.core.EngineWatch.relearn() }) { Text("Réapprendre") } },
        dismissButton = { TextButton(onClick = { relearn = false }) { Text("Annuler") } },
    )
}
