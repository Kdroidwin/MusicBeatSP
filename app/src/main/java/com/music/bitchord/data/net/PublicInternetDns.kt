package com.music.bitchord.data.net

import okhttp3.Dns
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/** Prevents script-controlled HTTPS clients from reaching local or reserved network addresses. */
object PublicInternetDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val resolved = Dns.SYSTEM.lookup(hostname)
        val public = resolved.filterNot(::isNonPublic)
        if (public.isEmpty()) throw UnknownHostException("Host does not resolve to a public address")
        return public
    }

    private fun isNonPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return true
        val bytes = address.address.map { it.toInt() and 0xff }
        return when (address) {
            is Inet4Address -> {
                val a = bytes[0]
                val b = bytes[1]
                when {
                    a == 0 || a == 10 || a == 127 || a >= 224 -> true
                    a == 100 && b in 64..127 -> true // Shared address space (RFC 6598).
                    a == 169 && b == 254 -> true
                    a == 172 && b in 16..31 -> true
                    a == 192 && b == 168 -> true
                    a == 192 && b == 0 && bytes[2] == 0 -> true
                    a == 192 && b == 0 && bytes[2] == 2 -> true
                    a == 192 && b == 88 && bytes[2] == 99 -> true // Deprecated 6to4 relay anycast.
                    a == 198 && b in 18..19 -> true
                    a == 198 && b == 51 && bytes[2] == 100 -> true
                    a == 203 && b == 0 && bytes[2] == 113 -> true
                    else -> false
                }
            }
            is Inet6Address -> {
                val first = bytes[0]
                val second = bytes[1]
                val ipv4Mapped = bytes.take(10).all { it == 0 } && bytes[10] == 0xff && bytes[11] == 0xff
                val mappedIpv4IsNonPublic = ipv4Mapped && isNonPublic(
                    InetAddress.getByAddress(bytes.takeLast(4).map { it.toByte() }.toByteArray()),
                )
                // Only global-unicast space is eligible. This also rejects
                // IPv6 transition, documentation, and other special ranges
                // that are not covered by Android's site-local helpers.
                val globalUnicast = first in 0x20..0x3f
                !globalUnicast || mappedIpv4IsNonPublic ||
                    (first and 0xfe) == 0xfc || first == 0xfe && (second and 0xc0) == 0x80 ||
                    first == 0xff || bytes.take(4) == listOf(0x20, 0x01, 0x0d, 0xb8) ||
                    (first == 0x3f && second == 0xff && (bytes[2] and 0xf0) == 0x00) ||
                    (first == 0x20 && second == 0x02) || // 6to4 embeds an IPv4 destination.
                    (first == 0x20 && second == 0x01 && bytes[2] == 0 && bytes[3] == 0) // Teredo.
            }
            else -> true
        }
    }
}
