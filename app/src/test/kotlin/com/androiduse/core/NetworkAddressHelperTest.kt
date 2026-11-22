package com.androiduse.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class NetworkAddressHelperTest {

    @Test
    fun `isTailscaleIpv4 matches RFC 6598 100_64_0_0 slash 10 range accurately`() {
        assertFalse(NetworkAddressHelper.isTailscaleIpv4(ip("100.63.255.255")))
        assertTrue(NetworkAddressHelper.isTailscaleIpv4(ip("100.64.0.0")))
        assertTrue(NetworkAddressHelper.isTailscaleIpv4(ip("100.100.100.100")))
        assertTrue(NetworkAddressHelper.isTailscaleIpv4(ip("100.127.255.255")))
        assertFalse(NetworkAddressHelper.isTailscaleIpv4(ip("100.128.0.0")))
        assertFalse(NetworkAddressHelper.isTailscaleIpv4(ip("192.168.1.10")))
        assertFalse(NetworkAddressHelper.isTailscaleIpv4(ip("127.0.0.1")))
    }

    @Test
    fun `isLoopbackHost identifies loopback hosts only`() {
        assertTrue(NetworkAddressHelper.isLoopbackHost("127.0.0.1"))
        assertTrue(NetworkAddressHelper.isLoopbackHost("localhost"))
        assertTrue(NetworkAddressHelper.isLoopbackHost("::1"))
        assertFalse(NetworkAddressHelper.isLoopbackHost("0.0.0.0"))
        assertFalse(NetworkAddressHelper.isLoopbackHost("100.64.1.2"))
    }

    private fun ip(value: String): ByteArray = InetAddress.getByName(value).address
}
