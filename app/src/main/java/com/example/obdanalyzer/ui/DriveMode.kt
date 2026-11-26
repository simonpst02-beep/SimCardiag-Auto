package com.example.obdanalyzer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.obdanalyzer.core.Conn
import com.example.obdanalyzer.core.Level
import com.example.obdanalyzer.core.Live
import com.example.obdanalyzer.core.ObdManager
import com.example.obdanalyzer.core.Settings
import com.example.obdanalyzer.core.Vehicle
import com.example.obdanalyzer.core.displayValue

/**
 * Écran simplifié pour la conduite : 4 très grandes valeurs, aucune manipulation nécessaire.
 * La taille des chiffres s'adapte à la place disponible, pour rester lisibles en portrait comme en paysage.
 */
@Composable
fun DriveMode(l: Live, v: Vehicle, s: Settings, onExit: () -> Unit) {
    BackHandler(onBack = onExit)
    val defs = s.driveMetrics.mapNotNull { ObdManager.metric(it) }.take(8)
    val alert = l.alerts.firstOrNull { it.level != Level.OK }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().displayCutoutPadding()) {
        val landscape = maxWidth > maxHeight
        val compact = maxHeight < 480.dp          // téléphone couché : peu de hauteur
        // Autoradio (écran large) : mode conduite sport
        if (landscape && maxWidth >= 700.dp) { SportDrive(l, v, s, onExit); return@BoxWithConstraints }
        val gap = if (compact) 8.dp else 12.dp
        Column(Modifier.fillMaxSize().padding(if (compact) 10.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (l.conn == Conn.RECONNECTING) "Reconnexion…" else v.name, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1)
                // en paysage, les boutons musique tiennent sur la ligne du haut ; en portrait, ils ont leur propre ligne
                if (landscape && (s.mediaButtons || s.spotifyButton)) { MediaBar(size = if (compact) 44.dp else 56.dp); Spacer(Modifier.width(12.dp)) }
                Text(clockText(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                if (compact) FilledTonalIconButton(onClick = onExit) { Icon(Icons.Rounded.Close, "Quitter") }
                else FilledTonalButton(onClick = onExit, modifier = Modifier.height(52.dp)) {
                    Icon(Icons.Rounded.Close, null); Spacer(Modifier.width(6.dp)); Text("Quitter")
                }
            }
            if (!landscape && (s.mediaButtons || s.spotifyButton)) MediaBar(Modifier.fillMaxWidth(), size = 52.dp)
            if (s.shiftLight && l.values.containsKey("rpm"))
                ShiftLight(l.values["rpm"], shiftRpmFor(s, v), Modifier.fillMaxWidth(), height = if (compact) 14.dp else 26.dp, leds = 12)
            if (alert != null) {
                val c = levelColor(alert.level)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(if (compact) 14.dp else 20.dp)).background(c)
                    .padding(horizontal = 16.dp, vertical = if (compact) 6.dp else 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.Warning, null, tint = Color.Black, modifier = Modifier.size(if (compact) 22.dp else 32.dp))
                    Text(alert.text, color = Color.Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
                        maxLines = if (compact) 1 else 3, overflow = TextOverflow.Ellipsis)
                }
            }
            // Jusqu'à 8 mesures ; les chiffres rapetissent tout seuls quand il y en a plus.
            // Téléphone couché : 1 ligne jusqu'à 4, puis 2 lignes. Tablette / autoradio : 2, 3 ou 4 colonnes. Portrait : 1 colonne jusqu'à 4, puis 2.
            val n = defs.size.coerceAtLeast(1)
            val cols = when {
                landscape && compact -> if (n <= 4) n else (n + 1) / 2
                landscape -> when { n <= 4 -> 2; n <= 6 -> 3; else -> 4 }
                else -> if (n <= 4) 1 else 2
            }
            defs.chunked(cols).forEach { row ->
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { d ->
                        val raw = l.values[d.key]
                        val (value, unitShown) = displayValue(d.key, raw, if (d.key == "cons") l.consUnit else d.unit, s)
                        val (_, lvl) = hint(d.key, raw, l, v)
                        val c = when (lvl) { Level.BAD -> LocalObd.current.bad; Level.WARN -> LocalObd.current.warn; else -> MaterialTheme.colorScheme.onSurface }
                        DriveCell(d.name, value?.let { ObdManager.fmt(it) } ?: "—", unitShown, c, Modifier.weight(1f).fillMaxHeight())
                    }
                    if (row.size < cols) Spacer(Modifier.weight((cols - row.size).toFloat()))
                }
            }
        }
    }
}

/** Une case : nom, valeur, unité. La valeur prend la plus grande taille qui tient dans la case. */
@Composable
private fun DriveCell(name: String, text: String, unit: String, color: Color, modifier: Modifier) {
    BoxWithConstraints(
        modifier.clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        val density = LocalDensity.current
        val small = maxHeight < 140.dp
        val labelSize = if (small) 12.sp else 16.sp
        val labelH = with(density) { (labelSize * 1.35f).toDp() } * 2       // nom + unité
        val byHeight = (maxHeight - labelH) * 0.82f
        val byWidth = maxWidth / (maxOf(text.length, 2) * 0.62f)
        val valueDp = minOf(byHeight, byWidth).coerceIn(18.dp, 160.dp)
        val valueSize = with(density) { valueDp.toSp() }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(name, fontSize = labelSize, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text, fontSize = valueSize, lineHeight = valueSize, fontWeight = FontWeight.Black, color = color, maxLines = 1, softWrap = false)
            Text(unit, fontSize = labelSize, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
