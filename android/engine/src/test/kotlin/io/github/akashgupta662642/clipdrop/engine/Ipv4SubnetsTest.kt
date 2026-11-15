package io.github.akashgupta662642.clipdrop.engine

import io.github.akashgupta662642.clipdrop.engine.Ipv4Subnets.LocalAddress
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Ipv4SubnetsTest {
    @Test
    fun `addresses in the same subnet match`() {
        assertTrue(Ipv4Subnets.onSameSubnet("192.168.1.20", LocalAddress("192.168.1.77", 24)))
        assertTrue(Ipv4Subnets.onSameSubnet("10.20.30.40", LocalAddress("10.99.0.1", 8)))
        assertTrue(Ipv4Subnets.onSameSubnet("172.16.5.4", LocalAddress("172.16.4.1", 23)))
    }

    @Test
    fun `addresses in other subnets don't`() {
        assertFalse(Ipv4Subnets.onSameSubnet("192.168.2.20", LocalAddress("192.168.1.77", 24)))
        assertFalse(Ipv4Subnets.onSameSubnet("172.16.6.4", LocalAddress("172.16.4.1", 23)))
        assertFalse(Ipv4Subnets.onSameSubnet("not-an-ip", LocalAddress("192.168.1.77", 24)))
        assertFalse(Ipv4Subnets.onSameSubnet("192.168.1.20", LocalAddress("192.168.1.77", 33)))
    }

    @Test
    fun `the QR code check passes if any host matches any local address`() {
        val locals = listOf(LocalAddress("10.0.2.15", 24), LocalAddress("192.168.43.2", 24))
        assertTrue(Ipv4Subnets.anyOnSameSubnet(listOf("172.20.0.1", "192.168.43.10"), locals))
        assertFalse(Ipv4Subnets.anyOnSameSubnet(listOf("172.20.0.1"), locals))
        assertFalse(Ipv4Subnets.anyOnSameSubnet(listOf("192.168.43.10"), emptyList()))
    }
}
