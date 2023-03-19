package com.carlom.klardrop.common.utils.network

expect class NetworkAddressUtil() {

  fun getLocalAddresses(): Set<String>

}