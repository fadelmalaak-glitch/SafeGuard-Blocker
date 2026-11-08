package com.safeguard.blocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Layer 1 — a local filtering DNS tunnel.
 *
 * Only DNS (UDP/53) is captured through the virtual interface; all other traffic
 * uses the normal network untouched, so browsing stays fast. Each DNS query is:
 *   1) checked against the local adult blocklist  -> answered with NXDOMAIN instantly, or
 *   2) forwarded to a filtering upstream resolver (CleanBrowsing Adult + Cloudflare Family)
 *      which blocks pornographic domains, and the real answer is relayed back.
 *
 * This works across every browser and app that uses the system resolver.
 */
class SafeDnsVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.safeguard.blocker.VPN_START"
        const val ACTION_STOP = "com.safeguard.blocker.VPN_STOP"

        private const val CHANNEL_ID = "safeguard_protection"
        private const val NOTIF_ID = 42

        // Sentinel DNS address advertised inside the tunnel; queries to it reach us.
        private const val SENTINEL_DNS = "10.111.0.1"
        private const val LOCAL_ADDR = "10.111.0.2"

        // Public unfiltered resolvers that apps/browsers may hardcode for plain DNS, DoH
        // (443) or DoT (853). Routed into the tunnel: plain DNS to them gets filtered by
        // our engine; any other traffic to them (DoH/DoT) is dropped, so browsers fall
        // back to the system (filtered) resolver. Our own upstream sockets are protect()ed
        // and these lists deliberately exclude the filtering upstreams below.
        private val BYPASS_RESOLVERS_V4 = listOf(
            "8.8.8.8", "8.8.4.4",                 // Google
            "1.1.1.1", "1.0.0.1",                 // Cloudflare (unfiltered)
            "9.9.9.9", "149.112.112.112",         // Quad9
            "208.67.222.222", "208.67.220.220",   // OpenDNS
            "94.140.14.14", "94.140.15.15",       // AdGuard
            "45.90.28.0", "45.90.30.0",           // NextDNS anycast
            "185.222.222.222", "45.11.45.11"      // DNS.SB
        )
        private val BYPASS_RESOLVERS_V6 = listOf(
            "2001:4860:4860::8888", "2001:4860:4860::8844",
            "2606:4700:4700::1111", "2606:4700:4700::1001",
            "2620:fe::fe", "2620:fe::9",
            "2620:119:35::35", "2620:119:53::53",
            "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff"
        )
        private const val LOCAL_ADDR_V6 = "fd00:5afe:6a4d::2"

        // Filtering upstream resolvers (adult + malware blocking).
        private val UPSTREAMS = listOf(
            "185.228.168.10", // CleanBrowsing Adult Filter (primary)
            "1.1.1.3",        // Cloudflare for Families (fallback)
            "185.228.169.11"  // CleanBrowsing Adult Filter (secondary)
        )
    }

    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private var worker: Thread? = null
    private lateinit var pool: ThreadPoolExecutor

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()

        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
            else -> startTunnel() // ACTION_START, or null (system restart)
        }
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "SafeGuard Protection",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
                nm.createNotificationChannel(ch)
            }
        }

        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE else 0
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            Notification.Builder(this)

        val notif: Notification =
            builder
                .setContentTitle("SafeGuard — الحماية مفعّلة")
                .setContentText("فلتر المحتوى الإباحي يعمل على كل التطبيقات")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(openPi)
                .setOngoing(true)
                .build()

        startForeground(NOTIF_ID, notif)
    }

    private fun startTunnel() {
        if (running) return
        val builder = Builder()
            .setSession("SafeGuard Filter")
            .setMtu(1500)
            .addAddress(LOCAL_ADDR, 32)
            .addDnsServer(SENTINEL_DNS)
            .addRoute(SENTINEL_DNS, 32)
        for (ip in BYPASS_RESOLVERS_V4) {
            try { builder.addRoute(ip, 32) } catch (_: Exception) {}
        }
        try {
            builder.addAddress(LOCAL_ADDR_V6, 128)
            for (ip in BYPASS_RESOLVERS_V6) {
                try { builder.addRoute(ip, 128) } catch (_: Exception) {}
            }
        } catch (_: Exception) { /* IPv6 unavailable; IPv4 protection still applies */ }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
        }
        builder.setBlocking(true)

        val pfd = try {
            builder.establish()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } ?: run {
            stopSelf()
            return
        }

        tun = pfd
        running = true
        pool = Executors.newFixedThreadPool(8) as ThreadPoolExecutor

        worker = Thread { relayLoop(pfd) }.also { it.start() }
    }

    private fun relayLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buffer = ByteArray(32767)
        try {
            while (running) {
                val n = input.read(buffer)
                if (n <= 0) continue
                val packet = buffer.copyOf(n)
                try {
                    pool.execute { handlePacket(packet, output) }
                } catch (_: Exception) { /* pool saturated; drop */ }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { input.close() } catch (_: Exception) {}
            try { output.close() } catch (_: Exception) {}
        }
    }

    /** Parse an IPv4/UDP/53 packet, filter the DNS query, write the response back. */
    private fun handlePacket(packet: ByteArray, output: FileOutputStream) {
        if (packet.size < 28) return
        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != 4) return
        val ihl = (packet[0].toInt() and 0x0F) * 4
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return // UDP only

        val totalLen = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        val udpOff = ihl
        if (packet.size < udpOff + 8) return
        val dstPort = ((packet[udpOff + 2].toInt() and 0xFF) shl 8) or (packet[udpOff + 3].toInt() and 0xFF)
        if (dstPort != 53) return

        val dnsOff = udpOff + 8
        val dnsEnd = minOf(totalLen, packet.size)
        if (dnsEnd <= dnsOff) return
        val dns = packet.copyOfRange(dnsOff, dnsEnd)

        val qname = parseQName(dns)
        val blocked = qname != null &&
            (Blocklists.isBlockedHost(qname) || Blocklists.isDohHost(qname))
        val response: ByteArray = if (blocked) {
            buildNxDomain(dns)
        } else {
            forwardUpstream(dns) ?: buildNxDomain(dns)
        }

        val reply = buildReplyPacket(packet, ihl, udpOff, response)
        synchronized(output) {
            try {
                output.write(reply)
                output.flush()
            } catch (_: Exception) {}
        }
    }

    private fun forwardUpstream(query: ByteArray): ByteArray? {
        for (server in UPSTREAMS) {
            try {
                DatagramSocket().use { socket ->
                    protect(socket)
                    socket.soTimeout = 4000
                    val addr = InetAddress.getByName(server)
                    socket.send(DatagramPacket(query, query.size, addr, 53))
                    val respBuf = ByteArray(4096)
                    val resp = DatagramPacket(respBuf, respBuf.size)
                    socket.receive(resp)
                    return respBuf.copyOf(resp.length)
                }
            } catch (_: Exception) { /* try next upstream */ }
        }
        return null
    }

    /** Read the first question's domain name from a DNS message. */
    private fun parseQName(dns: ByteArray): String? {
        if (dns.size < 13) return null
        var pos = 12
        val sb = StringBuilder()
        try {
            while (pos < dns.size) {
                val len = dns[pos].toInt() and 0xFF
                if (len == 0) break
                if (len and 0xC0 != 0) return null // compression not expected in a question
                pos++
                if (pos + len > dns.size) return null
                for (i in 0 until len) sb.append((dns[pos + i].toInt() and 0xFF).toChar())
                sb.append('.')
                pos += len
            }
        } catch (_: Exception) {
            return null
        }
        return if (sb.isEmpty()) null else sb.toString().trimEnd('.')
    }

    /** Turn a DNS query into an NXDOMAIN response (QR=1, RA=1, RCODE=3). */
    private fun buildNxDomain(query: ByteArray): ByteArray {
        val r = query.copyOf()
        if (r.size < 4) return r
        r[2] = (0x81).toByte()           // QR=1, Opcode=0, AA=0, TC=0, RD=1
        r[3] = (0x83).toByte()           // RA=1, Z=0, RCODE=3 (NXDOMAIN)
        if (r.size >= 8) { r[6] = 0; r[7] = 0 } // ANCOUNT = 0
        return r
    }

    /** Wrap a DNS payload into an IPv4/UDP packet addressed back to the original sender. */
    private fun buildReplyPacket(
        orig: ByteArray,
        ihl: Int,
        udpOff: Int,
        dnsPayload: ByteArray
    ): ByteArray {
        val udpLen = 8 + dnsPayload.size
        val totalLen = ihl + udpLen
        val out = ByteArray(totalLen)

        // Copy original IP header, then swap src/dst addresses.
        System.arraycopy(orig, 0, out, 0, ihl)
        // total length
        out[2] = ((totalLen ushr 8) and 0xFF).toByte()
        out[3] = (totalLen and 0xFF).toByte()
        // TTL
        out[8] = 64
        // swap src (12..15) <-> dst (16..19)
        for (i in 0 until 4) {
            val s = orig[12 + i]
            out[12 + i] = orig[16 + i]
            out[16 + i] = s
        }
        // zero checksum then recompute
        out[10] = 0; out[11] = 0
        val ipCk = checksum(out, 0, ihl)
        out[10] = ((ipCk ushr 8) and 0xFF).toByte()
        out[11] = (ipCk and 0xFF).toByte()

        // UDP header: swap ports, set length, checksum 0 (allowed for IPv4).
        val srcPort = ((orig[udpOff].toInt() and 0xFF) shl 8) or (orig[udpOff + 1].toInt() and 0xFF)
        val dstPort = ((orig[udpOff + 2].toInt() and 0xFF) shl 8) or (orig[udpOff + 3].toInt() and 0xFF)
        val u = ihl
        out[u] = ((dstPort ushr 8) and 0xFF).toByte()
        out[u + 1] = (dstPort and 0xFF).toByte()
        out[u + 2] = ((srcPort ushr 8) and 0xFF).toByte()
        out[u + 3] = (srcPort and 0xFF).toByte()
        out[u + 4] = ((udpLen ushr 8) and 0xFF).toByte()
        out[u + 5] = (udpLen and 0xFF).toByte()
        out[u + 6] = 0; out[u + 7] = 0

        System.arraycopy(dnsPayload, 0, out, ihl + 8, dnsPayload.size)
        return out
    }

    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun stopEverything() {
        running = false
        try { worker?.interrupt() } catch (_: Exception) {}
        try { if (this::pool.isInitialized) pool.shutdownNow() } catch (_: Exception) {}
        try { pool.awaitTermination(500, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        stopForeground(true)
        stopSelf()
    }

    override fun onRevoke() {
        // Best effort: if protection is still meant to be active, try to restart the
        // tunnel. (If the user revoked consent in Settings this may fail, in which case
        // we stop cleanly — the robust lock is the Device Owner always-on VPN.)
        running = false
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        if (Prefs.protectionEnabled(this) && ScheduleManager.shouldBeActiveNow(this)) {
            try {
                startTunnel()
            } catch (_: Exception) {
                stopEverything()
            }
        } else {
            stopEverything()
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        running = false
        try { tun?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}
