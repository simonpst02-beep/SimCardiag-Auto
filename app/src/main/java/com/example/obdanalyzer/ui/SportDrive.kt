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

/** Style du mode conduite, tiré du thème, de la police et de la couleur choisis dans les réglages. */
class DriveStyle(val bgCenter: Color, val bgEdge: Color, val accent: Color, val low: Color, val mid: Color, val high: Color,
                 val family: androidx.compose.ui.text.font.FontFamily, val italic: Boolean, val weight: FontWeight,
                 val carbon: Boolean = false, val text: Color = Color.White)

fun driveStyle(s: Settings): DriveStyle {
    val t = s.driveTheme
    val (fam, it, w) = when (s.driveFont) {
        com.example.obdanalyzer.core.DriveFont.SPORT -> Triple(androidx.compose.ui.text.font.FontFamily.Default, true, FontWeight.Black)
        com.example.obdanalyzer.core.DriveFont.MODERN -> Triple(androidx.compose.ui.text.font.FontFamily.SansSerif, false, FontWeight.Light)
        com.example.obdanalyzer.core.DriveFont.DIGITAL -> Triple(androidx.compose.ui.text.font.FontFamily.Monospace, false, FontWeight.Bold)
        com.example.obdanalyzer.core.DriveFont.CLASSIC -> Triple(androidx.compose.ui.text.font.FontFamily.Serif, false, FontWeight.Bold)
    }
    return DriveStyle(Color(t.bgCenter), Color(t.bgEdge), Color(if (s.driveAccent != 0L) s.driveAccent else t.accent),
        Color(t.low), Color(t.mid), Color(t.high), fam, it, w, t.carbon, Color(t.text))
}

/**
 * Mode conduite « sport » pour l'autoradio :
 * - à gauche, les boutons musique en colonne ;
 * - au centre, un compte-tours en arc (vert → orange → rouge, il clignote au régime de passage) avec la vitesse en grand ;
 * - à droite, les autres mesures en tuiles de style course.
 */
@Composable
fun SportDrive(l: Live, v: Vehicle, s: Settings, onExit: () -> Unit) {
    val obd = LocalObd.current
    val st = driveStyle(s)
    val accent = st.accent
    val alert = l.alerts.firstOrNull { it.level != Level.OK }
    val rpm = l.values["rpm"]
    val cool = l.values["cool"]
    val cold = s.coldAdvice && cool != null && cool < 60
    // moteur froid : on passe les vitesses plus tôt
    val shift = shiftRpmFor(s, v) - if (cold) 500 else 0
    val maxRpm = if (v.fuel == Fuel.DIESEL) 5000 else 7000
    val others = s.driveMetrics.filter { it != "speed" && it != "rpm" }.mapNotNull { ObdManager.metric(it) }.take(6)
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(st.bgCenter, st.bgEdge), radius = 1400f))
        .systemBarsPadding().displayCutoutPadding()) {
        DriveBackground(s.driveBg, st, Modifier.fillMaxSize())
        Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (s.mediaButtons || s.spotifyButton) MediaColumn(Modifier.width(78.dp).fillMaxHeight(), accent)
            Column(Modifier.weight(1.35f).fillMaxHeight()) {
                // en-tête : voiture, état, quitter
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (l.conn == Conn.CONNECTED) obd.ok else obd.warn))
                    Spacer(Modifier.width(8.dp))
                    Text((if (l.conn == Conn.RECONNECTING) "Reconnexion…" else v.name).uppercase(), color = Color.White.copy(alpha = 0.7f),
                        letterSpacing = 3.sp, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    Text(clockText(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp, fontFamily = st.family)
                    Spacer(Modifier.width(14.dp))
                    OutlinedButton(onClick = onExit, border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)), contentPadding = PaddingValues(horizontal = 14.dp)) {
                        Icon(Icons.Rounded.Close, null, tint = Color.White); Spacer(Modifier.width(4.dp)); Text("Quitter", color = Color.White)
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Tacho(rpm, l.values["speed"], s, shift, maxRpm, st)
                    // rapport engagé, en haut à droite du compte-tours
                    l.values["gear"]?.let { g ->
                        Box(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 4.dp).size(64.dp).clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.06f)).border(2.dp, accent, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                            Text(g.toInt().toString(), color = st.text, fontSize = 38.sp, fontWeight = FontWeight.Black, fontFamily = st.family,
                                fontStyle = if (st.italic) FontStyle.Italic else FontStyle.Normal)
                        }
                    }
                    // consommation instantanée comparée à la moyenne
                    ConsBar(l, v, st, Modifier.align(Alignment.BottomCenter).padding(bottom = if (alert != null || cold) 52.dp else 6.dp).fillMaxWidth(0.62f))
                    if (cold && alert == null) {
                        Row(Modifier.align(Alignment.BottomCenter).clip(RoundedCornerShape(14.dp)).background(Color(0xFF2EC7FF)).padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Moteur froid (${cool!!.toInt()} °C) : évitez les hauts régimes", color = Color.Black, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
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
                            SportTile(d.name, value?.let { ObdManager.fmt(it) } ?: "—", unit, lvl, st, Modifier.weight(1f).fillMaxHeight())
                        }
                        if (row.size == 1 && others.size > 3) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        // la nuit, tout l'écran est assombri pour ne pas éblouir
        if (s.nightDim && isNight()) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
    }
}

/** Compte-tours en arc de 240°, graduations tous les 1 000 tr/min, vitesse au centre. */
@Composable
private fun Tacho(rpm: Double?, speed: Double?, s: Settings, shift: Int, maxRpm: Int, st: DriveStyle) {
    val accent = st.accent
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
            drawArc(st.high.copy(alpha = 0.18f), start + sweep * shiftFrac, sweep * (1 - shiftFrac), false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Butt))
            // aiguille en arc : dégradé vert → orange → rouge
            if (frac > 0.002f) {
                val colors = listOf(st.low, st.mid, st.high)
                val col = when { over -> st.high.copy(alpha = blink); frac < shiftFrac * 0.6f -> colors[0]; frac < shiftFrac -> colors[1]; else -> colors[2] }
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
            Text(sp?.let { "%.0f".format(it) } ?: "—", fontSize = big, lineHeight = big, fontWeight = st.weight, fontFamily = st.family,
                fontStyle = if (st.italic) FontStyle.Italic else FontStyle.Normal, color = st.text)
            Text(unit.uppercase(), color = Color.White.copy(alpha = 0.6f), letterSpacing = 4.sp, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.clip(RoundedCornerShape(50)).border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom) {
                Text(rpm?.let { "%.0f".format(it) } ?: "—", fontWeight = FontWeight.Black, fontFamily = st.family,
                    fontStyle = if (st.italic) FontStyle.Italic else FontStyle.Normal, fontSize = 26.sp, color = st.text)
                Spacer(Modifier.width(6.dp))
                Text("TR/MIN", fontSize = 12.sp, letterSpacing = 2.sp, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }
}

/** Tuile de mesure : barre de couleur à gauche (vert / orange / rouge), nom en petites capitales, valeur en italique. */
@Composable
private fun SportTile(name: String, value: String, unit: String, lvl: Level?, st: DriveStyle, modifier: Modifier) {
    val accent = st.accent
    val obd = LocalObd.current
    val bar = when (lvl) { Level.BAD -> obd.bad; Level.WARN -> obd.warn; else -> accent }
    BoxWithConstraints(modifier.clip(RoundedCornerShape(18.dp))
        .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.02f))))
        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))) {
        val density = LocalDensity.current
        val tileH = maxHeight; val tileW = maxWidth
        Box(Modifier.width(5.dp).fillMaxHeight().background(bar))
        Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.Center) {
            Text(name.uppercase(), fontSize = 12.sp, letterSpacing = 1.5.sp, color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Bold)
            val byH = tileH * 0.42f
            val byW = tileW / (maxOf(value.length, 2) * 0.66f)
            val fs = with(density) { minOf(byH, byW).coerceIn(16.dp, 72.dp).toSp() }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, fontSize = fs, lineHeight = fs, fontWeight = if (st.weight == FontWeight.Light) FontWeight.Normal else FontWeight.Black,
                    fontFamily = st.family, fontStyle = if (st.italic) FontStyle.Italic else FontStyle.Normal,
                    color = if (lvl == Level.BAD || lvl == Level.WARN) bar else st.text, maxLines = 1, softWrap = false)
                Spacer(Modifier.width(4.dp))
                Text(unit, fontSize = 13.sp, color = Color.White.copy(alpha = 0.55f), maxLines = 1, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
    }
}


/** Tissage fibre de carbone (thèmes « Carbone »). */
@Composable
fun CarbonWeave(modifier: Modifier) {
    Canvas(modifier) {
        val c = 10f
        val a = Color.White.copy(alpha = 0.06f); val b = Color.White.copy(alpha = 0.02f)
        var y = 0f; var row = 0
        while (y < size.height) {
            var x = 0f; var col = 0
            while (x < size.width) {
                val hz = (row + col) % 2 == 0
                drawRect(if (hz) a else b, Offset(x, y), Size(c, c * 0.9f))
                drawRect(if (hz) b else a, Offset(x + c, y), Size(c * 0.9f, c))
                x += 2 * c; col++
            }
            y += c; row++
        }
        drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)), radius = size.maxDimension * 0.7f))
    }
}


/** Dessine le fond choisi ; « Du thème » = carbone pour les thèmes Carbone, halo pour les autres. */
@Composable
fun DriveBackground(bg: com.example.obdanalyzer.core.DriveBg, st: DriveStyle, modifier: Modifier) {
    val kind = if (bg == com.example.obdanalyzer.core.DriveBg.AUTO)
        (if (st.carbon) com.example.obdanalyzer.core.DriveBg.CARBON else com.example.obdanalyzer.core.DriveBg.GLOW) else bg
    if (kind == com.example.obdanalyzer.core.DriveBg.CARBON) { CarbonWeave(modifier); return }
    val a = st.accent
    Canvas(modifier) {
        val W = size.width; val H = size.height
        when (kind) {
            com.example.obdanalyzer.core.DriveBg.GLOW -> {
                drawCircle(Brush.radialGradient(listOf(a.copy(alpha = 0.22f), Color.Transparent), Offset(W * 0.42f, H * 0.55f), H * 0.75f), H * 0.75f, Offset(W * 0.42f, H * 0.55f))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, a.copy(alpha = 0.10f)), startY = H * 0.7f))
            }
            com.example.obdanalyzer.core.DriveBg.SPEED -> {
                for (i in 0 until 26) {
                    val y = H * ((i * 37 % 100) / 100f); val x = W * ((i * 53 % 100) / 100f)
                    val len = W * (0.12f + 0.10f * (i % 4))
                    drawLine(a.copy(alpha = 0.05f + 0.05f * (i % 3)), Offset(x, y), Offset(x + len, y - len * 0.18f), 2f + (i % 3), StrokeCap.Round)
                }
            }
            com.example.obdanalyzer.core.DriveBg.HEX -> {
                val r = 26f; val dx = r * 1.732f; val dy = r * 1.5f
                var row = 0; var y = 0f
                while (y < H + r) {
                    var x = if (row % 2 == 0) 0f else dx / 2
                    while (x < W + r) {
                        val path = androidx.compose.ui.graphics.Path()
                        for (k in 0..5) {
                            val ang = Math.toRadians(60.0 * k + 30)
                            val px = x + (r * cos(ang)).toFloat(); val py = y + (r * sin(ang)).toFloat()
                            if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
                        }
                        path.close()
                        drawPath(path, Color.White.copy(alpha = 0.05f), style = Stroke(1.5f))
                        x += dx
                    }
                    y += dy; row++
                }
                drawRect(Brush.radialGradient(listOf(a.copy(alpha = 0.10f), Color.Black.copy(alpha = 0.45f)), Offset(W / 2, H / 2), W * 0.7f))
            }
            com.example.obdanalyzer.core.DriveBg.GRID -> {
                val g = 40f
                var x = 0f; while (x < W) { drawLine(a.copy(alpha = 0.06f), Offset(x, 0f), Offset(x, H), 1f); x += g }
                var y = 0f; while (y < H) { drawLine(a.copy(alpha = 0.06f), Offset(0f, y), Offset(W, y), 1f); y += g }
                drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)), Offset(W / 2, H / 2), W * 0.65f))
            }
            com.example.obdanalyzer.core.DriveBg.BRUSHED -> {
                var y = 0f; var i = 0
                while (y < H) { drawLine(Color.White.copy(alpha = if (i % 3 == 0) 0.035f else 0.015f), Offset(0f, y), Offset(W, y), 1f); y += 2f; i++ }
                drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.06f), Color.Transparent, Color.White.copy(alpha = 0.04f)), Offset(0f, 0f), Offset(W, H)))
            }
            com.example.obdanalyzer.core.DriveBg.STRIPES -> {
                // deux bandes de course en biais derrière le compte-tours
                fun band(x0: Float, w: Float, al: Float) = drawPath(androidx.compose.ui.graphics.Path().apply {
                    moveTo(W * x0, 0f); lineTo(W * (x0 + w), 0f); lineTo(W * (x0 + w - 0.25f), H); lineTo(W * (x0 - 0.25f), H); close()
                }, a.copy(alpha = al))
                band(0.42f, 0.06f, 0.10f); band(0.50f, 0.025f, 0.08f)
            }
            com.example.obdanalyzer.core.DriveBg.CHECKER -> {
                // damier en fondu dans le coin bas droit
                val c = 22f
                var y = H * 0.55f; var row = 0
                while (y < H) {
                    var x = W * 0.55f; var col = 0
                    while (x < W) {
                        if ((row + col) % 2 == 0) {
                            val fade = (((x - W * 0.55f) / (W * 0.45f)) * ((y - H * 0.55f) / (H * 0.45f))).coerceIn(0f, 1f)
                            drawRect(Color.White.copy(alpha = 0.07f * fade), Offset(x, y), Size(c, c))
                        }
                        x += c; col++
                    }
                    y += c; row++
                }
            }
            else -> Unit
        }
    }
}


/** Heure actuelle, rafraîchie toutes les 10 s. */
@Composable
fun clockText(): String {
    val t by androidx.compose.runtime.produceState(java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE).format(java.util.Date())) {
        while (true) { value = java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE).format(java.util.Date()); kotlinx.coroutines.delay(10_000) }
    }
    return t
}

fun isNight(): Boolean { val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY); return h >= 20 || h < 7 }

/** Barre de consommation : verte sous la moyenne du véhicule, rouge au-dessus. */
@Composable
private fun ConsBar(l: Live, v: Vehicle, st: DriveStyle, modifier: Modifier) {
    val cons = l.values["cons"] ?: return
    if (l.consUnit != "L/100km") return
    val avg = l.stats.avgConsumption ?: v.lastAvgCons ?: return
    val ratio = (cons / (avg * 2)).toFloat().coerceIn(0f, 1f)
    val c = if (cons <= avg) Color(0xFF2EE59D) else if (cons <= avg * 1.5) Color(0xFFFFC233) else Color(0xFFFF2D2D)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f))) {
            Box(Modifier.fillMaxWidth(ratio).fillMaxHeight().clip(RoundedCornerShape(50)).background(c))
            Box(Modifier.fillMaxWidth(0.5f).fillMaxHeight()) { Box(Modifier.align(Alignment.CenterEnd).width(2.dp).fillMaxHeight().background(Color.White)) }
        }
        Text("${ObdManager.fmt(cons)} L/100 · moyenne ${ObdManager.fmt(avg)}", fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
    }
}
