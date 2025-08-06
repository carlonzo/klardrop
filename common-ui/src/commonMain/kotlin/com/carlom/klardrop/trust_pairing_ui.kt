package com.carlom.klardrop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.carlom.klardrop.common.trust.model.TrustNotification
import com.carlom.klardrop.common.utils.DeviceType
import kotlinx.coroutines.delay

/**
 * Trust pairing notification card that appears when a new device is nearby
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrustPairingNotification(
    notification: TrustNotification.NewDeviceNearby,
    modifier: Modifier = Modifier
) {
    var timeRemaining by remember { mutableIntStateOf(notification.timeoutSeconds) }
    var dismissed by remember { mutableStateOf(false) }
    
    // Countdown timer
    LaunchedEffect(notification) {
        while (timeRemaining > 0 && !dismissed) {
            delay(1000)
            timeRemaining--
        }
        if (!dismissed && timeRemaining <= 0) {
            notification.onDecline()
        }
    }
    
    AnimatedVisibility(
        visible = !dismissed,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Devices,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        
                        Column {
                            Text(
                                text = "New Device Nearby",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            
                            Text(
                                text = notification.device.deviceName,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            
                            Text(
                                text = getDeviceTypeLabel(notification.device.deviceType),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    // Timer indicator
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            progress = { timeRemaining.toFloat() / notification.timeoutSeconds },
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.dp,
                            color = if (timeRemaining < 10) 
                                MaterialTheme.colorScheme.error 
                            else 
                                MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = timeRemaining.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            dismissed = true
                            notification.onDecline()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Decline")
                    }
                    
                    Button(
                        onClick = {
                            dismissed = true
                            notification.onAccept()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Trust")
                    }
                }
            }
        }
    }
}

/**
 * Pairing progress dialog shown during the pairing process
 */
@Composable
fun PairingProgressDialog(
    deviceName: String,
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Animated pairing icon
                PairingAnimation()
                
                Text(
                    text = "Establishing Trust",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                
                Text(
                    text = "Securely pairing with $deviceName",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Pairing result dialog showing success or failure
 */
@Composable
fun PairingResultDialog(
    success: Boolean,
    deviceName: String,
    errorMessage: String? = null,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = if (success) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                text = if (success) "Device Trusted" else "Pairing Failed",
                textAlign = TextAlign.Center
            )
        },
        text = {
            Text(
                text = if (success) {
                    "$deviceName has been added to your trust group. You can now securely share files and sync clipboard."
                } else {
                    errorMessage ?: "Failed to establish trust with $deviceName. Please try again."
                },
                textAlign = TextAlign.Center
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        },
        modifier = modifier
    )
}

/**
 * One-tap trust approval dialog
 */
@Composable
fun QuickTrustDialog(
    deviceUi: DeviceUi,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDecline,
        modifier = modifier,
        icon = {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = "Trust This Device?",
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = deviceUi.deviceName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Text(
                    text = getDeviceTypeLabel(deviceUi.deviceType),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = "Once trusted, this device can:\n• Send and receive files\n• Sync clipboard content\n• Access shared data",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onApprove,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Trust Device")
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text("Not Now")
            }
        }
    )
}

/**
 * Animated pairing visual
 */
@Composable
private fun PairingAnimation() {
    val infiniteTransition = rememberInfiniteTransition(label = "pairing")
    
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    
    Box(
        modifier = Modifier.size(80.dp),
        contentAlignment = Alignment.Center
    ) {
        // Outer ring
        Box(
            modifier = Modifier
                .size(80.dp)
                .scale(scale)
                .border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                    shape = CircleShape
                )
        )
        
        // Inner icon
        Icon(
            imageVector = Icons.Default.Sync,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

private fun getDeviceTypeLabel(deviceType: DeviceType): String {
    return when (deviceType) {
        DeviceType.MOBILE -> "Mobile"
        DeviceType.DESKTOP -> "Desktop"
        DeviceType.UNKNOWN -> "Unknown Device"
    }
}