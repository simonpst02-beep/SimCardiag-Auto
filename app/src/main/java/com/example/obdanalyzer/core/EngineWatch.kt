package com.example.obdanalyzer.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

/** Une observation de la surveillance moteur, gardée jusqu'à ce que l'utilisateur l'efface. */
data class EngineNote(val id: String, val level: Level, val title: String, val text: String, val at: Long)

/**
 * Surveillance du moteur pendant la conduite, pensée pour les diesels type Corsa D 1.3 CDTI :
 * - thermostat : le moteur met-il trop longtemps à chauffer, ou refroidit-il sur route ?
 * - turbo : la pression à pleine charge baisse-t-elle par rapport à ce que la voiture faisait au début ?
 * - admission / vanne EGR : le débit d'air au ralenti ou à pleine charge s'écarte-t-il de l'habitude ?
 * - ralenti : le régime est-il irrégulier à froid (préchauffage) ou à chaud (injecteur) ?
 * Les références sont apprises sur la voiture elle-même (par véhicule) : on compare la voiture à elle-même.
 */
object EngineWatch {
    private lateinit var prefs: android.content.SharedPreferences
    private val _notes = MutableStateFlow<List<EngineNote>>(emptyList())
    val notes: StateFlow<List<EngineNote>> = _notes
    private var vehicleId = ""

    // Références apprises (moyennes glissantes) et nombre d'échantillons
    private val ref = HashMap<String, Double>()
    private val refN = HashMap<String, Int>()
    // Valeurs récentes du trajet en cours
    private val recent = HashMap<String, Double>()
    private val recentN = HashMap<String, Int>()

    fun init(ctx: Context) { prefs = ctx.getSharedPreferences("engine_watch", Context.MODE_PRIVATE) }

    /** Au début d'une connexion : charger les références de la voiture active. */
    fun startSession(vid: String) {
        if (!::prefs.isInitialized) return
        vehicleId = vid
        ref.clear(); refN.clear(); recent.clear(); recentN.clear()
        prefs.getString("ref_$vid", null)?.let { s ->
            runCatching {
                val o = JSONObject(s)
                o.keys().forEach { k -> val a = o.getJSONArray(k); ref[k] = a.getDouble(0); refN[k] = a.getInt(1) }
            }
        }
        _notes.value = prefs.getString("notes_$vid", null)?.let { s ->
            runCatching {
                val a = JSONArray(s)
                (0 until a.length()).map { i -> a.getJSONObject(i).let {
                    EngineNote(it.getString("id"), Level.valueOf(it.getString("level")), it.getString("title"), it.getString("text"), it.getLong("at")) } }
            }.getOrNull()
        } ?: emptyList()
        startCool = null; coldKm = 0.0; coldMin = 0.0; warmReached = false; maxCool = 0.0; cruiseS = 0.0
        stable = 0.0; idleRpm.clear(); flaggedThisTrip.clear()
    }

    fun endSession() { save() }

    fun clear() { _notes.value = emptyList(); saveNotes() }

    /** Tout réapprendre (après une réparation : turbo, EGR, thermostat changés…). */
    fun relearn() { ref.clear(); refN.clear(); recent.clear(); recentN.clear(); save(); clear() }

    fun learnedCount(): Int = refN.count { it.value >= REF_SAMPLES }

    private const val REF_SAMPLES = 60     // ~1 à 2 min de mesures utiles pour fixer une référence
    private const val RECENT_SAMPLES = 25

    // Thermostat
    private var startCool: Double? = null
    private var coldKm = 0.0
    private var coldMin = 0.0
    private var warmReached = false
    private var maxCool = 0.0
    private var cruiseS = 0.0
    // Pleine charge stable
    private var stable = 0.0
    // Ralenti
    private val idleRpm = ArrayDeque<Double>()
    private val flaggedThisTrip = HashSet<String>()
    private var lastSave = 0L

    fun feed(values: Map<String, Double>, dt: Double) {
        if (vehicleId.isEmpty() || dt !in 0.01..3.0) return
        val rpm = values["rpm"] ?: return
        if (rpm < 400) return
        val speed = values["speed"] ?: 0.0
        val cool = values["cool"]
        val load = values["load"]
        val pedal = values["pedal"]
        val map = values["map"]
        val baro = values["baro"] ?: 101.3
        val maf = values["maf"]

        // --- Thermostat ---
        if (cool != null) {
            if (startCool == null) startCool = cool
            maxCool = maxOf(maxCool, cool)
            val cold = (startCool ?: 99.0) < 35
            if (cold && !warmReached) {
                coldKm += speed * dt / 3600; coldMin += dt / 60
                if (cool >= 80) {
                    warmReached = true
                    learn("warmKm", coldKm, slow = true)
                }
                // 15 km roulés et toujours sous 70 °C : le moteur ne chauffe pas
                if (coldKm > 15 && cool < 70) flag("thermo", Level.WARN, "Thermostat à vérifier",
                    "Après ${coldKm.toInt()} km, le moteur n'est qu'à ${cool.toInt()} °C. Un thermostat bloqué ouvert est fréquent sur ce moteur : " +
                        "il augmente la consommation, encrasse la vanne EGR et chauffe mal l'habitacle.")
            }
            // Moteur déjà chaud qui refroidit sur route : thermostat qui reste ouvert
            if (maxCool >= 82 && speed > 70) cruiseS += dt else cruiseS = 0.0
            if (cruiseS > 180 && cool < 72) flag("thermo", Level.WARN, "Thermostat à vérifier",
                "Sur route, le moteur redescend à ${cool.toInt()} °C alors qu'il avait atteint ${maxCool.toInt()} °C : le thermostat reste sans doute ouvert.")
        }

        val warm = (cool ?: 0.0) >= 70

        // --- Turbo et débit d'air à pleine charge, régime moyen, moteur chaud ---
        val full = (load != null && load >= 85) || (pedal != null && pedal >= 80)
        if (warm && full && rpm in 2000.0..3400.0 && speed > 20) stable += dt else stable = 0.0
        if (stable >= 1.0) {
            if (map != null) {
                val boost = map - baro
                track("boost", boost)
                val r = ref["boost"]; val n = recentN["boost"] ?: 0
                if (r != null && (refN["boost"] ?: 0) >= REF_SAMPLES && n >= RECENT_SAMPLES) {
                    val now = recent["boost"]!!
                    if (now < r * 0.8) flag("turbo", Level.WARN, "Turbo moins puissant",
                        "À pleine charge, la pression turbo est de ${"%.2f".format(now / 100)} bar contre ${"%.2f".format(r / 100)} bar d'habitude. " +
                            "Causes fréquentes : durite de turbo fendue ou déboîtée, géométrie variable grippée, vanne EGR qui reste ouverte.")
                }
                if (n >= RECENT_SAMPLES && recent["boost"]!! < 45) flag("turbo_low", Level.WARN, "Le turbo souffle peu",
                    "À pleine charge, la pression turbo ne dépasse pas ${"%.2f".format(recent["boost"]!! / 100)} bar (attendu : environ 1 bar ou plus). Faites vérifier les durites et le turbo.")
            }
            if (maf != null) {
                val perK = maf / rpm * 1000   // débit d'air pour 1 000 tr/min
                track("mafLoad", perK)
                val r = ref["mafLoad"]; val n = recentN["mafLoad"] ?: 0
                if (r != null && (refN["mafLoad"] ?: 0) >= REF_SAMPLES && n >= RECENT_SAMPLES && recent["mafLoad"]!! < r * 0.8)
                    flag("air", Level.WARN, "Le moteur respire moins bien",
                        "À pleine charge, le débit d'air a baissé de ${((1 - recent["mafLoad"]!! / r) * 100).toInt()} % par rapport à d'habitude. " +
                            "Pistes : filtre à air encrassé, vanne EGR qui ne se ferme plus, débitmètre sale.")
            }
        }

        // --- Ralenti : débit d'air (EGR) et stabilité du régime ---
        val idle = speed < 1 && rpm in 600.0..1200.0
        if (idle) {
            idleRpm.addLast(rpm); while (idleRpm.size > 40) idleRpm.removeFirst()
            if (warm && (cool ?: 0.0) >= 80 && maf != null) {
                track("mafIdle", maf)
                val r = ref["mafIdle"]; val n = recentN["mafIdle"] ?: 0
                if (r != null && (refN["mafIdle"] ?: 0) >= REF_SAMPLES && n >= RECENT_SAMPLES) {
                    val dev = recent["mafIdle"]!! / r - 1
                    if (abs(dev) > 0.35) flag("egr", Level.WARN, "Vanne EGR à vérifier",
                        "Au ralenti moteur chaud, le débit d'air est ${if (dev > 0) "plus élevé" else "plus faible"} de ${(abs(dev) * 100).toInt()} % que d'habitude. " +
                            if (dev > 0) "La vanne EGR reste peut-être fermée (encrassée ou grippée)." else "La vanne EGR reste peut-être trop ouverte, ou une prise d'air fausse la mesure.")
                }
            }
            if (idleRpm.size >= 30) {
                val m = idleRpm.average()
                val sd = sqrt(idleRpm.sumOf { (it - m) * (it - m) } / idleRpm.size)
                if (cool != null && cool < 40 && sd > 70) flag("idle_cold", Level.WARN, "Ralenti irrégulier à froid",
                    "Le régime varie de ±${sd.toInt()} tr/min au ralenti moteur froid. Sur un diesel, c'est souvent une bougie de préchauffage usée, parfois un injecteur.")
                if (cool != null && cool >= 80 && sd > 45) flag("idle_warm", Level.WARN, "Ralenti irrégulier à chaud",
                    "Le régime varie de ±${sd.toInt()} tr/min au ralenti moteur chaud. Un injecteur qui fuit ou s'use est la cause la plus courante : faites contrôler les retours injecteurs.")
            }
        } else idleRpm.clear()

        val now = System.currentTimeMillis()
        if (now - lastSave > 60_000) { lastSave = now; save() }
    }

    /** Ajoute une valeur : d'abord à la référence (tant qu'elle n'est pas fixée), puis à la moyenne du trajet. */
    private fun track(key: String, x: Double) {
        val n = refN[key] ?: 0
        if (n < REF_SAMPLES) { learn(key, x); return }
        val rn = (recentN[key] ?: 0) + 1
        recentN[key] = rn
        recent[key] = recent[key]?.let { it + (x - it) / minOf(rn, 40) } ?: x
        // la référence suit très lentement l'usure normale, mais jamais une chute brutale
        val r = ref[key]!!
        if (abs(x / r - 1) < 0.1) ref[key] = r + (x - r) / 2000
    }

    private fun learn(key: String, x: Double, slow: Boolean = false) {
        val n = (refN[key] ?: 0) + 1
        refN[key] = n
        ref[key] = ref[key]?.let { it + (x - it) / minOf(n, if (slow) 5 else REF_SAMPLES) } ?: x
    }

    private fun flag(id: String, level: Level, title: String, text: String) {
        if (!flaggedThisTrip.add(id)) return
        _notes.value = listOf(EngineNote(id, level, title, text, System.currentTimeMillis())) + _notes.value.filter { it.id != id }
        saveNotes()
        ObdManager.engineNotice(title, text)
    }

    private fun save() {
        if (!::prefs.isInitialized || vehicleId.isEmpty()) return
        val o = JSONObject()
        ref.forEach { (k, v) -> o.put(k, JSONArray().put(v).put(refN[k] ?: 0)) }
        prefs.edit().putString("ref_$vehicleId", o.toString()).apply()
    }

    private fun saveNotes() {
        if (!::prefs.isInitialized || vehicleId.isEmpty()) return
        val a = JSONArray()
        _notes.value.forEach { a.put(JSONObject().put("id", it.id).put("level", it.level.name).put("title", it.title).put("text", it.text).put("at", it.at)) }
        prefs.edit().putString("notes_$vehicleId", a.toString()).apply()
    }
}
