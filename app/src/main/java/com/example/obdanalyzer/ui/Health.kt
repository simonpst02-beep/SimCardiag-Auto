package com.example.obdanalyzer.ui

import com.example.obdanalyzer.core.Conn
import com.example.obdanalyzer.core.Level
import com.example.obdanalyzer.core.Live
import com.example.obdanalyzer.core.ObdManager
import com.example.obdanalyzer.core.Vehicle
import com.example.obdanalyzer.core.status
import kotlin.math.roundToInt

data class Health(val level: Level, val title: String, val text: String)

/** Résumé en une phrase de l'état de la voiture. */
fun health(l: Live, v: Vehicle): Health {
    if (l.conn == Conn.RECONNECTING) return Health(Level.WARN, "Reconnexion…", "Le boîtier ne répond plus, l'appli réessaie toute seule. Votre trajet continue d'être compté.")
    l.alerts.firstOrNull { it.level == Level.BAD }?.let { return Health(Level.BAD, "Attention", it.text) }
    l.alerts.firstOrNull { it.level == Level.WARN }?.let { return Health(Level.WARN, "À surveiller", it.text) }
    com.example.obdanalyzer.core.EngineWatch.notes.value.firstOrNull()?.let { return Health(Level.WARN, it.title, it.text) }
    val cool = l.values["cool"]
    if (cool != null && cool < 65) return Health(Level.OK, "Tout va bien", "Le moteur est encore froid : évitez les hauts régimes quelques minutes.")
    val parts = mutableListOf<String>()
    cool?.let { parts += "moteur à ${it.roundToInt()} °C" }
    l.values["volt"]?.let { parts += "batterie ${ObdManager.fmt(it)} V" }
    if (l.dtcs != null) parts += "aucun code défaut"
    val s = if (parts.isEmpty()) "Bonne route !" else parts.joinToString(", ").replaceFirstChar { it.uppercase() } + ". Bonne route !"
    return Health(Level.OK, "Tout va bien", s)
}

/** Phrase d'aide sous une valeur, et son niveau. */
fun hint(key: String, v: Double?, l: Live, v0: Vehicle): Pair<String, Level?> {
    if (v == null || v.isNaN()) return "" to null
    val s = ObdManager.settings.value
    val rpm = l.values["rpm"] ?: 0.0
    return when (key) {
        "cool" -> when {
            v >= s.tempLimit -> "Trop chaud, arrêtez-vous" to Level.BAD
            v < 65 -> "Moteur froid, roulez en douceur" to Level.WARN
            else -> "Température normale" to Level.OK
        }
        "oil" -> if (v >= 135) "Huile très chaude" to Level.BAD else if (v < 70) "Huile encore froide" to Level.WARN else "Normale" to Level.OK
        "volt" -> if (rpm > 500) (if (v < (ObdManager.settings.value.altLowV.takeIf { it > 0 } ?: 13.0)) "L'alternateur ne charge plus" to Level.BAD else "Charge correcte" to Level.OK)
                  else (if (v < 12.2) "Batterie faible" to Level.BAD else "Batterie en forme" to Level.OK)
        "fuel" -> {
            val avg = l.stats.avgConsumption ?: 6.5
            val km = v / 100 * v0.tankL / (avg / 100)
            if (v <= 12) "Réserve · ≈ ${km.roundToInt()} km" to Level.WARN else "≈ ${(km / 10).roundToInt() * 10} km d'autonomie" to Level.OK
        }
        "cons" -> if (l.consUnit == "L/h") "À l'arrêt ou au pas" to null
                  else if (v < 6) "Conduite économe" to Level.OK else if (v > 12) "Ça consomme !" to Level.WARN else "Consommation normale" to null
        "rpm" -> when {
            v >= s.rpmLimit -> "Régime très élevé" to Level.BAD
            v >= 4000 -> "Régime élevé" to Level.WARN
            (l.values["speed"] ?: 0.0) > 3 && v < 1300 -> "Pensez à rétrograder" to Level.WARN
            else -> "" to null
        }
        "speed" -> if (v < 2) "À l'arrêt" to null else "" to null
        "range" -> if (v < 60) "Pensez à faire le plein" to Level.WARN
                   else (if (l.values["fuel"] == null) "Estimé depuis le dernier plein" to null else "Selon votre conso du jour" to Level.OK)
        "left" -> if (l.values["fuel"] == null) "Estimé depuis le dernier plein ou le niveau indiqué" to null else "" to null
        else -> "" to null
    }
}

/** Prochain entretien à signaler sur l'accueil. */
fun nextMaintenance(v: Vehicle, extraKm: Double): String? {
    val now = System.currentTimeMillis()
    val st = v.maintenance.map { it.status(v.odometerKm + extraKm, now) }
    val o = st.firstOrNull { it.overdue } ?: st.firstOrNull { it.soon } ?: return null
    val left = buildList {
        o.kmLeft?.let { add(if (it <= 0) "dépassé de ${(-it).roundToInt()} km" else "dans ${it.roundToInt()} km") }
        o.daysLeft?.let { add(if (it <= 0) "échéance passée" else "dans $it jours") }
    }.joinToString(" ou ")
    return "${o.item.name} : $left"
}
