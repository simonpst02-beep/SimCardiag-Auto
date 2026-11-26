package com.example.obdanalyzer.core

import android.content.Context
import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Zones de danger, comme Coyote ou Waze, dans le cadre autorisé en France : l'appli annonce une zone
 * (300 m en ville, 2 km sur route, 4 km sur autoroute) sans jamais indiquer l'emplacement exact du radar.
 * Source : liste officielle des radars fixes publiée sur data.gouv.fr, téléchargée une fois par mois.
 */
object DangerZones {

    private const val DATASET = "https://www.data.gouv.fr/api/1/datasets/6712583387af110196942793/"
    private class Zone(val lat: Double, val lon: Double, val halfM: Float)

    @Volatile private var zones: List<Zone> = emptyList()
    private var inside: Zone? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "zones.csv")

    fun count() = zones.size

    /** Dernier problème rencontré (téléchargement, format), affiché dans les réglages. */
    @Volatile var lastError: String? = null; private set

    /** Nombre de zones à moins de [km] km (sans jamais donner leur position). */
    fun around(loc: Location, km: Double): Int {
        val d = FloatArray(1)
        return zones.count { z ->
            kotlin.math.abs(z.lat - loc.latitude) < km / 100 && kotlin.math.abs(z.lon - loc.longitude) < km / 70 &&
                run { Location.distanceBetween(loc.latitude, loc.longitude, z.lat, z.lon, d); d[0] <= km * 1000 }
        }
    }

    /** Charge la liste (téléchargement si absente ou vieille de plus de 30 jours). Renvoie le nombre de zones. */
    suspend fun load(ctx: Context, force: Boolean = false): Int = withContext(Dispatchers.IO) {
        val f = file(ctx)
        lastError = null
        if (force || !f.exists() || System.currentTimeMillis() - f.lastModified() > 30L * 86_400_000) {
            runCatching { download(f) }.onFailure { lastError = "Téléchargement impossible (${it.message ?: "pas d'internet"})" }
        }
        if (f.exists()) {
            // le fichier officiel peut être en UTF-8 ou en Latin-1
            val bytes = f.readBytes()
            val text = String(bytes, Charsets.UTF_8).let { if (it.contains('\uFFFD')) String(bytes, Charsets.ISO_8859_1) else it }.removePrefix("\uFEFF")
            zones = parse(text)
            if (zones.isEmpty()) lastError = "Fichier téléchargé mais illisible (format changé ?)"
        } else if (lastError == null) lastError = "Liste pas encore téléchargée"
        zones.size
    }

    private fun get(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 30_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "SimCarDiag")
        }
        return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun download(f: File) {
        val res = JSONObject(get(DATASET)).getJSONArray("resources")
        val csv = (0 until res.length()).map { res.getJSONObject(it) }
            .filter { it.optString("format").equals("csv", true) }
            .maxByOrNull { it.optString("last_modified") } ?: return
        val text = get(csv.getString("url"))
        if (text.length > 1000) f.writeText(text)
    }

    /** Lecture tolérante du CSV : séparateur « ; » ou « , », colonnes repérées par leur nom. */
    private fun parse(text: String): List<Zone> {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2) return emptyList()
        val sep = if (lines[0].count { it == ';' } > lines[0].count { it == ',' }) ';' else ','
        fun split(l: String) = l.split(sep).map { it.trim().trim('"') }
        val head = split(lines[0]).map { it.lowercase() }
        // colonnes repérées en priorité par leur nom exact (« longueur_troncon » contient aussi « lon » !)
        fun col(exact: List<String>, part: List<String>) = head.indexOfFirst { it in exact }.takeIf { it >= 0 }
            ?: head.indexOfFirst { h -> part.any { p -> h.startsWith(p) } }
        val iLat = col(listOf("latitude", "lat", "y"), listOf("lat"))
        val iLon = col(listOf("longitude", "lon", "lng", "long", "x"), listOf("longit", "lng"))
        if (iLat < 0 || iLon < 0) return emptyList()
        val iRoute = head.indexOfFirst { "route" in it || "voie" in it }
        val iVma = head.indexOfFirst { "vma" in it || ("vitesse" in it && "lourd" !in it) }.takeIf { it >= 0 }
            ?: head.indexOfFirst { "vitesse" in it || "kmh" in it }
        fun num(s: String?) = s?.replace(',', '.')?.toDoubleOrNull()
        return lines.drop(1).mapNotNull { l ->
            // avec « , » comme séparateur, les décimales sont des points ; avec « ; », elles peuvent être des virgules
            val c = split(l)
            val lat = num(c.getOrNull(iLat)) ?: return@mapNotNull null
            val lon = num(c.getOrNull(iLon)) ?: return@mapNotNull null
            if (lat !in 41.0..51.5 || lon !in -5.5..10.0) return@mapNotNull null
            val route = c.getOrNull(iRoute).orEmpty().uppercase()
            val vma = num(c.getOrNull(iVma))
            val len = when {
                route.startsWith("A") || (vma != null && vma >= 110) -> 4000f
                vma != null && vma <= 50 -> 300f
                else -> 2000f
            }
            Zone(lat, lon, len / 2)
        }
    }

    /** Appelé en roulant : renvoie true quand on est dans une zone de danger. */
    fun check(loc: Location): Boolean {
        val z = zones
        if (z.isEmpty()) return false
        val d = FloatArray(1)
        inside?.let { cur ->
            Location.distanceBetween(loc.latitude, loc.longitude, cur.lat, cur.lon, d)
            if (d[0] <= cur.halfM + 50) return true
            inside = null
        }
        val dLat = 0.04; val dLon = 0.06     // ~4 km : filtre rapide avant le calcul précis
        for (zone in z) {
            if (kotlin.math.abs(zone.lat - loc.latitude) > dLat || kotlin.math.abs(zone.lon - loc.longitude) > dLon) continue
            Location.distanceBetween(loc.latitude, loc.longitude, zone.lat, zone.lon, d)
            if (d[0] <= zone.halfM) { inside = zone; return true }
        }
        return false
    }

    fun reset() { inside = null }
}
