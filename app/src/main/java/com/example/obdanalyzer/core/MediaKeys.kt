package com.example.obdanalyzer.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.view.KeyEvent
import com.example.obdanalyzer.service.MediaListener

/**
 * Commandes de la musique en cours (Spotify, radio, YouTube Music…) et du volume.
 * 1. Si l'accès « contrôle de la musique » est donné : on pilote directement l'appli qui joue (le plus fiable).
 * 2. Sinon : touches multimédia simulées, que certains autoradios ignorent.
 * Pour le volume, on règle à la fois le son multimédia et le volume « suggéré » du système,
 * que beaucoup d'autoradios relient à leur amplificateur.
 */
object MediaKeys {
    private fun am(ctx: Context) = ctx.getSystemService(AudioManager::class.java)

    /** L'accès « contrôle de la musique » (écoute des notifications) est-il accordé ? */
    fun hasAccess(ctx: Context): Boolean {
        val flat = AndroidSettings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(":").any { it.contains(ctx.packageName) }
    }

    /** Ouvre l'écran Android où l'on autorise l'accès. */
    fun openAccessSettings(ctx: Context) {
        runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private var hinted = false

    /** Appli qui joue (ou la dernière active). */
    private fun controller(ctx: Context): MediaController? = runCatching {
        if (!hasAccess(ctx)) {
            if (!hinted) { hinted = true; ObdManager.toast("Si les boutons musique ne réagissent pas : Réglages › Autoradio › « Autoriser le contrôle de la musique ».") }
            return null
        }
        val msm = ctx.getSystemService(MediaSessionManager::class.java) ?: return null
        val list = msm.getActiveSessions(ComponentName(ctx, MediaListener::class.java))
        list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
    }.getOrNull()

    fun volumeDown(ctx: Context) = volume(ctx, AudioManager.ADJUST_LOWER)
    fun volumeUp(ctx: Context) = volume(ctx, AudioManager.ADJUST_RAISE)

    private fun volume(ctx: Context, dir: Int) = runCatching {
        val a = am(ctx) ?: return@runCatching
        controller(ctx)?.let { c -> runCatching { c.adjustVolume(dir, AudioManager.FLAG_SHOW_UI); return@runCatching } }
        runCatching { a.adjustSuggestedStreamVolume(dir, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI) }
            .onFailure { a.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI) }
    }

    /** Envoie une touche multimédia simulée (solution de secours). */
    private fun key(ctx: Context, code: Int) = runCatching {
        val a = am(ctx) ?: return@runCatching
        val t = SystemClock.uptimeMillis()
        a.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        a.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    fun playPause(ctx: Context) {
        val c = controller(ctx)
        if (c != null) runCatching {
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
        } else key(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    }
    fun previous(ctx: Context) { controller(ctx)?.let { runCatching { it.transportControls.skipToPrevious() } } ?: key(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
    fun next(ctx: Context) { controller(ctx)?.let { runCatching { it.transportControls.skipToNext() } } ?: key(ctx, KeyEvent.KEYCODE_MEDIA_NEXT) }

    const val SPOTIFY = "com.spotify.music"

    /** Ouvre Spotify ; s'il n'est pas installé, ouvre sa page dans le Play Store. Renvoie false si rien n'a pu s'ouvrir. */
    fun openSpotify(ctx: Context): Boolean {
        val launch = ctx.packageManager.getLaunchIntentForPackage(SPOTIFY)
        val i = launch ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$SPOTIFY"))
        return runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrElse {
            runCatching {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://open.spotify.com")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
            }.getOrDefault(false)
        }
    }

    fun isPlaying(ctx: Context): Boolean =
        controller(ctx)?.playbackState?.state == PlaybackState.STATE_PLAYING || am(ctx)?.isMusicActive == true
}
