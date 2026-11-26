package com.example.obdanalyzer.obd

/** Famille d'une mesure : sert à la couleur et à l'icône dans l'interface. */
enum class Family { ENGINE, TEMP, ENERGY, BATTERY, SPEED, CUSTOM }

/** Description d'une mesure affichable (PID standard, valeur calculée ou capteur personnalisé). */
data class MetricDef(
    val key: String,
    val name: String,
    val unit: String,
    val min: Double,
    val max: Double,
    val family: Family,
)

/** PID standard du mode 01 (SAE J1979). */
data class Pid(
    val code: String,
    val def: MetricDef,
    val bytes: Int,
    val decode: (IntArray) -> Double,
)

object Pids {
    private fun p(code: String, key: String, name: String, unit: String, min: Double, max: Double, fam: Family, bytes: Int, f: (IntArray) -> Double) =
        Pid(code, MetricDef(key, name, unit, min, max, fam), bytes, f)

    val ALL: List<Pid> = listOf(
        p("0D", "speed", "Vitesse", "km/h", 0.0, 200.0, Family.SPEED, 1) { it[0].toDouble() },
        p("0C", "rpm", "Régime", "tr/min", 0.0, 7000.0, Family.ENGINE, 2) { (it[0] * 256 + it[1]) / 4.0 },
        p("05", "cool", "Temp. moteur", "°C", 40.0, 130.0, Family.TEMP, 1) { it[0] - 40.0 },
        p("2F", "fuel", "Carburant", "%", 0.0, 100.0, Family.ENERGY, 1) { it[0] * 100 / 255.0 },
        p("04", "load", "Charge moteur", "%", 0.0, 100.0, Family.ENGINE, 1) { it[0] * 100 / 255.0 },
        p("11", "thr", "Pédale d'accélérateur", "%", 0.0, 100.0, Family.ENGINE, 1) { it[0] * 100 / 255.0 },
        p("0B", "map", "Pression admission", "kPa", 0.0, 250.0, Family.ENGINE, 1) { it[0].toDouble() },
        p("10", "maf", "Débit d'air", "g/s", 0.0, 200.0, Family.ENGINE, 2) { (it[0] * 256 + it[1]) / 100.0 },
        p("0F", "iat", "Air d'admission", "°C", -20.0, 80.0, Family.TEMP, 1) { it[0] - 40.0 },
        p("46", "ext", "Temp. extérieure", "°C", -20.0, 45.0, Family.TEMP, 1) { it[0] - 40.0 },
        p("5C", "oil", "Huile moteur", "°C", 40.0, 150.0, Family.TEMP, 1) { it[0] - 40.0 },
        // Pour fiabiliser la consommation estimée
        p("03", "fcut", "Coupure injection", "", 0.0, 1.0, Family.ENGINE, 1) { if (it[0] == 4) 1.0 else 0.0 },
        p("06", "stft", "Correction carburant court terme", "%", -25.0, 25.0, Family.ENGINE, 1) { (it[0] - 128) * 100 / 128.0 },
        p("07", "ltft", "Correction carburant long terme", "%", -25.0, 25.0, Family.ENGINE, 1) { (it[0] - 128) * 100 / 128.0 },
        p("44", "lambda", "Richesse demandée (lambda)", "", 0.5, 2.0, Family.ENGINE, 2) { (it[0] * 256 + it[1]) * 2 / 65536.0 },
        // Mesures présentes sur la Corsa D 1.3 CDTI (et beaucoup d'autres diesels)
        p("23", "rail", "Pression rail injection", "bar", 0.0, 1800.0, Family.ENGINE, 2) { (it[0] * 256 + it[1]) * 10 / 100.0 },
        p("2C", "egr", "Vanne EGR demandée", "%", 0.0, 100.0, Family.ENGINE, 1) { it[0] * 100 / 255.0 },
        p("33", "baro", "Pression atmosphérique", "kPa", 70.0, 110.0, Family.ENGINE, 1) { it[0].toDouble() },
        p("42", "ecuv", "Tension calculateur", "V", 9.0, 16.0, Family.ENERGY, 2) { (it[0] * 256 + it[1]) / 1000.0 },
        p("49", "pedal", "Pédale d'accélérateur", "%", 0.0, 100.0, Family.ENGINE, 1) { it[0] * 100 / 255.0 },
        p("21", "milkm", "Distance voyant allumé", "km", 0.0, 5000.0, Family.ENGINE, 2) { (it[0] * 256 + it[1]).toDouble() },
        p("5E", "fuelrate", "Débit carburant", "L/h", 0.0, 30.0, Family.ENERGY, 2) { (it[0] * 256 + it[1]) / 20.0 },
        // Filtre à particules (diesel récents) : octet A = capteurs présents, puis banc 1
        p("7A", "dpfp", "FAP · pression diff.", "kPa", 0.0, 30.0, Family.TEMP, 7) { (it[1] * 256 + it[2]) / 100.0 },
        p("7C", "dpft", "FAP · température", "°C", 0.0, 800.0, Family.TEMP, 9) { (it[1] * 256 + it[2]) / 10.0 - 40 },
    )

    val VOLT = MetricDef("volt", "Batterie", "V", 10.0, 16.0, Family.BATTERY)
    val CONS = MetricDef("cons", "Consommation", "L/100km", 0.0, 20.0, Family.ENERGY)
    val LEFT = MetricDef("left", "Carburant restant", "L", 0.0, 70.0, Family.ENERGY)
    val RANGE = MetricDef("range", "Autonomie", "km", 0.0, 900.0, Family.ENERGY)
    val GPSV = MetricDef("gpsspeed", "Vitesse GPS", "km/h", 0.0, 200.0, Family.SPEED)
    val BOOST = MetricDef("boost", "Pression turbo", "bar", -0.2, 2.0, Family.ENGINE)
    val GEAR = MetricDef("gear", "Rapport engagé", "", 0.0, 6.0, Family.SPEED)

    fun byCode(code: String): Pid? = ALL.firstOrNull { it.code == code }
}
