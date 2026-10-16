package io.github.jqssun.airplay.net

import android.content.Context
import android.util.Log
import io.github.jqssun.airplay.Prefs
import okhttp3.Dns
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class CustomDns(private val context: Context) : Dns {

    private val prefs by lazy {
        context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
    }

    private data class CacheEntry(val addresses: List<InetAddress>, val expireAt: Long)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    override fun lookup(hostname: String): List<InetAddress> {
        // Direct IP address (IPv4 or IPv6)
        if (isIpLiteral(hostname)) {
            return listOf(InetAddress.getByName(hostname))
        }

        val customEnabled = prefs.getBoolean(Prefs.CUSTOM_DNS_ENABLED, Prefs.DEF_CUSTOM_DNS_ENABLED)
        if (!customEnabled) {
            return Dns.SYSTEM.lookup(hostname)
        }

        val now = System.currentTimeMillis()
        cache[hostname]?.let { entry ->
            if (now < entry.expireAt) {
                return entry.addresses
            } else {
                cache.remove(hostname)
            }
        }

        val dnsServerConfig = prefs.getString(Prefs.CUSTOM_DNS_SERVERS, Prefs.DEF_CUSTOM_DNS_SERVERS)
            ?: Prefs.DEF_CUSTOM_DNS_SERVERS
        val servers = dnsServerConfig.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        for (serverIp in servers) {
            try {
                val ips = queryDnsServer(hostname, serverIp)
                if (ips.isNotEmpty()) {
                    val inetAddresses = ips.map { InetAddress.getByAddress(hostname, it) }
                    cache[hostname] = CacheEntry(inetAddresses, now + CACHE_TTL_MS)
                    Log.i(TAG, "Resolved $hostname via custom DNS $serverIp: ${ips.joinToString { formatIp(it) }}")
                    return inetAddresses
                }
            } catch (e: Exception) {
                Log.w(TAG, "Custom DNS query to $serverIp for $hostname failed: ${e.message}")
            }
        }

        Log.d(TAG, "Custom DNS lookup failed or empty for $hostname, falling back to system DNS")
        return Dns.SYSTEM.lookup(hostname)
    }

    private fun isIpLiteral(host: String): Boolean {
        if (host.contains(':')) return true
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { it.toIntOrNull()?.let { num -> num in 0..255 } == true }
    }

    private fun formatIp(bytes: ByteArray): String {
        return if (bytes.size == 4) {
            bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
        } else {
            InetAddress.getByAddress(bytes).hostAddress ?: "unknown"
        }
    }

    private fun queryDnsServer(hostname: String, serverIp: String): List<ByteArray> {
        val txId = Random.nextInt(1, 65535)
        val queryPacket = buildQueryPacket(hostname, txId)

        DatagramSocket().use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val serverAddr = InetAddress.getByName(serverIp)
            socket.send(DatagramPacket(queryPacket, queryPacket.size, serverAddr, DNS_PORT))

            val receiveBuffer = ByteArray(1024)
            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
            socket.receive(receivePacket)

            return parseDnsResponse(receiveBuffer, receivePacket.length, txId)
        }
    }

    private fun buildQueryPacket(hostname: String, txId: Int): ByteArray {
        val out = ByteArrayOutputStream()
        // Header (12 bytes)
        out.write((txId shr 8) and 0xFF)
        out.write(txId and 0xFF)
        out.write(0x01) // Flags high: RD=1
        out.write(0x00) // Flags low
        out.write(0x00); out.write(0x01) // QDCOUNT = 1
        out.write(0x00); out.write(0x00) // ANCOUNT = 0
        out.write(0x00); out.write(0x00) // NSCOUNT = 0
        out.write(0x00); out.write(0x00) // ARCOUNT = 0

        // Question: QNAME
        for (part in hostname.split('.')) {
            val bytes = part.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size and 0xFF)
            out.write(bytes)
        }
        out.write(0x00) // End of QNAME

        // QTYPE = 1 (Type A, IPv4)
        out.write(0x00); out.write(0x01)
        // QCLASS = 1 (IN)
        out.write(0x00); out.write(0x01)

        return out.toByteArray()
    }

    private fun parseDnsResponse(data: ByteArray, length: Int, expectedTxId: Int): List<ByteArray> {
        if (length < 12) return emptyList()

        val txId = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        if (txId != expectedTxId) return emptyList()

        val flags = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val qr = (flags and 0x8000) != 0
        val rcode = flags and 0x000F
        if (!qr || rcode != 0) return emptyList()

        val qdCount = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        val anCount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        if (anCount == 0) return emptyList()

        var offset = 12

        // Skip Question section
        for (i in 0 until qdCount) {
            while (offset < length) {
                val len = data[offset].toInt() and 0xFF
                if (len == 0) {
                    offset++
                    break
                } else if ((len and 0xC0) == 0xC0) {
                    offset += 2
                    break
                } else {
                    offset += 1 + len
                }
            }
            offset += 4 // QTYPE (2) + QCLASS (2)
        }

        val results = mutableListOf<ByteArray>()

        // Parse Answer section
        for (i in 0 until anCount) {
            if (offset >= length) break

            // Skip NAME
            while (offset < length) {
                val len = data[offset].toInt() and 0xFF
                if (len == 0) {
                    offset++
                    break
                } else if ((len and 0xC0) == 0xC0) {
                    offset += 2
                    break
                } else {
                    offset += 1 + len
                }
            }

            if (offset + 10 > length) break
            val type = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            val rdLength = ((data[offset + 8].toInt() and 0xFF) shl 8) or (data[offset + 9].toInt() and 0xFF)
            offset += 10

            if (offset + rdLength > length) break

            if (type == 1 && rdLength == 4) { // Type A (IPv4)
                results.add(data.copyOfRange(offset, offset + 4))
            } else if (type == 28 && rdLength == 16) { // Type AAAA (IPv6)
                results.add(data.copyOfRange(offset, offset + 16))
            }

            offset += rdLength
        }

        return results
    }

    companion object {
        private const val TAG = "CustomDns"
        private const val DNS_PORT = 53
        private const val SOCKET_TIMEOUT_MS = 2000
        private const val CACHE_TTL_MS = 60_000L
    }
}
