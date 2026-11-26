package com.example.obdanalyzer.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * Connexion Bluetooth classique (SPP) vers un adaptateur ELM327 / OBDLink (puce STN).
 * Une seule commande à la fois grâce au mutex.
 */
class ObdConnection {

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private var socket: BluetoothSocket? = null
    // Flux communs aux trois liaisons : Bluetooth, Wi-Fi ou câble USB
    private var input: java.io.InputStream? = null
    private var output: java.io.OutputStream? = null
    private var closer: (() -> Unit)? = null
    private val mutex = Mutex()

    /** Liaison déjà ouverte par Wi-Fi ou USB (voir obd/Links.kt). */
    fun attach(i: java.io.InputStream, o: java.io.OutputStream, onClose: () -> Unit) {
        close()
        physical = false
        input = i; output = o; closer = onClose
    }

    var isCan = false; private set
    var isStn = false; private set
    var protocol = ""; private set

    /** Journal des derniers échanges avec le boîtier (pour le diagnostic de connexion). */
    private val journal = ArrayDeque<String>()
    private val timeFmt = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.FRANCE)

    fun note(line: String) = synchronized(journal) {
        journal.addLast("${timeFmt.format(java.util.Date())}  $line")
        while (journal.size > 400) journal.removeFirst()
    }

    fun journal(): List<String> = synchronized(journal) { journal.toList() }

    val isOpen: Boolean get() = input != null

    @SuppressLint("MissingPermission")
    /**
     * Arrête une recherche d'appareils Bluetooth en cours : Android la déconseille pendant une liaison,
     * elle ralentit fortement le débit et fait décrocher les boîtiers OBD (les autoradios en lancent souvent seuls).
     * Renvoie true si une recherche était en cours.
     */
    fun stopDiscovery(adapter: android.bluetooth.BluetoothAdapter?): Boolean = runCatching {
        if (adapter?.isDiscovering == true) { adapter.cancelDiscovery(); true } else false
    }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    /** Façons d'ouvrir la liaison Bluetooth ; certains autoradios n'en supportent bien qu'une. */
    val BT_MODES = listOf("standard (sécurisée)", "non sécurisée", "canal direct 1")

    suspend fun open(device: BluetoothDevice, adapter: android.bluetooth.BluetoothAdapter? = null, mode: Int = 0) = withContext(Dispatchers.IO) {
        close()
        physical = false
        if (stopDiscovery(adapter)) note("Recherche Bluetooth de l'appareil arrêtée avant la connexion")
        note("Liaison Bluetooth : ${BT_MODES[mode % 3]}")
        fun channel1() = (device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(device, 1) as BluetoothSocket).also { it.connect() }
        val s = try {
            when (mode % 3) {
                1 -> device.createInsecureRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
                2 -> channel1()
                else -> device.createRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
            }
        } catch (e: IOException) {
            note("Ouverture ${BT_MODES[mode % 3]} impossible, essai par le canal direct")
            channel1()
        }
        socket = s
        input = s.inputStream; output = s.outputStream; closer = { runCatching { s.close() } }
    }

    /** Initialise l'adaptateur. [log] reçoit chaque échange pour l'écran « Détails techniques ». */
    /** Ancienne méthode (2.0.35) : minutage agressif sur OBDLink, protocole non figé. */
    var legacy = false

    /**
     * Vide tout ce qui arrive encore du boîtier, jusqu'à 400 ms de calme (3 s au plus).
     * Après un blocage Bluetooth, des réponses en retard peuvent arriver d'un coup (ex. « ATZ → 410D00 ») :
     * sans ce nettoyage, toutes les réponses suivantes sont décalées d'un cran.
     */
    suspend fun settle(quietMs: Long = 400, maxMs: Long = 3000): Int = withContext(Dispatchers.IO) {
        val input = input ?: return@withContext 0
        var n = 0
        val end = System.currentTimeMillis() + maxMs
        var lastByte = System.currentTimeMillis()
        while (System.currentTimeMillis() < end && System.currentTimeMillis() - lastByte < quietMs) {
            if (input.available() > 0) { input.read(); n++; lastByte = System.currentTimeMillis() } else delay(10)
        }
        if (n > 0) note("$n octets en retard ignorés (réponses d'avant la coupure)")
        n
    }

    suspend fun initialize(log: (String, Int) -> Unit) {
        settle()
        val id = send("ATZ", 5000)
        log("ATZ → ${ObdParser.clean(id).trim()}", 1)
        delay(800)
        settle()
        // Puce STN (OBDLink MX, MX+, LX…) : STI répond « STN1110… », AT@1 répond « OBDLink … »
        isStn = try { ObdParser.clean(send("STI")).contains("STN") } catch (e: IOException) { false }
        if (!isStn) isStn = try { ObdParser.clean(send("AT@1")).contains("OBDLINK") } catch (e: IOException) { false }
        log(if (isStn) "Boîtier OBDLink (puce STN) : mode rapide" else "Boîtier ELM327 standard", 1)
        // Diagnostic des coupures : le boîtier dit s'il s'est mis en veille et pourquoi (tension, inactivité…),
        // et quels déclencheurs de veille sont actifs. Tout est noté dans le journal technique.
        if (isStn) {
            runCatching { send("STSLLT", 2000) }
            runCatching { send("STSLCS", 2000) }
        }
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) send(c)
        // Minutage adaptatif normal : le mode agressif (ATAT2) fait rater des réponses des calculateurs lents (Opel / GM)
        send(if (legacy && isStn) "ATAT2" else "ATAT1")
        log("ATE0 ATL0 ATS0 ATH0 ATSP0 → OK", 2)
        // Recherche du protocole : le calculateur peut mettre quelques secondes à se réveiller après le contact
        var found = false
        for (attempt in 1..3) {
            val r = try { send("0100", 15000) } catch (e: IOException) { "" }
            log("0100 (essai $attempt) → ${ObdParser.clean(r).replace("\r", " ").trim().ifEmpty { "pas de réponse" }}", 2)
            if (!ObdParser.isError(r) && ObdParser.parseMode01(r, "00", 4) != null) { found = true; break }
            delay(2000)
        }
        if (!found) throw IOException("La voiture ne répond pas : vérifiez que le contact est mis, puis réessayez.")
        refreshProtocol()
        // On fige le protocole trouvé : sinon, à la moindre absence de réponse, le boîtier relance une recherche
        // de plusieurs secondes et l'appli croit la connexion perdue (déconnexions / reconnexions en boucle)
        if (!legacy) protoNum?.let { n -> runCatching { send("ATSP$n") }; note("Protocole figé : ATSP$n") }
        log("Protocole : $protocol", 3)
    }

    /** Numéro du protocole trouvé (ex. « 6 » = CAN 11 bits 500 kbit/s), pour le figer. */
    var protoNum: Char? = null; private set

    /** Relit le protocole trouvé par l'adaptateur. */
    suspend fun refreshProtocol() {
        val dp = ObdParser.clean(send("ATDPN")).filter { it.isLetterOrDigit() }
        isCan = dp.lastOrNull()?.let { it in "6789ABC" } ?: false
        protoNum = dp.lastOrNull()?.takeIf { it in "123456789ABC" }
        protocol = ObdParser.clean(send("ATDP")).replace("\r", " ").replace("AUTO,", "").trim()
    }

    private var lastEnd = 0L

    suspend fun send(cmd: String, timeoutMs: Long = 4000): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val input = input ?: throw IOException("Non connecté")
            val output = output ?: throw IOException("Non connecté")
            // petit intervalle entre deux commandes : ménage les liaisons Bluetooth fragiles (autoradios)
            val gap = 25 - (System.currentTimeMillis() - lastEnd)
            if (gap > 0) delay(gap)
            while (input.available() > 0) input.read()
            output.write("$cmd\r".toByteArray())
            output.flush()
            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (input.available() > 0) {
                    val b = input.read()
                    if (b < 0) throw IOException("Flux fermé")
                    if (b == '>'.code) {
                        note("$cmd → ${sb.toString().replace('\r', ' ').trim()}")
                        lastEnd = System.currentTimeMillis()
                        return@withContext sb.toString()
                    }
                    sb.append(b.toChar())
                } else {
                    delay(4)
                }
            }
            note("$cmd → délai dépassé (${timeoutMs} ms)${if (sb.isNotEmpty()) " : " + sb.toString().replace('\r', ' ').trim() else ""}")
            // Resynchronisation : on laisse le boîtier finir (ou s'interrompre) pour que la réponse en retard
            // ne se mélange pas à la commande suivante
            val end = System.currentTimeMillis() + 400
            while (System.currentTimeMillis() < end) {
                if (input.available() > 0) { if (input.read() == '>'.code) break } else delay(5)
            }
            throw ObdTimeout("Délai dépassé pour $cmd")
        }
    }

    /** Le boîtier est là mais une requête n'a pas eu de réponse à temps (calculateur occupé, mesure non gérée). */
    class ObdTimeout(msg: String) : IOException(msg)

    /** true : on parle directement au calculateur moteur (7E0) avec filtre de réception 7E8. */
    var physical = false

    /** En-tête par défaut après une requête vers un calculateur précis. */
    suspend fun resetHeader() {
        // ATAR (réception automatique) et non « ATCRA » sans adresse : sur certains boîtiers, ce dernier ouvre le filtre
        // à tout le trafic de la voiture, ce qui sature le boîtier et le Bluetooth
        if (physical) { send("ATSH7E0"); send("ATAR"); return }
        send(if (isCan) "ATSH7DF" else "ATSH686AF1")
        if (isCan) runCatching { send("ATAR") }
    }

    fun close() {
        runCatching { closer?.invoke() }
        runCatching { socket?.close() }
        socket = null; input = null; output = null; closer = null
    }
}
