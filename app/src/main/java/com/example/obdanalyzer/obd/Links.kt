package com.example.obdanalyzer.obd

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.resume

/**
 * Les deux autres façons de relier l'autoradio au boîtier, en plus du Bluetooth :
 * - Wi-Fi : boîtier qui crée son propre réseau (OBDLink MX WiFi, ELM327 WiFi…), adresse 192.168.0.10, port 35000 en général ;
 * - USB : boîtier à câble (OBDLink SX USB, ELM327 USB…) branché sur une prise USB de l'autoradio.
 * Le nom mémorisé du boîtier commence par « wifi: » ou « usb: » ; sinon c'est une adresse Bluetooth.
 */
object Links {

    fun isWifi(a: String) = a.startsWith("wifi:")
    fun isUsb(a: String) = a.startsWith("usb:")
    fun isBluetooth(a: String) = !isWifi(a) && !isUsb(a)

    fun wifiAddress(host: String, port: Int) = "wifi:$host:$port"
    fun label(a: String): String = when {
        isWifi(a) -> "Boîtier Wi-Fi (${a.removePrefix("wifi:")})"
        isUsb(a) -> "Boîtier USB"
        else -> a
    }

    // ---------------------------------------------------------------- Wi-Fi

    /**
     * Ouvre la liaison Wi-Fi. Le boîtier n'a pas accès à Internet : sans précaution, Android enverrait la connexion
     * par la 4G. On la force donc sur le réseau Wi-Fi.
     */
    suspend fun openWifi(ctx: Context, conn: ObdConnection, address: String) = withContext(Dispatchers.IO) {
        val parts = address.removePrefix("wifi:").split(":")
        val host = parts.getOrNull(0)?.ifBlank { null } ?: "192.168.0.10"
        val port = parts.getOrNull(1)?.toIntOrNull() ?: 35000
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val wifi = cm?.allNetworks?.firstOrNull { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            ?: throw IOException("Pas de Wi-Fi : connectez l'autoradio au réseau Wi-Fi du boîtier (souvent « WiFi_OBDII » ou « OBDLink MX »), puis réessayez.")
        val s: Socket = wifi.socketFactory.createSocket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), 5000)
        } catch (e: IOException) {
            runCatching { s.close() }
            throw IOException("Le boîtier Wi-Fi ne répond pas à $host:$port. Vérifiez que l'autoradio est bien connecté à son réseau Wi-Fi.")
        }
        conn.attach(s.getInputStream(), s.getOutputStream()) { runCatching { s.close() } }
        conn.note("Liaison Wi-Fi : $host:$port")
    }

    // ---------------------------------------------------------------- USB

    private const val ACTION_USB = "fr.simcardiag.USB_PERMISSION"

    fun usbDevices(ctx: Context): List<UsbDevice> {
        val um = ctx.getSystemService(UsbManager::class.java) ?: return emptyList()
        return UsbSerialProber.getDefaultProber().findAllDrivers(um).map { it.device }
    }

    /** Demande à Android l'accès au câble USB (une fenêtre s'affiche la première fois). */
    private suspend fun askPermission(ctx: Context, um: UsbManager, dev: UsbDevice): Boolean {
        if (um.hasPermission(dev)) return true
        return withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine { cont ->
                val rx = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        runCatching { ctx.unregisterReceiver(this) }
                        if (cont.isActive) cont.resume(um.hasPermission(dev))
                    }
                }
                ContextCompat.registerReceiver(ctx, rx, IntentFilter(ACTION_USB), ContextCompat.RECEIVER_NOT_EXPORTED)
                cont.invokeOnCancellation { runCatching { ctx.unregisterReceiver(rx) } }
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                um.requestPermission(dev, PendingIntent.getBroadcast(ctx, 0, Intent(ACTION_USB).setPackage(ctx.packageName), flags))
            }
        } ?: false
    }

    /**
     * Ouvre le premier boîtier USB trouvé. La vitesse dépend du modèle (115 200 bauds pour les OBDLink,
     * 38 400 pour beaucoup d'ELM327) : on essaie les plus courantes jusqu'à obtenir une réponse.
     */
    suspend fun openUsb(ctx: Context, conn: ObdConnection) {
        val um = ctx.getSystemService(UsbManager::class.java) ?: throw IOException("USB non disponible sur cet appareil")
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(um).firstOrNull()
            ?: throw IOException("Aucun boîtier USB détecté : branchez le câble sur une prise USB de l'autoradio.")
        if (!askPermission(ctx, um, driver.device)) throw IOException("Accès au câble USB refusé : rebranchez-le et acceptez la demande.")
        withContext(Dispatchers.IO) {
            val connection = um.openDevice(driver.device) ?: throw IOException("Impossible d'ouvrir le câble USB")
            val port = driver.ports.first()
            port.open(connection)
            for (baud in listOf(115200, 38400, 500000, 230400, 9600)) {
                port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                runCatching { port.dtr = true; port.rts = true }
                val streams = UsbStreams(port)
                conn.attach(streams.input, streams.output) { streams.stop(); runCatching { port.close() } }
                val ok = runCatching { conn.send("ATI", 1500) }.getOrNull()?.let { it.contains("ELM") || it.contains("STN") || it.contains("OBD") } == true
                if (ok) { conn.note("Liaison USB : ${driver.javaClass.simpleName.removeSuffix("SerialDriver")} à $baud bauds"); return@withContext }
                streams.stop()
            }
            runCatching { port.close() }
            throw IOException("Le boîtier USB ne répond pas (vitesse inconnue).")
        }
    }

    /** Transforme le port série USB en flux classiques, comme une liaison Bluetooth. */
    private class UsbStreams(private val port: UsbSerialPort) {
        private val queue = LinkedBlockingQueue<Int>()
        @Volatile private var running = true
        private val reader = Thread {
            val buf = ByteArray(256)
            while (running) {
                val n = try { port.read(buf, 200) } catch (e: Exception) { if (running) running = false; -1 }
                for (i in 0 until n.coerceAtLeast(0)) queue.put(buf[i].toInt() and 0xFF)
            }
        }.apply { isDaemon = true; start() }

        fun stop() { running = false }

        val input = object : InputStream() {
            override fun available(): Int { if (!running && queue.isEmpty()) throw IOException("Câble USB débranché"); return queue.size }
            override fun read(): Int = queue.take()
        }
        val output = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()))
            override fun write(b: ByteArray, off: Int, len: Int) {
                try { port.write(b.copyOfRange(off, off + len), 1000) } catch (e: Exception) { throw IOException("Câble USB débranché") }
            }
        }
    }
}
