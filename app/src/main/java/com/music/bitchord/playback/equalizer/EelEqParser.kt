package com.music.bitchord.playback.equalizer

import com.music.bitchord.data.settings.ParametricEqBand
import com.music.bitchord.data.settings.ParametricEqConfig
import com.music.bitchord.data.settings.ParametricEqFilterType
import com.music.bitchord.data.settings.ParametricEqPreset
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.pow

/**
 * Parses RootlessJamesDSP LiveProg (.eel) scripts and EqualizerAPO (.txt) files
 * into high-precision [ParametricEqPreset] / [ParametricEqConfig] models and
 * produces a detailed human-readable analysis of the EQ and channel operations.
 */
object EelEqParser {

    /** Imported EEL/EqualizerAPO is parsed as data only, with a bounded input size. */
    const val MAX_INPUT_CHARS = 256 * 1024

    data class FilterPairDiff(
        val index: Int,
        val filterType: ParametricEqFilterType,
        val frequencyHz: Float,
        val q: Float,
        val leftGainDb: Float,
        val rightGainDb: Float,
    ) {
        val isComplementary: Boolean
            get() = abs(leftGainDb + rightGainDb) < 0.02f && abs(leftGainDb) > 0.001f
    }

    data class EelAnalysisReport(
        val title: String,
        val sourceFileName: String?,
        val notes: String?,
        val swapChannels: Boolean,
        val swapBeforeEq: Boolean,
        val globalPreampDb: Float,
        val lrPreampDb: Float,
        val leftGainDb: Float,
        val rightGainDb: Float,
        val totalLeftPreampDb: Float,
        val totalRightPreampDb: Float,
        val linearGainLeft: Double,
        val linearGainRight: Double,
        val globalBands: List<ParametricEqBand>,
        val leftBands: List<ParametricEqBand>,
        val rightBands: List<ParametricEqBand>,
        val lrPairs: List<FilterPairDiff>,
        val summaryLinesJa: List<String>,
    )

    data class ParseResult(
        val preset: ParametricEqPreset,
        val report: EelAnalysisReport,
    )

    private data class RawFilterDef(
        val instanceName: String,
        val filterType: ParametricEqFilterType,
        val frequencyHz: Float,
        val q: Float,
        val gainDb: Float,
    )

    fun parse(content: String, fileName: String? = null): ParseResult {
        require(content.isNotBlank()) { "EQプリセットが空です" }
        require(content.length <= MAX_INPUT_CHARS) { "EQプリセットは256 KiB以下にしてください" }
        val cleanFileName = fileName?.substringAfterLast('/')?.substringAfterLast('\\')
        val isApoFormat = !content.contains("@init", ignoreCase = true) &&
            !content.contains("@sample", ignoreCase = true) &&
            content.lines().any { it.trim().startsWith("Filter ", ignoreCase = true) || it.trim().startsWith("Preamp:", ignoreCase = true) }

        val preset = if (isApoFormat) {
            parseApoContent(content, cleanFileName)
        } else {
            parseEelContent(content, cleanFileName)
        }

        require(
            preset.globalBands.isNotEmpty() || preset.leftBands.isNotEmpty() || preset.rightBands.isNotEmpty() ||
                preset.swapChannels || preset.globalPreampDb != 0f || preset.lrPreampDb != 0f ||
                preset.leftGainDb != 0f || preset.rightGainDb != 0f,
        ) { "対応するEQフィルターが見つかりません" }
        require(
            preset.globalBands.size <= ParametricEqBand.MAX_BANDS_PER_CHANNEL &&
                preset.leftBands.size <= ParametricEqBand.MAX_BANDS_PER_CHANNEL &&
                preset.rightBands.size <= ParametricEqBand.MAX_BANDS_PER_CHANNEL,
        ) { "各チャンネルのフィルターは最大${ParametricEqBand.MAX_BANDS_PER_CHANNEL}個です" }

        val report = analyze(preset.toConfig(enabled = true), preset.name, preset.sourceFileName ?: cleanFileName, preset.notes)
        return ParseResult(preset = preset, report = report)
    }

    fun analyze(
        config: ParametricEqConfig,
        title: String = config.activePresetName ?: "Custom PEQ",
        sourceFileName: String? = config.sourceFileName,
        notes: String? = config.notes,
    ): EelAnalysisReport {
        val totalLeftDb = config.effectiveLeftPreampDb
        val totalRightDb = config.effectiveRightPreampDb
        val linLeft = 10.0.pow(totalLeftDb.toDouble() / 20.0)
        val linRight = 10.0.pow(totalRightDb.toDouble() / 20.0)

        val activeGlobal = config.globalBands.filter { it.enabled }
        val activeLeft = config.leftBands.filter { it.enabled }
        val activeRight = config.rightBands.filter { it.enabled }

        val pairCount = minOf(activeLeft.size, activeRight.size)
        val lrPairs = mutableListOf<FilterPairDiff>()
        for (i in 0 until pairCount) {
            val l = activeLeft[i]
            val r = activeRight[i]
            if (l.filterType == r.filterType && abs(l.frequencyHz - r.frequencyHz) < 0.5f && abs(l.q - r.q) < 0.01f) {
                lrPairs.add(
                    FilterPairDiff(
                        index = i + 1,
                        filterType = l.filterType,
                        frequencyHz = l.frequencyHz,
                        q = l.q,
                        leftGainDb = l.gainDb,
                        rightGainDb = r.gainDb,
                    ),
                )
            }
        }

        val summaryLines = mutableListOf<String>()

        // 1. Channel Swap summary
        if (config.swapChannels) {
            val timing = if (config.swapBeforeEq) "EQ処理前 (入力段)" else "EQ処理後 (出力段)"
            summaryLines.add("・チャンネルスワップ: ON ($timing で Left ↔ Right を入れ替え)")
        } else {
            summaryLines.add("・チャンネルスワップ: OFF (通常ステレオ L→L, R→R)")
        }

        // 2. Preamp summary
        if (abs(config.globalPreampDb) > 0.001f || abs(config.lrPreampDb) > 0.001f ||
            abs(config.leftGainDb) > 0.001f || abs(config.rightGainDb) > 0.001f
        ) {
            val parts = mutableListOf<String>()
            if (abs(config.globalPreampDb) > 0.001f) {
                parts.add("Global ${fmtSignedDb(config.globalPreampDb)}")
            }
            if (abs(config.lrPreampDb) > 0.001f) {
                parts.add("L/R ${fmtSignedDb(config.lrPreampDb)}")
            }
            if (abs(config.leftGainDb) > 0.001f || abs(config.rightGainDb) > 0.001f) {
                parts.add("Ch補正 L:${fmtSignedDb(config.leftGainDb)} / R:${fmtSignedDb(config.rightGainDb)}")
            }
            val totalText = if (abs(totalLeftDb - totalRightDb) < 0.005f) {
                "${fmtSignedDb(totalLeftDb)} (リニア倍率 ×${String.format(Locale.US, "%.4f", linLeft)})"
            } else {
                "L: ${fmtSignedDb(totalLeftDb)} (×${String.format(Locale.US, "%.4f", linLeft)}) / R: ${fmtSignedDb(totalRightDb)} (×${String.format(Locale.US, "%.4f", linRight)})"
            }
            summaryLines.add("・プリアンプ: ${parts.joinToString(" + ")} → 合計 $totalText")
        } else {
            summaryLines.add("・プリアンプ: 0.0 dB (等倍 ×1.0000)")
        }

        // 3. Global bands summary
        if (activeGlobal.isNotEmpty()) {
            val typeCounts = activeGlobal.groupingBy { it.filterType.displayLabel }.eachCount()
                .entries.joinToString(", ") { "${it.key}×${it.value}" }
            val minF = activeGlobal.minOf { it.frequencyHz }
            val maxF = activeGlobal.maxOf { it.frequencyHz }
            summaryLines.add("・両ch共通 (Global) EQ: ${activeGlobal.size}バンド ($typeCounts | ${fmtHz(minF)} 〜 ${fmtHz(maxF)})")
        } else {
            summaryLines.add("・両ch共通 (Global) EQ: なし (0バンド)")
        }

        // 4. L/R independent bands summary
        if (activeLeft.isNotEmpty() || activeRight.isNotEmpty()) {
            val complementaryCount = lrPairs.count { it.isComplementary }
            val extraNote = if (complementaryCount > 0 && complementaryCount == activeLeft.size && activeLeft.size == activeRight.size) {
                " (全${complementaryCount}バンドがL/R逆相ゲインによる左右バランス補正ペア)"
            } else if (complementaryCount > 0) {
                " (うち${complementaryCount}バンドがL/R逆相ゲインペア)"
            } else {
                ""
            }
            summaryLines.add("・左右独立PEQ: Left ${activeLeft.size}バンド / Right ${activeRight.size}バンド$extraNote")
        } else {
            summaryLines.add("・左右独立PEQ: なし (Left 0 / Right 0)")
        }

        return EelAnalysisReport(
            title = title,
            sourceFileName = sourceFileName,
            notes = notes,
            swapChannels = config.swapChannels,
            swapBeforeEq = config.swapBeforeEq,
            globalPreampDb = config.globalPreampDb,
            lrPreampDb = config.lrPreampDb,
            leftGainDb = config.leftGainDb,
            rightGainDb = config.rightGainDb,
            totalLeftPreampDb = totalLeftDb,
            totalRightPreampDb = totalRightDb,
            linearGainLeft = linLeft,
            linearGainRight = linRight,
            globalBands = activeGlobal,
            leftBands = activeLeft,
            rightBands = activeRight,
            lrPairs = lrPairs,
            summaryLinesJa = summaryLines,
        )
    }

    private fun parseEelContent(content: String, fileName: String?): ParametricEqPreset {
        var title: String? = null
        var sourceFile: String? = null
        val notesList = mutableListOf<String>()
        var commentGlobalPreamp: Float? = null
        var commentLrPreamp: Float? = null

        val titleCommentRegex = Regex("""^//\s*Title:\s*(.+)$""", RegexOption.IGNORE_CASE)
        val sourceCommentRegex = Regex("""^//\s*Source:\s*(.+)$""", RegexOption.IGNORE_CASE)
        val noteCommentRegex = Regex("""^//\s*Note:\s*(.+)$""", RegexOption.IGNORE_CASE)
        val globalPreampCommentRegex = Regex("""^//\s*Global\s+Preamp:\s*([+-]?\d+(?:\.\d+)?)\s*dB""", RegexOption.IGNORE_CASE)
        val lrPreampCommentRegex = Regex("""^//\s*L/R\s+Preamp:\s*([+-]?\d+(?:\.\d+)?)\s*dB""", RegexOption.IGNORE_CASE)
        val descRegex = Regex("""^desc:\s*(.+)$""", RegexOption.IGNORE_CASE)

        for (rawLine in content.lines()) {
            val line = rawLine.trim()
            titleCommentRegex.find(line)?.let { if (title == null) title = it.groupValues[1].trim() }
            sourceCommentRegex.find(line)?.let { sourceFile = it.groupValues[1].trim() }
            noteCommentRegex.find(line)?.let { notesList.add(it.groupValues[1].trim()) }
            globalPreampCommentRegex.find(line)?.let { commentGlobalPreamp = it.groupValues[1].toFloatOrNull() }
            lrPreampCommentRegex.find(line)?.let { commentLrPreamp = it.groupValues[1].toFloatOrNull() }
            descRegex.find(line)?.let { title = it.groupValues[1].trim() }
        }

        val finalTitle = title?.takeIf { it.isNotBlank() }
            ?: fileName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            ?: "Imported EEL Preset"

        // Split into @init and @sample sections
        val initIdx = content.indexOf("@init", ignoreCase = true)
        val sampleIdx = content.indexOf("@sample", ignoreCase = true)

        val initSection = when {
            initIdx >= 0 && sampleIdx > initIdx -> content.substring(initIdx, sampleIdx)
            initIdx >= 0 -> content.substring(initIdx)
            sampleIdx > 0 -> content.substring(0, sampleIdx)
            else -> content
        }
        val sampleSection = if (sampleIdx >= 0) content.substring(sampleIdx) else ""

        // Strip comments for code parsing
        val cleanInit = stripComments(initSection)
        val cleanSample = stripComments(sampleSection)

        // Parse preamp variables in @init
        val varMap = mutableMapOf<String, Double>()
        val varAssignRegex = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*=\s*([+-]?\d+(?:\.\d+)?)\s*;""")
        for (match in varAssignRegex.findAll(cleanInit)) {
            val name = match.groupValues[1].lowercase()
            val value = match.groupValues[2].toDoubleOrNull() ?: continue
            varMap[name] = value
        }

        val globalPreampDb = (varMap["global_preamp_db"] ?: varMap["global_preamp"] ?: varMap["preamp_db"] ?: varMap["preamp"])?.toFloat()
            ?: commentGlobalPreamp
            ?: 0f
        val lrPreampDb = (varMap["lr_preamp_db"] ?: varMap["ch_preamp_db"] ?: varMap["channel_preamp_db"])?.toFloat()
            ?: commentLrPreamp
            ?: 0f
        val leftGainDb = (varMap["leftgain"] ?: varMap["left_gain_db"] ?: varMap["left_preamp_db"])?.toFloat() ?: 0f
        val rightGainDb = (varMap["rightgain"] ?: varMap["right_gain_db"] ?: varMap["right_preamp_db"])?.toFloat() ?: 0f

        // Parse filter calculations in @init:
        // e.g. g_band1L.calc_lshelf(23, 0.758, 1.37);
        val filterCallRegex = Regex(
            """\b([A-Za-z_][A-Za-z0-9_]*)\.([A-Za-z_][A-Za-z0-9_]*)\s*\(\s*([+-]?\d+(?:\.\d+)?)\s*,\s*([+-]?\d+(?:\.\d+)?)\s*(?:,\s*([+-]?\d+(?:\.\d+)?))?\s*\)""",
        )

        val rawFilters = mutableListOf<RawFilterDef>()
        for (match in filterCallRegex.findAll(cleanInit)) {
            val instanceName = match.groupValues[1]
            val funcName = match.groupValues[2]
            val filterType = ParametricEqFilterType.fromEelFunction(funcName) ?: continue
            val freq = match.groupValues[3].toFloatOrNull() ?: continue
            val q = match.groupValues[4].toFloatOrNull() ?: continue
            val gain = match.groupValues[5].takeIf { it.isNotBlank() }?.toFloatOrNull() ?: 0f
            rawFilters.add(
                RawFilterDef(
                    instanceName = instanceName,
                    filterType = filterType,
                    frequencyHz = freq,
                    q = q,
                    gainDb = gain,
                ),
            )
            require(rawFilters.size <= ParametricEqBand.MAX_BANDS_PER_CHANNEL * 3) {
                "EQフィルターが多すぎます"
            }
        }

        // Analyze @sample routing and channel swap
        val spl0Order = mutableListOf<String>()
        val spl1Order = mutableListOf<String>()
        val processCallRegex = Regex(
            """\b(spl0|spl1)\s*=\s*([A-Za-z_][A-Za-z0-9_]*)\.[A-Za-z0-9_]*process[A-Za-z0-9_]*\s*\(\s*\1\s*\)""",
            RegexOption.IGNORE_CASE,
        )
        for (match in processCallRegex.findAll(cleanSample)) {
            val ch = match.groupValues[1].lowercase()
            val inst = match.groupValues[2]
            if (ch == "spl0") spl0Order.add(inst)
            if (ch == "spl1") spl1Order.add(inst)
        }

        // Detect channel swap in @sample
        val (swapChannels, swapBeforeEq) = detectChannelSwap(cleanSample, notesList)

        // Classify filters into Global, Left, Right
        val globalBands = mutableListOf<ParametricEqBand>()
        val leftBands = mutableListOf<ParametricEqBand>()
        val rightBands = mutableListOf<ParametricEqBand>()
        val consumedInstances = mutableSetOf<String>()

        val filterByName = rawFilters.associateBy { it.instanceName }

        // First check explicit g_*L / g_*R or paired *L / *R with identical parameters
        for (filter in rawFilters) {
            if (filter.instanceName in consumedInstances) continue
            val name = filter.instanceName
            if (name.endsWith("L", ignoreCase = true)) {
                val baseName = name.dropLast(1)
                val rightCandidateName = "${baseName}R"
                val rightPartner = filterByName[rightCandidateName] ?: filterByName["${baseName}r"]
                if (rightPartner != null &&
                    rightPartner.filterType == filter.filterType &&
                    abs(rightPartner.frequencyHz - filter.frequencyHz) < 0.001f &&
                    abs(rightPartner.q - filter.q) < 0.001f &&
                    abs(rightPartner.gainDb - filter.gainDb) < 0.001f
                ) {
                    consumedInstances.add(filter.instanceName)
                    consumedInstances.add(rightPartner.instanceName)
                    globalBands.add(
                        ParametricEqBand(
                            frequencyHz = filter.frequencyHz,
                            q = filter.q,
                            gainDb = filter.gainDb,
                            filterType = filter.filterType,
                        ),
                    )
                    continue
                }
            }
        }

        // Remaining filters: classify by @sample usage (spl0 vs spl1) or instance prefix (l_ / r_ / g_)
        val spl0Set = spl0Order.toSet()
        val spl1Set = spl1Order.toSet()

        for (filter in rawFilters) {
            if (filter.instanceName in consumedInstances) continue
            val name = filter.instanceName
            val lower = name.lowercase()
            val band = ParametricEqBand(
                frequencyHz = filter.frequencyHz,
                q = filter.q,
                gainDb = filter.gainDb,
                filterType = filter.filterType,
            )
            when {
                name in spl0Set && name in spl1Set -> globalBands.add(band)
                name in spl0Set -> leftBands.add(band)
                name in spl1Set -> rightBands.add(band)
                lower.startsWith("g_") || lower.startsWith("global_") -> globalBands.add(band)
                lower.startsWith("l_") || lower.startsWith("left_") || lower.endsWith("_l") -> leftBands.add(band)
                lower.startsWith("r_") || lower.startsWith("right_") || lower.endsWith("_r") -> rightBands.add(band)
                else -> globalBands.add(band)
            }
            consumedInstances.add(name)
        }

        return ParametricEqPreset(
            id = UUID.nameUUIDFromBytes(finalTitle.toByteArray()).toString(),
            name = finalTitle,
            fileName = fileName,
            sourceFileName = sourceFile ?: fileName,
            notes = notesList.take(100).joinToString(" ").take(2_000).takeIf { it.isNotBlank() },
            swapChannels = swapChannels,
            swapBeforeEq = swapBeforeEq,
            globalPreampDb = globalPreampDb,
            lrPreampDb = lrPreampDb,
            leftGainDb = leftGainDb,
            rightGainDb = rightGainDb,
            globalBands = globalBands,
            leftBands = leftBands,
            rightBands = rightBands,
            rawEelContent = content,
        )
    }

    private fun detectChannelSwap(cleanSample: String, notesList: List<String>): Pair<Boolean, Boolean> {
        // Pattern A: temp = spl0; spl0 = spl1; spl1 = temp;
        val patternA = Regex(
            """\b([A-Za-z_][A-Za-z0-9_]*)\s*=\s*spl0\s*;[\s\S]{0,120}?\bspl0\s*=\s*spl1\b[\s\S]{0,120}?\bspl1\s*=\s*\1\b""",
            RegexOption.IGNORE_CASE,
        )
        // Pattern B: temp = spl1; spl1 = spl0; spl0 = temp;
        val patternB = Regex(
            """\b([A-Za-z_][A-Za-z0-9_]*)\s*=\s*spl1\s*;[\s\S]{0,120}?\bspl1\s*=\s*spl0\b[\s\S]{0,120}?\bspl0\s*=\s*\1\b""",
            RegexOption.IGNORE_CASE,
        )
        // Pattern C (RootlessJamesDSP swapChannels.eel): tmp0 = spl0; tmp1 = spl1; spl0 = tmp1 ...; spl1 = tmp0 ...;
        val patternC = Regex(
            """\b([A-Za-z_][A-Za-z0-9_]*)\s*=\s*spl0\s*;\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*spl1\s*;[\s\S]{0,160}?\bspl0\s*=\s*\2\b[\s\S]{0,160}?\bspl1\s*=\s*\1\b""",
            RegexOption.IGNORE_CASE,
        )

        val match = patternA.find(cleanSample) ?: patternB.find(cleanSample) ?: patternC.find(cleanSample)
        if (match != null) {
            val firstProcessIdx = cleanSample.indexOf("process", ignoreCase = true)
            val swapBefore = firstProcessIdx < 0 || match.range.first < firstProcessIdx
            return true to swapBefore
        }

        val noteIndicatesSwap = notesList.any {
            it.contains("swap", ignoreCase = true) &&
                (it.contains("Left", ignoreCase = true) || it.contains("channel", ignoreCase = true))
        }
        return noteIndicatesSwap to true
    }

    private fun parseApoContent(content: String, fileName: String?): ParametricEqPreset {
        val globalBands = mutableListOf<ParametricEqBand>()
        val leftBands = mutableListOf<ParametricEqBand>()
        val rightBands = mutableListOf<ParametricEqBand>()
        var globalPreampDb = 0f
        var lrPreampDb = 0f
        var currentChannel = 0 // 0 = global/all, 1 = L, 2 = R

        val preampRegex = Regex("""Preamp:\s*([+-]?\d+(?:\.\d+)?)\s*dB""", RegexOption.IGNORE_CASE)
        val channelRegex = Regex("""Channel:\s*(.+)""", RegexOption.IGNORE_CASE)
        val filterRegex = Regex(
            """Filter\s+\d+:\s+(ON|OFF)\s+(\S+)\s+Fc\s+([\d.]+)\s+Hz(?:\s+Gain\s+([+-]?[\d.]+)\s+dB)?(?:\s+Q\s+([\d.]+))?""",
            RegexOption.IGNORE_CASE,
        )

        for (rawLine in content.lines()) {
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith("#") || line.startsWith("//")) continue

            preampRegex.find(line)?.let { match ->
                val db = match.groupValues[1].toFloatOrNull() ?: 0f
                if (currentChannel == 0) globalPreampDb = db else lrPreampDb = db
                continue
            }

            channelRegex.find(line)?.let { match ->
                val chStr = match.groupValues[1].trim().uppercase()
                currentChannel = when {
                    chStr == "L" || chStr == "1" || chStr == "LEFT" || chStr == "FL" -> 1
                    chStr == "R" || chStr == "2" || chStr == "RIGHT" || chStr == "FR" -> 2
                    else -> 0
                }
                continue
            }

            filterRegex.find(line)?.let { match ->
                val enabled = match.groupValues[1].equals("ON", ignoreCase = true)
                val typeStr = match.groupValues[2]
                val freq = match.groupValues[3].toFloatOrNull() ?: return@let
                val gain = match.groupValues[4].takeIf { it.isNotBlank() }?.toFloatOrNull() ?: 0f
                val q = match.groupValues[5].takeIf { it.isNotBlank() }?.toFloatOrNull() ?: 0.707f
                val type = ParametricEqFilterType.fromApoLabel(typeStr) ?: return@let
                val band = ParametricEqBand(
                    frequencyHz = freq,
                    q = q,
                    gainDb = gain,
                    filterType = type,
                    enabled = enabled,
                )
                when (currentChannel) {
                    1 -> leftBands.add(band)
                    2 -> rightBands.add(band)
                    else -> globalBands.add(band)
                }
            }
        }

        val name = fileName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Imported APO Preset"
        return ParametricEqPreset(
            id = UUID.nameUUIDFromBytes(name.toByteArray()).toString(),
            name = name,
            fileName = fileName,
            sourceFileName = fileName,
            swapChannels = false,
            globalPreampDb = globalPreampDb,
            lrPreampDb = lrPreampDb,
            globalBands = globalBands,
            leftBands = leftBands,
            rightBands = rightBands,
            rawEelContent = content,
        )
    }

    private fun stripComments(code: String): String {
        val noBlock = code.replace(Regex("""/\*[\s\S]*?\*/"""), "")
        return noBlock.lines().joinToString("\n") { line ->
            val idx = line.indexOf("//")
            if (idx >= 0) line.substring(0, idx) else line
        }
    }

    fun fmtSignedDb(db: Float): String =
        if (db > 0f) String.format(Locale.US, "+%.2f dB", db)
        else String.format(Locale.US, "%.2f dB", db)

    fun fmtHz(hz: Float): String =
        if (abs(hz - kotlin.math.round(hz)) < 0.05f) "${kotlin.math.round(hz).toInt()} Hz"
        else String.format(Locale.US, "%.1f Hz", hz)
}
