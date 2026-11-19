package com.music.bitchord.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.documentfile.provider.DocumentFile
import com.music.bitchord.R
import com.music.bitchord.data.stats.PERIODS_MINUTES
import com.music.bitchord.data.stats.PeriodicBackupConfig
import com.music.bitchord.data.stats.PeriodicBackupFileMode
import com.music.bitchord.data.stats.PeriodicBackupKind
import com.music.bitchord.data.stats.PeriodicBackupScheduler
import com.music.bitchord.data.stats.PeriodicBackupSecretStore
import com.music.bitchord.data.stats.PeriodicBackupStore
import kotlinx.coroutines.delay

@Composable
internal fun PeriodicBackupDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var config by remember(context) { mutableStateOf(PeriodicBackupStore.load(context)) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var passwordConfirm by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var setupMessage by remember { mutableStateOf<String?>(null) }
    val hasPassword = remember(config, showPasswordDialog) { PeriodicBackupSecretStore.hasPassword(context) }

    fun update(transform: (PeriodicBackupConfig) -> PeriodicBackupConfig, kind: PeriodicBackupKind? = null) {
        PeriodicBackupStore.update(context, transform)
        config = PeriodicBackupStore.load(context)
        if (kind != null) PeriodicBackupScheduler.schedule(context, kind)
    }

    val chooseExportFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && persistTreePermission(context, uri)) {
            update({ it.copy(exportFolder = uri.toString()) }, PeriodicBackupKind.EXPORT)
            setupMessage = null
        } else if (uri != null) {
            setupMessage = context.getString(R.string.periodic_backup_folder_permission_error)
        }
    }
    val chooseImportFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && persistTreePermission(context, uri)) {
            update({ it.copy(importFolder = uri.toString()) }, PeriodicBackupKind.IMPORT)
            setupMessage = null
        } else if (uri != null) {
            setupMessage = context.getString(R.string.periodic_backup_folder_permission_error)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            config = PeriodicBackupStore.load(context)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    stringResource(R.string.periodic_backup_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.periodic_backup_description), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))

                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.periodic_backup_export), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.periodic_backup_export_description), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = config.exportEnabled,
                            onCheckedChange = { enabled ->
                                if (!enabled || (config.exportFolder != null && hasPassword)) {
                                    update({ it.copy(exportEnabled = enabled) }, PeriodicBackupKind.EXPORT)
                                    setupMessage = null
                                } else {
                                    setupMessage = context.getString(R.string.periodic_backup_setup_required)
                                }
                            },
                        )
                    }
                    TextButton(onClick = { chooseExportFolder.launch(null) }) {
                        Text(
                            stringResource(
                                R.string.periodic_backup_export_folder,
                                folderDisplayName(context, config.exportFolder),
                            ),
                        )
                    }
                    IntervalMenu(
                        title = stringResource(R.string.periodic_backup_interval),
                        minutes = config.exportIntervalMinutes,
                        onSelect = { interval -> update({ it.copy(exportIntervalMinutes = interval) }, PeriodicBackupKind.EXPORT) },
                    )

                    Text(stringResource(R.string.periodic_backup_file_mode), style = MaterialTheme.typography.titleSmall)
                    FileModeChoice(
                        selected = config.fileMode == PeriodicBackupFileMode.OVERWRITE,
                        label = stringResource(R.string.periodic_backup_overwrite),
                        onClick = { update({ it.copy(fileMode = PeriodicBackupFileMode.OVERWRITE) }) },
                    )
                    FileModeChoice(
                        selected = config.fileMode == PeriodicBackupFileMode.KEEP_ALL,
                        label = stringResource(R.string.periodic_backup_keep_all),
                        onClick = { update({ it.copy(fileMode = PeriodicBackupFileMode.KEEP_ALL) }) },
                    )
                    FileModeChoice(
                        selected = config.fileMode == PeriodicBackupFileMode.KEEP_LIMIT,
                        label = stringResource(R.string.periodic_backup_keep_limit),
                        onClick = { update({ it.copy(fileMode = PeriodicBackupFileMode.KEEP_LIMIT) }) },
                    )
                    if (config.fileMode == PeriodicBackupFileMode.KEEP_LIMIT) {
                        Text(stringResource(R.string.periodic_backup_keep_count, config.keepFileCount))
                        Slider(
                            value = config.keepFileCount.toFloat(),
                            onValueChange = { count -> update({ it.copy(keepFileCount = count.toInt().coerceIn(1, 100)) }) },
                            valueRange = 1f..100f,
                            steps = 98,
                        )
                    }
                    config.lastExportStatus?.let { Text(stringResource(R.string.periodic_backup_last_export, it), style = MaterialTheme.typography.bodySmall) }

                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.periodic_backup_import), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.periodic_backup_import_description), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = config.importEnabled,
                            onCheckedChange = { enabled ->
                                if (!enabled || (config.importFolder != null && hasPassword)) {
                                    update({ it.copy(importEnabled = enabled) }, PeriodicBackupKind.IMPORT)
                                    setupMessage = null
                                } else {
                                    setupMessage = context.getString(R.string.periodic_backup_setup_required)
                                }
                            },
                        )
                    }
                    TextButton(onClick = { chooseImportFolder.launch(null) }) {
                        Text(
                            stringResource(
                                R.string.periodic_backup_import_folder,
                                folderDisplayName(context, config.importFolder),
                            ),
                        )
                    }
                    IntervalMenu(
                        title = stringResource(R.string.periodic_backup_interval),
                        minutes = config.importIntervalMinutes,
                        onSelect = { interval -> update({ it.copy(importIntervalMinutes = interval) }, PeriodicBackupKind.IMPORT) },
                    )
                    Text(stringResource(R.string.periodic_backup_import_warning), style = MaterialTheme.typography.bodySmall)
                    config.lastImportStatus?.let { Text(stringResource(R.string.periodic_backup_last_import, it), style = MaterialTheme.typography.bodySmall) }

                    HorizontalDivider()
                    TextButton(onClick = { showPasswordDialog = true; password = ""; passwordConfirm = ""; passwordError = null }) {
                        Text(stringResource(if (hasPassword) R.string.periodic_backup_change_password else R.string.periodic_backup_set_password))
                    }
                    if (hasPassword) {
                        TextButton(onClick = {
                            PeriodicBackupSecretStore.clear(context)
                            update({ it.copy(exportEnabled = false, importEnabled = false) }, PeriodicBackupKind.EXPORT)
                            PeriodicBackupScheduler.schedule(context, PeriodicBackupKind.IMPORT)
                            setupMessage = context.getString(R.string.periodic_backup_password_removed)
                        }) {
                            Text(stringResource(R.string.periodic_backup_remove_password))
                        }
                    }
                    setupMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }

                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                }
            }
        }
    }

    if (showPasswordDialog) {
        AlertDialog(
            onDismissRequest = { showPasswordDialog = false },
            title = { Text(stringResource(R.string.periodic_backup_password_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.periodic_backup_password_description))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; passwordError = null },
                        label = { Text(stringResource(R.string.backup_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = passwordConfirm,
                        onValueChange = { passwordConfirm = it; passwordError = null },
                        label = { Text(stringResource(R.string.backup_password_confirmation)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    passwordError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (password.length < 8) {
                        passwordError = context.getString(R.string.backup_password_too_short)
                    } else if (password != passwordConfirm) {
                        passwordError = context.getString(R.string.backup_password_mismatch)
                    } else {
                        val secret = password.toCharArray()
                        try {
                            PeriodicBackupSecretStore.save(context, secret)
                            showPasswordDialog = false
                            config = PeriodicBackupStore.load(context)
                            PeriodicBackupScheduler.schedule(context, PeriodicBackupKind.EXPORT)
                            PeriodicBackupScheduler.schedule(context, PeriodicBackupKind.IMPORT)
                            setupMessage = context.getString(R.string.periodic_backup_password_saved)
                        } catch (error: Exception) {
                            passwordError = error.message ?: context.getString(R.string.unknown_error)
                        } finally {
                            secret.fill('\u0000')
                            password = ""
                            passwordConfirm = ""
                        }
                    }
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showPasswordDialog = false; password = ""; passwordConfirm = "" }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun IntervalMenu(title: String, minutes: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("$title: ${stringResource(intervalLabel(minutes))}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PERIODS_MINUTES.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(intervalLabel(option))) },
                    onClick = { expanded = false; onSelect(option) },
                )
            }
        }
    }
}

@Composable
private fun intervalLabel(minutes: Int): Int = when (minutes) {
    5 -> R.string.periodic_backup_interval_5m
    10 -> R.string.periodic_backup_interval_10m
    30 -> R.string.periodic_backup_interval_30m
    60 -> R.string.periodic_backup_interval_1h
    360 -> R.string.periodic_backup_interval_6h
    720 -> R.string.periodic_backup_interval_12h
    1440 -> R.string.periodic_backup_interval_1d
    10080 -> R.string.periodic_backup_interval_1w
    else -> R.string.periodic_backup_interval_1mo
}

@Composable
private fun FileModeChoice(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onClick) { Text(label) }
    }
}

private fun persistTreePermission(context: android.content.Context, uri: Uri): Boolean = runCatching {
    context.contentResolver.takePersistableUriPermission(
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    )
}.isSuccess

@Composable
private fun folderDisplayName(context: android.content.Context, folder: String?): String {
    if (folder == null) return stringResource(R.string.periodic_backup_folder_not_selected)
    return runCatching { DocumentFile.fromTreeUri(context, Uri.parse(folder))?.name }
        .getOrNull()
        ?.takeIf(String::isNotBlank)
        ?: stringResource(R.string.periodic_backup_folder_selected)
}
