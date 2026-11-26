package com.example.obdanalyzer.core

import java.util.UUID

enum class Fuel(val label: String, val afr: Double, val densityGPerL: Double) {
    ESSENCE("Essence", 14.7, 745.0),
    DIESEL("Diesel", 14.5, 832.0),
}

enum class ThemeMode(val label: String) { SYSTEM("Comme le téléphone"), LIGHT("Clair"), DARK("Sombre"), AUTO_HOURS("Nuit de 19 h à 7 h") }
/** Thèmes du mode conduite : fond, couleur principale, couleurs du compte-tours. */
enum class DriveTheme(val label: String, val bgCenter: Long, val bgEdge: Long, val accent: Long, val low: Long, val mid: Long, val high: Long,
                      val carbon: Boolean = false, val text: Long = 0xFFFFFFFF) {
    // Écuries
    RACE("Course", 0xFF1A1416, 0xFF07080B, 0xFFE10600, 0xFF2EE59D, 0xFFFFC233, 0xFFFF2D2D),
    PAPAYA("Orange piste", 0xFF1C140C, 0xFF080605, 0xFFFF8000, 0xFFFFB366, 0xFFFF8000, 0xFFFF2D2D),
    RACING_BLUE("Bleu racing", 0xFF0B1630, 0xFF04070F, 0xFF1E5BFF, 0xFF7FB2FF, 0xFF1E5BFF, 0xFFFF3B30),
    ROSSO("Rouge italien", 0xFF220A0A, 0xFF0A0303, 0xFFD40000, 0xFFFFD200, 0xFFFF7A00, 0xFFD40000),
    // Carbone et couleur
    CARBON_YELLOW("Carbone jaune", 0xFF15161A, 0xFF060607, 0xFFFFD200, 0xFFFFFFFF, 0xFFFFD200, 0xFFFF3B30, carbon = true),
    CARBON_GREEN("Carbone vert", 0xFF121714, 0xFF050706, 0xFF00D26A, 0xFF7DFFB8, 0xFF00D26A, 0xFFFF3B30, carbon = true),
    CARBON_BLUE("Carbone bleu", 0xFF11151C, 0xFF050608, 0xFF00A8FF, 0xFF8FD8FF, 0xFF00A8FF, 0xFFFF3B30, carbon = true),
    // Compteur d'autrefois
    RETRO_90("Compteur années 90", 0xFF0E0C08, 0xFF040403, 0xFFFF8A00, 0xFFFFB347, 0xFFFF8A00, 0xFFFF3B1F, text = 0xFFFFB347),
}

/** Fonds du mode conduite, teintés de la couleur du thème. */
enum class DriveBg(val label: String) {
    AUTO("Du thème"), PLAIN("Uni"), GLOW("Halo"), SPEED("Lignes de vitesse"), HEX("Calandre"),
    GRID("Grille tech"), BRUSHED("Métal brossé"), STRIPES("Bandes course"), CHECKER("Drapeau"), CARBON("Carbone"),
}

/** Polices du mode conduite (polices de l'appareil, aucun téléchargement). */
enum class DriveFont(val label: String) { SPORT("Sport (italique)"), MODERN("Moderne"), DIGITAL("Digital"), CLASSIC("Classique") }

enum class LayoutMode(val label: String) { AUTO("Automatique"), PHONE("Téléphone"), TABLET("Tablette"), CAR("Autoradio") }
enum class Orientation(val label: String) { AUTO("Automatique"), PORTRAIT("Portrait"), LANDSCAPE("Paysage") }
enum class PollRate(val label: String, val delayMs: Long) { ECO("Économie de batterie", 250), NORMAL("Normale", 60), FAST("Rapide", 0) }
enum class ConsUnit(val label: String) { L100("L/100 km"), KML("km/L") }
enum class TempUnit(val label: String) { C("°C"), F("°F") }
enum class Wallpaper(val label: String) {
    NONE("Uni"), CARBON("Fibre de carbone"), STRIPES("Bandes de course"), NIGHT_ROAD("Route de nuit"),
    TACHO("Compte-tours"), CHECKERED("Damier"), CIRCUIT("Circuit"), SYNTHWAVE("Rétro synthwave"), HONEYCOMB("Calandre"),
    CARBON_RED("Carbone rouge sport"), CARBON_GREEN("Carbone vert néon"),
    CARBON_RED_LIVE("Carbone rouge dynamique"), CARBON_GREEN_LIVE("Carbone vert dynamique")
}
enum class Accent(val label: String, val argb: Long) {
    BLUE("Bleu", 0xFF8AB4FF), RED("Rouge course", 0xFFFF5A5F), ORANGE("Orange", 0xFFFF9F43), GREEN("Vert", 0xFF4CD787), YELLOW("Jaune", 0xFFFFD23F), NEON("Vert néon", 0xFF39FF14)
}

/** Un widget du tableau de bord. kind = val, gauge, chart, stats, dtc, rec, eco */
data class WidgetCfg(val kind: String, val metric: String? = null, val wide: Boolean = false)

data class CustomPid(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val unit: String,
    val header: String = "",      // ex. "7E0" ; vide = en-tête par défaut
    val command: String,          // ex. "013C" ou "221940"
    val formula: String,          // ex. "(A*256+B)/10-40"
    val min: Double = 0.0,
    val max: Double = 100.0,
) {
    val key: String get() = "c:$id"
}

data class MaintItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val everyKm: Int? = null,
    val everyMonths: Int? = null,
    val lastKm: Double,
    val lastDate: Long,
)

data class MaintStatus(val item: MaintItem, val kmLeft: Double?, val daysLeft: Long?) {
    val overdue: Boolean get() = (kmLeft != null && kmLeft <= 0) || (daysLeft != null && daysLeft <= 0)
    val soon: Boolean get() = !overdue && ((kmLeft != null && kmLeft < 1000) || (daysLeft != null && daysLeft < 30))
}

fun MaintItem.status(odometerKm: Double, now: Long): MaintStatus {
    val kmLeft = everyKm?.let { it - (odometerKm - lastKm) }
    val daysLeft = everyMonths?.let {
        val due = lastDate + it * 30L * 24 * 3600 * 1000
        (due - now) / (24L * 3600 * 1000)
    }
    return MaintStatus(this, kmLeft, daysLeft)
}

data class Vehicle(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val vin: String? = null,
    val fuel: Fuel = Fuel.ESSENCE,
    val tankL: Double = 50.0,
    val odometerKm: Double = 0.0,
    val preset: String = Presets.DEFAULT,
    val layout: List<WidgetCfg> = Presets.get(Presets.DEFAULT),
    val maintenance: List<MaintItem> = emptyList(),
    val customPids: List<CustomPid> = emptyList(),
    val lastFuelPct: Double? = null,
    val lastAvgCons: Double? = null,
    /** Correction de la consommation estimée, calculée à partir du carnet de carburant (1 = aucune). */
    val consFactor: Double = 1.0,
    /** Cylindrée en litres : sert à estimer le débit d'air quand la voiture n'a pas de débitmètre. */
    val engineL: Double = 1.4,
    /** Niveau du réservoir indiqué à la main (litres) et quand : point de départ du calcul quand la voiture ne donne pas sa jauge. */
    val fuelRefL: Double? = null,
    val fuelRefAt: Long = 0,
    /** Rapport vitesse GPS / vitesse compteur, mesuré en roulant (1,00 = compteur juste ; 0,96 = compteur 4 % trop haut). */
    val speedFactor: Double = 1.0,
    /** km de mesure qui ont servi à calculer ce rapport (fiable à partir de 10 km). */
    val speedSampleKm: Double = 0.0,
    /** Tension la plus basse pendant les derniers démarrages (V), la plus récente en dernier. */
    val crankHistory: List<Double> = emptyList(),
    val lastCrankAt: Long = 0,
    /** Où la voiture a été garée (fin du dernier trajet avec GPS). */
    val parkLat: Double? = null,
    val parkLon: Double? = null,
    val parkAt: Long = 0,
    /** Le carburant a déjà été détecté automatiquement une fois : ensuite, on respecte le choix de l'utilisateur. */
    val fuelAutoSet: Boolean = false,
    /** Rapports de boîte appris en roulant : km/h pour 1 000 tr/min, du 1er au dernier. */
    val gearRatios: List<Double> = emptyList(),
)

data class TripSummary(
    val id: String = UUID.randomUUID().toString(),
    val vehicleId: String,
    val start: Long,
    val durationS: Long,
    val distanceKm: Double,
    val fuelL: Double,
    val maxSpeed: Double,
    val avgSpeed: Double,
    val ecoScore: Int,
    val harshAcc: Int,
    val harshBrake: Int,
    val file: String? = null,
    /** Villes de départ et d'arrivée (trouvées grâce au GPS), si connues. */
    val fromPlace: String? = null,
    val toPlace: String? = null,
) {
    val route: String? get() = when {
        fromPlace != null && toPlace != null && fromPlace != toPlace -> "$fromPlace → $toPlace"
        fromPlace != null -> "Dans $fromPlace"
        else -> null
    }
    val avgConsumption: Double? get() = if (distanceKm > 0.5 && fuelL > 0) fuelL / distanceKm * 100 else null
    fun cost(pricePerL: Double): Double = fuelL * pricePerL
}

private val TEMP_KEYS = setOf("cool", "iat", "ext", "oil", "dpft")

/** Valeur et unité à afficher selon les préférences (km/L, °F). */
fun displayValue(key: String, v: Double?, unit: String, s: Settings): Pair<Double?, String> = when {
    v == null -> null to unit
    key == "cons" && unit == "L/100km" && s.consUnit == ConsUnit.KML -> (if (v > 0.1) 100 / v else null) to "km/L"
    key in TEMP_KEYS && s.tempUnit == TempUnit.F -> (v * 9 / 5 + 32) to "°F"
    else -> v to unit
}

data class Settings(
    val theme: ThemeMode = ThemeMode.DARK,
    val sound: Boolean = true,
    val vibration: Boolean = true,
    val rpmLimit: Int = 5500,
    val tempLimit: Int = 105,
    val autoReconnect: Boolean = true,
    val driveMetrics: List<String> = listOf("speed", "rpm", "cool", "cons"),
    val monitorDtc: Boolean = true,
    val gps: Boolean = true,
    val activeVehicleId: String? = null,
    val lastDevice: String? = null,
    // Affichage
    val keepScreenOn: Boolean = true,
    val wallpaper: Wallpaper = Wallpaper.NONE,
    val accent: Accent = Accent.BLUE,
    val orientation: Orientation = Orientation.LANDSCAPE,
    val consUnit: ConsUnit = ConsUnit.L100,
    val tempUnit: TempUnit = TempUnit.C,
    // Conduite
    val autoDriveKmh: Int = 0,            // 0 = désactivé
    val autoRecord: Boolean = false,
    val pollRate: PollRate = PollRate.NORMAL,
    val notifValues: Boolean = true,
    // Alertes supplémentaires
    val speedAlertKmh: Int = 0,           // 0 = désactivé
    val reserveKm: Int = 60,
    val batteryLowV: Double = 12.2,
    val breakMinutes: Int = 120,          // 0 = désactivé
    // Carburant & données
    val fuelPrice: Double = 1.85,
    val keepTripsDays: Int = 0,           // 0 = illimité
    // Autoradio
    val autoStartBoot: Boolean = true,       // ouvrir l'appli au démarrage de l'appareil
    val autoConnectBt: Boolean = true,        // se connecter quand le boîtier apparaît en Bluetooth
    val fullscreen: Boolean = true,          // masquer les barres d'Android
    val layoutMode: LayoutMode = LayoutMode.CAR,
    // Sécurité
    val voiceAlerts: Boolean = false,
    val shiftLight: Boolean = true,
    val shiftRpm: Int = 0,
    /** Connexion « comme hier soir » (méthode de la 2.0.35) : recherche de protocole libre, dialogue général, délais longs. */
    val legacyLink: Boolean = false,
    // Apparence du mode conduite sport
    val driveTheme: DriveTheme = DriveTheme.RACE,
    val driveFont: DriveFont = DriveFont.SPORT,
    val driveAccent: Long = 0,                // 0 = couleur du thème, sinon couleur choisie (ARGB)
    val driveBg: DriveBg = DriveBg.AUTO,
    val nightDim: Boolean = true,             // assombrir le mode conduite la nuit
    val coldAdvice: Boolean = true,           // conseil moteur froid                    // régime de passage du témoin ; 0 = auto (diesel 2 500, essence 3 000)
    val drivingLock: Boolean = true,
    val showHealth: Boolean = true,           // carte « État du véhicule » sur l'accueil
    // GPS
    val gpsDistance: Boolean = true,          // compter les km avec le GPS quand la voiture ne donne pas sa vitesse
    val gpsCorrect: Boolean = false,
    val dangerZones: Boolean = false,         // annonce des zones de danger (radars fixes, sans position exacte)
    val maintNotif: Boolean = true,           // rappels d'entretien par notification          // corriger la vitesse, les km et la consommation avec l'écart compteur / GPS
    val mediaButtons: Boolean = true,
    val spotifyButton: Boolean = false,       // raccourci Spotify, indépendant des boutons musique
    // Mises à jour
    val autoUpdateCheck: Boolean = true,
    val githubToken: String = "",         // seulement si le dépôt GitHub est privé
)

object Presets {
    const val DEFAULT = "Essentiel"
    private fun w(k: String, m: String? = null, wide: Boolean = k !in setOf("val", "gauge")) = WidgetCfg(k, m, wide)

    val ALL: Map<String, List<WidgetCfg>> = linkedMapOf(
        "Essentiel" to listOf(w("gauge", "speed"), w("gauge", "rpm"), w("val", "cons"), w("val", "range"), w("val", "cool"), w("val", "volt"), w("stats"), w("rec")),
        "Éco" to listOf(w("val", "cons", true), w("val", "range"), w("val", "left"), w("eco"), w("gauge", "load"), w("gauge", "rpm"), w("chart", "cons"), w("val", "fuel"), w("stats"), w("rec")),
        "Sport" to listOf(w("gauge", "rpm", true), w("val", "speed"), w("val", "thr"), w("gauge", "map"), w("gauge", "load"), w("val", "iat"), w("val", "oil"), w("chart", "rpm"), w("rec")),
        "Diagnostic" to listOf(w("dtc"), w("val", "cool"), w("val", "volt"), w("val", "iat"), w("val", "maf"), w("val", "map"), w("val", "load"), w("chart", "cool"), w("chart", "volt")),
        "Tout afficher" to listOf("speed", "rpm", "cool", "fuel", "left", "range", "volt", "cons", "load", "thr", "map", "maf", "iat", "ext", "oil", "fuelrate").map { w("val", it) } +
            listOf(w("chart", "rpm"), w("chart", "speed"), w("stats"), w("eco"), w("dtc"), w("rec")),
    )

    fun get(name: String): List<WidgetCfg> = ALL[name] ?: ALL.getValue(DEFAULT)

    fun defaultMaintenance(odometerKm: Double, now: Long) = listOf(
        MaintItem(name = "Vidange + filtre à huile", everyKm = 15000, everyMonths = 12, lastKm = odometerKm, lastDate = now),
        MaintItem(name = "Filtre à air", everyKm = 30000, everyMonths = 24, lastKm = odometerKm, lastDate = now),
        MaintItem(name = "Contrôle technique", everyMonths = 24, lastKm = odometerKm, lastDate = now),
        MaintItem(name = "Liquide de frein", everyMonths = 24, lastKm = odometerKm, lastDate = now),
    )
}

data class FuelEntry(
    val id: String = UUID.randomUUID().toString(),
    val vehicleId: String,
    val date: Long,
    val liters: Double,
    val pricePerL: Double,
    val odometerKm: Double,
    val full: Boolean = true,
) {
    val cost: Double get() = liters * pricePerL
}

data class FuelStats(
    val realConsumption: Double?,   // L/100 km entre pleins complets
    val costPerKm: Double?,
    val totalCost: Double,
    val totalLiters: Double,
    val monthly: List<Pair<String, Double>>, // "2026-11" -> coût
)

/** Consommation réelle calculée entre le premier et le dernier plein complet. */
fun fuelStats(entries: List<FuelEntry>): FuelStats {
    val s = entries.sortedBy { it.odometerKm }
    val fullIdx = s.indices.filter { s[it].full }
    var cons: Double? = null
    var perKm: Double? = null
    if (fullIdx.size >= 2) {
        val a = fullIdx.first(); val b = fullIdx.last()
        val km = s[b].odometerKm - s[a].odometerKm
        val liters = (a + 1..b).sumOf { s[it].liters }
        val cost = (a + 1..b).sumOf { s[it].cost }
        if (km > 0) { cons = liters / km * 100; perKm = cost / km }
    }
    val cal = java.util.Calendar.getInstance()
    val monthly = s.groupBy {
        cal.timeInMillis = it.date
        "%04d-%02d".format(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1)
    }.map { (k, v) -> k to v.sumOf { it.cost } }.sortedBy { it.first }
    return FuelStats(cons, perKm, s.sumOf { it.cost }, s.sumOf { it.liters }, monthly)
}

data class PerfRun(
    val id: String = UUID.randomUUID().toString(),
    val vehicleId: String,
    val date: Long,
    val kind: String,      // "0-100", "80-120", "400 m"
    val seconds: Double,
    val endSpeed: Double,
)

/** Un point du trajet, enregistré chaque seconde (pour le revoir et la carte). */
data class TripPoint(
    val t: Long, val speed: Double?, val rpm: Double?, val cool: Double?, val cons: Double?, val thr: Double?,
    val lat: Double?, val lon: Double?, val event: Int = 0, // 1 accélération brusque, -1 freinage brusque
)
