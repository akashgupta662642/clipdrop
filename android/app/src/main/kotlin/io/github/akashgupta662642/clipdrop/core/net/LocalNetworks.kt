package io.github.akashgupta662642.clipdrop.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.akashgupta662642.clipdrop.engine.Ipv4Subnets
import java.net.Inet4Address
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.SocketFactory

/**
 * The phone's local network. Sockets are created from the LAN network's own factory, so ClipDrop
 * traffic can never fall back to mobile data. Reconnect-on-Wi-Fi arrives with the NetworkMonitor
 * in a later milestone.
 */
@Singleton
class LocalNetworks @Inject constructor(@ApplicationContext context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /** The active network if it is Wi-Fi or Ethernet, otherwise null. */
    fun lanNetwork(): Network? {
        val network = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        val isLan = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return network.takeIf { isLan }
    }

    fun socketFactory(): SocketFactory = lanNetwork()?.socketFactory ?: SocketFactory.getDefault()

    /** This phone's IPv4 addresses on the LAN, for the pairing diagnostics. */
    fun localAddresses(): List<Ipv4Subnets.LocalAddress> {
        val network = lanNetwork() ?: return emptyList()
        return connectivity.getLinkProperties(network)?.linkAddresses.orEmpty()
            .filter { it.address is Inet4Address }
            .mapNotNull { link -> link.address.hostAddress?.let { Ipv4Subnets.LocalAddress(it, link.prefixLength) } }
    }
}
