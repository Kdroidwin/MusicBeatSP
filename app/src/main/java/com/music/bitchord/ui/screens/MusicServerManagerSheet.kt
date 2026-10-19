package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.sources.RemoteServerCredentials
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicServerManagerSheet(onDismiss: () -> Unit) {
    val configs by SourceRegistry.configs.collectAsState()
    var editor by remember { mutableStateOf<SourceConfig?>(null) }
    var addKind by remember { mutableStateOf<SourceKind?>(null) }
    val servers = configs.filter { it.kind.isRemoteMusicServer }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.music_servers), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.music_servers_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (servers.isEmpty()) {
                Text(
                    stringResource(R.string.music_servers_empty),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(servers, key = { it.id }) { config ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.WifiTethering, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(config.displayName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${stringResource(if (config.kind == SourceKind.JELLYFIN) R.string.add_jellyfin else R.string.add_navidrome)} · ${config.baseUrl}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            Switch(checked = config.enabled, onCheckedChange = { SourceRegistry.setEnabled(config.id, it) })
                            IconButton(onClick = { editor = config }) {
                                Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.edit))
                            }
                            IconButton(onClick = { SourceRegistry.remove(config.id) }) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.delete))
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = { addKind = SourceKind.JELLYFIN }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.add_jellyfin))
                }
                OutlinedButton(onClick = { addKind = SourceKind.NAVIDROME }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.add_navidrome))
                }
            }
        }
    }

    val target = editor
    if (target != null) {
        RemoteServerEditorDialog(
            initial = target,
            onDismiss = { editor = null },
            onSave = { updated ->
                editor = null
                updated
            },
        )
    }
    val kind = addKind
    if (kind != null) {
        RemoteServerEditorDialog(
            initial = SourceConfig(kind = kind),
            onDismiss = { addKind = null },
            onSave = { saved ->
                addKind = null
                saved
            },
        )
    }
}

@Composable
private fun RemoteServerEditorDialog(
    initial: SourceConfig,
    onDismiss: () -> Unit,
    onSave: (SourceConfig) -> Unit,
) {
    var label by remember(initial.id) { mutableStateOf(initial.label) }
    var url by remember(initial.id) { mutableStateOf(initial.baseUrl) }
    val savedCredentials = remember(initial.id) { SourceRegistry.serverCredentials(initial.id) }
    var username by remember(initial.id) { mutableStateOf(savedCredentials?.username.orEmpty()) }
    var password by remember(initial.id) { mutableStateOf(savedCredentials?.password.orEmpty()) }
    var allowInsecureHttp by remember(initial.id) { mutableStateOf(initial.allowInsecureHttp) }
    var testing by remember(initial.id) { mutableStateOf(false) }
    var status by remember(initial.id) { mutableStateOf<SourceHealth?>(null) }
    var error by remember(initial.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val isJellyfin = initial.kind == SourceKind.JELLYFIN
    val httpOptInMissing = url.trim().startsWith("http://", ignoreCase = true) && !allowInsecureHttp

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text(stringResource(if (isJellyfin) R.string.add_jellyfin else R.string.add_navidrome)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.music_server_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.music_server_url)) },
                    placeholder = { Text(if (isJellyfin) "https://music.example:8096" else "https://music.example") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (url.trim().startsWith("http://", ignoreCase = true)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = allowInsecureHttp,
                            onCheckedChange = { allowInsecureHttp = it },
                        )
                        Text(
                            stringResource(R.string.music_server_allow_http),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.music_server_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.music_server_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.music_server_credential_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (status != null) {
                    val message = when (val result = status) {
                        is SourceHealth.Ok -> result.detail ?: stringResource(R.string.music_server_test_ok)
                        is SourceHealth.Rejected -> result.reason
                        is SourceHealth.Unreachable -> result.reason
                        null -> ""
                    }
                    Text(message, color = if (status is SourceHealth.Ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(
                enabled = url.isNotBlank() && username.isNotBlank() && password.isNotBlank() && !httpOptInMissing && !testing,
                onClick = {
                    error = null
                    val saved = SourceRegistry.saveRemoteServer(
                        initial.copy(label = label.trim(), baseUrl = url.trim(), allowInsecureHttp = allowInsecureHttp),
                        RemoteServerCredentials(username.trim(), password),
                    )
                    saved.fold(
                        onSuccess = { onSave(initial.copy(label = label.trim(), baseUrl = url.trim(), allowInsecureHttp = allowInsecureHttp)) },
                        onFailure = { error = it.message ?: "Could not securely save server credentials" },
                    )
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = url.isNotBlank() && username.isNotBlank() && password.isNotBlank() && !httpOptInMissing && !testing,
                    onClick = {
                        error = null
                        testing = true
                        scope.launch {
                            status = SourceRegistry.probeCandidate(
                                initial.copy(label = label.trim(), baseUrl = url.trim(), allowInsecureHttp = allowInsecureHttp),
                                RemoteServerCredentials(username.trim(), password),
                            )
                            testing = false
                        }
                    },
                ) {
                    if (testing) CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.test_connection))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
