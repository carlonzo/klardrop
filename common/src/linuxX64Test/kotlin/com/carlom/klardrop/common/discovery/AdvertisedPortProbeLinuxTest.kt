@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.carlom.klardrop.common.discovery

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.htonl
import platform.posix.listen
import platform.posix.ntohs
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socklen_tVar
import platform.posix.socket
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val INADDR_LOOPBACK_VALUE = 0x7F000001u

class AdvertisedPortProbeLinuxTest {

    @Test
    fun liveListenerReportsAlive() = memScoped {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        assertTrue(fd >= 0)
        try {
            val addr = alloc<sockaddr_in>().apply {
                sin_family = AF_INET.toUShort()
                sin_port = 0u
                sin_addr.s_addr = htonl(INADDR_LOOPBACK_VALUE)
            }
            val bindRet = bind(fd, addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().toUInt())
            assertTrue(bindRet == 0)
            val listenRet = listen(fd, 5)
            assertTrue(listenRet == 0)

            val boundAddr = alloc<sockaddr_in>()
            val len = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().toUInt() }
            getsockname(fd, boundAddr.ptr.reinterpret<sockaddr>(), len.ptr)
            val assignedPort = ntohs(boundAddr.sin_port).toInt()
            assertTrue(assignedPort > 0)

            assertTrue(verifyAdvertisedPortAlive(assignedPort))
        } finally {
            close(fd)
        }
    }

    @Test
    fun closedPortReportsDead() = memScoped {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        assertTrue(fd >= 0)
        val addr = alloc<sockaddr_in>().apply {
            sin_family = AF_INET.toUShort()
            sin_port = 0u
            sin_addr.s_addr = htonl(INADDR_LOOPBACK_VALUE)
        }
        bind(fd, addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().toUInt())
        val boundAddr = alloc<sockaddr_in>()
        val len = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().toUInt() }
        getsockname(fd, boundAddr.ptr.reinterpret<sockaddr>(), len.ptr)
        val deadPort = ntohs(boundAddr.sin_port).toInt()
        close(fd)

        assertFalse(verifyAdvertisedPortAlive(deadPort), "closed port must not report alive")
    }
}
