package com.example.obdanalyzer.data

import android.content.Context
import com.example.obdanalyzer.core.CustomPid
import com.example.obdanalyzer.core.Fuel
import com.example.obdanalyzer.core.FuelEntry
import com.example.obdanalyzer.core.PerfRun
import com.example.obdanalyzer.core.TripPoint
import com.example.obdanalyzer.core.MaintItem
import com.example.obdanalyzer.core.Settings
import com.example.obdanalyzer.core.ThemeMode
import com.example.obdanalyzer.core.Orientation
import com.example.obdanalyzer.core.PollRate
import com.example.obdanalyzer.core.ConsUnit
import com.example.obdanalyzer.core.TempUnit
import com.example.obdanalyzer.core.Wallpaper
import com.example.obdanalyzer.core.Accent
import com.example.obdanalyzer.core.LayoutMode
import com.example.obdanalyzer.core.TripSummary
import com.example.obdanalyzer.core.Vehicle
import com.example.obdanalyzer.core.WidgetCfg
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Sauvegarde locale en JSON (préférences + fichier des trajets). */
class Store(private val context: Context) {

    private val prefs = context.getSharedPreferences("obd", Context.MODE_PRIVATE)
    private val tripsFile = File(context.filesDir, "trips.json")
    val logsDir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs").apply { mkdirs() }

    // ---------- Réglages ----------

    fun loadSettings(): Settings = runCatching {
        val o = JSONObject(prefs.getString("settings", null) ?: return Settings())
        Settings(
            theme = ThemeMode.valueOf(o.optString("theme", "DARK")),
            sound = o.optBoolean("sound", true),
            vibration = o.optBoolean("vibration", true),
            rpmLimit = o.optInt("rpmLimit", 5500),
            tempLimit = o.optInt("tempLimit", 105),
            autoReconnect = o.optBoolean("autoReconnect", true),
            driveMetrics = o.optJSONArray("driveMetrics")?.strings() ?: Settings().driveMetrics,
            monitorDtc = o.optBoolean("monitorDtc", true),
            gps = o.optBoolean("gps", true),
            activeVehicleId = o.optString("activeVehicleId").ifEmpty { null },
            lastDevice = o.optString("lastDevice").ifEmpty { null },
            keepScreenOn = o.optBoolean("keepScreenOn", true),
            wallpaper = enumOr(o.optString("wallpaper"), Wallpaper.NONE),
            accent = enumOr(o.optString("accent"), Accent.BLUE),
            orientation = enumOr(o.optString("orientation"), Orientation.LANDSCAPE),
            consUnit = enumOr(o.optString("consUnit"), ConsUnit.L100),
            tempUnit = enumOr(o.optString("tempUnit"), TempUnit.C),
            autoDriveKmh = o.optInt("autoDriveKmh", 0),
            autoRecord = o.optBoolean("autoRecord", false),
            pollRate = enumOr(o.optString("pollRate"), PollRate.NORMAL),
            notifValues = o.optBoolean("notifValues", true),
            speedAlertKmh = o.optInt("speedAlertKmh", 0),
            reserveKm = o.optInt("reserveKm", 60),
            batteryLowV = o.optDouble("batteryLowV", 12.2),
            breakMinutes = o.optInt("breakMinutes", 120),
            fuelPrice = o.optDouble("fuelPrice", 1.85),
            keepTripsDays = o.optInt("keepTripsDays", 0),
            autoUpdateCheck = o.optBoolean("autoUpdateCheck", true),
            autoStartBoot = o.optBoolean("autoStartBoot", true),
            autoConnectBt = o.optBoolean("autoConnectBt", true),
            fullscreen = o.optBoolean("fullscreen", true),
            layoutMode = enumOr(o.optString("layoutMode"), LayoutMode.CAR),
            voiceAlerts = o.optBoolean("voiceAlerts", false),
            shiftLight = o.optBoolean("shiftLight", true),
            drivingLock = o.optBoolean("drivingLock", true),
            gpsDistance = o.optBoolean("gpsDistance", true),
            showHealth = o.optBoolean("showHealth", true),
            gpsCorrect = o.optBoolean("gpsCorrect", false),
            dangerZones = o.optBoolean("dangerZones", false),
            maintNotif = o.optBoolean("maintNotif", true),
            mediaButtons = o.optBoolean("mediaButtons", true),
            shiftRpm = o.optInt("shiftRpm", 0),
            legacyLink = o.optBoolean("legacyLink", false),
            driveTheme = enumOr(o.optString("driveTheme"), com.example.obdanalyzer.core.DriveTheme.RACE),
            driveFont = enumOr(o.optString("driveFont"), com.example.obdanalyzer.core.DriveFont.SPORT),
            driveAccent = o.optLong("driveAccent", 0),
            spotifyButton = o.optBoolean("spotifyButton", false),
            githubToken = o.optString("githubToken", ""),
        )
    }.getOrDefault(Settings())

    fun saveSettings(s: Settings) {
        val o = JSONObject()
            .put("theme", s.theme.name).put("sound", s.sound).put("vibration", s.vibration)
            .put("rpmLimit", s.rpmLimit).put("tempLimit", s.tempLimit).put("autoReconnect", s.autoReconnect)
            .put("driveMetrics", JSONArray(s.driveMetrics)).put("monitorDtc", s.monitorDtc).put("gps", s.gps)
            .put("activeVehicleId", s.activeVehicleId ?: "").put("lastDevice", s.lastDevice ?: "")
            .put("keepScreenOn", s.keepScreenOn).put("wallpaper", s.wallpaper.name).put("accent", s.accent.name).put("orientation", s.orientation.name).put("consUnit", s.consUnit.name)
            .put("tempUnit", s.tempUnit.name).put("autoDriveKmh", s.autoDriveKmh).put("autoRecord", s.autoRecord)
            .put("pollRate", s.pollRate.name).put("notifValues", s.notifValues).put("speedAlertKmh", s.speedAlertKmh)
            .put("reserveKm", s.reserveKm).put("batteryLowV", s.batteryLowV).put("breakMinutes", s.breakMinutes)
            .put("fuelPrice", s.fuelPrice).put("keepTripsDays", s.keepTripsDays)
            .put("autoUpdateCheck", s.autoUpdateCheck).put("githubToken", s.githubToken)
            .put("autoStartBoot", s.autoStartBoot).put("autoConnectBt", s.autoConnectBt).put("fullscreen", s.fullscreen)
            .put("layoutMode", s.layoutMode.name).put("voiceAlerts", s.voiceAlerts).put("shiftLight", s.shiftLight)
            .put("drivingLock", s.drivingLock).put("gpsDistance", s.gpsDistance).put("showHealth", s.showHealth).put("gpsCorrect", s.gpsCorrect).put("dangerZones", s.dangerZones).put("maintNotif", s.maintNotif).put("mediaButtons", s.mediaButtons).put("shiftRpm", s.shiftRpm).put("legacyLink", s.legacyLink)
            .put("driveTheme", s.driveTheme.name).put("driveFont", s.driveFont.name).put("driveAccent", s.driveAccent).put("spotifyButton", s.spotifyButton)
        prefs.edit().putString("settings", o.toString()).apply()
    }

    // ---------- Véhicules ----------

    fun loadVehicles(): List<Vehicle> = runCatching {
        val a = JSONArray(prefs.getString("vehicles", null) ?: return emptyList())
        (0 until a.length()).map { vehicleFrom(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun saveVehicles(list: List<Vehicle>) {
        val a = JSONArray()
        list.forEach { a.put(vehicleTo(it)) }
        prefs.edit().putString("vehicles", a.toString()).apply()
    }

    private fun vehicleTo(v: Vehicle) = JSONObject()
        .put("id", v.id).put("name", v.name).put("vin", v.vin ?: "").put("fuel", v.fuel.name)
        .put("tankL", v.tankL).put("odometerKm", v.odometerKm).put("preset", v.preset)
        .put("lastFuelPct", v.lastFuelPct ?: -1.0).put("lastAvgCons", v.lastAvgCons ?: -1.0).put("consFactor", v.consFactor).put("engineL", v.engineL).put("fuelRefL", v.fuelRefL ?: -1.0).put("fuelRefAt", v.fuelRefAt).put("speedFactor", v.speedFactor).put("speedSampleKm", v.speedSampleKm)
        .put("crankHistory", JSONArray(v.crankHistory)).put("lastCrankAt", v.lastCrankAt)
        .put("parkLat", v.parkLat ?: 999.0).put("parkLon", v.parkLon ?: 999.0).put("parkAt", v.parkAt).put("fuelAutoSet", v.fuelAutoSet)
        .put("layout", JSONArray().apply { v.layout.forEach { put(JSONObject().put("k", it.kind).put("m", it.metric ?: "").put("w", it.wide)) } })
        .put("maintenance", JSONArray().apply {
            v.maintenance.forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).put("everyKm", it.everyKm ?: -1)
                    .put("everyMonths", it.everyMonths ?: -1).put("lastKm", it.lastKm).put("lastDate", it.lastDate))
            }
        })
        .put("customPids", JSONArray().apply {
            v.customPids.forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).put("unit", it.unit).put("header", it.header)
                    .put("command", it.command).put("formula", it.formula).put("min", it.min).put("max", it.max))
            }
        })

    private fun vehicleFrom(o: JSONObject): Vehicle {
        val layout = o.optJSONArray("layout")?.let { a ->
            (0 until a.length()).map { i ->
                val w = a.getJSONObject(i)
                WidgetCfg(w.getString("k"), w.optString("m").ifEmpty { null }, w.optBoolean("w"))
            }
        }
        val maint = o.optJSONArray("maintenance")?.let { a ->
            (0 until a.length()).map { i ->
                val m = a.getJSONObject(i)
                MaintItem(
                    id = m.getString("id"), name = m.getString("name"),
                    everyKm = m.optInt("everyKm", -1).takeIf { it > 0 },
                    everyMonths = m.optInt("everyMonths", -1).takeIf { it > 0 },
                    lastKm = m.optDouble("lastKm", 0.0), lastDate = m.optLong("lastDate", 0L),
                )
            }
        } ?: emptyList()
        val custom = o.optJSONArray("customPids")?.let { a ->
            (0 until a.length()).map { i ->
                val c = a.getJSONObject(i)
                CustomPid(
                    id = c.getString("id"), name = c.getString("name"), unit = c.optString("unit"),
                    header = c.optString("header"), command = c.getString("command"), formula = c.getString("formula"),
                    min = c.optDouble("min", 0.0), max = c.optDouble("max", 100.0),
                )
            }
        } ?: emptyList()
        val base = Vehicle(
            id = o.getString("id"), name = o.getString("name"),
            vin = o.optString("vin").ifEmpty { null },
            fuel = runCatching { Fuel.valueOf(o.optString("fuel")) }.getOrDefault(Fuel.ESSENCE),
            tankL = o.optDouble("tankL", 50.0), odometerKm = o.optDouble("odometerKm", 0.0),
            preset = o.optString("preset", "Essentiel"),
            maintenance = maint, customPids = custom,
            lastFuelPct = o.optDouble("lastFuelPct", -1.0).takeIf { it >= 0 },
            lastAvgCons = o.optDouble("lastAvgCons", -1.0).takeIf { it >= 0 },
            consFactor = o.optDouble("consFactor", 1.0),
            engineL = o.optDouble("engineL", 1.4),
            fuelRefL = o.optDouble("fuelRefL", -1.0).takeIf { it >= 0 },
            fuelRefAt = o.optLong("fuelRefAt", 0),
            speedFactor = o.optDouble("speedFactor", 1.0),
            speedSampleKm = o.optDouble("speedSampleKm", 0.0),
            crankHistory = o.optJSONArray("crankHistory")?.let { a -> (0 until a.length()).map { a.getDouble(it) } } ?: emptyList(),
            lastCrankAt = o.optLong("lastCrankAt", 0),
            parkLat = o.optDouble("parkLat", 999.0).takeIf { it < 900 },
            parkLon = o.optDouble("parkLon", 999.0).takeIf { it < 900 },
            parkAt = o.optLong("parkAt", 0),
            fuelAutoSet = o.optBoolean("fuelAutoSet", false),
        )
        return if (layout != null) base.copy(layout = layout) else base
    }

    // ---------- Trajets ----------

    fun loadTrips(): List<TripSummary> = runCatching {
        if (!tripsFile.exists()) return emptyList()
        val a = JSONArray(tripsFile.readText())
        (0 until a.length()).map { i ->
            val t = a.getJSONObject(i)
            TripSummary(
                id = t.getString("id"), vehicleId = t.getString("vehicleId"), start = t.getLong("start"),
                durationS = t.getLong("durationS"), distanceKm = t.getDouble("distanceKm"), fuelL = t.getDouble("fuelL"),
                maxSpeed = t.getDouble("maxSpeed"), avgSpeed = t.getDouble("avgSpeed"), ecoScore = t.getInt("ecoScore"),
                harshAcc = t.optInt("harshAcc"), harshBrake = t.optInt("harshBrake"),
                file = t.optString("file").ifEmpty { null },
                fromPlace = t.optString("fromPlace").ifEmpty { null },
                toPlace = t.optString("toPlace").ifEmpty { null },
            )
        }
    }.getOrDefault(emptyList())

    fun saveTrips(list: List<TripSummary>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("id", it.id).put("vehicleId", it.vehicleId).put("start", it.start)
                .put("durationS", it.durationS).put("distanceKm", it.distanceKm).put("fuelL", it.fuelL)
                .put("maxSpeed", it.maxSpeed).put("avgSpeed", it.avgSpeed).put("ecoScore", it.ecoScore)
                .put("harshAcc", it.harshAcc).put("harshBrake", it.harshBrake).put("file", it.file ?: "")
                .put("fromPlace", it.fromPlace ?: "").put("toPlace", it.toPlace ?: ""))
        }
        tripsFile.writeText(a.toString())
    }

    // ---------- Carnet de carburant ----------

    private val fuelFile = File(context.filesDir, "fuel.json")

    fun loadFuel(): List<FuelEntry> = runCatching {
        if (!fuelFile.exists()) return emptyList()
        val a = JSONArray(fuelFile.readText())
        (0 until a.length()).map { i ->
            val f = a.getJSONObject(i)
            FuelEntry(f.getString("id"), f.getString("vehicleId"), f.getLong("date"), f.getDouble("liters"),
                f.getDouble("pricePerL"), f.getDouble("odometerKm"), f.optBoolean("full", true))
        }
    }.getOrDefault(emptyList())

    fun saveFuel(list: List<FuelEntry>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("id", it.id).put("vehicleId", it.vehicleId).put("date", it.date).put("liters", it.liters)
                .put("pricePerL", it.pricePerL).put("odometerKm", it.odometerKm).put("full", it.full))
        }
        fuelFile.writeText(a.toString())
    }

    // ---------- Chronos ----------

    private val perfFile = File(context.filesDir, "perf.json")

    fun loadPerf(): List<PerfRun> = runCatching {
        if (!perfFile.exists()) return emptyList()
        val a = JSONArray(perfFile.readText())
        (0 until a.length()).map { i ->
            val r = a.getJSONObject(i)
            PerfRun(r.getString("id"), r.getString("vehicleId"), r.getLong("date"), r.getString("kind"), r.getDouble("seconds"), r.optDouble("endSpeed", 0.0))
        }
    }.getOrDefault(emptyList())

    fun savePerf(list: List<PerfRun>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("id", it.id).put("vehicleId", it.vehicleId).put("date", it.date).put("kind", it.kind)
                .put("seconds", it.seconds).put("endSpeed", it.endSpeed))
        }
        perfFile.writeText(a.toString())
    }

    // ---------- Séries des trajets (pour les revoir) ----------

    private val seriesDir = File(context.filesDir, "series").apply { mkdirs() }

    fun saveSeries(tripId: String, pts: List<TripPoint>) {
        val a = JSONArray()
        pts.forEach { p ->
            a.put(JSONArray().put(p.t).put(p.speed ?: JSONObject.NULL).put(p.rpm ?: JSONObject.NULL).put(p.cool ?: JSONObject.NULL)
                .put(p.cons ?: JSONObject.NULL).put(p.thr ?: JSONObject.NULL).put(p.lat ?: JSONObject.NULL).put(p.lon ?: JSONObject.NULL).put(p.event))
        }
        File(seriesDir, "$tripId.json").writeText(a.toString())
    }

    fun loadSeries(tripId: String): List<TripPoint> = runCatching {
        val f = File(seriesDir, "$tripId.json")
        if (!f.exists()) return emptyList()
        val a = JSONArray(f.readText())
        fun JSONArray.d(i: Int): Double? = if (isNull(i)) null else optDouble(i)
        (0 until a.length()).map { i ->
            val r = a.getJSONArray(i)
            TripPoint(r.getLong(0), r.d(1), r.d(2), r.d(3), r.d(4), r.d(5), r.d(6), r.d(7), r.optInt(8))
        }
    }.getOrDefault(emptyList())

    fun deleteSeries(tripId: String) { File(seriesDir, "$tripId.json").delete() }

    // ---------- Sauvegarde complète ----------

    /** Toutes les données (sauf les fichiers CSV et les séries) dans un seul JSON. */
    fun exportAll(): String = JSONObject()
        .put("format", "simcardiag-backup").put("version", 2)
        .put("settings", prefs.getString("settings", "{}"))
        .put("vehicles", prefs.getString("vehicles", "[]"))
        .put("trips", if (tripsFile.exists()) tripsFile.readText() else "[]")
        .put("fuel", if (fuelFile.exists()) fuelFile.readText() else "[]")
        .put("perf", if (perfFile.exists()) perfFile.readText() else "[]")
        .toString(2)

    /** Remplace les données par celles de la sauvegarde. Renvoie false si le fichier n'est pas reconnu. */
    fun importAll(json: String): Boolean = runCatching {
        val o = JSONObject(json)
        if (o.optString("format") != "simcardiag-backup") return false
        JSONArray(o.getString("vehicles")) // validation
        prefs.edit().putString("settings", o.getString("settings")).putString("vehicles", o.getString("vehicles")).apply()
        tripsFile.writeText(o.optString("trips", "[]"))
        fuelFile.writeText(o.optString("fuel", "[]"))
        perfFile.writeText(o.optString("perf", "[]"))
        true
    }.getOrDefault(false)

    /**
     * Fusionne une sauvegarde venant d'un autre appareil (téléphone ↔ autoradio) : on garde tout ce qui est ici
     * et on ajoute ce qui manque (trajets, pleins, chronos, véhicules), sans toucher aux réglages de cet appareil.
     * Pour un même véhicule, on garde le plus grand kilométrage et on ajoute les mesures perso absentes.
     */
    fun mergeAll(json: String): Boolean = runCatching {
        val o = JSONObject(json)
        if (o.optString("format") != "simcardiag-backup") return false
        fun union(local: String, other: String, keep: (JSONObject, JSONObject) -> JSONObject = { a, _ -> a }): String {
            val a = JSONArray(local); val b = JSONArray(other)
            val byId = LinkedHashMap<String, JSONObject>()
            for (i in 0 until a.length()) a.getJSONObject(i).let { byId[it.optString("id")] = it }
            for (i in 0 until b.length()) b.getJSONObject(i).let { x ->
                val id = x.optString("id")
                byId[id] = byId[id]?.let { keep(it, x) } ?: x
            }
            return JSONArray(byId.values.toList()).toString()
        }
        val vehicles = union(prefs.getString("vehicles", "[]")!!, o.getString("vehicles")) { mine, other ->
            if (other.optDouble("odometerKm", 0.0) > mine.optDouble("odometerKm", 0.0)) mine.put("odometerKm", other.optDouble("odometerKm"))
            val mp = mine.optJSONArray("customPids") ?: JSONArray()
            val names = (0 until mp.length()).map { mp.getJSONObject(it).optString("command") + mp.getJSONObject(it).optString("formula") }.toSet()
            other.optJSONArray("customPids")?.let { op ->
                for (i in 0 until op.length()) op.getJSONObject(i).let { p ->
                    if (p.optString("command") + p.optString("formula") !in names) mp.put(p)
                }
            }
            mine.put("customPids", mp)
        }
        fun local(f: File) = if (f.exists()) f.readText() else "[]"
        prefs.edit().putString("vehicles", vehicles).apply()
        tripsFile.writeText(union(local(tripsFile), o.optString("trips", "[]")))
        fuelFile.writeText(union(local(fuelFile), o.optString("fuel", "[]")))
        perfFile.writeText(union(local(perfFile), o.optString("perf", "[]")))
        true
    }.getOrDefault(false)

    /** Efface toutes les données de l'appli (réglages, véhicules, trajets, pleins, chronos, fichiers). */
    fun wipe() {
        prefs.edit().clear().apply()
        listOf(tripsFile, fuelFile, perfFile).forEach { it.delete() }
        seriesDir.listFiles()?.forEach { it.delete() }
        logsDir.listFiles()?.forEach { it.delete() }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String, def: E): E =
        runCatching { enumValueOf<E>(name) }.getOrDefault(def)

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
}
