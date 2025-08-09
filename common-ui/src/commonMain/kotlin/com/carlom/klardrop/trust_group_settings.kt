package com.carlom.klardrop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.common.trust.model.TrustGroup
import kotlinx.coroutines.flow.StateFlow
import com.carlom.klardrop.utils.TimeFormatUtils

/**
 * Trust group settings screen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrustGroupSettingsScreen(
    trustGroup: StateFlow<TrustGroup?>,
    deviceName: StateFlow<String>,
    onUpdateGroupName: (String) -> Unit,
    onUpdateDeviceName: (String) -> Unit,
    onRotateGroupKey: () -> Unit,
    onExportGroup: (password: String) -> Unit,
    onImportGroup: (password: String) -> Unit,
    onDeleteGroup: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val group by trustGroup.collectAsState()
    val currentDeviceName by deviceName.collectAsState()
    
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trust Group Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        modifier = modifier
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Device name section
            item {
                DeviceNameSection(
                    currentName = currentDeviceName,
                    onUpdateName = onUpdateDeviceName
                )
            }
            
            // Group info section
            group?.let { trustGroup ->
                item {
                    GroupInfoSection(
                        group = trustGroup,
                        onUpdateGroupName = onUpdateGroupName
                    )
                }
                
                // Security section
                item {
                    SecuritySection(
                        onRotateKey = onRotateGroupKey,
                        onExport = { showExportDialog = true },
                        onImport = { showImportDialog = true }
                    )
                }
                
                // Danger zone
                item {
                    DangerZoneSection(
                        onDeleteGroup = { showDeleteDialog = true }
                    )
                }
            }
        }
    }
    
    // Export dialog
    if (showExportDialog) {
        PasswordDialog(
            title = "Export Trust Group",
            message = "Enter a password to encrypt your trust group data",
            confirmText = "Export",
            onDismiss = { showExportDialog = false },
            onConfirm = { password ->
                onExportGroup(password)
                showExportDialog = false
            }
        )
    }
    
    // Import dialog
    if (showImportDialog) {
        PasswordDialog(
            title = "Import Trust Group",
            message = "Enter the password used to encrypt the trust group data",
            confirmText = "Import",
            onDismiss = { showImportDialog = false },
            onConfirm = { password ->
                onImportGroup(password)
                showImportDialog = false
            }
        )
    }
    
    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Delete Trust Group?") },
            text = { 
                Text("This will remove all trusted devices and delete all trust data. This action cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteGroup()
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Device name section
 */
@Composable
private fun DeviceNameSection(
    currentName: String,
    onUpdateName: (String) -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }
    var editedName by remember(currentName) { mutableStateOf(currentName) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Device Name",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            if (isEditing) {
                OutlinedTextField(
                    value = editedName,
                    onValueChange = { editedName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Device Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    trailingIcon = {
                        Row {
                            IconButton(
                                onClick = {
                                    onUpdateName(editedName)
                                    isEditing = false
                                }
                            ) {
                                Icon(Icons.Default.Check, contentDescription = "Save")
                            }
                            IconButton(
                                onClick = {
                                    editedName = currentName
                                    isEditing = false
                                }
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel")
                            }
                        }
                    }
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = currentName,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    
                    IconButton(onClick = { isEditing = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit")
                    }
                }
            }
            
            Text(
                text = "This name is shown to other devices in your trust group",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Group info section
 */
@Composable
private fun GroupInfoSection(
    group: TrustGroup,
    onUpdateGroupName: (String) -> Unit
) {
    var isEditingName by remember { mutableStateOf(false) }
    var editedGroupName by remember(group.groupName) { mutableStateOf(group.groupName ?: "") }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Group Information",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            // Group name
            if (isEditingName) {
                OutlinedTextField(
                    value = editedGroupName,
                    onValueChange = { editedGroupName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Group Name (Optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    trailingIcon = {
                        Row {
                            IconButton(
                                onClick = {
                                    onUpdateGroupName(editedGroupName)
                                    isEditingName = false
                                }
                            ) {
                                Icon(Icons.Default.Check, contentDescription = "Save")
                            }
                            IconButton(
                                onClick = {
                                    editedGroupName = group.groupName ?: ""
                                    isEditingName = false
                                }
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel")
                            }
                        }
                    }
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Group Name",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = group.groupName ?: "Unnamed Group",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    
                    IconButton(onClick = { isEditingName = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit")
                    }
                }
            }
            
            Divider()
            
            // Group stats
            InfoRow("Group ID", group.groupId.take(8) + "...")
            InfoRow("Devices", "${group.devices.size} connected")
            InfoRow("Created", TimeFormatUtils.formatRelativeTime(group.createdAt))
            InfoRow("Last Updated", TimeFormatUtils.formatRelativeTime(group.updatedAt))
            InfoRow("Protocol Version", "v${group.protocolVersion}")
        }
    }
}

/**
 * Security section
 */
@Composable
private fun SecuritySection(
    onRotateKey: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Security",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            // Rotate group key
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = onRotateKey
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Key,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "Rotate Group Key",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Generate new encryption key for enhanced security",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // Export/Import
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onExport,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Upload, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Export")
                }
                
                OutlinedButton(
                    onClick = onImport,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Import")
                }
            }
        }
    }
}

/**
 * Danger zone section
 */
@Composable
private fun DangerZoneSection(
    onDeleteGroup: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Danger Zone",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            
            Text(
                text = "Deleting the trust group will remove all trusted devices and cannot be undone",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            
            Button(
                onClick = onDeleteGroup,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Delete Trust Group")
            }
        }
    }
}

/**
 * Info row helper
 */
@Composable
private fun InfoRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * Password dialog for export/import
 */
@Composable
private fun PasswordDialog(
    title: String,
    message: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(message)
                
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (showPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = if (showPassword) "Hide password" else "Show password"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password) },
                enabled = password.isNotEmpty()
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Format relative time
 */
@OptIn(kotlin.time.ExperimentalTime::class)
private fun TimeFormatUtils.formatRelativeTime(timestamp: Long): String {
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val diff = now - timestamp
    
    return when {
        diff < 60_000 -> "just now"
        diff < 3600_000 -> "${diff / 60_000} minutes ago"
        diff < 86400_000 -> "${diff / 3600_000} hours ago"
        else -> "${diff / 86400_000} days ago"
    }
}

