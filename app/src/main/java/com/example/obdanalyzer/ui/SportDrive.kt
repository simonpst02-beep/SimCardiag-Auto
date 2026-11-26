package com.example.obdanalyzer.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.obdanalyzer.core.Conn
import com.example.obdanalyzer.core.Fuel
import com.example.obdanalyzer.core.Level
import com.example.obdanalyzer.core.Live
import com.example.obdanalyzer.core.ObdManager
import com.example.obdanalyzer.core.Settings
import com.example.obdanalyzer.core.Vehicle
import com.example.obdanalyzer.core.displayValue
import kotlin.math.cos
import kotlin.math.sin

/**
 * Mode conduite « sport » pour l'autoradio :
 * - à gauche, les boutons musique en colonne ;
 * - au centre, un compte-tours en arc (vert → orange → rouge, il clignote au régime de passage) avec la vitesse en grand ;
 * - à droite, les autres mesures en tuiles de style course.
 */
@Composable
fun SportDrive(l: Live, v: Vehicle, s: Settings, onExit: () -> Unit) {
    val obd = LocalObd.current
    val accent = MaterialTheme.colorScheme.primary
    val alert = l.alerts.firstOrNull { it.level != Level.OK }
    val rpm = l.values["rpm"]
    val shift = shiftRpmFor(s, v)
    val maxRpm = if (v.fuel == Fuel.DIESEL) 5000 else 7000
    val others = s.driveMetrics.filter { it != "speed" && it != "rpm" }.mapNotNull { ObdManager.metric(it) }.take(6)
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF161A22), Color(0xFF07080B)), radius = 1400f))
        .systemBarsPadding().displayCutoutPadding()) {
        Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (s.mediaButtons || s.spotifyButton) MediaColumn(Modifier.width(78.dp).fillMaxHeight())
            Column(Modifier.weight(1.35f).fillMaxHeight()) {
                // en-tête : voiture, état, quitter
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (l.conn == Conn.CONNECTED) obd.ok else obd.warn))
                    Spacer(Modifier.width(8.dp))
                    Text((if (l.conn == Conn.RECONNECTING) "Reconnexion…" else v.name).uppercase(), color = Color.White.copy(alpha = 0.7f),
                        letterSpacing = 3.sp, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onExit, border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)), contentPadding = PaddingValues(horizontal = 14.dp)) {
                        Icon(Icons.Rounded.Close, null, tint = Color.White); Spacer(Modifier.width(4.dp)); Text("Quitter", color = Color.White)
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Tacho(rpm, l.values["speed"], s, shift, maxRpm, accent)
                    if (alert != null) {
                        val c = levelColor(alert.level)
                        Row(Modifier.align(Alignment.BottomCenter).clip(RoundedCornerShape(14.dp)).background(c).padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.Warning, null, tint = Color.Black)
                            Text(alert.text, color = Color.Black, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (others.isNotEmpty()) Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val rows = others.chunked(if (others.size > 3) 2 else 1)
                rows.forEach { row ->
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { d ->
                            val raw = l.values[d.key]
                            val (value, unit) = displayValue(d.key, raw, if (d.key == "cons") l.consUnit else d.unit, s)
                            val (_, lvl) = hint(d.key, raw, l, v)
                            SportTile(d.name, value?.let { ObdManager.fmt(it) } ?: "—", unit, lvl, accent, Modifier.weight(1f).fillMaxHeight())
                        }
                        if (row.size == 1 && others.size > 3) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** Compte-tours en arc de 240°, graduations tous les 1 000 tr/min, vitesse au centre. */
@Composable
private fun Tacho(rpm: Double?, speed: Double?, s: Settings, shift: Int, maxRpm: Int, accent: Color) {
    val target = ((rpm ?: 0.0) / maxRpm).toFloat().coerceIn(0f, 1f)
    val frac by animateFloatAsState(target, tween(180), label = "rpm")
    val over = (rpm ?: 0.0) >= shift
    val blink by rememberInfiniteTransition(label = "shift").animateFloat(1f, 0.25f, infiniteRepeatable(tween(150), RepeatMode.Reverse), label = "b")
    val shiftFrac = (shift.toFloat() / maxRpm).coerceIn(0f, 1f)
    BoxWithConstraints(Modifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight * 1.18f)
        val density = LocalDensity.current
        Canvas(Modifier.size(side, side * 0.86f)) {
            val stroke = size.width * 0.075f
            val r = size.width / 2 - stroke
            val c = Offset(size.width / 2, size.width / 2 + stroke * 0.2f)
            val tl = Offset(c.x - r, c.y - r)
            val sz = Size(r * 2, r * 2)
            val start = 150f; val sweep = 240f
            // piste
            drawArc(Color.White.copy(alpha = 0.06f), start, sweep, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
            // zone rouge après le régime de passage
            drawArc(Color(0xFFFF2D2D).copy(alpha = 0.18f), start + sweep * shiftFrac, sweep * (1 - shiftFrac), false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Butt))
            // aiguille en arc : dégradé vert → orange → rouge
            if (frac > 0.002f) {
                val colors = listOf(Color(0xFF2EE59D), Color(0xFFFFC233), Color(0xFFFF2D2D))
                val col = when { over -> Color(0xFFFF2D2D).copy(alpha = blink); frac < shiftFrac * 0.6f -> colors[0]; frac < shiftFrac -> colors[1]; else -> colors[2] }
                drawArc(col.copy(alpha = col.alpha * 0.25f), start, sweep * frac, false, Offset(tl.x - stroke * 0.3f, tl.y - stroke * 0.3f),
                    Size(sz.width + stroke * 0.6f, sz.height + stroke * 0.6f), style = Stroke(stroke * 1.8f, cap = StrokeCap.Round))
                drawArc(col, start, sweep * frac, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            // graduations
            for (k in 0..maxRpm / 1000) {
                val a = Math.toRadians((start + sweep * (k * 1000f / maxRpm)).toDouble())
                val r1 = r - stroke * 0.9f; val r2 = r - stroke * 1.6f
                drawLine(Color.White.copy(alpha = if (k * 1000 >= shift) 0.9f else 0.5f),
                    Offset(c.x + (r1 * cos(a)).toFloat(), c.y + (r1 * sin(a)).toFloat()),
                    Offset(c.x + (r2 * cos(a)).toFloat(), c.y + (r2 * sin(a)).toFloat()), strokeWidth = 4f, cap = StrokeCap.Round)
            }
        }
        // vitesse en très grand au centre, chiffres italiques façon course
        val (sp, unit) = displayValue("speed", speed, "km/h", s)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val big = with(density) { (side * 0.30f).toSp() }
            Text(sp?.let { "%.0f".format(it) } ?: "—", fontSize = big, lineHeight = big, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, color = Color.White)
            Text(unit.uppercase(), color = Color.White.copy(alpha = 0.6f), letterSpacing = 4.sp, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.clip(RoundedCornerShape(50)).border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom) {
                Text(rpm?.let { "%.0f".format(it) } ?: "—", fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, fontSize = 26.sp, color = Color.White)
                Spacer(Modifier.width(6.dp))
                Text("TR/MIN", fontSize = 12.sp, letterSpacing = 2.sp, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }
}

/** Tuile de mesure : barre de couleur à gauche (vert / orange / rouge), nom en petites capitales, valeur en italique. */
@Composable
private fun SportTile(name: String, value: String, unit: String, lvl: Level?, accent: Color, modifier: Modifier) {
    val obd = LocalObd.current
    val bar = when (lvl) { Level.BAD -> obd.bad; Level.WARN -> obd.warn; else -> accent }
    BoxWithConstraints(modifier.clip(RoundedCornerShape(18.dp))
        .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.02f))))
        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))) {
        val density = LocalDensity.current
        Box(Modifier.width(5.dp).fillMaxHeight().background(bar))
        Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.Center) {
            Text(name.uppercase(), fontSize = 12.sp, letterSpacing = 1.5.sp, color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Bold)
            val byH = maxHeight * 0.42f
            val byW = maxWidth / (maxOf(value.length, 2) * 0.66f)
            val fs = with(density) { minOf(byH, byW).coerceIn(16.dp, 72.dp).toSp() }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, fontSize = fs, lineHeight = fs, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic,
                    color = if (lvl == Level.BAD || lvl == Level.WARN) bar else Color.White, maxLines = 1, softWrap = false)
                Spacer(Modifier.width(4.dp))
                Text(unit, fontSize = 13.sp, color = Color.White.copy(alpha = 0.55f), maxLines = 1, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
    }
}
