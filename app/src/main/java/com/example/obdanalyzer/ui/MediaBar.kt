package com.example.obdanalyzer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.obdanalyzer.core.MediaKeys
import kotlinx.coroutines.delay

/** Volume − / précédent / lecture-pause / suivant / volume + : gros boutons faciles à toucher en conduisant. */
@Composable
fun MediaBar(modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val ctx = LocalContext.current
    val st = com.example.obdanalyzer.core.ObdManager.settings.collectAsState().value
    val spotify = st.spotifyButton
    val media = st.mediaButtons
    var playing by remember { mutableStateOf(MediaKeys.isPlaying(ctx)) }
    LaunchedEffect(Unit) { while (true) { playing = MediaKeys.isPlaying(ctx); delay(1000) } }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(if (size < 48.dp) 6.dp else 10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        if (spotify) FilledIconButton(onClick = { MediaKeys.openSpotify(ctx) }, Modifier.size(size),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF1DB954), contentColor = Color.Black)) {
            Icon(Icons.Rounded.MusicNote, "Ouvrir Spotify", Modifier.size(size * 0.5f))
        }
        if (media) {
        FilledTonalIconButton(onClick = { MediaKeys.volumeDown(ctx) }, Modifier.size(size)) {
            Icon(Icons.AutoMirrored.Rounded.VolumeDown, "Baisser le volume", Modifier.size(size * 0.5f))
        }
        FilledTonalIconButton(onClick = { MediaKeys.previous(ctx) }, Modifier.size(size)) {
            Icon(Icons.Rounded.SkipPrevious, "Morceau précédent", Modifier.size(size * 0.5f))
        }
        FilledIconButton(onClick = { MediaKeys.playPause(ctx); playing = !playing }, Modifier.size(size)) {
            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Lecture", Modifier.size(size * 0.55f))
        }
        FilledTonalIconButton(onClick = { MediaKeys.next(ctx) }, Modifier.size(size)) {
            Icon(Icons.Rounded.SkipNext, "Morceau suivant", Modifier.size(size * 0.5f))
        }
        FilledTonalIconButton(onClick = { MediaKeys.volumeUp(ctx) }, Modifier.size(size)) {
            Icon(Icons.AutoMirrored.Rounded.VolumeUp, "Monter le volume", Modifier.size(size * 0.5f))
        }
        }
    }
}

/** Même boutons en colonne, pour la gauche du mode conduite : volume + en haut, volume − en bas, comme un bouton physique. */
@Composable
fun MediaColumn(modifier: Modifier = Modifier, accentColor: Color? = null) {
    val ctx = LocalContext.current
    val st = com.example.obdanalyzer.core.ObdManager.settings.collectAsState().value
    var playing by remember { mutableStateOf(MediaKeys.isPlaying(ctx)) }
    LaunchedEffect(Unit) { while (true) { playing = MediaKeys.isPlaying(ctx); delay(1000) } }
    val items = buildList<Triple<androidx.compose.ui.graphics.vector.ImageVector, String, () -> Unit>> {
        if (st.spotifyButton) add(Triple(Icons.Rounded.MusicNote, "Ouvrir Spotify", { MediaKeys.openSpotify(ctx); Unit }))
        if (st.mediaButtons) {
            add(Triple(Icons.AutoMirrored.Rounded.VolumeUp, "Monter le volume", { MediaKeys.volumeUp(ctx); Unit }))
            add(Triple(Icons.Rounded.SkipPrevious, "Morceau précédent", { MediaKeys.previous(ctx); Unit }))
            add(Triple(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Lecture",
                { MediaKeys.playPause(ctx); playing = !playing }))
            add(Triple(Icons.Rounded.SkipNext, "Morceau suivant", { MediaKeys.next(ctx); Unit }))
            add(Triple(Icons.AutoMirrored.Rounded.VolumeDown, "Baisser le volume", { MediaKeys.volumeDown(ctx); Unit }))
        }
    }
    val accent = accentColor ?: MaterialTheme.colorScheme.primary
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (icon, desc, action) ->
            val main = desc == "Pause" || desc == "Lecture"
            val sp = desc == "Ouvrir Spotify"
            Surface(onClick = action, shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                color = when { sp -> Color(0xFF1DB954); main -> accent; else -> Color.White.copy(alpha = 0.07f) },
                border = if (main || sp) null else androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                modifier = Modifier.weight(1f).fillMaxWidth()) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, desc, Modifier.size(30.dp), tint = when { sp -> Color.Black; main -> if (accent.luminance() > 0.5f) Color.Black else Color.White; else -> Color.White })
                }
            }
        }
    }
}
