package moe.ouom.neriplayer.lanltw.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import moe.ouom.neriplayer.data.model.lan.LanDiscoveredRoom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** NSD discovery lifetime is owned by the room UI; registration belongs to the host session. */
class LanDiscovery(context: Context) {
    private val manager = context.applicationContext.getSystemService(NsdManager::class.java)
    private val rooms = ConcurrentHashMap<String, LanDiscoveredRoom>()
    private val mutable = MutableStateFlow<List<LanDiscoveredRoom>>(emptyList())
    val nearby: StateFlow<List<LanDiscoveredRoom>> = mutable
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val generation = AtomicLong()
    private val found = ConcurrentHashMap.newKeySet<String>()
    private val pending = java.util.concurrent.ConcurrentLinkedQueue<Pair<NsdServiceInfo, Long>>()
    private val resolving = java.util.concurrent.atomic.AtomicBoolean(false)
    fun advertise(roomId: String, name: String, port: Int, onError: () -> Unit) {
        unregister()
        val info = NsdServiceInfo().apply { serviceName = "Neri-${roomId.take(8)}"; serviceType = TYPE; setPort(port); setAttribute("room", roomId); setAttribute("name", name); setAttribute("version", "2") }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { onError() }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        registration = listener
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { registration = null; onError() }
    }
    fun discover(onError: () -> Unit) {
        if (discovery != null) return
        val ticket = generation.incrementAndGet()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) { if (generation.get() == ticket) { stop(); onError() } }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) { if (generation.get() == ticket) { found.add(info.serviceName); pending.add(info to ticket); resolveNext() } }
            override fun onServiceLost(info: NsdServiceInfo) { if (generation.get() == ticket) { found.remove(info.serviceName); rooms.remove(info.serviceName); publish() } }
        }
        discovery = listener
        runCatching { manager.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { stop(); onError() }
    }
    private fun resolveNext() {
        if (!resolving.compareAndSet(false, true)) return
        val next = pending.poll()
        if (next == null) { resolving.set(false); return }
        resolve(next.first, next.second)
    }
    private fun resolved() { resolving.set(false); resolveNext() }
    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo, ticket: Long) {
        runCatching { manager.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) { resolved() }
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                try {
                if (ticket != generation.get() || resolved.serviceName !in found) return
                val id = resolved.attributes["room"]?.toString(Charsets.UTF_8) ?: return
                val version = resolved.attributes["version"]?.toString(Charsets.UTF_8)
                val address = resolved.host?.hostAddress ?: return
                if (version != "2" || id.length > 64) return
                val name = resolved.attributes["name"]?.toString(Charsets.UTF_8)?.take(24) ?: resolved.serviceName
                rooms[resolved.serviceName] = LanDiscoveredRoom(id, name, address, resolved.port)
                publish()
                } finally { this@LanDiscovery.resolved() }
            }
        }) }.onFailure { resolved() }
    }
    private fun publish() { mutable.value = rooms.values.distinctBy { it.key }.sortedBy { it.name } }
    fun stop() {
        generation.incrementAndGet(); discovery?.let { runCatching { manager.stopServiceDiscovery(it) } }; discovery = null
        found.clear(); pending.clear(); rooms.clear(); publish()
    }
    fun unregister() { registration?.let { runCatching { manager.unregisterService(it) } }; registration = null }
    companion object { private const val TYPE = "_neriplayer._tcp." }
}
