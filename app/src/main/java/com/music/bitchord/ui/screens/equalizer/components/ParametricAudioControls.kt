package com.music.bitchord.ui.screens.equalizer.components

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.ParametricEqBand
import com.music.bitchord.data.settings.ParametricEqChannelTarget
import com.music.bitchord.data.settings.ParametricEqConfig
import com.music.bitchord.data.settings.ParametricEqFilterType
import com.music.bitchord.data.settings.ParametricEqPreset
import com.music.bitchord.playback.equalizer.BiquadUtils
import com.music.bitchord.playback.equalizer.EelEqParser
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParametricEqControls(
    config: ParametricEqConfig,
    presets: List<ParametricEqPreset>,
    onConfigChange: (ParametricEqConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) } // 0: 構成・一覧, 1: Global, 2: Left, 3: Right
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var showRawEelDialog by remember { mutableStateOf(false) }
    var editingBandTarget by remember { mutableStateOf<Pair<ParametricEqChannelTarget, Int>?>(null) }
    var editingPreampDialog by remember { mutableStateOf(false) }
    var expandedBandIds by remember { mutableStateOf(setOf<String>()) }

    val report = remember(config) {
        EelEqParser.analyze(config)
    }

    val importEelLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val displayName = queryDisplayName(context, uri) ?: "imported_eq.eel"
                    val text = context.contentResolver.openInputStream(uri)?.use(::readBoundedUtf8)
                        ?: error("ファイルを読み込めませんでした")
                    AppSettings.importEelPreset(text, displayName)
                }
            }
            result.onSuccess { parsed ->
                selectedTab = 0
                Toast.makeText(
                    context,
                    "プリセット「${parsed.preset.name}」を読み込み・保存しました",
                    Toast.LENGTH_SHORT,
                ).show()
            }.onFailure { err ->
                Toast.makeText(
                    context,
                    "読み込みエラー: ${err.message}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    val exportEelLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val eelText = BiquadUtils.toEelString(config)
            context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
                it.write(eelText)
            }
            Toast.makeText(context, ".eelファイルを書き出しました", Toast.LENGTH_SHORT).show()
        }.onFailure { err ->
            Toast.makeText(context, "書き出しエラー: ${err.message}", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                RoundedCornerShape(18.dp),
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. Header & Master Switch
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            ) {
                Text(
                    text = "高精度 PEQ / 左右独立EQ / EEL DSP",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "RootlessJamesDSP準拠 64-bit Biquad・L/R独立PEQ・チャンネルスワップ (アプリ内専用)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = config.enabled,
                onCheckedChange = { AppSettings.setParametricEqEnabled(it) },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }

        // 2. Action Toolbar (.eel Import / Sample / Save Preset / Export / Reset)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = { importEelLauncher.launch(arrayOf("*/*")) },
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.FileOpen,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(".eel / EQ読込", style = MaterialTheme.typography.labelMedium)
            }

            OutlinedButton(
                onClick = { showSavePresetDialog = true },
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.BookmarkAdd,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("プリセット保存", style = MaterialTheme.typography.labelMedium)
            }

            OutlinedButton(
                onClick = {
                    val parsed = AppSettings.loadBundledSampleEel()
                    if (parsed != null) {
                        selectedTab = 0
                        Toast.makeText(
                            context,
                            "「${parsed.preset.name}」を読み込みました",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Analytics,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("ig955 eq_swap.eel読込", style = MaterialTheme.typography.labelMedium)
            }

            OutlinedButton(
                onClick = {
                    val suggested = (config.activePresetName ?: "custom_peq")
                        .replace(Regex("""[^\w\-. ]"""), "_") + ".eel"
                    exportEelLauncher.launch(suggested)
                },
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.FileUpload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(".eel書出", style = MaterialTheme.typography.labelMedium)
            }

            OutlinedButton(
                onClick = { showRawEelDialog = true },
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Code,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("EELコード確認", style = MaterialTheme.typography.labelMedium)
            }

            OutlinedButton(
                onClick = {
                    AppSettings.resetParametricEqConfig()
                    Toast.makeText(context, "PEQ・スワップを初期状態にリセットしました", Toast.LENGTH_SHORT).show()
                },
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.RestartAlt,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("フラットに戻す", style = MaterialTheme.typography.labelMedium)
            }
        }

        // 3. Saved Presets Section (いつでも呼び出し・削除可能)
        SavedEelPresetsSection(
            presets = presets,
            activePresetId = config.activePresetId,
            onSelectPreset = { preset ->
                AppSettings.selectParametricEqPreset(preset.id)
                Toast.makeText(context, "プリセット「${preset.name}」を適用しました", Toast.LENGTH_SHORT).show()
            },
            onDeletePreset = { preset ->
                AppSettings.deleteParametricEqPreset(preset.id)
                Toast.makeText(context, "プリセット「${preset.name}」を削除しました", Toast.LENGTH_SHORT).show()
            },
        )

        // 4. Frequency Response Curve Graph (Global, Left, Right)
        ParametricEqResponseGraph(
            config = config,
            modifier = Modifier.fillMaxWidth(),
        )

        // 5. Active EQ & Channel Swap Summary Banner (具体的にどのようなEQやスワップが生じているか表示)
        ActiveDspSummaryCard(
            config = config,
            report = report,
        )

        // 6. Channel Swap & Preamp Quick Controls
        ChannelSwapAndPreampCard(
            config = config,
            onEditPreampNumeric = { editingPreampDialog = true },
        )

        // 7. Channel / View Mode Tabs
        val tabs = listOf(
            "詳細一覧 (${report.globalBands.size + report.leftBands.size + report.rightBands.size})",
            "両ch共通 (${config.globalBands.size})",
            "左ch独立 (${config.leftBands.size})",
            "右ch独立 (${config.rightBands.size})",
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tabs.forEachIndexed { idx, label ->
                FilterChip(
                    selected = selectedTab == idx,
                    onClick = { selectedTab = idx },
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                    shape = RoundedCornerShape(10.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
        }

        when (selectedTab) {
            0 -> DetailedFilterBreakdownView(
                config = config,
                report = report,
                onJumpToEdit = { target, index ->
                    editingBandTarget = target to index
                },
            )
            1 -> ChannelBandsEditorSection(
                target = ParametricEqChannelTarget.GLOBAL,
                bands = config.globalBands,
                expandedBandIds = expandedBandIds,
                onToggleExpand = { id ->
                    expandedBandIds = if (id in expandedBandIds) expandedBandIds - id else expandedBandIds + id
                },
                onBandsChange = { AppSettings.setParametricEqChannelBands(ParametricEqChannelTarget.GLOBAL, it) },
                onOpenNumericEditor = { idx -> editingBandTarget = ParametricEqChannelTarget.GLOBAL to idx },
                onCopyFromOpposite = null,
                onInvertFromOpposite = null,
            )
            2 -> ChannelBandsEditorSection(
                target = ParametricEqChannelTarget.LEFT,
                bands = config.leftBands,
                expandedBandIds = expandedBandIds,
                onToggleExpand = { id ->
                    expandedBandIds = if (id in expandedBandIds) expandedBandIds - id else expandedBandIds + id
                },
                onBandsChange = { AppSettings.setParametricEqChannelBands(ParametricEqChannelTarget.LEFT, it) },
                onOpenNumericEditor = { idx -> editingBandTarget = ParametricEqChannelTarget.LEFT to idx },
                onCopyFromOpposite = if (config.rightBands.isNotEmpty()) {
                    {
                        val copied = config.rightBands.map { it.copy(id = java.util.UUID.randomUUID().toString()) }
                        AppSettings.setParametricEqChannelBands(ParametricEqChannelTarget.LEFT, copied)
                    }
                } else null,
                onInvertFromOpposite = if (config.rightBands.isNotEmpty()) {
                    {
                        val inverted = config.rightBands.map {
                            it.copy(gainDb = -it.gainDb, id = java.util.UUID.randomUUID().toString())
                        }
                        AppSettings.setParametricEqChannelBands(ParametricEqChannelTarget.LEFT, inverted)
                    }
                } else null,
            )
            3 -> ChannelBandsEditorSection(
                target = ParametricEqChannelTarget.RIGHT,
                bands = config.rightBands,
                expandedBandIds = expandedBandIds,
                onToggleExpand = { id ->
                    expandedBandIds = if (id in expandedBandIds) expandedBandIds - id else expandedBandIds + id
                },
                onBandsChange = { AppSettings.setParametricEqChannelBands(ParametricEqChannelTarget.RIGHT, it) },
                onOpenNumericEditor = { idx -> editingBandTarget = ParametricEqChannelTarget.RIGHT to idx },
                onCopyFromOpposite = if (config.leftBands.isNotEmpty()) {
                    {
                        val copied = config.leftBands.map { it.copy(id = java.util.UUID.randomUUID().toString()) }
                        AppSettings.setParametricEqChannelBands(ParametricEqChannelTarget.RIGHT, copied)
                    }
                } else null,
                onInvertFromOpposite = if (config.leftBands.isNotEmpty()) {
                    {
                        val inverted = config.leftBands.map {
                            it.copy(gainDb = -it.gainDb, id = java.util.UUID.randomUUID().toString())
                        }
                        AppSettings.setParametricEqChannelBands(ParametricEqChannelTarget.RIGHT, inverted)
                    }
                } else null,
            )
        }
    }

    // Dialogs
    if (showSavePresetDialog) {
        SavePresetDialog(
            initialName = config.activePresetName ?: "My PEQ Preset",
            onDismiss = { showSavePresetDialog = false },
            onSave = { name ->
                val saved = AppSettings.saveCurrentAsParametricEqPreset(name)
                showSavePresetDialog = false
                Toast.makeText(context, "プリセット「${saved.name}」を保存しました", Toast.LENGTH_SHORT).show()
            },
        )
    }

    if (showRawEelDialog) {
        RawEelPreviewDialog(
            eelCode = BiquadUtils.toEelString(config),
            onDismiss = { showRawEelDialog = false },
        )
    }

    if (editingPreampDialog) {
        PreampNumericEditDialog(
            config = config,
            onDismiss = { editingPreampDialog = false },
            onConfirm = { gDb, lrDb, lDb, rDb ->
                AppSettings.setParametricEqPreamps(
                    globalDb = gDb,
                    lrDb = lrDb,
                    leftDb = lDb,
                    rightDb = rDb,
                )
                editingPreampDialog = false
            },
        )
    }

    editingBandTarget?.let { (target, index) ->
        val list = when (target) {
            ParametricEqChannelTarget.GLOBAL -> config.globalBands
            ParametricEqChannelTarget.LEFT -> config.leftBands
            ParametricEqChannelTarget.RIGHT -> config.rightBands
        }
        val band = list.getOrNull(index)
        if (band != null) {
            BandNumericEditDialog(
                target = target,
                bandIndex = index,
                band = band,
                onDismiss = { editingBandTarget = null },
                onConfirm = { updatedBand ->
                    val mutable = list.toMutableList()
                    if (index in mutable.indices) {
                        mutable[index] = updatedBand.sanitized(band)
                        AppSettings.setParametricEqChannelBands(target, mutable)
                    }
                    editingBandTarget = null
                },
            )
        } else {
            editingBandTarget = null
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavedEelPresetsSection(
    presets: List<ParametricEqPreset>,
    activePresetId: String?,
    onSelectPreset: (ParametricEqPreset) -> Unit,
    onDeletePreset: (ParametricEqPreset) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                RoundedCornerShape(14.dp),
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "保存済みプリセット (${presets.size})",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "タップで呼び出し / ゴミ箱で削除",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (presets.isEmpty()) {
            Text(
                text = "保存されたプリセットはありません。「.eel / EQ読込」または「プリセット保存」から追加できます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            presets.forEach { preset ->
                val isSelected = preset.id == activePresetId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            },
                        )
                        .border(
                            width = if (isSelected) 1.5.dp else 0.5.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(12.dp),
                        )
                        .clickable { onSelectPreset(preset) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = preset.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (isSelected) {
                                BadgeTag(
                                    text = "適用中",
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (preset.swapChannels) {
                                BadgeTag(
                                    text = "L↔R Swap ON",
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            }
                            if (abs(preset.totalPreampDb) > 0.001f) {
                                BadgeTag(
                                    text = "Preamp ${EelEqParser.fmtSignedDb(preset.totalPreampDb)}",
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                            BadgeTag(
                                text = "Global:${preset.globalBands.size} / L:${preset.leftBands.size} / R:${preset.rightBands.size}",
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    IconButton(
                        onClick = { onDeletePreset(preset) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = "プリセットを削除",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ParametricEqResponseGraph(
    config: ParametricEqConfig,
    modifier: Modifier = Modifier,
) {
    val response = remember(
        config.globalBands,
        config.leftBands,
        config.rightBands,
        config.globalPreampDb,
        config.lrPreampDb,
        config.leftGainDb,
        config.rightGainDb,
    ) {
        BiquadUtils.computeChannelResponses(config, numPoints = 200)
    }

    val minCurve = minOf(
        response.leftGainsDb.minOrNull() ?: 0.0,
        response.rightGainsDb.minOrNull() ?: 0.0,
        response.globalGainsDb.minOrNull() ?: 0.0,
    )
    val maxCurve = maxOf(
        response.leftGainsDb.maxOrNull() ?: 0.0,
        response.rightGainsDb.maxOrNull() ?: 0.0,
        response.globalGainsDb.maxOrNull() ?: 0.0,
    )
    val minDb = floor(minOf(minCurve - 2.0, -12.0)).coerceAtLeast(-36.0)
    val maxDb = ceil(maxOf(maxCurve + 2.0, 12.0)).coerceAtMost(36.0)

    val leftColor = Color(0xFF00E5FF)
    val rightColor = Color(0xFFFF6E40)
    val globalColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val zeroLineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)

    Column(
        modifier = modifier
            .background(
                Color(0xFF10131A),
                RoundedCornerShape(14.dp),
            )
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "周波数特性 (20 Hz – 20 kHz)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LegendDot(color = leftColor, label = "L ch (合算)")
                LegendDot(color = rightColor, label = "R ch (合算)")
                LegendDot(color = Color.White.copy(alpha = 0.75f), label = "Global")
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(148.dp),
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height
                val logMin = ln(20.0)
                val logMax = ln(20_000.0)

                fun xOfFreq(f: Double): Float =
                    (((ln(f.coerceIn(20.0, 20_000.0)) - logMin) / (logMax - logMin)) * w).toFloat()

                fun yOfDb(db: Double): Float {
                    val norm = ((db - minDb) / (maxDb - minDb)).coerceIn(0.0, 1.0)
                    return ((1.0 - norm) * h).toFloat()
                }

                // Horizontal dB grid lines every 3 dB
                var dbLine = (ceil(minDb / 3.0) * 3.0).toInt()
                while (dbLine <= maxDb.toInt()) {
                    val y = yOfDb(dbLine.toDouble())
                    drawLine(
                        color = if (dbLine == 0) zeroLineColor else gridColor,
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = if (dbLine == 0) 2.2f else 1f,
                    )
                    dbLine += 3
                }

                // Vertical frequency grid lines
                val freqGrid = doubleArrayOf(20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0)
                for (f in freqGrid) {
                    val x = xOfFreq(f)
                    drawLine(
                        color = gridColor,
                        start = Offset(x, 0f),
                        end = Offset(x, h),
                        strokeWidth = 1f,
                    )
                }

                val freqs = response.frequencies
                if (freqs.isNotEmpty()) {
                    // Global dashed path
                    val globalPath = Path()
                    val leftPath = Path()
                    val rightPath = Path()
                    val leftFillPath = Path()

                    for (i in freqs.indices) {
                        val x = xOfFreq(freqs[i])
                        val yg = yOfDb(response.globalGainsDb[i])
                        val yl = yOfDb(response.leftGainsDb[i])
                        val yr = yOfDb(response.rightGainsDb[i])
                        if (i == 0) {
                            globalPath.moveTo(x, yg)
                            leftPath.moveTo(x, yl)
                            rightPath.moveTo(x, yr)
                            leftFillPath.moveTo(x, yl)
                        } else {
                            globalPath.lineTo(x, yg)
                            leftPath.lineTo(x, yl)
                            rightPath.lineTo(x, yr)
                            leftFillPath.lineTo(x, yl)
                        }
                    }

                    leftFillPath.lineTo(w, h)
                    leftFillPath.lineTo(0f, h)
                    leftFillPath.close()

                    drawPath(
                        path = leftFillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(leftColor.copy(alpha = 0.20f), Color.Transparent),
                        ),
                    )

                    drawPath(
                        path = globalPath,
                        color = globalColor,
                        style = Stroke(
                            width = 2.5f,
                            cap = StrokeCap.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f),
                        ),
                    )

                    drawPath(
                        path = rightPath,
                        color = rightColor,
                        style = Stroke(width = 4.5f, cap = StrokeCap.Round),
                    )

                    drawPath(
                        path = leftPath,
                        color = leftColor,
                        style = Stroke(width = 4.5f, cap = StrokeCap.Round),
                    )
                }
            }
        }

        // Frequency scale labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            listOf("20Hz", "100Hz", "500Hz", "1kHz", "4kHz", "10kHz", "20kHz").forEach { lbl ->
                Text(
                    text = lbl,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 10.sp,
                )
            }
        }

        val lPeak = response.leftGainsDb.maxOrNull() ?: 0.0
        val lMin = response.leftGainsDb.minOrNull() ?: 0.0
        val rPeak = response.rightGainsDb.maxOrNull() ?: 0.0
        val rMin = response.rightGainsDb.minOrNull() ?: 0.0
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = String.format(Locale.US, "L ch: 最大 %+.2f dB / 最小 %+.2f dB", lPeak, lMin),
                style = MaterialTheme.typography.labelSmall,
                color = leftColor,
            )
            Text(
                text = String.format(Locale.US, "R ch: 最大 %+.2f dB / 最小 %+.2f dB", rPeak, rMin),
                style = MaterialTheme.typography.labelSmall,
                color = rightColor,
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun ActiveDspSummaryCard(
    config: ParametricEqConfig,
    report: EelEqParser.EelAnalysisReport,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f),
                RoundedCornerShape(14.dp),
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                shape = RoundedCornerShape(14.dp),
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "現在のEQ・スワップ適用状況: ${config.activePresetName ?: "カスタム構成"}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            BadgeTag(
                text = if (config.enabled) "DSP稼働中" else "バイパス(OFF)",
                containerColor = if (config.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (config.enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!config.sourceFileName.isNullOrBlank()) {
            Text(
                text = "読込元: ${config.sourceFileName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!config.notes.isNullOrBlank()) {
            Text(
                text = "備考: ${config.notes}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        report.summaryLinesJa.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ChannelSwapAndPreampCard(
    config: ParametricEqConfig,
    onEditPreampNumeric: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                RoundedCornerShape(14.dp),
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Channel Swap Toggle Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.SwapHoriz,
                contentDescription = null,
                tint = if (config.swapChannels) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            ) {
                Text(
                    text = "左右チャンネルスワップ (Left ↔ Right)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (config.swapChannels) {
                        if (config.swapBeforeEq) "有効: 入力L↔Rを入れ替えてからEQ処理 (.eel準拠)"
                        else "有効: EQ処理後に出力L↔Rを入れ替え"
                    } else {
                        "無効: 通常のステレオ配置 (L → L / R → R)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (config.swapChannels) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = config.swapChannels,
                onCheckedChange = { AppSettings.setParametricEqSwapChannels(it, config.swapBeforeEq) },
            )
        }

        AnimatedVisibility(visible = config.swapChannels) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "スワップ位置:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilterChip(
                    selected = config.swapBeforeEq,
                    onClick = { AppSettings.setParametricEqSwapChannels(true, true) },
                    label = { Text("EQ処理前 (入力スワップ)", style = MaterialTheme.typography.labelSmall) },
                )
                FilterChip(
                    selected = !config.swapBeforeEq,
                    onClick = { AppSettings.setParametricEqSwapChannels(true, false) },
                    label = { Text("EQ処理後 (出力スワップ)", style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        // Preamp Section
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = "プリアンプ設定 (Preamp)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val linGain = Math.pow(10.0, config.totalPreampDb.toDouble() / 20.0)
                Text(
                    text = String.format(
                        Locale.US,
                        "Global %+.2f dB + L/R %+.2f dB = 合計 %+.2f dB (×%.4f)",
                        config.globalPreampDb,
                        config.lrPreampDb,
                        config.totalPreampDb,
                        linGain,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            TextButton(onClick = onEditPreampNumeric) {
                Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("数値入力", style = MaterialTheme.typography.labelMedium)
            }
        }

        ValueSlider(
            label = "Global Preamp (全体プリアンプ)",
            value = config.globalPreampDb,
            valueRange = -20f..12f,
            valueText = EelEqParser.fmtSignedDb(config.globalPreampDb),
            onValueChange = {
                val snapped = if (abs(it) < 0.08f) 0f else (kotlin.math.round(it * 10f) / 10f)
                AppSettings.setParametricEqPreamps(globalDb = snapped)
            },
        )

        ValueSlider(
            label = "L/R Preamp (チャンネル共通プリアンプ)",
            value = config.lrPreampDb,
            valueRange = -20f..12f,
            valueText = EelEqParser.fmtSignedDb(config.lrPreampDb),
            onValueChange = {
                val snapped = if (abs(it) < 0.08f) 0f else (kotlin.math.round(it * 10f) / 10f)
                AppSettings.setParametricEqPreamps(lrDb = snapped)
            },
        )
    }
}

@Composable
private fun DetailedFilterBreakdownView(
    config: ParametricEqConfig,
    report: EelEqParser.EelAnalysisReport,
    onJumpToEdit: (ParametricEqChannelTarget, Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                RoundedCornerShape(14.dp),
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 1. Global Filters Table
        Text(
            text = "1. 両ch共通フィルター (Global Filters: ${config.globalBands.size}バンド)",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )

        if (config.globalBands.isEmpty()) {
            Text(
                text = "共通フィルターなし",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            config.globalBands.forEachIndexed { idx, band ->
                CompactBandRow(
                    index = idx + 1,
                    channelBadge = "Global",
                    band = band,
                    onClickEdit = { onJumpToEdit(ParametricEqChannelTarget.GLOBAL, idx) },
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        // 2. L / R Channel-Specific Filters Table
        Text(
            text = "2. 左右独立フィルター (Left: ${config.leftBands.size}バンド / Right: ${config.rightBands.size}バンド)",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.tertiary,
        )

        if (config.leftBands.isEmpty() && config.rightBands.isEmpty()) {
            Text(
                text = "左右独立フィルターなし (「左ch独立」「右ch独立」タブから追加可能)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (report.lrPairs.size == config.leftBands.size && config.leftBands.size == config.rightBands.size && report.lrPairs.isNotEmpty()) {
            Text(
                text = "左右ペア比較表示 (タップで各バンドを編集):",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            report.lrPairs.forEachIndexed { idx, pair ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1.1f),
                    ) {
                        Text(
                            text = "#${pair.index}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FilterTypeChip(pair.filterType)
                        Text(
                            text = "${EelEqParser.fmtHz(pair.frequencyHz)} (Q ${String.format(Locale.US, "%.3f", pair.q)})",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "L: ${EelEqParser.fmtSignedDb(pair.leftGainDb)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00B8D4),
                            modifier = Modifier.clickable { onJumpToEdit(ParametricEqChannelTarget.LEFT, idx) },
                        )
                        Text(
                            text = "R: ${EelEqParser.fmtSignedDb(pair.rightGainDb)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF6E40),
                            modifier = Modifier.clickable { onJumpToEdit(ParametricEqChannelTarget.RIGHT, idx) },
                        )
                    }
                }
            }
        } else {
            if (config.leftBands.isNotEmpty()) {
                Text(
                    text = "Left Channel (spl0):",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF00B8D4),
                )
                config.leftBands.forEachIndexed { idx, band ->
                    CompactBandRow(
                        index = idx + 1,
                        channelBadge = "L ch",
                        band = band,
                        onClickEdit = { onJumpToEdit(ParametricEqChannelTarget.LEFT, idx) },
                    )
                }
            }
            if (config.rightBands.isNotEmpty()) {
                Text(
                    text = "Right Channel (spl1):",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFFF6E40),
                )
                config.rightBands.forEachIndexed { idx, band ->
                    CompactBandRow(
                        index = idx + 1,
                        channelBadge = "R ch",
                        band = band,
                        onClickEdit = { onJumpToEdit(ParametricEqChannelTarget.RIGHT, idx) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactBandRow(
    index: Int,
    channelBadge: String,
    band: ParametricEqBand,
    onClickEdit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .clickable { onClickEdit() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "#$index",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilterTypeChip(band.filterType)
            Text(
                text = EelEqParser.fmtHz(band.frequencyHz),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = if (band.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
            )
            Text(
                text = "Q ${String.format(Locale.US, "%.3f", band.q)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val gainColor = when {
                !band.enabled -> MaterialTheme.colorScheme.outline
                band.gainDb > 0.01f -> MaterialTheme.colorScheme.primary
                band.gainDb < -0.01f -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                text = EelEqParser.fmtSignedDb(band.gainDb),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = gainColor,
            )
            Icon(
                imageVector = Icons.Rounded.Edit,
                contentDescription = "編集",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChannelBandsEditorSection(
    target: ParametricEqChannelTarget,
    bands: List<ParametricEqBand>,
    expandedBandIds: Set<String>,
    onToggleExpand: (String) -> Unit,
    onBandsChange: (List<ParametricEqBand>) -> Unit,
    onOpenNumericEditor: (Int) -> Unit,
    onCopyFromOpposite: (() -> Unit)?,
    onInvertFromOpposite: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                RoundedCornerShape(14.dp),
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${target.labelJa} (${bands.size}バンド)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = {
                        val nextFreq = when (bands.size) {
                            0 -> 60f
                            1 -> 250f
                            2 -> 1000f
                            3 -> 4000f
                            else -> 8000f
                        }
                        onBandsChange(
                            bands + ParametricEqBand(
                                frequencyHz = nextFreq,
                                q = 1.414f,
                                gainDb = 0f,
                                filterType = ParametricEqFilterType.PEAKING,
                            ),
                        )
                    },
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("バンド追加", style = MaterialTheme.typography.labelSmall)
                }
                if (bands.isNotEmpty()) {
                    TextButton(onClick = { onBandsChange(emptyList()) }) {
                        Text("全消去", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        if (onCopyFromOpposite != null || onInvertFromOpposite != null) {
            val otherCh = if (target == ParametricEqChannelTarget.LEFT) "右ch(R)" else "左ch(L)"
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                onCopyFromOpposite?.let { copyFn ->
                    AssistChip(
                        onClick = copyFn,
                        label = { Text("${otherCh}のバンドをそのまま複製", style = MaterialTheme.typography.labelSmall) },
                    )
                }
                onInvertFromOpposite?.let { invFn ->
                    AssistChip(
                        onClick = invFn,
                        label = { Text("${otherCh}のゲイン反転(±逆相)を複製", style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }

        if (bands.isEmpty()) {
            Text(
                text = "このチャンネルにはフィルターがありません。「+ バンド追加」で追加できます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            bands.forEachIndexed { index, band ->
                val isExpanded = band.id in expandedBandIds || bands.size <= 3
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onToggleExpand(band.id) },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "#${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            FilterTypeChip(band.filterType)
                            Text(
                                text = "${EelEqParser.fmtHz(band.frequencyHz)} | ${EelEqParser.fmtSignedDb(band.gainDb)} | Q ${String.format(Locale.US, "%.3f", band.q)}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        IconButton(
                            onClick = { onOpenNumericEditor(index) },
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = "数値入力",
                                modifier = Modifier.size(16.dp),
                            )
                        }

                        Switch(
                            checked = band.enabled,
                            onCheckedChange = { checked ->
                                val updated = bands.toMutableList()
                                updated[index] = band.copy(enabled = checked)
                                onBandsChange(updated)
                            },
                        )

                        IconButton(
                            onClick = {
                                val updated = bands.toMutableList().apply { removeAt(index) }
                                onBandsChange(updated)
                            },
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = "バンド削除",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                        }

                        IconButton(
                            onClick = { onToggleExpand(band.id) },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null,
                            )
                        }
                    }

                    AnimatedVisibility(visible = isExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            // Filter Type selector chips
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                listOf(
                                    ParametricEqFilterType.PEAKING,
                                    ParametricEqFilterType.LOW_SHELF,
                                    ParametricEqFilterType.HIGH_SHELF,
                                    ParametricEqFilterType.LOW_PASS,
                                    ParametricEqFilterType.HIGH_PASS,
                                    ParametricEqFilterType.NOTCH,
                                ).forEach { ft ->
                                    FilterChip(
                                        selected = band.filterType == ft,
                                        onClick = {
                                            val updated = bands.toMutableList()
                                            updated[index] = band.copy(filterType = ft)
                                            onBandsChange(updated)
                                        },
                                        label = { Text(ft.displayLabel, style = MaterialTheme.typography.labelSmall) },
                                    )
                                }
                            }

                            ValueSlider(
                                label = stringResource(R.string.parametric_eq_frequency),
                                value = frequencyToPosition(band.frequencyHz),
                                valueText = EelEqParser.fmtHz(band.frequencyHz),
                                onValueChange = { position ->
                                    val updated = bands.toMutableList()
                                    updated[index] = band.copy(frequencyHz = positionToFrequency(position))
                                    onBandsChange(updated)
                                },
                            )
                            ValueSlider(
                                label = stringResource(R.string.parametric_eq_q),
                                value = band.q.coerceIn(0.1f, 10f),
                                valueRange = 0.1f..10f,
                                valueText = String.format(Locale.US, "%.3f", band.q),
                                onValueChange = { qVal ->
                                    val updated = bands.toMutableList()
                                    updated[index] = band.copy(q = qVal)
                                    onBandsChange(updated)
                                },
                            )
                            ValueSlider(
                                label = stringResource(R.string.parametric_eq_gain),
                                value = band.gainDb.coerceIn(-20f, 20f),
                                valueRange = -20f..20f,
                                valueText = EelEqParser.fmtSignedDb(band.gainDb),
                                onValueChange = { gainVal ->
                                    val updated = bands.toMutableList()
                                    updated[index] = band.copy(gainDb = gainVal)
                                    onBandsChange(updated)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterTypeChip(type: ParametricEqFilterType) {
    val bg = when (type) {
        ParametricEqFilterType.PEAKING -> MaterialTheme.colorScheme.primaryContainer
        ParametricEqFilterType.LOW_SHELF -> MaterialTheme.colorScheme.secondaryContainer
        ParametricEqFilterType.HIGH_SHELF -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val fg = when (type) {
        ParametricEqFilterType.PEAKING -> MaterialTheme.colorScheme.onPrimaryContainer
        ParametricEqFilterType.LOW_SHELF -> MaterialTheme.colorScheme.onSecondaryContainer
        ParametricEqFilterType.HIGH_SHELF -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    BadgeTag(text = type.displayLabel, containerColor = bg, contentColor = fg)
}

@Composable
private fun BadgeTag(
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Box(
        modifier = Modifier
            .background(containerColor, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = contentColor,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun SavePresetDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("現在のPEQ・スワップ設定をプリセット保存") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "プリセット名を入力してください（いつでも呼び出し・削除できます）:",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("プリセット名") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
    )
}

@Composable
private fun RawEelPreviewDialog(
    eelCode: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("RootlessJamesDSP EEL2 コード表示") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
                    .background(Color(0xFF10131A), RoundedCornerShape(10.dp))
                    .padding(10.dp),
            ) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        text = eelCode,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFE0E6ED),
                        fontSize = 11.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("閉じる")
            }
        },
    )
}

@Composable
private fun PreampNumericEditDialog(
    config: ParametricEqConfig,
    onDismiss: () -> Unit,
    onConfirm: (Float, Float, Float, Float) -> Unit,
) {
    var globalText by remember { mutableStateOf(config.globalPreampDb.toString()) }
    var lrText by remember { mutableStateOf(config.lrPreampDb.toString()) }
    var leftText by remember { mutableStateOf(config.leftGainDb.toString()) }
    var rightText by remember { mutableStateOf(config.rightGainDb.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("プリアンプ高精度数値入力 (dB)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = globalText,
                    onValueChange = { globalText = it },
                    label = { Text("Global Preamp (dB)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = lrText,
                    onValueChange = { lrText = it },
                    label = { Text("L/R Preamp (dB)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = leftText,
                    onValueChange = { leftText = it },
                    label = { Text("Left ch 追加ゲイン補正 (dB)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = rightText,
                    onValueChange = { rightText = it },
                    label = { Text("Right ch 追加ゲイン補正 (dB)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        globalText.toFloatOrNull() ?: config.globalPreampDb,
                        lrText.toFloatOrNull() ?: config.lrPreampDb,
                        leftText.toFloatOrNull() ?: config.leftGainDb,
                        rightText.toFloatOrNull() ?: config.rightGainDb,
                    )
                },
            ) {
                Text("適用")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BandNumericEditDialog(
    target: ParametricEqChannelTarget,
    bandIndex: Int,
    band: ParametricEqBand,
    onDismiss: () -> Unit,
    onConfirm: (ParametricEqBand) -> Unit,
) {
    var selectedType by remember { mutableStateOf(band.filterType) }
    var freqText by remember { mutableStateOf(band.frequencyHz.toString()) }
    var gainText by remember { mutableStateOf(band.gainDb.toString()) }
    var qText by remember { mutableStateOf(band.q.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${target.shortLabel} Band #${bandIndex + 1} 高精度編集") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("フィルター種別:", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        ParametricEqFilterType.PEAKING,
                        ParametricEqFilterType.LOW_SHELF,
                        ParametricEqFilterType.HIGH_SHELF,
                        ParametricEqFilterType.LOW_PASS,
                        ParametricEqFilterType.HIGH_PASS,
                        ParametricEqFilterType.NOTCH,
                    ).forEach { ft ->
                        FilterChip(
                            selected = selectedType == ft,
                            onClick = { selectedType = ft },
                            label = { Text(ft.displayLabel) },
                        )
                    }
                }
                OutlinedTextField(
                    value = freqText,
                    onValueChange = { freqText = it },
                    label = { Text("周波数 Fc (Hz) [10 – 24000]") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = gainText,
                    onValueChange = { gainText = it },
                    label = { Text("ゲイン Gain (dB) [-30 – +30]") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = qText,
                    onValueChange = { qText = it },
                    label = { Text("Q値 (先鋭度) [0.05 – 30]") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        band.copy(
                            filterType = selectedType,
                            frequencyHz = freqText.toFloatOrNull() ?: band.frequencyHz,
                            gainDb = gainText.toFloatOrNull() ?: band.gainDb,
                            q = qText.toFloatOrNull() ?: band.q,
                        ),
                    )
                },
            ) {
                Text("適用")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
    )
}

@Composable
fun ReverbControls(
    enabled: Boolean,
    amount: Int,
    onEnabledChange: (Boolean) -> Unit,
    onAmountChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Waves, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                text = stringResource(R.string.reverb),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
        Text(
            text = stringResource(R.string.reverb_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.reverb_amount),
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$amount%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.width(48.dp),
            )
        }
        Slider(
            value = amount.toFloat(),
            onValueChange = { onAmountChange(it.toInt()) },
            valueRange = 0f..100f,
            enabled = enabled,
        )
    }
}

@Composable
private fun ValueSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    valueText: String,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.width(76.dp),
            )
        }
        Slider(value = value.coerceIn(valueRange.start, valueRange.endInclusive), onValueChange = onValueChange, valueRange = valueRange)
    }
}

private fun frequencyToPosition(frequencyHz: Float): Float {
    val minLog = ln(20f)
    val maxLog = ln(20_000f)
    return ((ln(frequencyHz.coerceIn(20f, 20_000f)) - minLog) /
        (maxLog - minLog)).coerceIn(0f, 1f)
}

private fun positionToFrequency(position: Float): Float {
    val minLog = ln(20f)
    val maxLog = ln(20_000f)
    return exp(minLog + (maxLog - minLog) * position.coerceIn(0f, 1f))
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
    }.getOrNull() ?: uri.lastPathSegment
}

private fun readBoundedUtf8(input: java.io.InputStream): String {
    val maxBytes = EelEqParser.MAX_INPUT_CHARS
    val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= maxBytes) { "EQプリセットは256 KiB以下にしてください" }
        output.write(buffer, 0, count)
    }
    return output.toString(Charsets.UTF_8.name())
}
