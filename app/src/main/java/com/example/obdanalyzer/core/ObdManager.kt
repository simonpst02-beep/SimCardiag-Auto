package com.example.obdanalyzer.core

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import com.example.obdanalyzer.data.Store
import com.example.obdanalyzer.obd.DataLogger
import com.example.obdanalyzer.obd.Dtc
import com.example.obdanalyzer.obd.Ecu
import com.example.obdanalyzer.obd.EcuDtc
import com.example.obdanalyzer.obd.EcuParser
import com.example.obdanalyzer.obd.EcuResult
import com.example.obdanalyzer.obd.Net
import com.example.obdanalyzer.widget.CarWidget
import com.example.obdanalyzer.obd.Expr
import com.example.obdanalyzer.obd.MetricDef
import com.example.obdanalyzer.obd.ObdConnection
import com.example.obdanalyzer.obd.Links
import com.example.obdanalyzer.obd.ObdParser
import com.example.obdanalyzer.obd.Pid
import com.example.obdanalyzer.obd.Pids
import com.example.obdanalyzer.obd.Readiness
import com.example.obdanalyzer.obd.Vin
import com.example.obdanalyzer.obd.Family
import com.example.obdanalyzer.service.ObdService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Calendar
import kotlin.math.max

enum class Conn { IDLE, CONNECTING, CONNECTED, RECONNECTING }
enum class Level { OK, WARN, BAD }

data class DeviceInfo(val name: String, val address: String, val isObd: Boolean)
data class Alert(val id: String, val level: Level, val text: String)
data class FreezeFrame(val dtc: String?, val values: List<Pair<String, String>>)

data class LiveStats(
    val durationS: Long = 0, val distanceKm: Double = 0.0, val fuelL: Double = 0.0,
    val maxSpeed: Double = 0.0, val avgSpeed: Double = 0.0, val eco: Int = 100,
    val harshAcc: Int = 0, val harshBrake: Int = 0, val tips: List<String> = emptyList(),
) {
    val avgConsumption: Double? get() = if (distanceKm > 0.3 && fuelL > 0) fuelL / distanceKm * 100 else null
}

data class Live(
    val conn: Conn = Conn.IDLE,
    val step: Int = 0,
    val device: String? = null,
    val error: String? = null,
    val log: List<String> = emptyList(),
    val supported: List<Pid> = emptyList(),
    val protocol: String = "",
    val isStn: Boolean = false,
    val multi: Boolean = false,
    val values: Map<String, Double> = emptyMap(),
    val consUnit: String = "L/100km",
    val history: Map<String, List<Float>> = emptyMap(),
    val stats: LiveStats = LiveStats(),
    val alerts: List<Alert> = emptyList(),
    val recording: Boolean = false,
    val recStart: Long = 0,
    val diagLoading: Boolean = false,
    val dtcs: List<Dtc>? = null,
    val pending: List<Dtc> = emptyList(),
    val readiness: Readiness? = null,
    val freeze: FreezeFrame? = null,
    val vin: String? = null,
    val message: String? = null,
)

const val HISTORY = 120

/** Test de batterie et de démarreur. */
data class BatteryTest(
    val phase: String = "idle",          // idle, rest, crank, charge, done, error
    val rest: Double? = null,
    val crankMin: Double? = null,
    val charge: Double? = null,
    val samples: List<Float> = emptyList(),
    val message: String = "",
)

/** Options de l'analyse approfondie. */
data class ScanOptions(val hs11: Boolean = true, val hs29: Boolean = false, val ms: Boolean = false, val msBaud: Int = 95238)

/** État de la recherche et des tests par calculateur. */
data class EcuScan(
    val running: Boolean = false,
    val progress: Float = 0f,
    val label: String = "",
    val ecus: List<Ecu> = emptyList(),
    val results: Map<String, EcuResult> = emptyMap(),
    val testing: String? = null,
    val deepDone: Boolean = false,
)

/**
 * Cœur de l'appli : connexion, lecture en boucle, reconnexion, trajets, véhicules.
 * Vit tant que le processus vit ; le service au premier plan le garde en vie écran éteint.
 */
@SuppressLint("StaticFieldLeak")
object ObdManager {

    private lateinit var ctx: Context
    private lateinit var store: Store
    private lateinit var alerts: AlertPlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val conn = ObdConnection()
    private var logger: DataLogger? = null
    private var pollJob: Job? = null
    private var sessionJob: Job? = null

    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live.asStateFlow()
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    private val _vehicles = MutableStateFlow<List<Vehicle>>(emptyList())
    val vehicles: StateFlow<List<Vehicle>> = _vehicles.asStateFlow()
    private val _trips = MutableStateFlow<List<TripSummary>>(emptyList())
    val trips: StateFlow<List<TripSummary>> = _trips.asStateFlow()
    private val _ecu = MutableStateFlow(EcuScan())
    val ecu: StateFlow<EcuScan> = _ecu.asStateFlow()
    @Volatile private var pollPaused = false
    private val _fuel = MutableStateFlow<List<FuelEntry>>(emptyList())
    val fuelLog: StateFlow<List<FuelEntry>> = _fuel.asStateFlow()
    private val _perf = MutableStateFlow<List<PerfRun>>(emptyList())
    val perfRuns: StateFlow<List<PerfRun>> = _perf.asStateFlow()
    private val _battery = MutableStateFlow(BatteryTest())
    val battery: StateFlow<BatteryTest> = _battery.asStateFlow()
    private val _perfStatus = MutableStateFlow("")
    val perfStatus: StateFlow<String> = _perfStatus.asStateFlow()

    private lateinit var gps: GpsTracker
    private val perfTimer = PerfTimer()
    private val series = ArrayList<TripPoint>()
    private var lastSeriesT = 0L
    private var lastWidgetT = 0L
    private var lastDtcCheck = 0L
    private var knownDtcs: Set<String>? = null

    // Session (= trajet en cours, survit aux reconnexions)
    private var sessionStart = 0L
    private var eco = EcoTracker()
    private var dist = 0.0; private var fuel = 0.0; private var maxSpeed = 0.0
    private var speedSum = 0.0; private var speedN = 0
    private var multiFails = 0
    private var activeAlertIds = emptySet<String>()
    private val hist = HashMap<String, ArrayDeque<Float>>()
    private var recFile: String? = null
    private var pendingEvent = 0

    private val adapter: BluetoothAdapter?
        get() = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    fun init(context: Context) {
        if (::ctx.isInitialized) return
        ctx = context.applicationContext
        store = Store(ctx)
        alerts = AlertPlayer(ctx)
        _settings.value = store.loadSettings()
        var vs = store.loadVehicles()
        if (vs.isEmpty()) {
            val v = Vehicle(name = "Ma voiture", maintenance = Presets.defaultMaintenance(0.0, System.currentTimeMillis()))
            vs = listOf(v)
            store.saveVehicles(vs)
        }
        _vehicles.value = vs
        if (_settings.value.activeVehicleId == null || vs.none { it.id == _settings.value.activeVehicleId }) {
            updateSettings { it.copy(activeVehicleId = vs.first().id) }
        }
        _trips.value = store.loadTrips()
        _fuel.value = store.loadFuel()
        _perf.value = store.loadPerf()
        gps = GpsTracker(ctx)
        pruneTrips()
        startZoneWatch()
    }

    /**
     * Zones de danger aussi quand le boîtier n'est pas connecté : il suffit du GPS de l'appareil.
     * (Connecté, c'est la boucle de lecture qui s'en charge, avec les autres alertes.)
     */
    private fun startZoneWatch() {
        scope.launch {
            var loaded = false
            var wasIn = false
            while (true) {
                delay(2000)
                val s = _settings.value
                if (!s.dangerZones) { wasIn = false; continue }
                if (!loaded) { loaded = DangerZones.load(ctx) > 0 }
                if (_live.value.conn != Conn.IDLE) { wasIn = false; continue }
                gps.start()
                val inside = gps.fresh()?.let { DangerZones.check(it) } ?: false
                if (inside && !wasIn) {
                    _live.update { it.copy(message = "Zone de danger : respectez les limitations, soyez prudent.") }
                    alerts.play(false, s.sound && !s.voiceAlerts, s.vibration)
                    if (s.voiceAlerts) alerts.speak("Zone de danger")
                }
                wasIn = inside
            }
        }
    }

    /** Pour les réglages : nombre de zones chargées, zones autour de soi, problème éventuel. */
    fun zoneStatus(): String {
        val n = DangerZones.count()
        val err = DangerZones.lastError
        if (n == 0) return err ?: "Liste pas encore chargée (internet nécessaire)"
        val loc = gps.fresh() ?: FuelPrices.lastLocation(ctx)
        val near = loc?.let { DangerZones.around(it, 10.0) }
        val inside = loc?.let { DangerZones.check(it) } == true
        return "$n zones chargées" + (near?.let { " · $it à moins de 10 km" } ?: " · position inconnue") + (if (inside) " · vous êtes dans une zone" else "")
    }

    val logsDir get() = store.logsDir

    // ---------------------------------------------------------------- véhicules & réglages

    fun activeVehicle(): Vehicle =
        _vehicles.value.firstOrNull { it.id == _settings.value.activeVehicleId } ?: _vehicles.value.first()

    fun updateSettings(f: (Settings) -> Settings) {
        _settings.value = f(_settings.value)
        store.saveSettings(_settings.value)
    }

    fun updateVehicle(id: String, f: (Vehicle) -> Vehicle) {
        _vehicles.value = _vehicles.value.map { if (it.id == id) f(it) else it }
        store.saveVehicles(_vehicles.value)
    }

    fun updateActive(f: (Vehicle) -> Vehicle) = updateVehicle(activeVehicle().id, f)

    fun addVehicle(name: String, fuel: Fuel): Vehicle {
        val v = Vehicle(name = name, fuel = fuel, maintenance = Presets.defaultMaintenance(0.0, System.currentTimeMillis()))
        _vehicles.value = _vehicles.value + v
        store.saveVehicles(_vehicles.value)
        return v
    }

    fun deleteVehicle(id: String) {
        if (_vehicles.value.size <= 1) return
        _vehicles.value = _vehicles.value.filterNot { it.id == id }
        store.saveVehicles(_vehicles.value)
        if (_settings.value.activeVehicleId == id) updateSettings { it.copy(activeVehicleId = _vehicles.value.first().id) }
    }

    fun selectVehicle(id: String) = updateSettings { it.copy(activeVehicleId = id) }

    fun deleteTrip(t: TripSummary) {
        t.file?.let { java.io.File(store.logsDir, it).delete() }
        store.deleteSeries(t.id)
        _trips.value = _trips.value.filterNot { it.id == t.id }
        store.saveTrips(_trips.value)
    }

    /** Toutes les mesures affichables pour le véhicule actif. */
    fun metricDefs(): List<MetricDef> =
        Pids.ALL.map { it.def } + listOf(Pids.VOLT, Pids.CONS, Pids.LEFT, Pids.RANGE, Pids.GPSV, Pids.BOOST, Pids.GEAR) +
            activeVehicle().customPids.map { MetricDef(it.key, it.name, it.unit, it.min, it.max, Family.CUSTOM) }

    fun metric(key: String?): MetricDef? = metricDefs().firstOrNull { it.key == key }

    // ---------------------------------------------------------------- Bluetooth

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<DeviceInfo> {
        val a = adapter ?: return emptyList()
        if (!a.isEnabled) return emptyList()
        return try {
            a.bondedDevices.map {
                val n = it.name ?: "Inconnu"
                DeviceInfo(n, it.address, listOf("OBD", "ELM", "VGATE", "VLINK", "V-LINK").any { k -> n.contains(k, true) })
            }.sortedByDescending { it.isObd }
        } catch (e: SecurityException) { emptyList() }
    }

    fun bluetoothOn(): Boolean = adapter?.isEnabled == true

    /** À l'ouverture de l'appli : reconnexion au dernier boîtier utilisé. */
    fun autoConnect() {
        val s = _settings.value
        if (_live.value.conn == Conn.IDLE && s.autoReconnect && s.lastDevice != null &&
            (!Links.isBluetooth(s.lastDevice) || bluetoothOn())) connect(s.lastDevice)
    }

    /** Connexion lancée par le démarrage de l'appareil ou la détection Bluetooth. Renvoie false s'il n'y a rien à faire. */
    fun autoConnectFromBackground(): Boolean {
        val dev = _settings.value.lastDevice ?: return false
        if (_live.value.conn != Conn.IDLE) return true
        if (Links.isBluetooth(dev) && !bluetoothOn()) return false
        connect(dev)
        return true
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        if (Links.isBluetooth(address) && adapter == null) return
        if (_live.value.conn != Conn.IDLE) return
        sessionJob?.cancel()
        sessionJob = scope.launch {
            val name = if (Links.isBluetooth(address))
                (try { adapter?.getRemoteDevice(address)?.name } catch (e: SecurityException) { null } ?: address)
            else Links.label(address)
            _live.value = Live(conn = Conn.CONNECTING, device = name)
            ObdService.start(ctx)
            try {
                openAndInit(address)
                updateSettings { it.copy(lastDevice = address) }
                startSession()
                _live.update { it.copy(conn = Conn.CONNECTED, step = 4, message = "Connecté ! ${it.supported.size} capteurs disponibles") }
                startPolling()
                if (_settings.value.autoRecord && logger == null) toggleRecording()
            } catch (e: Exception) {
                conn.close()
                ObdService.stop(ctx)
                _live.update {
                    it.copy(conn = Conn.IDLE, error = e.message?.takeIf { m -> m.startsWith("La voiture") }
                        ?: if (!Links.isBluetooth(address) && e.message != null) e.message
                    else "Impossible de se connecter à $name. Vérifiez que le contact est mis et que le boîtier est bien branché.")
                }
            }
        }
    }

    private fun addLog(line: String, step: Int) =
        _live.update { it.copy(log = it.log + line, step = max(it.step, step)) }

    @SuppressLint("MissingPermission")
    private suspend fun openAndInit(address: String) {
        conn.legacy = _settings.value.legacyLink
        when {
            Links.isWifi(address) -> { Links.openWifi(ctx, conn, address); addLog("Wi-Fi connecté", 1) }
            Links.isUsb(address) -> { Links.openUsb(ctx, conn); addLog("Câble USB connecté", 1) }
            else -> {
                val d = adapter?.getRemoteDevice(address) ?: throw IOException("Bluetooth indisponible")
                conn.open(d, adapter, btMode)
                addLog("Bluetooth connecté", 1)
            }
        }
        conn.initialize { line, step -> addLog(line, step) }
        val supported = detectSupported()
        addLog("${supported.size} capteurs détectés", 4)
        val vin = runCatching { ObdParser.parseVin(conn.send("0902", 5000)) }.getOrNull()
        _live.update {
            it.copy(supported = supported, protocol = conn.protocol, isStn = conn.isStn, multi = conn.isCan, vin = vin ?: it.vin)
        }
        multiFails = 0
        if (vin != null) matchVehicle(vin)
        detectFuelAndTarget()
        // Lecture groupée seulement en dialogue direct avec le calculateur moteur : en dialogue général,
        // plusieurs calculateurs répondent en même temps (réponses longues et mélangées, boîtier saturé)
        _live.update { it.copy(multi = if (conn.legacy) conn.isCan else conn.isCan && conn.physical) }
    }

    /**
     * 1. Diesel ou essence : la voiture l'indique dans 0101 (bit « allumage par compression »).
     *    Le carburant du véhicule est corrigé tout seul, ce qui change complètement le calcul de consommation.
     * 2. Sur CAN, on parle directement au calculateur moteur (7E0 → 7E8) au lieu de toute la voiture (7DF) :
     *    une seule réponse par question, plus de réponses en retard d'un autre calculateur qui décalent tout.
     */
    private suspend fun detectFuelAndTarget() {
        runCatching {
            val r = ObdParser.parseReadiness(conn.send("0101", 3000))
            val v = activeVehicle()
            val codes = _live.value.supported.map { it.code }.toSet()
            // Certains calculateurs (ex. Corsa D 1.3 CDTI) ne mettent pas le bit « diesel » :
            // pression de rail (PID 23) sans corrections d'injection essence (06/07) = diesel à rampe commune
            val diesel = r?.diesel == true || ("23" in codes && "06" !in codes && "07" !in codes)
            if (diesel && v.fuel != Fuel.DIESEL && !v.fuelAutoSet) {
                updateVehicle(v.id) { it.copy(fuel = Fuel.DIESEL, fuelAutoSet = true) }
                conn.note("Moteur diesel détecté : carburant du véhicule réglé sur Diesel")
                _live.update { it.copy(message = "Moteur diesel détecté : carburant réglé sur Diesel. Vérifiez la cylindrée dans Garage (1,3 pour une Corsa 1.3 CDTI).") }
            }
        }
        if (!conn.legacy && conn.isCan && conn.protoNum in listOf('6', '8')) {
            runCatching {
                // ATSH7E0 seul : en réception automatique (ATAR), le boîtier écoute 7E8 tout seul
                conn.send("ATSH7E0")
                // 2 essais avec 0100 (toujours géré) : la première réponse peut être lente juste après le changement d'adresse
                var ok = false
                for (i in 1..2) {
                    val r = try { conn.send("0100", 3000) } catch (e: ObdConnection.ObdTimeout) { "" }
                    if (ObdParser.parseMode01(r, "00", 4) != null) { ok = true; break }
                    delay(300)
                }
                if (ok) { conn.physical = true; conn.note("Dialogue direct avec le calculateur moteur (7E0/7E8)") }
                else { conn.send("ATSH7DF"); conn.send("ATAR"); conn.note("Calculateur moteur pas en 7E0 : dialogue général conservé") }
            }
        }
    }

    private suspend fun detectSupported(): List<Pid> {
        val codes = mutableSetOf<String>()
        var base = 0x00
        while (base <= 0x40) {
            // jusqu'à 3 essais : une réponse décalée ou vide ne doit pas réduire la liste à 3 mesures
            var set = emptySet<String>()
            for (attempt in 1..3) {
                set = runCatching { ObdParser.parseSupported(conn.send("01%02X".format(base), 5000), base) }.getOrDefault(emptySet())
                if (set.isNotEmpty()) break
                conn.settle()
            }
            codes += set
            if ("%02X".format(base + 0x20) !in set) break
            base += 0x20
        }
        val list = Pids.ALL.filter { it.code in codes }
        return list.ifEmpty { Pids.ALL.filter { it.code in setOf("0C", "0D", "05") } }
    }

    /** Associe la voiture branchée à un véhicule enregistré, ou en crée un. */
    private fun matchVehicle(vin: String) {
        val known = _vehicles.value.firstOrNull { it.vin == vin }
        val year = Calendar.getInstance().get(Calendar.YEAR)
        when {
            known != null -> if (known.id != activeVehicle().id) {
                selectVehicle(known.id)
                _live.update { it.copy(message = "Véhicule reconnu : ${known.name}") }
            }
            activeVehicle().vin == null -> {
                val brand = Vin.brand(vin)
                updateActive { v -> v.copy(vin = vin, name = if (v.name == "Ma voiture" && brand != null) brand else v.name) }
            }
            else -> {
                val v = addVehicle(Vin.brand(vin) ?: "Nouvelle voiture", Fuel.ESSENCE).copy(vin = vin)
                updateVehicle(v.id) { v }
                selectVehicle(v.id)
                _live.update { it.copy(message = "Nouveau véhicule détecté : ${Vin.describe(vin, year)}") }
            }
        }
    }

    private fun startSession() {
        sessionStart = System.currentTimeMillis()
        eco = EcoTracker()
        dist = 0.0; fuel = 0.0; maxSpeed = 0.0; speedSum = 0.0; speedN = 0
        hist.clear()
        series.clear(); lastSeriesT = 0L
        perfTimer.reset()
        knownDtcs = null; lastDtcCheck = System.currentTimeMillis()
        if (_settings.value.gps || _settings.value.gpsDistance || _settings.value.gpsCorrect || _settings.value.dangerZones) gps.start()
        if (_settings.value.dangerZones) scope.launch { DangerZones.load(ctx) }
        DangerZones.reset(); inZone = false; lastZoneCheck = 0L
        lastGpsKm = 0.0; cmpObdKm = 0.0; cmpGpsKm = 0.0
        activeAlertIds = emptySet()
    }

    // ---------------------------------------------------------------- batterie au démarrage

    private val voltWindow = ArrayDeque<Pair<Long, Double>>()
    private var crankWatch = false
    private var wasOff = false

    /**
     * Pendant que le moteur est coupé, on garde les tensions des 8 dernières secondes.
     * Quand le moteur démarre, la plus basse est la tension au démarreur : sous 9,6 V la batterie est fatiguée.
     */
    private fun watchCrank(values: Map<String, Double>, now: Long) {
        val rpm = values["rpm"] ?: 0.0
        values["volt"]?.let { voltWindow.addLast(now to it) }
        while (voltWindow.isNotEmpty() && now - voltWindow.first().first > 8000) voltWindow.removeFirst()
        if (rpm < 300) { wasOff = true; crankWatch = true; return }
        if (wasOff && rpm > 400) {
            wasOff = false
            val min = voltWindow.minOfOrNull { it.second }
            crankWatch = false
            if (min != null && min > 5 && min < 12.4) {
                val v = activeVehicle()
                updateVehicle(v.id) { it.copy(crankHistory = (it.crankHistory + min).takeLast(10), lastCrankAt = now) }
                val s = _settings.value
                val (msg, severe) = when {
                    min < 9.6 -> "Batterie faible au démarrage (${"%.1f".format(min)} V) : faites-la tester, elle risque de lâcher par temps froid." to true
                    min < 10.0 -> "Batterie un peu juste au démarrage (${"%.1f".format(min)} V). À surveiller." to false
                    else -> "Démarrage : ${"%.1f".format(min)} V, batterie en bonne forme." to false
                }
                _live.update { it.copy(message = msg) }
                if (min < 10.0) {
                    alerts.play(severe, s.sound && !s.voiceAlerts, s.vibration)
                    if (s.voiceAlerts) alerts.speak(if (severe) "Batterie faible au démarrage" else "Batterie un peu juste au démarrage")
                }
            }
        }
        if (rpm > 400) crankWatch = false
    }

    // ---------------------------------------------------------------- lecture en boucle

    private fun startPolling() {
        pollJob?.cancel()
        pidMiss.clear(); pidSkipped.clear()
        pollJob = scope.launch {
            var fails = 0
            var loop = 0
            var last = System.currentTimeMillis()
            while (isActive) {
                if (pollPaused) { delay(100); last = System.currentTimeMillis(); continue }
                val values = HashMap<String, Double>()
                val ok = try {
                    // toutes les ~10 s : si l'autoradio relance une recherche Bluetooth, on l'arrête (cause connue de décrochages)
                    if (loop % 100 == 0 && conn.stopDiscovery(adapter)) conn.note("Recherche Bluetooth de l'appareil en cours pendant la liaison : arrêtée")
                    readStandard(values)
                    // moteur arrêté, contact mis : tension lue à chaque tour pour saisir la chute au démarreur
                    val engineOff = (values["rpm"] ?: 0.0) < 300
                    if (loop % 10 == 0 || engineOff || crankWatch)
                        (try { conn.send("ATRV", 1500) } catch (e: ObdConnection.ObdTimeout) { null })?.let { r -> ObdParser.parseVoltage(r)?.let { values["volt"] = it } }
                    else _live.value.values["volt"]?.let { values["volt"] = it }
                    if (loop % 4 == 0) readCustom(values)
                    else activeVehicle().customPids.forEach { c -> _live.value.values[c.key]?.let { values[c.key] = it } }
                    true
                } catch (e: BoxSilent) { noteSilence(); onConnectionLost(); return@launch
                } catch (e: IOException) { false }

                if (!ok) {
                    fails++
                    val max = if (conn.legacy) 5 else 4
                    if (fails >= max) { conn.note("$max échecs de suite : connexion perdue"); noteSilence(); onConnectionLost(); return@launch }
                    delay(300)
                    continue
                }
                fails = 0
                loop++
                if (_settings.value.monitorDtc && System.currentTimeMillis() - lastDtcCheck > 60_000) {
                    lastDtcCheck = System.currentTimeMillis()
                    runCatching { monitorDtcs() }
                }
                val now = System.currentTimeMillis()
                val dt = (now - last) / 1000.0
                last = now
                watchCrank(values, now)
                compute(values, dt)
                delay(when (_settings.value.pollRate) {
                    PollRate.ECO -> 250L
                    PollRate.FAST -> 0L
                    PollRate.NORMAL -> if (_live.value.multi) 30L else 60L
                })
            }
        }
    }

    // Mesures qui ne répondent pas : mises de côté pour ce trajet après 3 absences de suite
    private val pidMiss = HashMap<String, Int>()
    private val pidSkipped = HashSet<String>()
    private var pollLoop = 0

    /** Mesures lues à chaque tour : celles affichées, celles du mode conduite et celles qui servent aux calculs. */
    private fun fastKeys(): Set<String> {
        val v = activeVehicle()
        return v.layout.mapNotNull { it.metric }.toSet() + _settings.value.driveMetrics +
            setOf("rpm", "speed", "maf", "map", "iat", "load", "fuelrate", "fcut", "stft", "ltft", "lambda", "fuel", "cool")
    }

    /**
     * Lecture des mesures standard.
     * - les mesures utiles sont lues à chaque tour, les autres un tour sur 8 : le boîtier n'est plus saturé ;
     * - une mesure qui ne répond pas à temps ne coupe plus la connexion : elle est sautée, puis mise de côté ;
     * - la connexion n'est déclarée perdue que si plus rien ne répond.
     */
    private suspend fun readStandard(values: HashMap<String, Double>) {
        if (conn.legacy) { readStandardLegacy(values); return }
        pollLoop++
        val fast = fastKeys()
        val pids = _live.value.supported.filter { it.code !in pidSkipped && (it.def.key in fast || pollLoop % 8 == 0) }
        if (pids.isEmpty()) return
        var answered = 0
        var timeouts = 0
        if (_live.value.multi) {
            for (chunk in pids.chunked(6)) {
                val raw = try { conn.send("01" + chunk.joinToString("") { it.code }, 3000) }
                    catch (e: ObdConnection.ObdTimeout) { timeouts++; multiFails++; if (multiFails >= 3) _live.update { it.copy(multi = false) }; continue }
                val parsed = ObdParser.parseMulti(raw, chunk)
                if (parsed.isNotEmpty()) answered++
                for (p in chunk) parsed[p.code]?.let { values[p.def.key] = p.decode(it) }
                if (parsed.size * 2 < chunk.size) {
                    multiFails++
                    if (multiFails >= 3) { _live.update { it.copy(multi = false) }; conn.note("Lecture groupée abandonnée : réponses incomplètes") }
                }
            }
        } else {
            var inARow = 0
            for (p in pids) {
                val raw = try { conn.send("01${p.code}", 2500).also { inARow = 0 } } catch (e: ObdConnection.ObdTimeout) {
                    timeouts++; inARow++; miss(p.code)
                    // deux silences de suite : on vérifie tout de suite que le boîtier répond encore
                    if (inARow >= 2) { checkBox(); inARow = 0 }
                    continue
                }
                val bytes = ObdParser.parseMode01(raw, p.code, p.bytes)
                if (bytes != null) { values[p.def.key] = p.decode(bytes); pidMiss.remove(p.code); answered++ } else miss(p.code)
            }
        }
        // Plus rien ne répond (et ce n'est pas juste « moteur coupé ») : on vérifie que le boîtier est encore là
        if (answered == 0 && timeouts > 0) checkBox()
    }

    /** Le boîtier lui-même est muet (liaison Bluetooth tombée) : reconnexion immédiate, sans attendre. */
    class BoxSilent : IOException("Le boîtier ne répond plus")

    // Liaison Bluetooth adaptative : après 2 silences du boîtier en moins de 3 minutes,
    // on essaie une autre façon d'ouvrir la liaison (et on garde celle qui tient)
    private val btPrefs by lazy { ctx.getSharedPreferences("bt", android.content.Context.MODE_PRIVATE) }
    private var btMode: Int
        get() = btPrefs.getInt("mode", 0)
        set(v) { btPrefs.edit().putInt("mode", v).apply() }
    private val silences = ArrayDeque<Long>()

    private fun noteSilence() {
        val now = System.currentTimeMillis()
        silences.addLast(now)
        while (silences.isNotEmpty() && now - silences.first() > 180_000) silences.removeFirst()
        if (silences.size >= 2 && Links.isBluetooth(_settings.value.lastDevice ?: "")) {
            silences.clear()
            btMode = (btMode + 1) % 3
            conn.note("Coupures répétées : prochaine liaison Bluetooth en mode « ${conn.BT_MODES[btMode]} »")
        }
    }

    /** Le boîtier n'est déclaré perdu qu'après 2 vérifications muettes de suite (plus de 6 s de silence). */
    private suspend fun checkBox() {
        for (i in 1..2) {
            try { conn.send("ATRV", 3000); return } catch (e: ObdConnection.ObdTimeout) { conn.note("Boîtier muet (vérification $i/2)") }
        }
        conn.note("Le boîtier ne répond plus du tout : liaison Bluetooth probablement coupée, reconnexion")
        throw BoxSilent()
    }

    /** Lecture comme en 2.0.35 : toutes les mesures à chaque tour, 4 s d'attente, une absence de réponse = tour raté. */
    private suspend fun readStandardLegacy(values: HashMap<String, Double>) {
        val pids = _live.value.supported
        if (_live.value.multi) {
            for (chunk in pids.chunked(6)) {
                val parsed = ObdParser.parseMulti(conn.send("01" + chunk.joinToString("") { it.code }), chunk)
                for (p in chunk) parsed[p.code]?.let { values[p.def.key] = p.decode(it) }
                if (parsed.size * 2 < chunk.size) { multiFails++; if (multiFails >= 3) _live.update { it.copy(multi = false) } }
            }
        } else for (p in pids) ObdParser.parseMode01(conn.send("01${p.code}"), p.code, p.bytes)?.let { values[p.def.key] = p.decode(it) }
    }

    private fun miss(code: String) {
        val n = (pidMiss[code] ?: 0) + 1
        pidMiss[code] = n
        // on ne met jamais de côté le régime ni la vitesse
        if (n >= 3 && code != "0C" && code != "0D") { pidSkipped += code; conn.note("Mesure 01$code mise de côté : pas de réponse 3 fois de suite") }
    }

    private suspend fun readCustom(values: HashMap<String, Double>) {
        for (c in activeVehicle().customPids) {
            val expr = Expr.compile(c.formula) ?: continue
            if (c.header.isNotBlank()) conn.send("ATSH${c.header.trim()}")
            val bytes = try { ObdParser.parseGeneric(conn.send(c.command.replace(" ", ""), 2000), c.command) }
                catch (e: ObdConnection.ObdTimeout) { null }
            if (c.header.isNotBlank()) conn.resetHeader()
            if (bytes != null) values[c.key] = runCatching { expr.eval(bytes) }.getOrDefault(Double.NaN)
        }
    }

    // ---------------------------------------------------------------- rapports de boîte

    // regroupement des rapports km/h pour 1 000 tr/min : centre, nombre d'échantillons
    private val gearClusters = mutableListOf<DoubleArray>()
    private var prevSpeedForGear: Double? = null
    private var gearSaveTimer = 0.0

    /**
     * Apprend les rapports : en régime stable (ni embrayage ni accélération forte), le rapport vitesse / régime
     * est constant pour une vitesse donnée. On regroupe les valeurs proches (à 6 % près) ; les groupes
     * assez fournis deviennent les rapports, triés du 1er au 5e (ou 6e).
     */
    private fun learnGear(v: Vehicle, speed: Double, rpm: Double, dt: Double) {
        val prev = prevSpeedForGear; prevSpeedForGear = speed
        if (speed < 8 || rpm < 1000 || dt !in 0.05..2.0 || prev == null) return
        if (kotlin.math.abs(speed - prev) / dt > 4.0) return          // changement de vitesse ou embrayage en cours
        val r = speed / rpm * 1000
        if (gearClusters.isEmpty()) v.gearRatios.forEach { gearClusters += doubleArrayOf(it, 50.0) }
        val near = gearClusters.minByOrNull { kotlin.math.abs(it[0] - r) / it[0] }
        if (near != null && kotlin.math.abs(near[0] - r) / near[0] < 0.06) {
            near[1] += 1.0
            near[0] += (r - near[0]) / near[1].coerceAtMost(200.0)
        } else if (gearClusters.size < 8) gearClusters += doubleArrayOf(r, 1.0)
        gearSaveTimer += dt
        if (gearSaveTimer > 30) {
            gearSaveTimer = 0.0
            val learned = gearClusters.filter { it[1] >= 25 }.map { it[0] }.sorted().take(6)
            if (learned.size >= 2 && learned != v.gearRatios) updateVehicle(v.id) { it.copy(gearRatios = learned) }
        }
    }

    /** Rapport engagé (1, 2, 3…) si le rapport vitesse / régime correspond à un rapport appris (à 7 % près). */
    private fun gearOf(v: Vehicle, speed: Double, rpm: Double): Int? {
        if (speed < 3 || rpm < 600 || v.gearRatios.size < 2) return null
        val r = speed / rpm * 1000
        val i = v.gearRatios.indices.minByOrNull { kotlin.math.abs(v.gearRatios[it] - r) / v.gearRatios[it] } ?: return null
        return if (kotlin.math.abs(v.gearRatios[i] - r) / v.gearRatios[i] < 0.07) i + 1 else null
    }

    /** Oublier les rapports appris (après un changement de véhicule ou de boîte). */
    fun resetGears() { gearClusters.clear(); updateActive { it.copy(gearRatios = emptyList()) } }

    private var lphSmooth: Double? = null
    private var consSource = ""

    /**
     * Débit de carburant en L/h.
     * 1. La voiture le donne (PID 5E) : rare avant 2015.
     * 2. Sinon : débit d'air (débitmètre, ou calcul pression × régime × cylindrée) ÷ rapport air/carburant.
     *    - essence : rapport 14,7 corrigé par la richesse et les corrections d'injection ; 0 en coupure d'injection (décélération) ;
     *    - diesel : le moteur tourne avec un grand excès d'air (rapport 20 à 80 selon la charge), d'où le calcul selon la charge.
     *      Utiliser 14,5 comme avant surestimait la consommation de 2 à 4 fois au ralenti.
     * 3. Le tout est recalé sur vos pleins (facteur du véhicule).
     */
    private fun estimateLph(values: Map<String, Double>, v: Vehicle): Double? {
        values["fuelrate"]?.let { consNote("débit carburant de la voiture"); return it * v.consFactor }
        if (values["fcut"] == 1.0) return 0.0
        val rpm = values["rpm"]
        if (rpm != null && rpm < 300) return 0.0
        val maf = values["maf"]?.also { consNote("débitmètre d'air") } ?: run {
            val map = values["map"] ?: return null
            if (rpm == null) return null
            val tK = (values["iat"] ?: 25.0) + 273.15
            val ve = if (v.fuel == Fuel.DIESEL) 0.9 else 0.85
            consNote("pression d'admission × régime × cylindrée")
            // g/s = P·V·(tr/min ÷ 120)·rendement ÷ (R·T)
            map * 1000 * (v.engineL / 1000) * (rpm / 120) * ve / (287.05 * tK) * 1000
        }
        val lambda = values["lambda"]
        val afr: Double
        var trim = 1.0
        if (v.fuel == Fuel.DIESEL) {
            afr = if (lambda != null && lambda in 1.0..1.95) 14.5 * lambda
            else 18.0 / ((values["load"] ?: 25.0) / 100).coerceIn(0.22, 1.0)
        } else {
            afr = if (lambda != null && lambda in 0.6..1.6) 14.7 * lambda else 14.7
            trim = (1 + ((values["stft"] ?: 0.0) + (values["ltft"] ?: 0.0)) / 100).coerceIn(0.75, 1.25)
        }
        return maf / afr * trim * 3600 / v.fuel.densityGPerL * v.consFactor
    }

    private fun consNote(src: String) {
        if (src != consSource) { consSource = src; conn.note("Consommation calculée par : $src") }
    }

    // Comparaison compteur / GPS pendant le trajet
    private var lastGpsKm = 0.0
    private var cmpObdKm = 0.0
    private var cmpGpsKm = 0.0

    private fun compute(values: HashMap<String, Double>, dt: Double) {
        val v = activeVehicle()
        val s0 = _settings.value
        val gpsV = gps.speedKmh()
        if (gpsV != null) values["gpsspeed"] = gpsV
        // Mesure de l'écart compteur / GPS : en ligne droite et à vitesse stable, au-dessus de 40 km/h
        val obdV = values["speed"]
        if (obdV != null && gpsV != null && obdV > 40 && gpsV > 35 && dt in 0.0..2.0 && kotlin.math.abs(obdV - gpsV) / obdV < 0.15) {
            cmpObdKm += obdV * dt / 3600; cmpGpsKm += gpsV * dt / 3600
        }
        // Vitesse corrigée par le GPS si l'option est active et la mesure fiable
        if (s0.gpsCorrect && v.speedSampleKm >= 10 && obdV != null) values["speed"] = obdV * v.speedFactor
        val speed = values["speed"]
        val rpm = values["rpm"]
        // Pression de turbo : pression d'admission moins pression atmosphérique (en bar)
        values["map"]?.let { map -> values["boost"] = ((map - (values["baro"] ?: 101.3)) / 100.0).coerceAtLeast(-0.2) }
        // Rapport engagé : appris sur les premiers kilomètres, puis reconnu en direct
        if (speed != null && rpm != null) {
            learnGear(v, speed, rpm, dt)
            gearOf(v, speed, rpm)?.let { values["gear"] = it.toDouble() }
        }
        val lph = estimateLph(values, v)
        var unit = "L/100km"
        if (lph != null) {
            // l'affichage est lissé sur ~3 s pour ne pas sauter à chaque lecture ; le cumul utilise la valeur brute
            val a = if (dt in 0.0..5.0) dt / (3.0 + dt) else 1.0
            lphSmooth = lphSmooth?.let { it + a * (lph - it) } ?: lph
            val shown = lphSmooth!!
            if (speed != null && speed > 10) values["cons"] = shown / speed * 100 else { values["cons"] = shown; unit = "L/h" }
        } else lphSmooth = null
        // Carburant restant : jauge de la voiture si elle la transmet (rare avant 2010, ex. Corsa D),
        // sinon calcul depuis le dernier plein complet ou le dernier niveau indiqué à la main
        val left = values["fuel"]?.let { it / 100 * v.tankL } ?: estimatedLeft(v)
        if (left != null) {
            values["left"] = left
            val avg = (if (dist > 5) fuel / dist * 100 else null) ?: v.lastAvgCons ?: 6.5
            values["range"] = left / avg * 100
        }
        // Distance : vitesse de la voiture ; sinon (vitesse absente, coupure, reconnexion) distance mesurée par le GPS
        val gk = gps.totalKm
        val gpsDelta = (gk - lastGpsKm).coerceAtLeast(0.0)
        lastGpsKm = gk
        if (s0.gpsDistance && gpsDelta <= 50.0) {
            val obdInc = if (speed != null && dt in 0.0..5.0) speed * dt / 3600 else 0.0
            // vitesse absente : tout vient du GPS ; après une coupure (reconnexion), on rattrape les km faits pendant le trou
            if (speed == null || dt !in 0.0..5.0) dist += gpsDelta
            else if (gpsDelta - obdInc > 0.2) dist += gpsDelta - obdInc
        }
        if (dt in 0.0..5.0) {
            if (speed != null) {
                dist += speed * dt / 3600
                maxSpeed = max(maxSpeed, speed)
                speedSum += speed; speedN++
            }
            if (lph != null) fuel += lph * dt / 3600
            val a0 = eco.harshAcc; val b0 = eco.harshBrake
            eco.add(speed, rpm, dt)
            pendingEvent = if (eco.harshAcc > a0) 1 else if (eco.harshBrake > b0) -1 else pendingEvent
        }
        for ((k, x) in values) {
            val q = hist.getOrPut(k) { ArrayDeque() }
            q.addLast(x.toFloat())
            if (q.size > HISTORY) q.removeFirst()
        }
        val stats = LiveStats(
            durationS = (System.currentTimeMillis() - sessionStart) / 1000,
            distanceKm = dist, fuelL = fuel, maxSpeed = maxSpeed,
            avgSpeed = if (speedN > 0) speedSum / speedN else 0.0,
            eco = eco.score, harshAcc = eco.harshAcc, harshBrake = eco.harshBrake, tips = eco.tips(),
        )
        val al = buildAlerts(values)
        val newOnes = al.filter { it.id !in activeAlertIds && it.level != Level.OK }
        if (newOnes.isNotEmpty()) {
            val s = _settings.value
            alerts.play(newOnes.any { it.level == Level.BAD }, s.sound && !s.voiceAlerts, s.vibration)
            if (s.voiceAlerts) alerts.speak(newOnes.sortedBy { if (it.level == Level.BAD) 0 else 1 }.first().text)
        }
        activeAlertIds = al.map { it.id }.toSet()

        val now = System.currentTimeMillis()
        if (speed != null) {
            for ((kind, sec) in perfTimer.feed(now, speed)) {
                val run = PerfRun(vehicleId = v.id, date = now, kind = kind, seconds = sec, endSpeed = speed)
                _perf.value = listOf(run) + _perf.value
                store.savePerf(_perf.value)
                _live.update { it.copy(message = "Chrono $kind : ${"%.2f".format(sec)} s") }
            }
            _perfStatus.value = perfTimer.status
        }
        if (now - lastSeriesT >= 1000 && series.size < 4 * 3600) {
            lastSeriesT = now
            val loc = gps.fresh()
            series += TripPoint(now - sessionStart, speed, rpm, values["cool"], values["cons"]?.takeIf { unit == "L/100km" }, values["thr"],
                loc?.latitude, loc?.longitude, pendingEvent)
            pendingEvent = 0
        }
        if (now - lastWidgetT > 30_000) { lastWidgetT = now; CarWidget.refresh(ctx) }
        logger?.log(_live.value.supported.map { values[it.def.key] } + listOf(values["volt"], values["cons"]) +
            v.customPids.map { values[it.key] })

        _live.update {
            it.copy(
                values = values, consUnit = unit, stats = stats, alerts = al,
                history = hist.mapValues { e -> e.value.toList() },
            )
        }
    }

    private var inZone = false
    private var lastZoneCheck = 0L

    private fun buildAlerts(values: Map<String, Double>): List<Alert> {
        val s = _settings.value
        val a = mutableListOf<Alert>()
        if (s.dangerZones) {
            val now = System.currentTimeMillis()
            if (now - lastZoneCheck > 2000) { lastZoneCheck = now; inZone = gps.fresh()?.let { DangerZones.check(it) } ?: false }
            if (inZone) a += Alert("zone", Level.WARN, "Zone de danger : respectez les limitations, soyez prudent.")
        } else inZone = false
        val rpm = values["rpm"] ?: 0.0
        values["cool"]?.let { if (it >= s.tempLimit) a += Alert("cool", Level.BAD, "Le moteur chauffe trop (${it.toInt()} °C). Arrêtez-vous dès que possible et laissez refroidir avant de vérifier le liquide.") }
        values["oil"]?.let { if (it >= 135) a += Alert("oil", Level.BAD, "Huile moteur très chaude (${it.toInt()} °C). Levez le pied.") }
        values["volt"]?.let {
            if (rpm > 500 && it < 13.0) a += Alert("alt", Level.BAD, "L'alternateur ne recharge plus la batterie (${"%.1f".format(it)} V). Faites-le contrôler rapidement.")
            if (rpm < 100 && it < s.batteryLowV) a += Alert("bat", Level.WARN, "Batterie faible (${"%.1f".format(it)} V) : le prochain démarrage pourrait être difficile.")
        }
        if (rpm >= s.rpmLimit) a += Alert("rpm", Level.WARN, "Régime élevé : passez la vitesse supérieure.")
        val range = values["range"]
        if ((range != null && range <= s.reserveKm) || (range == null && (values["fuel"] ?: 100.0) <= 12))
            a += Alert("fuel", Level.WARN, "Vous êtes sur la réserve${range?.let { " (≈ ${it.toInt()} km)" } ?: ""} : pensez à faire le plein.")
        if (s.speedAlertKmh > 0) values["speed"]?.let { if (it >= s.speedAlertKmh) a += Alert("speed", Level.WARN, "Vitesse : ${it.toInt()} km/h (alerte à ${s.speedAlertKmh}).") }
        if (s.breakMinutes > 0 && sessionStart > 0 && System.currentTimeMillis() - sessionStart > s.breakMinutes * 60_000L)
            a += Alert("break", Level.WARN, "Vous roulez depuis plus de ${s.breakMinutes / 60} h${if (s.breakMinutes % 60 > 0) " ${s.breakMinutes % 60}" else ""} : pensez à faire une pause.")
        _live.value.dtcs?.let { if (it.isNotEmpty()) a += Alert("dtc", Level.WARN, "${it.size} code(s) défaut enregistré(s). Voir le diagnostic.") }
        val now = System.currentTimeMillis()
        val v = activeVehicle()
        val odo = v.odometerKm + dist
        v.maintenance.map { it.status(odo, now) }.firstOrNull { it.overdue }?.let {
            a += Alert("maint", Level.WARN, "Entretien à faire : ${it.item.name}.")
        }
        return a
    }

    // ---------------------------------------------------------------- reconnexion

    private fun onConnectionLost() {
        conn.close()
        if (!_settings.value.autoReconnect) { endSession("La connexion avec le boîtier a été perdue."); return }
        val address = _settings.value.lastDevice ?: run { endSession("Connexion perdue."); return }
        _live.update { it.copy(conn = Conn.RECONNECTING, message = null) }
        pollJob = scope.launch {
            val until = System.currentTimeMillis() + 5 * 60_000
            var wait = 500L
            while (isActive && System.currentTimeMillis() < until) {
                delay(wait)
                try {
                    openAndInit(address)
                    _live.update { it.copy(conn = Conn.CONNECTED, message = "Reconnecté au boîtier") }
                    startPolling()
                    return@launch
                } catch (e: Exception) {
                    conn.close()
                    wait = if (wait < 2000) 2000 else minOf(wait * 2, 15_000)
                }
            }
            endSession("Le boîtier ne répond plus. Trajet enregistré dans l'historique.")
        }
    }

    // ---------------------------------------------------------------- fin de trajet

    fun disconnect() {
        pollJob?.cancel()
        sessionJob?.cancel()
        conn.close()
        endSession(null)
    }

    /** Positions GPS d'un trajet (latitude, longitude), dans l'ordre. */
    fun tripPoints(t: TripSummary): List<Pair<Double, Double>> =
        store.loadSeries(t.id).mapNotNull { p -> p.lat?.let { la -> p.lon?.let { lo -> la to lo } } }

    /** Cherche en arrière-plan les villes de départ et d'arrivée d'un trajet (internet nécessaire). */
    private fun nameTrip(id: String, pts: List<TripPoint>) { scope.launch { resolvePlaces(id, pts) } }

    private suspend fun resolvePlaces(id: String, pts: List<TripPoint>): Boolean {
        val gps = pts.mapNotNull { p -> p.lat?.let { la -> p.lon?.let { lo -> la to lo } } }
        if (gps.isEmpty()) return false
        val a = Places.city(ctx, gps.first().first, gps.first().second) ?: return false
        delay(1100)   // OpenStreetMap demande au plus une recherche par seconde
        val b = Places.city(ctx, gps.last().first, gps.last().second)
        _trips.value = _trips.value.map { if (it.id == id) it.copy(fromPlace = a, toPlace = b ?: a) else it }
        store.saveTrips(_trips.value)
        return true
    }

    private var naming = false

    /** Complète les villes des trajets récents qui n'en ont pas encore (ex. pas de réseau au moment du trajet). */
    fun nameRecentTrips() {
        if (naming) return
        naming = true
        scope.launch {
            try {
                for (t in _trips.value.filter { it.fromPlace == null }.take(15)) {
                    val pts = withContext(Dispatchers.IO) { store.loadSeries(t.id) }
                    if (resolvePlaces(t.id, pts)) delay(1100)
                }
            } finally { naming = false }
        }
    }

    private var sessionDist = 0.0
    private var sessionFuel = 0.0
    private var sessionEco = 100
    private var sessionTips = emptyList<String>()

    private fun endSession(msg: String?) {
        pollJob?.cancel()
        sessionDist = dist; sessionFuel = fuel; sessionEco = eco.score; sessionTips = eco.tips()
        prevSpeedForGear = null
        val file = logger?.stop()
        logger = null
        if (dist > 0.3 && sessionStart > 0) {
            val t = TripSummary(
                vehicleId = activeVehicle().id, start = sessionStart,
                durationS = (System.currentTimeMillis() - sessionStart) / 1000,
                distanceKm = dist, fuelL = fuel, maxSpeed = maxSpeed,
                avgSpeed = if (speedN > 0) speedSum / speedN else 0.0,
                ecoScore = eco.score, harshAcc = eco.harshAcc, harshBrake = eco.harshBrake, file = file ?: recFile,
            )
            _trips.value = listOf(t) + _trips.value
            store.saveTrips(_trips.value)
            if (series.isNotEmpty()) store.saveSeries(t.id, series.toList())
            nameTrip(t.id, series.toList())
            val d = dist
            updateActive { it.copy(odometerKm = it.odometerKm + d) }
            // Écart compteur / GPS : moyenne pondérée avec les mesures des trajets précédents
            if (cmpObdKm >= 2) {
                val o = cmpObdKm; val g = cmpGpsKm
                updateActive {
                    val prevKm = it.speedSampleKm.coerceAtMost(200.0)
                    val f = ((it.speedFactor * prevKm + (g / o) * o) / (prevKm + o)).coerceIn(0.85, 1.10)
                    it.copy(speedFactor = f, speedSampleKm = it.speedSampleKm + o)
                }
            }
        }
        val lastFuel = _live.value.values["fuel"]
        val avg = _live.value.stats.avgConsumption
        if (lastFuel != null || avg != null) updateActive { it.copy(lastFuelPct = lastFuel ?: it.lastFuelPct, lastAvgCons = avg ?: it.lastAvgCons) }
        // Retrouver ma voiture : dernière position GPS connue
        (gps.last ?: series.lastOrNull { it.lat != null }?.let { p -> android.location.Location("").apply { latitude = p.lat!!; longitude = p.lon!!; time = System.currentTimeMillis() } })
            ?.let { loc -> updateActive { it.copy(parkLat = loc.latitude, parkLon = loc.longitude, parkAt = System.currentTimeMillis()) } }
        series.clear()
        gps.stop()
        recFile = null
        sessionStart = 0
        dist = 0.0
        // Bilan du trajet : distance, carburant, note d'éco-conduite et le conseil principal
        val summary = if (sessionDist > 0.3) {
            val tip = sessionTips.firstOrNull()
            "Trajet : ${fmt(sessionDist)} km · ${"%.1f".format(sessionFuel)} L · éco-conduite $sessionEco/100" + (tip?.let { "\n$it" } ?: "")
        } else null
        _live.value = Live(conn = Conn.IDLE, message = listOfNotNull(msg, summary).joinToString("\n").ifBlank { null })
        _ecu.value = EcuScan()
        pollPaused = false
        _battery.value = BatteryTest()
        ObdService.stop(ctx)
        CarWidget.refresh(ctx)
    }

    // ---------------------------------------------------------------- enregistrement CSV

    fun toggleRecording() {
        val l = logger
        if (l != null) {
            recFile = l.stop()
            logger = null
            _live.update { it.copy(recording = false, message = "Détail du trajet enregistré (CSV)") }
        } else {
            val nl = DataLogger(store.logsDir)
            nl.start(_live.value.supported.map { "${it.def.name} (${it.def.unit})" } + listOf("Batterie (V)", "Consommation") +
                activeVehicle().customPids.map { "${it.name} (${it.unit})" })
            logger = nl
            _live.update { it.copy(recording = true, recStart = System.currentTimeMillis()) }
        }
    }

    // ---------------------------------------------------------------- diagnostic

    fun readDiagnostics() {
        if (_live.value.conn != Conn.CONNECTED || _live.value.diagLoading) return
        scope.launch {
            _live.update { it.copy(diagLoading = true) }
            try {
                val dtcs = ObdParser.parseDtcs(askDtc("03"), conn.isCan, "43").map { ObdParser.explain(it) }
                val pending = runCatching { ObdParser.parseDtcs(askDtc("07"), conn.isCan, "47") }.getOrDefault(emptyList())
                    .filterNot { p -> dtcs.any { it.code == p } }.map { ObdParser.explain(it) }
                val ready = runCatching { ObdParser.parseReadiness(conn.send("0101", 4000)) }.getOrNull()
                var freeze: FreezeFrame? = null
                if (dtcs.isNotEmpty()) {
                    val fd = ObdParser.parseFreezeDtc(conn.send("020200", 4000))
                    val vals = mutableListOf<Pair<String, String>>()
                    for (code in listOf("0D", "0C", "05", "04", "0B", "11", "0F")) {
                        val p = Pids.byCode(code) ?: continue
                        ObdParser.parseMode02(conn.send("02${code}00", 3000), code, p.bytes)?.let {
                            vals += p.def.name to "${fmt(p.decode(it))} ${p.def.unit}"
                        }
                    }
                    if (fd != null || vals.isNotEmpty()) freeze = FreezeFrame(fd, vals)
                }
                _live.update { it.copy(diagLoading = false, dtcs = dtcs, pending = pending, readiness = ready, freeze = freeze) }
            } catch (e: IOException) {
                _live.update { it.copy(diagLoading = false, message = "Lecture impossible : ${e.message}") }
            }
        }
    }

    /**
     * Demande des codes défaut (mode 03 ou 07), avec la lecture en continu mise en pause.
     * Si personne ne répond à la demande générale, on interroge directement le calculateur moteur (7E0).
     */
    private suspend fun askDtc(mode: String): String {
        val was = pollPaused
        pollPaused = true
        try {
            delay(150)
            // Diagnostic rapide : on interroge toute la voiture (7DF), pour voir aussi les codes de la boîte automatique
            if (conn.physical) { conn.send("ATSH7DF"); conn.send("ATAR") }
            try {
                repeat(2) { try { return conn.send(mode, 6000) } catch (e: ObdConnection.ObdTimeout) { } }
            } finally { if (conn.physical) runCatching { conn.resetHeader() } }
            if (conn.isCan) {
                conn.note("Pas de réponse à $mode : demande directe au calculateur moteur")
                try { conn.send("ATSH7E0"); return conn.send(mode, 6000) } finally { runCatching { conn.resetHeader() } }
            }
            throw IOException("Le calculateur moteur ne répond pas à la demande des codes défaut ($mode)")
        } finally { pollPaused = was }
    }

    fun clearDtcs() {
        scope.launch {
            try {
                conn.send("04", 8000)
                _live.update { it.copy(dtcs = emptyList(), pending = emptyList(), freeze = null, message = "Codes effacés, le voyant moteur va s'éteindre") }
                readDiagnostics()
            } catch (e: IOException) {
                _live.update { it.copy(message = "Effacement impossible : ${e.message}") }
            }
        }
    }

    // ---------------------------------------------------------------- tests par calculateur

    /** Met la lecture en direct en pause, exécute [block], puis remet l'adaptateur dans son état normal. */
    private suspend fun <T> exclusive(block: suspend () -> T): T {
        pollPaused = true
        delay(300)
        try {
            return block()
        } finally {
            restoreAdapter()
            pollPaused = false
        }
    }

    private suspend fun restoreAdapter() {
        runCatching {
            conn.send("ATD", 3000)
            for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) conn.send(c)
            conn.send(if (conn.isStn) "ATAT2" else "ATAT1")
            conn.send("0100", 8000)
        }
    }

    /**
     * Recherche des calculateurs.
     * Rapide : les calculateurs OBD (moteur, boîte…) qui répondent à la requête générale.
     * Approfondie : interroge chaque adresse CAN 0x600–0x7F7 avec « Tester Present » (3E 00).
     */
    fun scanEcus(deep: Boolean, opt: ScanOptions = ScanOptions()) {
        if (_live.value.conn != Conn.CONNECTED || _ecu.value.running) return
        scope.launch {
            _ecu.update { it.copy(running = true, progress = 0f, label = "Recherche des calculateurs OBD…") }
            val found = LinkedHashMap<String, Ecu>()
            _ecu.value.ecus.forEach { found[it.net.name + it.resp] = it }
            fun add(e: Ecu) { val k = e.net.name + e.resp; if (!found.containsKey(k)) found[k] = e }
            fun progress(p: Float, label: String) = _ecu.update { it.copy(progress = p, label = label) }
            try {
                exclusive {
                    if (conn.isCan) {
                        conn.send("ATH1")
                        for (r in EcuParser.responders(conn.send("0100", 5000))) {
                            val id = r.toInt(16)
                            if (id in 0x7E8..0x7EF) add(Ecu("%03X".format(id - 8), r, EcuParser.obdName(r), obd = true))
                        }
                        if (deep && opt.hs11) {
                            conn.send("ATCF000"); conn.send("ATCM000"); conn.send("ATST19")
                            val ids = EcuParser.SCAN_IDS
                            ids.forEachIndexed { i, id ->
                                if (i % 8 == 0) progress(i.toFloat() / ids.size * part(opt, 0), "CAN principal · adresse %03X…".format(id))
                                conn.send("ATSH%03X".format(id))
                                val raw = try { conn.send("3E00", 600) } catch (e: IOException) { "" }
                                EcuParser.testerPresentReplies(raw).firstOrNull { it != "%03X".format(id) }?.let {
                                    add(Ecu("%03X".format(id), it, "Calculateur $it", obd = false))
                                }
                            }
                        }
                        if (deep && opt.hs29) {
                            // Adressage normalisé 29 bits : requête 18 DA tt F1, réponse 18 DA F1 tt
                            conn.send("ATSP7"); conn.send("ATH1"); conn.send("ATCP18")
                            conn.send("ATCF18DAF100"); conn.send("ATCM1FFFFF00"); conn.send("ATST19")
                            for (t in 0..0xFF) {
                                if (t == 0xF1) continue
                                if (t % 8 == 0) progress(part(opt, 1) + t / 255f * part(opt, 2), "Adresses 29 bits · %02X…".format(t))
                                conn.send("ATSHDA%02XF1".format(t))
                                val raw = try { conn.send("3E00", 600) } catch (e: IOException) { "" }
                                EcuParser.testerPresentReplies29(raw).firstOrNull()?.let { tt ->
                                    add(Ecu("18DA${tt}F1", "18DAF1$tt", "Calculateur 29 bits $tt", obd = false, net = Net.HS29))
                                }
                            }
                        }
                        if (deep && opt.ms && conn.isStn) {
                            // OBDLink : réseau confort sur les broches 3-11 (Opel ≈ 95 kbit/s, Ford 125 kbit/s)
                            conn.send("STP53"); conn.send("STPBR${opt.msBaud}"); conn.send("ATH1")
                            conn.send("ATCF000"); conn.send("ATCM000"); conn.send("ATST19")
                            val ids = EcuParser.SCAN_IDS
                            ids.forEachIndexed { i, id ->
                                if (i % 8 == 0) progress(part(opt, 3) + i.toFloat() / ids.size * part(opt, 4), "Réseau confort · adresse %03X…".format(id))
                                conn.send("ATSH%03X".format(id))
                                val raw = try { conn.send("3E00", 600) } catch (e: IOException) { "" }
                                EcuParser.testerPresentReplies(raw).firstOrNull { it != "%03X".format(id) }?.let {
                                    add(Ecu("%03X".format(id), it, "Confort $it", obd = false, net = Net.MS))
                                }
                            }
                        }
                    } else {
                        add(Ecu("", "MOT", "Moteur (ECM)", obd = true))
                    }
                }
                val list = found.values.toList()
                _ecu.update {
                    it.copy(running = false, progress = 1f, ecus = list, deepDone = it.deepDone || deep,
                        label = "${list.size} calculateur${if (list.size > 1) "s" else ""} trouvé${if (list.size > 1) "s" else ""}" +
                            if (deep && opt.ms && !conn.isStn) " · réseau confort non disponible (boîtier sans puce STN)" else "")
                }
            } catch (e: IOException) {
                _ecu.update { it.copy(running = false, label = "Recherche interrompue : ${e.message}") }
            }
        }
    }

    /** Répartition de la barre de progression entre les réseaux choisis. idx pair = début, impair = largeur. */
    private fun part(o: ScanOptions, idx: Int): Float {
        val w = listOf(if (o.hs11) 2f else 0f, if (o.hs29) 1f else 0f, if (o.ms) 2f else 0f)
        val tot = w.sum().coerceAtLeast(1f)
        return when (idx) {
            0 -> w[0] / tot
            1 -> w[0] / tot
            2 -> w[1] / tot
            3 -> (w[0] + w[1]) / tot
            else -> w[2] / tot
        }
    }

    fun testEcu(e: Ecu) {
        if (_live.value.conn != Conn.CONNECTED || _ecu.value.testing != null) return
        scope.launch { runEcuTest(e) }
    }

    fun testAllEcus() {
        if (_live.value.conn != Conn.CONNECTED || _ecu.value.testing != null) return
        scope.launch { for (e in _ecu.value.ecus) runEcuTest(e) }
    }

    private suspend fun runEcuTest(e: Ecu) {
        _ecu.update { it.copy(testing = e.net.name + e.resp) }
        val res = try {
            exclusive { if (e.obd) testObdEcu(e) else testDiagEcu(e) }
        } catch (ex: IOException) {
            EcuResult(e, false, 0, "—", null, emptyList(), note = "Erreur : ${ex.message}")
        }
        _ecu.update { s ->
            val ecus = if (res.name != null && !e.obd) s.ecus.map { if (it.resp == e.resp && it.net == e.net) it.copy(name = res.name) else it } else s.ecus
            s.copy(testing = null, results = s.results + (e.net.name + e.resp to res), ecus = ecus)
        }
    }

    private suspend fun target(e: Ecu) {
        when (e.net) {
            Net.HS -> if (e.req.isNotEmpty()) { conn.send("ATSH${e.req}"); conn.send("ATCRA${e.resp}") }
            Net.HS29 -> {
                conn.send("ATSP7"); conn.send("ATCP18")
                conn.send("ATSH${e.req.substring(2)}")   // DA tt F1
                conn.send("ATCRA${e.resp}")
            }
            Net.MS -> {
                conn.send("STP53"); conn.send("STPBR95238")
                conn.send("ATSH${e.req}"); conn.send("ATCRA${e.resp}")
            }
        }
    }

    private suspend fun testObdEcu(e: Ecu): EcuResult {
        target(e)
        val t0 = System.currentTimeMillis()
        val r03 = conn.send("03", 5000)
        val ms = System.currentTimeMillis() - t0
        if (ObdParser.isError(r03)) return EcuResult(e, false, ms, "OBD", null, emptyList(), note = "Ne répond pas")
        val dtcs = ObdParser.parseDtcs(r03, conn.isCan, "43").map { EcuDtc(it, null) }
        val pending = ObdParser.parseDtcs(conn.send("07", 5000), conn.isCan, "47")
        val permanent = ObdParser.parseDtcs(conn.send("0A", 5000), conn.isCan, "4A")
        val ready = ObdParser.parseReadiness(conn.send("0101", 3000))
        val name = EcuParser.ascii(conn.send("090A", 3000), "490A01")
        return EcuResult(e, true, ms, "OBD", name, dtcs, pending, permanent, ready)
    }

    private suspend fun testDiagEcu(e: Ecu): EcuResult {
        target(e)
        val t0 = System.currentTimeMillis()
        var raw = conn.send("1902FF", 5000)
        val ms = System.currentTimeMillis() - t0
        var proto = "UDS"
        var dtcs = EcuParser.udsDtcs(raw)
        if (dtcs == null) {
            raw = conn.send("1802FF00", 5000)
            dtcs = EcuParser.kwpDtcs(raw)
            proto = "KWP2000"
        }
        val name = EcuParser.ascii(conn.send("22F197", 3000), "62F197")
            ?: EcuParser.ascii(conn.send("22F18C", 3000), "62F18C")
        if (dtcs == null) {
            return EcuResult(e, true, ms, "Constructeur", name, emptyList(),
                note = "Le calculateur répond, mais avec un langage propre à la marque : lecture des codes impossible ici.")
        }
        return EcuResult(e, true, ms, proto, name, dtcs)
    }

    fun clearEcu(e: Ecu) {
        if (_live.value.conn != Conn.CONNECTED) return
        scope.launch {
            val ok = try {
                exclusive {
                    target(e)
                    when {
                        e.obd -> EcuParser.positive(conn.send("04", 8000), "44")
                        _ecu.value.results[e.net.name + e.resp]?.protocol == "KWP2000" -> EcuParser.positive(conn.send("14FF00", 8000), "54")
                        else -> EcuParser.positive(conn.send("14FFFFFF", 8000), "54")
                    }
                }
            } catch (ex: IOException) { false }
            _live.update { it.copy(message = if (ok) "Codes effacés : ${e.name}" else "Le calculateur ${e.name} a refusé l'effacement (moteur à couper ?)") }
            if (ok) runEcuTest(e)
        }
    }

    // ---------------------------------------------------------------- défauts intermittents

    private suspend fun monitorDtcs() {
        val now = (ObdParser.parseDtcs(conn.send("03", 3000), conn.isCan, "43") +
            ObdParser.parseDtcs(conn.send("07", 3000), conn.isCan, "47")).toSet()
        val known = knownDtcs
        knownDtcs = now
        if (known == null) return
        val fresh = now - known
        if (fresh.isNotEmpty()) {
            val d = ObdParser.explain(fresh.first())
            val s = _settings.value
            alerts.play(false, s.sound, s.vibration)
            _live.update {
                it.copy(message = "Nouveau défaut détecté : ${d.code} · ${d.title}",
                    pending = (it.pending + fresh.map { c -> ObdParser.explain(c) }).distinctBy { x -> x.code })
            }
        }
    }

    // ---------------------------------------------------------------- test batterie

    /** Tension au repos, chute pendant le démarrage, puis charge de l'alternateur. */
    fun startBatteryTest() {
        if (_live.value.conn != Conn.CONNECTED || _battery.value.phase in setOf("rest", "crank", "charge")) return
        scope.launch {
            pollPaused = true
            delay(300)
            try {
                _battery.value = BatteryTest("rest", message = "Mesure au repos… laissez le moteur coupé, contact mis.")
                val rest = average(2500)
                _battery.value = _battery.value.copy(rest = rest, phase = "crank", message = "Démarrez le moteur maintenant.")
                val samples = ArrayList<Float>()
                var min = rest ?: 14.0
                val until = System.currentTimeMillis() + 12_000
                while (System.currentTimeMillis() < until) {
                    val v = try { ObdParser.parseVoltage(conn.send("ATRV", 800)) } catch (e: IOException) { null }
                    if (v != null) {
                        samples += v.toFloat(); min = minOf(min, v)
                        _battery.value = _battery.value.copy(samples = samples.toList(), crankMin = min)
                    }
                    delay(40)
                }
                _battery.value = _battery.value.copy(phase = "charge", message = "Moteur au ralenti, mesure de la charge…")
                delay(3000)
                val charge = average(3000)
                _battery.value = _battery.value.copy(phase = "done", charge = charge, message = "")
            } catch (e: Exception) {
                _battery.value = _battery.value.copy(phase = "error", message = "Test interrompu : ${e.message}")
            } finally {
                pollPaused = false
            }
        }
    }

    fun resetBatteryTest() { if (_battery.value.phase in setOf("done", "error")) _battery.value = BatteryTest() }

    private suspend fun average(ms: Long): Double? {
        val vals = ArrayList<Double>()
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            runCatching { ObdParser.parseVoltage(conn.send("ATRV", 800)) }.getOrNull()?.let { vals += it }
            delay(100)
        }
        return if (vals.isEmpty()) null else vals.average()
    }

    // ---------------------------------------------------------------- carburant & chronos

    fun addFuel(e: FuelEntry) {
        _fuel.value = (_fuel.value + e).sortedByDescending { it.date }
        store.saveFuel(_fuel.value)
        if (e.odometerKm > activeVehicle().odometerKm) updateVehicle(e.vehicleId) { it.copy(odometerKm = e.odometerKm) }
        calibrate(e.vehicleId)
    }

    /** Litres consommés depuis le dernier plein complet (null si aucun plein noté). */
    private fun litersSinceFull(v: Vehicle): Double? {
        val last = _fuel.value.filter { it.vehicleId == v.id && it.full }.maxByOrNull { it.date } ?: return null
        return _trips.value.filter { it.vehicleId == v.id && it.start > last.date }.sumOf { it.fuelL }
    }

    private var refSessionFuel = 0.0

    /**
     * Litres restants estimés : on part du repère le plus récent (plein complet = réservoir plein, ou niveau indiqué à la main),
     * on ajoute les pleins partiels notés depuis, on retire ce que les trajets ont consommé. null si aucun repère.
     */
    private fun estimatedLeft(v: Vehicle): Double? {
        val full = _fuel.value.filter { it.vehicleId == v.id && it.full }.maxByOrNull { it.date }
        val (refL, refAt) = when {
            v.fuelRefL != null && (full == null || v.fuelRefAt > full.date) -> v.fuelRefL to v.fuelRefAt
            full != null -> v.tankL to full.date
            else -> return null
        }
        val added = _fuel.value.filter { it.vehicleId == v.id && !it.full && it.date > refAt }.sumOf { it.liters }
        val used = _trips.value.filter { it.vehicleId == v.id && it.start > refAt }.sumOf { it.fuelL }
        val current = if (sessionStart >= refAt) fuel else (fuel - refSessionFuel).coerceAtLeast(0.0)
        return (refL + added - used - current).coerceIn(0.0, v.tankL)
    }

    /** L'utilisateur indique le niveau de la jauge (0 à 1) : nouveau point de départ pour le carburant restant et l'autonomie. */
    fun setFuelLevel(fraction: Double) {
        val v = activeVehicle()
        refSessionFuel = fuel
        updateVehicle(v.id) { it.copy(fuelRefL = fraction.coerceIn(0.0, 1.0) * it.tankL, fuelRefAt = System.currentTimeMillis()) }
        _live.update { it.copy(message = "Niveau enregistré : l'autonomie est calculée à partir de maintenant") }
    }

    /**
     * Ajuste l'estimation de consommation de l'appli sur la consommation réelle mesurée entre deux pleins complets.
     * Le facteur reste entre 0,4 et 2,5 pour écarter les erreurs de saisie.
     */
    private fun calibrate(vehicleId: String) {
        val fills = _fuel.value.filter { it.vehicleId == vehicleId }.sortedBy { it.date }
        val full = fills.filter { it.full }
        if (full.size < 2) return
        val a = full[full.size - 2]; val b = full.last()
        val realL = fills.filter { it.date > a.date && it.date <= b.date }.sumOf { it.liters }
        val estL = _trips.value.filter { it.vehicleId == vehicleId && it.start > a.date && it.start <= b.date }.sumOf { it.fuelL }
        if (realL <= 0 || estL < 5) return
        val v = _vehicles.value.firstOrNull { it.id == vehicleId } ?: return
        // les trajets ont été estimés avec l'ancien facteur : on corrige de façon cumulative
        val f = (v.consFactor * realL / estL).coerceIn(0.4, 2.5)
        updateVehicle(vehicleId) { it.copy(consFactor = f) }
        _live.update { it.copy(message = "Consommation recalée sur vos pleins (×${"%.2f".format(f)})") }
    }

    fun deleteFuel(id: String) {
        _fuel.value = _fuel.value.filterNot { it.id == id }
        store.saveFuel(_fuel.value)
    }

    fun deletePerf(id: String) {
        _perf.value = _perf.value.filterNot { it.id == id }
        store.savePerf(_perf.value)
    }

    fun series(tripId: String): List<TripPoint> = store.loadSeries(tripId)

    // ---------------------------------------------------------------- sauvegarde

    fun exportBackup(): String = store.exportAll()

    /** Fichier de synchronisation reçu d'un autre appareil, en attente du choix Fusionner / Remplacer. */
    private val _pendingImport = MutableStateFlow<String?>(null)
    val pendingImport: StateFlow<String?> = _pendingImport.asStateFlow()
    fun offerImport(json: String?) { _pendingImport.value = json?.takeIf { it.contains("simcardiag-backup") } }

    /** Fichier à envoyer vers l'autre appareil (Bluetooth, Quick Share, mail, Drive…). */
    fun syncFile(): java.io.File {
        val dir = java.io.File(ctx.cacheDir, "updates").apply { mkdirs() }
        return java.io.File(dir, "simcardiag-synchro.json").apply { writeText(exportBackup()) }
    }

    fun importBackup(json: String, merge: Boolean = false): Boolean {
        if (_live.value.conn != Conn.IDLE) return false
        _pendingImport.value = null
        if (!(if (merge) store.mergeAll(json) else store.importAll(json))) return false
        _settings.value = store.loadSettings()
        _vehicles.value = store.loadVehicles().ifEmpty { _vehicles.value }
        _trips.value = store.loadTrips()
        _fuel.value = store.loadFuel()
        _perf.value = store.loadPerf()
        if (_vehicles.value.none { it.id == _settings.value.activeVehicleId }) updateSettings { it.copy(activeVehicleId = _vehicles.value.first().id) }
        CarWidget.refresh(ctx)
        return true
    }

    /** Supprime les trajets plus anciens que la durée de conservation choisie. */
    fun pruneTrips() {
        val days = _settings.value.keepTripsDays
        if (days <= 0) return
        val limit = System.currentTimeMillis() - days * 86_400_000L
        _trips.value.filter { it.start < limit }.forEach { deleteTrip(it) }
    }

    fun forgetDevice() = updateSettings { it.copy(lastDevice = null) }

    /** Efface toutes les données et repart d'une appli neuve (boîtier déconnecté uniquement). */
    fun wipeAll(): Boolean {
        if (_live.value.conn != Conn.IDLE) return false
        store.wipe()
        _settings.value = Settings()
        val v = Vehicle(name = "Ma voiture", maintenance = Presets.defaultMaintenance(0.0, System.currentTimeMillis()))
        _vehicles.value = listOf(v); store.saveVehicles(_vehicles.value)
        updateSettings { it.copy(activeVehicleId = v.id) }
        _trips.value = emptyList(); _fuel.value = emptyList(); _perf.value = emptyList()
        CarWidget.refresh(ctx)
        return true
    }

    /** Derniers échanges avec le boîtier, avec un en-tête utile pour un diagnostic à distance. */
    fun journalText(): String {
        val l = _live.value
        val head = listOf(
            "SimCarDiag ${Updater.currentName(ctx)} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}",
            "Boîtier : ${l.device ?: "—"} · état : ${l.conn} · protocole : ${l.protocol.ifEmpty { "—" }} · STN : ${l.isStn} · lecture groupée : ${l.multi}",
            "Méthode : ${if (_settings.value.legacyLink) "comme hier soir (2.0.35)" else "nouvelle"} · dialogue direct moteur : ${conn.physical} · liaison : ${conn.BT_MODES[btMode]}",
            "Capteurs : ${l.supported.joinToString(" ") { it.code }.ifEmpty { "—" }}",
            "Véhicule : ${activeVehicle().name} ${activeVehicle().vin ?: ""}",
            "",
        )
        return (head + l.log.map { "[connexion] $it" } + conn.journal()).joinToString("\n")
    }

    fun consumeMessage() = _live.update { it.copy(message = null) }
    fun toast(text: String) = _live.update { it.copy(message = text) }
    fun clearError() = _live.update { it.copy(error = null) }

    fun fmt(v: Double): String =
        if (v.isNaN()) "—" else if (kotlin.math.abs(v) >= 100) String.format(java.util.Locale.FRANCE, "%.0f", v)
        else String.format(java.util.Locale.FRANCE, "%.1f", v)
}
