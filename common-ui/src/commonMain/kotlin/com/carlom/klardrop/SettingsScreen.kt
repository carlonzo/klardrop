package com.carlom.klardrop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import com.carlom.klardrop.common.CommonPlatformDependencies
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    discoveryController: DiscoveryController
) {
    val scope = rememberCoroutineScope()
    val discoveryState by discoveryController.screenStateFlow.collectAsState()
    var isEditingName by remember { mutableStateOf(false) }
    var tempDeviceName by remember { mutableStateOf("") }

    LaunchedEffect(discoveryState.currentDeviceName) {
        if (tempDeviceName.isEmpty()) {
            discoveryState.currentDeviceName?.let { currentName ->
                if (currentName.isNotEmpty()) {
                    tempDeviceName = currentName
                }
            }
        }
    }

    Scaffold(
        topBar = {
            SettingsTopAppBar(
                onBackClicked = { discoveryController.onBackFromSettings() }
            )
        }
    ) { paddingValues ->
        SettingsContent(
            modifier = modifier
                .padding(paddingValues)
                .fillMaxSize(),
            currentDeviceName = discoveryState.currentDeviceName ?: "",
            systemDeviceName = discoveryState.systemDeviceName ?: "",
            isEditingName = isEditingName,
            tempDeviceName = tempDeviceName,
            onEditNameClick = {
                tempDeviceName = discoveryState.currentDeviceName ?: ""
                isEditingName = true
            },
            onTempNameChange = { tempDeviceName = it },
            onSaveNameClick = {
                scope.launch {
                    discoveryController.saveCustomDeviceName(tempDeviceName.ifBlank { null })
                    isEditingName = false
                }
            },
            onCancelNameClick = {
                isEditingName = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopAppBar(
    onBackClicked: () -> Unit
) {
    TopAppBar(
        title = { Text("Settings") },
        navigationIcon = {
            IconButton(onClick = onBackClicked) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }
        }
    )
}

@Composable
private fun SettingsContent(
    modifier: Modifier = Modifier,
    currentDeviceName: String,
    systemDeviceName: String,
    isEditingName: Boolean,
    tempDeviceName: String,
    onEditNameClick: () -> Unit,
    onTempNameChange: (String) -> Unit,
    onSaveNameClick: () -> Unit,
    onCancelNameClick: () -> Unit
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        DeviceNameSection(
            currentDeviceName = currentDeviceName,
            systemDeviceName = systemDeviceName,
            isEditingName = isEditingName,
            tempDeviceName = tempDeviceName,
            onEditNameClick = onEditNameClick,
            onTempNameChange = onTempNameChange,
            onSaveNameClick = onSaveNameClick,
            onCancelNameClick = onCancelNameClick
        )
        
        // Additional settings sections can be added here
        // e.g., AppearanceSection(), NotificationSection(), etc.
    }
}

@Composable
private fun DeviceNameSection(
    currentDeviceName: String,
    systemDeviceName: String,
    isEditingName: Boolean,
    tempDeviceName: String,
    onEditNameClick: () -> Unit,
    onTempNameChange: (String) -> Unit,
    onSaveNameClick: () -> Unit,
    onCancelNameClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Device Name",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        
        if (isEditingName) {
            DeviceNameEditContent(
                tempDeviceName = tempDeviceName,
                onTempNameChange = onTempNameChange,
                onSaveNameClick = onSaveNameClick,
                onCancelNameClick = onCancelNameClick
            )
        } else {
            DeviceNameDisplayContent(
                currentDeviceName = currentDeviceName,
                systemDeviceName = systemDeviceName,
                onEditNameClick = onEditNameClick
            )
        }
    }
}

@Composable
private fun DeviceNameDisplayContent(
    currentDeviceName: String,
    systemDeviceName: String,
    onEditNameClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = currentDeviceName.takeIf { it.isNotBlank() } ?: systemDeviceName,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onEditNameClick) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "Edit device name"
                )
            }
        }
        
        Text(
            text = if (currentDeviceName.isNotEmpty() && currentDeviceName != systemDeviceName) {
                "System name: $systemDeviceName"
            } else {
                "Using system name: $systemDeviceName"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DeviceNameEditContent(
    tempDeviceName: String,
    onTempNameChange: (String) -> Unit,
    onSaveNameClick: () -> Unit,
    onCancelNameClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = tempDeviceName,
            onValueChange = onTempNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Device Name") },
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { onSaveNameClick() }
            ),
            singleLine = true
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
        ) {
            TextButton(onClick = onCancelNameClick) {
                Text("Cancel")
            }
            Button(
                onClick = onSaveNameClick,
                enabled = tempDeviceName.isNotBlank()
            ) {
                Text("Save")
            }
        }
    }
}