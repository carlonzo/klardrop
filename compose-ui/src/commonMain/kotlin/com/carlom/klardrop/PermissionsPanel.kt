package com.carlom.klardrop

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.carlom.klardrop.common.permissions.Capability
import com.carlom.klardrop.common.permissions.CapabilityStatus
import com.carlom.klardrop.common.permissions.PermissionsState
import com.carlom.klardrop.components.KdPermissionItem
import com.carlom.klardrop.components.PermissionsChecklist
import com.carlom.klardrop.theme.KdTheme

@Composable
fun PermissionsPanel(
    state: PermissionsState,
    onRequestCapability: (Capability) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dismissedNotes by rememberSaveable { mutableStateOf(setOf<String>()) }

    val spacing = KdTheme.spacing

    val pendingCapabilities = remember(state) {
        state.capabilities
            .filter { (_, status) -> status == CapabilityStatus.Denied || status == CapabilityStatus.Unknown }
            .keys
            .toList()
    }

    val visibleNotes = remember(state, dismissedNotes) {
        state.educationalNotes.filterNot { it.id in dismissedNotes }
    }

    if (pendingCapabilities.isEmpty() && visibleNotes.isEmpty()) return

    val items = buildList {
        pendingCapabilities.forEach { capability ->
            val (title, caption) = capabilityCopy(capability)
            add(
                KdPermissionItem(
                    id = capability.name,
                    title = title,
                    caption = caption,
                    isGranted = false,
                )
            )
        }
        visibleNotes.forEach { note ->
            add(
                KdPermissionItem(
                    id = note.id,
                    title = note.message,
                    caption = "",
                    isGranted = false,
                )
            )
        }
    }

    PermissionsChecklist(
        items = items,
        onAllow = { item ->
            val capability = pendingCapabilities.firstOrNull { it.name == item.id }
            if (capability != null) {
                onRequestCapability(capability)
            } else {
                dismissedNotes = dismissedNotes + item.id
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.s4, vertical = spacing.s2),
    )
}

private fun capabilityCopy(capability: Capability): Pair<String, String> = when (capability) {
    Capability.LOCAL_NETWORK -> "Local network" to
        "Needed to find other devices on your Wi-Fi."
    Capability.BLUETOOTH -> "Bluetooth" to
        "Used as a fallback transport when Wi-Fi reachability isn't enough."
    Capability.NOTIFICATIONS -> "Notifications" to
        "Lets Klardrop alert you when a transfer arrives."
    Capability.NEARBY_WIFI_DEVICES -> "Nearby Wi-Fi devices" to
        "Required on Android 13+ to discover peers without sharing your location."
    Capability.LOCATION -> "Location" to
        "Required on older Android versions to scan for nearby Bluetooth devices."
}
