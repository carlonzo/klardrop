package com.carlom.klardrop.common.mdns

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference

internal interface DnsSdLibrary : Library {

  fun DNSServiceBrowse(
    sdRef: PointerByReference,
    flags: Int,
    interfaceIndex: Int,
    regtype: String,
    domain: String?,
    callBack: BrowseCallback,
    context: Pointer?,
  ): Int

  fun DNSServiceResolve(
    sdRef: PointerByReference,
    flags: Int,
    interfaceIndex: Int,
    name: String,
    regtype: String,
    domain: String,
    callBack: ResolveCallback,
    context: Pointer?,
  ): Int

  fun DNSServiceGetAddrInfo(
    sdRef: PointerByReference,
    flags: Int,
    interfaceIndex: Int,
    protocol: Int,
    hostname: String,
    callBack: GetAddrInfoCallback,
    context: Pointer?,
  ): Int

  fun DNSServiceRegister(
    sdRef: PointerByReference,
    flags: Int,
    interfaceIndex: Int,
    name: String?,
    regtype: String,
    domain: String?,
    host: String?,
    port: Short,
    txtLen: Short,
    txtRecord: ByteArray?,
    callBack: RegisterCallback?,
    context: Pointer?,
  ): Int

  fun DNSServiceProcessResult(sdRef: Pointer): Int

  fun DNSServiceRefSockFD(sdRef: Pointer): Int

  fun DNSServiceRefDeallocate(sdRef: Pointer)

  fun interface BrowseCallback : Callback {
    fun invoke(
      sdRef: Pointer,
      flags: Int,
      interfaceIndex: Int,
      errorCode: Int,
      serviceName: String?,
      regtype: String?,
      replyDomain: String?,
      context: Pointer?,
    )
  }

  fun interface ResolveCallback : Callback {
    fun invoke(
      sdRef: Pointer,
      flags: Int,
      interfaceIndex: Int,
      errorCode: Int,
      fullname: String?,
      hosttarget: String?,
      port: Short,
      txtLen: Short,
      txtRecord: Pointer?,
      context: Pointer?,
    )
  }

  fun interface GetAddrInfoCallback : Callback {
    fun invoke(
      sdRef: Pointer,
      flags: Int,
      interfaceIndex: Int,
      errorCode: Int,
      hostname: String?,
      address: Pointer?,
      ttl: Int,
      context: Pointer?,
    )
  }

  fun interface RegisterCallback : Callback {
    fun invoke(
      sdRef: Pointer,
      flags: Int,
      errorCode: Int,
      name: String?,
      regtype: String?,
      domain: String?,
      context: Pointer?,
    )
  }

  companion object {
    val INSTANCE: DnsSdLibrary by lazy {
      Native.load("dns_sd", DnsSdLibrary::class.java)
    }

    const val FLAG_ADD: Int = 0x2
    const val FLAG_MORE_COMING: Int = 0x1
    const val PROTOCOL_IPV4: Int = 1
    const val ERR_NO_ERROR: Int = 0
  }
}

internal interface PosixCLibrary : Library {
  fun poll(fds: PollFd, nfds: Int, timeout: Int): Int

  companion object {
    val INSTANCE: PosixCLibrary by lazy {
      Native.load("c", PosixCLibrary::class.java)
    }

    const val POLLIN: Short = 0x0001
  }
}

@Structure.FieldOrder("fd", "events", "revents")
internal open class PollFd : Structure() {
  @JvmField var fd: Int = 0
  @JvmField var events: Short = 0
  @JvmField var revents: Short = 0
}
