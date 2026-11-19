package com.carlom.klardrop.common.persistence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.carlom.klardrop.common.utils.Coroutines
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.time.Duration.Companion.days

/** Last authenticated listen port and tailnet addresses of a peer. */
@Serializable
internal data class KnownEndpoint(
  val deviceId: String,
  val port: Int,
  val tailnetAddresses: List<String> = emptyList(),
  val lastSeenMs: Long = 0,
  val lanAddresses: List<String> = emptyList(),
  val lanAddressesSeenMs: Long = 0,
)

/**
 * Where the peers we have connected to listen, so they can be re-dialed over the tailnet when
 * mDNS can't see them. Also keeps this device's own fallback listen port, used when the default
 * port is taken by another process.
 */
internal interface KnownEndpointsRepository {
  /** Entries seen within [KNOWN_ENDPOINT_TTL], keyed by device id. */
  suspend fun endpoints(): Map<String, KnownEndpoint>

  /**
   * Records a peer after an authenticated handshake. Empty [tailnetAddresses] keeps the ones
   * already stored (the peer may just have Tailscale off right now). An address recorded for this
   * peer is dropped from any other entry: the IP was reassigned or the peer reset its identity.
   */
  suspend fun record(deviceId: String, port: Int, tailnetAddresses: List<String>)

  /**
   * Records a peer's manual LAN address. Sets [lanAddresses] (new address first, distinct,
   * capped at 4), refreshes lastSeenMs and port. Evicts the address from any other entry.
   */
  suspend fun recordManual(deviceId: String, port: Int, lanAddress: String)

  suspend fun fallbackListenPort(): Int
  suspend fun saveFallbackListenPort(port: Int)
}

internal val KNOWN_ENDPOINT_TTL = 30.days
internal val LAN_ADDRESS_TTL = 14.days
private const val MAX_KNOWN_ENDPOINTS = 64

internal class KnownEndpointsRepositoryImpl(
  private val dataStore: DataStore<Preferences>,
  private val coroutines: Coroutines,
  private val nowMs: () -> Long,
) : KnownEndpointsRepository {

  private val fallbackPortKey = intPreferencesKey("fallback_listen_port")

  private fun endpointKey(deviceId: String) = byteArrayPreferencesKey("$ENDPOINT_PREFIX$deviceId")

  private fun Preferences.allEndpoints(): List<KnownEndpoint> = asMap().mapNotNull { (key, value) ->
    if (!key.name.startsWith(ENDPOINT_PREFIX)) return@mapNotNull null
    runCatching { ProtoBuf.decodeFromByteArray<KnownEndpoint>(value as ByteArray) }.getOrNull()
  }

  override suspend fun endpoints(): Map<String, KnownEndpoint> = withContext(coroutines.ioDispatcher) {
    val now = nowMs()
    val cutoff = now - KNOWN_ENDPOINT_TTL.inWholeMilliseconds
    val lanCutoff = now - LAN_ADDRESS_TTL.inWholeMilliseconds
    dataStore.data.first().allEndpoints()
      .filter { it.lastSeenMs >= cutoff }
      .map { endpoint ->
        // Drop LAN addresses older than 14 days
        if (endpoint.lanAddresses.isNotEmpty() && endpoint.lanAddressesSeenMs < lanCutoff) {
          endpoint.copy(lanAddresses = emptyList(), lanAddressesSeenMs = 0)
        } else {
          endpoint
        }
      }
      .associateBy { it.deviceId }
  }

  override suspend fun record(deviceId: String, port: Int, tailnetAddresses: List<String>) {
    if (port <= 0) return
    withContext(coroutines.ioDispatcher) {
      dataStore.edit { prefs ->
        val all = prefs.allEndpoints()
        val previous = all.firstOrNull { it.deviceId == deviceId }
        val updated = KnownEndpoint(
          deviceId = deviceId,
          port = port,
          tailnetAddresses = tailnetAddresses.distinct().ifEmpty { previous?.tailnetAddresses.orEmpty() },
          lastSeenMs = nowMs(),
          lanAddresses = previous?.lanAddresses.orEmpty(),
          lanAddressesSeenMs = previous?.lanAddressesSeenMs ?: 0,
        )
        prefs[endpointKey(deviceId)] = ProtoBuf.encodeToByteArray(updated)

        val others = all.filter { it.deviceId != deviceId }
        others.filter { other -> other.tailnetAddresses.any { it in updated.tailnetAddresses } }.forEach { other ->
          prefs[endpointKey(other.deviceId)] = ProtoBuf.encodeToByteArray(
            other.copy(tailnetAddresses = other.tailnetAddresses - updated.tailnetAddresses.toSet()),
          )
        }
        // Peers seen once on some café Wi-Fi shouldn't pile up forever.
        others.sortedByDescending { it.lastSeenMs }.drop(MAX_KNOWN_ENDPOINTS - 1).forEach {
          prefs.remove(endpointKey(it.deviceId))
        }
      }
    }
  }

  override suspend fun recordManual(deviceId: String, port: Int, lanAddress: String) {
    if (port <= 0 || lanAddress.isBlank()) return
    withContext(coroutines.ioDispatcher) {
      dataStore.edit { prefs ->
        val all = prefs.allEndpoints()
        val previous = all.firstOrNull { it.deviceId == deviceId }
        val cleanLan = lanAddress.trim().trim('[', ']').substringBefore('%')
        // Addresses not confirmed within LAN_ADDRESS_TTL are stale: don't carry them forward.
        val fresh = previous?.takeIf { it.lanAddressesSeenMs >= nowMs() - LAN_ADDRESS_TTL.inWholeMilliseconds }
        val newLanAddresses = (listOf(cleanLan) + fresh?.lanAddresses.orEmpty()).distinct().take(4)
        val updated = KnownEndpoint(
          deviceId = deviceId,
          port = port,
          tailnetAddresses = previous?.tailnetAddresses.orEmpty(),
          lastSeenMs = nowMs(),
          lanAddresses = newLanAddresses,
          lanAddressesSeenMs = nowMs(),
        )
        prefs[endpointKey(deviceId)] = ProtoBuf.encodeToByteArray(updated)

        val others = all.filter { it.deviceId != deviceId }
        others.filter { other -> other.lanAddresses.any { it in updated.lanAddresses } }.forEach { other ->
          prefs[endpointKey(other.deviceId)] = ProtoBuf.encodeToByteArray(
            other.copy(lanAddresses = other.lanAddresses - updated.lanAddresses.toSet()),
          )
        }
        others.sortedByDescending { it.lastSeenMs }.drop(MAX_KNOWN_ENDPOINTS - 1).forEach {
          prefs.remove(endpointKey(it.deviceId))
        }
      }
    }
  }

  override suspend fun fallbackListenPort(): Int = withContext(coroutines.ioDispatcher) {
    dataStore.data.first()[fallbackPortKey] ?: 0
  }

  override suspend fun saveFallbackListenPort(port: Int) {
    withContext(coroutines.ioDispatcher) {
      dataStore.edit { it[fallbackPortKey] = port }
    }
  }

  private companion object {
    const val ENDPOINT_PREFIX = "endpoint:"
  }
}
