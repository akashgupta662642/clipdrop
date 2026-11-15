package io.github.akashgupta662642.clipdrop.engine

/**
 * Subnet arithmetic for the pairing diagnostics of PROTOCOL.md §10.3: when no address in the QR
 * code shares a subnet with the phone, the two devices are on different networks; when one does
 * but nothing answered, a firewall is the likely cause.
 */
public object Ipv4Subnets {
    /** An IPv4 address of this device with its network prefix length, such as 192.168.1.23/24. */
    public data class LocalAddress(val address: String, val prefixLength: Int)

    /** True if [host] lies in the subnet of [local]. Non-IPv4 input is never on the same subnet. */
    public fun onSameSubnet(host: String, local: LocalAddress): Boolean {
        val a = toInt(host) ?: return false
        val b = toInt(local.address) ?: return false
        if (local.prefixLength !in 0..32) return false
        val mask = if (local.prefixLength == 0) 0 else -1 shl (32 - local.prefixLength)
        return a and mask == b and mask
    }

    /** True if any of [hosts] is on the same subnet as any of [locals]. */
    public fun anyOnSameSubnet(hosts: List<String>, locals: List<LocalAddress>): Boolean =
        hosts.any { host -> locals.any { onSameSubnet(host, it) } }

    private fun toInt(address: String): Int? {
        val octets = address.split('.').map { it.toIntOrNull() ?: return null }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return null
        return octets.fold(0) { acc, octet -> (acc shl 8) or octet }
    }
}
