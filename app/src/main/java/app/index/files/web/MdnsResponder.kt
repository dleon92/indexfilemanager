package app.index.files.web

import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * Responde na rede local (mDNS, RFC 6762) pelo nome fixo [HOST] ("index.local") com o IP do
 * celular no Wi-Fi. Assim o computador abre http://index.local:porta mesmo quando o roteador
 * troca o IP. Só responde perguntas do tipo A/ANY por esse nome; não anuncia mais nada.
 */
class MdnsResponder {

    @Volatile private var socket: MulticastSocket? = null
    private var thread: Thread? = null

    fun start() {
        val s = runCatching {
            MulticastSocket(null as java.net.SocketAddress?).apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
                timeToLive = 255
            }
        }.getOrElse { Log.w(TAG, "mDNS indisponível", it); return }
        val group = InetSocketAddress(GROUP, PORT)
        lanInterfaces().forEach { ni -> runCatching { s.joinGroup(group, ni) } }
        socket = s
        thread = Thread({ loop(s) }, "index-mdns").apply { isDaemon = true; start() }
        // Anúncio inicial: atualiza o cache de quem já conhecia o nome com IP antigo.
        Thread {
            repeat(2) {
                for ((ni, ip) in lanAddresses()) {
                    runCatching {
                        val out = answer(0, null, ip)
                        synchronized(s) {
                            s.networkInterface = ni
                            s.send(DatagramPacket(out, out.size, group))
                        }
                    }
                }
                Thread.sleep(1000)
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        thread = null
    }

    private fun loop(s: MulticastSocket) {
        val buf = ByteArray(1500)
        while (!s.isClosed) {
            val p = DatagramPacket(buf, buf.size)
            try {
                s.receive(p)
            } catch (_: Exception) {
                break
            }
            runCatching { handle(s, p) }
        }
    }

    private fun handle(s: MulticastSocket, p: DatagramPacket) {
        val d = p.data
        val len = p.length
        if (len < 12) return
        val id = u16(d, 0)
        val flags = u16(d, 2)
        if (flags and 0x8000 != 0) return // é resposta, não pergunta
        val qd = u16(d, 4)
        var off = 12
        repeat(qd) {
            val (name, next) = readName(d, len, off) ?: return
            if (next + 4 > len) return
            val type = u16(d, next)
            val cls = u16(d, next + 2)
            off = next + 4
            if (!name.equals(HOST, true) || (type != 1 && type != 255) || (cls and 0x7FFF) != 1) return@repeat
            // Responde com o IP da rede de quem perguntou (Wi-Fi, roteador do celular...).
            val (ni, ip) = lanAddresses().firstOrNull { sameSubnet(it.second, it.third, p.address) }
                ?.let { it.first to it.second } ?: return
            val legacy = p.port != PORT
            val out = answer(if (legacy) id else 0, if (legacy) name else null, ip)
            synchronized(s) {
                if (legacy) {
                    s.send(DatagramPacket(out, out.size, InetSocketAddress(p.address, p.port)))
                } else {
                    // Multicast pela interface certa (senão sai pelos dados móveis) e também direto
                    // para quem perguntou, que alguns sistemas só aceitam assim.
                    s.networkInterface = ni
                    runCatching { s.send(DatagramPacket(out, out.size, InetSocketAddress(GROUP, PORT))) }
                    s.send(DatagramPacket(out, out.size, InetSocketAddress(p.address, PORT)))
                }
            }
            return
        }
    }

    /** Resposta com o registro A. [question] repete a pergunta (consulta "legada", porta ≠ 5353). */
    private fun answer(id: Int, question: String?, addr: Inet4Address): ByteArray {
        val o = ByteArrayOutputStream()
        fun w16(v: Int) { o.write(v shr 8 and 0xFF); o.write(v and 0xFF) }
        w16(id); w16(0x8400); w16(if (question != null) 1 else 0); w16(1); w16(0); w16(0)
        if (question != null) { writeName(o, HOST); w16(1); w16(1) }
        writeName(o, HOST)
        w16(1); w16(if (question != null) 1 else 0x8001) // classe IN (+ cache-flush no mDNS)
        w16(0); w16(TTL)
        w16(4); o.write(addr.address)
        return o.toByteArray()
    }

    private fun writeName(o: ByteArrayOutputStream, name: String) {
        for (label in name.split('.')) {
            val b = label.toByteArray()
            o.write(b.size); o.write(b)
        }
        o.write(0)
    }

    /** Lê um nome DNS (com ponteiros de compressão). Devolve o nome e o fim dele no pacote. */
    private fun readName(d: ByteArray, len: Int, start: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var off = start
        var end = -1
        var jumps = 0
        while (true) {
            if (off >= len) return null
            val l = d[off].toInt() and 0xFF
            when {
                l == 0 -> { if (end < 0) end = off + 1; break }
                l and 0xC0 == 0xC0 -> {
                    if (off + 1 >= len || ++jumps > 10) return null
                    if (end < 0) end = off + 2
                    off = ((l and 0x3F) shl 8) or (d[off + 1].toInt() and 0xFF)
                }
                else -> {
                    if (off + 1 + l > len) return null
                    if (sb.isNotEmpty()) sb.append('.')
                    sb.append(String(d, off + 1, l, Charsets.UTF_8))
                    off += 1 + l
                }
            }
        }
        return sb.toString() to end
    }

    private fun u16(d: ByteArray, i: Int) = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)

    /** (interface, IPv4, prefixo) das redes locais. */
    private fun lanAddresses(): List<Triple<NetworkInterface, Inet4Address, Int>> = lanInterfaces().flatMap { ni ->
        ni.interfaceAddresses.mapNotNull { ia ->
            (ia.address as? Inet4Address)?.takeIf { it.isSiteLocalAddress }?.let { Triple(ni, it, ia.networkPrefixLength.toInt()) }
        }
    }

    private fun sameSubnet(a: Inet4Address, prefix: Int, b: InetAddress): Boolean {
        if (b !is Inet4Address) return false
        val bits = prefix.coerceIn(0, 32)
        val mask = if (bits == 0) 0 else (-1 shl (32 - bits))
        fun Inet4Address.n() = address.fold(0) { acc, x -> (acc shl 8) or (x.toInt() and 0xFF) }
        return (a.n() and mask) == (b.n() and mask)
    }

    private fun lanInterfaces(): List<NetworkInterface> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { ni ->
            ni.isUp && !ni.isLoopback && ni.supportsMulticast() &&
                ni.inetAddresses.toList().any { it is Inet4Address && it.isSiteLocalAddress } &&
                WebService.isLanInterface(ni.name)
        }
    }.getOrDefault(emptyList())

    companion object {
        private const val TAG = "Index.mDNS"
        const val HOST = "index.local"
        private const val PORT = 5353
        private const val TTL = 120
        private val GROUP: InetAddress = InetAddress.getByName("224.0.0.251")
    }
}
