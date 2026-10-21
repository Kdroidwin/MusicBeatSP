package com.music.bitchord.data.settings

import kotlinx.serialization.Serializable
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Filter types supported by the high-precision RBJ Biquad parametric equalizer,
 * modeled after RootlessJamesDSP & EqualizerAPO / EEL2 LiveProg.
 */
@Serializable
enum class ParametricEqFilterType(
    val code: Int,
    val apoLabel: String,
    val displayLabel: String,
    val eelFunction: String,
    val descriptionJa: String,
) {
    PEAKING(0, "PK", "PK", "calc_peak", "ピーキング (Peaking)"),
    LOW_SHELF(1, "LSC", "LSC", "calc_lshelf", "ローシェルフ (Low Shelf)"),
    HIGH_SHELF(2, "HSC", "HSC", "calc_hshelf", "ハイシェルフ (High Shelf)"),
    LOW_PASS(3, "LPQ", "LPF", "calc_lpf", "ローパス (Low Pass)"),
    HIGH_PASS(4, "HPQ", "HPF", "calc_hpf", "ハイパス (High Pass)"),
    BAND_PASS(5, "BP", "BPF", "calc_bpf", "バンドパス (Band Pass)"),
    NOTCH(6, "NO", "NO", "calc_notch", "ノッチ (Notch)"),
    ALL_PASS(7, "AP", "AP", "calc_allpass", "オールパス (All Pass)");

    companion object {
        fun fromCode(code: Int): ParametricEqFilterType =
            entries.firstOrNull { it.code == code } ?: PEAKING

        fun fromApoLabel(label: String): ParametricEqFilterType? = when (label.trim().uppercase()) {
            "PK", "PEAK", "PEAKING" -> PEAKING
            "LSC", "LS", "LOW_SHELF", "LOWSHELF" -> LOW_SHELF
            "HSC", "HS", "HIGH_SHELF", "HIGHSHELF" -> HIGH_SHELF
            "LP", "LPF", "LPQ", "LOW_PASS", "LOWPASS" -> LOW_PASS
            "HP", "HPF", "HPQ", "HIGH_PASS", "HIGHPASS" -> HIGH_PASS
            "BP", "BPF", "BAND_PASS", "BANDPASS" -> BAND_PASS
            "NO", "NOTCH" -> NOTCH
            "AP", "APF", "ALL_PASS", "ALLPASS" -> ALL_PASS
            else -> null
        }

        fun fromEelFunction(funcName: String): ParametricEqFilterType? {
            val lower = funcName.trim().lowercase()
            return when {
                lower.contains("lshelf") || lower.contains("lowshelf") || lower.endsWith("_lsc") || lower.endsWith("_ls") -> LOW_SHELF
                lower.contains("hshelf") || lower.contains("highshelf") || lower.endsWith("_hsc") || lower.endsWith("_hs") -> HIGH_SHELF
                lower.contains("peak") || lower.endsWith("_pk") -> PEAKING
                lower.contains("lpf") || lower.contains("lowpass") || lower.endsWith("_lp") -> LOW_PASS
                lower.contains("hpf") || lower.contains("highpass") || lower.endsWith("_hp") -> HIGH_PASS
                lower.contains("bpf") || lower.contains("bandpass") || lower.endsWith("_bp") -> BAND_PASS
                lower.contains("notch") -> NOTCH
                lower.contains("allpass") || lower.contains("apf") -> ALL_PASS
                else -> null
            }
        }
    }
}

enum class ParametricEqChannelTarget(val labelJa: String, val shortLabel: String) {
    GLOBAL("両ch共通 (Global)", "Global"),
    LEFT("左ch独立 (Left)", "L ch"),
    RIGHT("右ch独立 (Right)", "R ch"),
}

/** One biquad filter band in the high-precision software parametric equalizer. */
@Serializable
data class ParametricEqBand(
    val frequencyHz: Float,
    val q: Float,
    val gainDb: Float,
    val filterType: ParametricEqFilterType = ParametricEqFilterType.PEAKING,
    val enabled: Boolean = true,
    val id: String = UUID.randomUUID().toString(),
) {
    fun sanitized(fallback: ParametricEqBand = DEFAULT_BAND): ParametricEqBand = ParametricEqBand(
        frequencyHz = frequencyHz.takeIf(Float::isFinite)?.coerceIn(MIN_FREQUENCY_HZ, MAX_FREQUENCY_HZ)
            ?: fallback.frequencyHz,
        q = q.takeIf(Float::isFinite)?.coerceIn(MIN_Q, MAX_Q) ?: fallback.q,
        gainDb = gainDb.takeIf(Float::isFinite)?.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB) ?: fallback.gainDb,
        filterType = filterType,
        enabled = enabled,
        id = id.ifBlank { UUID.randomUUID().toString() },
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("freq", frequencyHz.toDouble())
        put("q", q.toDouble())
        put("gain", gainDb.toDouble())
        put("type", filterType.code)
        put("enabled", enabled)
    }

    companion object {
        const val MIN_FREQUENCY_HZ = 10f
        const val MAX_FREQUENCY_HZ = 24_000f
        const val MIN_Q = 0.05f
        const val MAX_Q = 30f
        const val MIN_GAIN_DB = -30f
        const val MAX_GAIN_DB = 30f
        const val MAX_BANDS_PER_CHANNEL = 64

        val DEFAULT_BAND = ParametricEqBand(frequencyHz = 1_000f, q = 1.414f, gainDb = 0f)

        val DEFAULTS = listOf(
            ParametricEqBand(frequencyHz = 100f, q = 0.707f, gainDb = 0f),
            ParametricEqBand(frequencyHz = 1_000f, q = 0.707f, gainDb = 0f),
            ParametricEqBand(frequencyHz = 8_000f, q = 0.707f, gainDb = 0f),
        )

        fun fromJson(obj: JSONObject): ParametricEqBand? {
            val freq = obj.optDouble("freq", Double.NaN).toFloat()
            val q = obj.optDouble("q", Double.NaN).toFloat()
            val gain = obj.optDouble("gain", Double.NaN).toFloat()
            if (!freq.isFinite() || !q.isFinite() || !gain.isFinite()) return null
            val type = ParametricEqFilterType.fromCode(obj.optInt("type", 0))
            val enabled = obj.optBoolean("enabled", true)
            val id = obj.optString("id", "").ifBlank { UUID.randomUUID().toString() }
            return ParametricEqBand(
                frequencyHz = freq,
                q = q,
                gainDb = gain,
                filterType = type,
                enabled = enabled,
                id = id,
            ).sanitized()
        }

        fun encode(bands: List<ParametricEqBand>): String = bands.map { band ->
            val safe = band.sanitized()
            "${safe.frequencyHz},${safe.q},${safe.gainDb},${safe.filterType.code},${if (safe.enabled) 1 else 0}"
        }.joinToString(";")

        fun decode(value: String?): List<ParametricEqBand> {
            if (value.isNullOrBlank()) return DEFAULTS
            val values = value.split(';').filter { it.isNotBlank() }.take(MAX_BANDS_PER_CHANNEL)
            if (values.isEmpty()) return DEFAULTS
            return values.mapIndexedNotNull { index, entry ->
                val fallback = DEFAULTS.getOrElse(index) { DEFAULT_BAND }
                val fields = entry.split(',')
                if (fields.size >= 3) {
                    ParametricEqBand(
                        frequencyHz = fields[0].toFloatOrNull() ?: fallback.frequencyHz,
                        q = fields[1].toFloatOrNull() ?: fallback.q,
                        gainDb = fields[2].toFloatOrNull() ?: fallback.gainDb,
                        filterType = fields.getOrNull(3)?.toIntOrNull()?.let(ParametricEqFilterType::fromCode)
                            ?: ParametricEqFilterType.PEAKING,
                        enabled = fields.getOrNull(4)?.let { it != "0" && !it.equals("false", ignoreCase = true) } ?: true,
                    ).sanitized(fallback)
                } else null
            }.ifEmpty { DEFAULTS }
        }
    }
}

/**
 * Complete in-app PEQ & DSP configuration including:
 * - Global PEQ bands (applied to both L and R)
 * - Left-channel independent PEQ bands (applied to L only)
 * - Right-channel independent PEQ bands (applied to R only)
 * - Global Preamp (dB), L/R Preamp (dB), and per-channel gain offsets (dB)
 * - Stereo Channel Swap (L <-> R, before or after EQ)
 */
@Serializable
data class ParametricEqConfig(
    val enabled: Boolean = false,
    val swapChannels: Boolean = false,
    val swapBeforeEq: Boolean = true,
    val globalPreampDb: Float = 0f,
    val lrPreampDb: Float = 0f,
    val leftGainDb: Float = 0f,
    val rightGainDb: Float = 0f,
    val globalBands: List<ParametricEqBand> = ParametricEqBand.DEFAULTS,
    val leftBands: List<ParametricEqBand> = emptyList(),
    val rightBands: List<ParametricEqBand> = emptyList(),
    val activePresetId: String? = null,
    val activePresetName: String? = null,
    val sourceFileName: String? = null,
    val notes: String? = null,
) {
    val totalPreampDb: Float
        get() = globalPreampDb + lrPreampDb

    val effectiveLeftPreampDb: Float
        get() = globalPreampDb + lrPreampDb + leftGainDb

    val effectiveRightPreampDb: Float
        get() = globalPreampDb + lrPreampDb + rightGainDb

    val activeGlobalBands: List<ParametricEqBand>
        get() = globalBands.filter { it.enabled && isBandEffective(it) }

    val activeLeftBands: List<ParametricEqBand>
        get() = leftBands.filter { it.enabled && isBandEffective(it) }

    val activeRightBands: List<ParametricEqBand>
        get() = rightBands.filter { it.enabled && isBandEffective(it) }

    /** Allocation-free check used on the real-time audio thread. */
    val hasActiveFilters: Boolean
        get() = globalBands.any { it.enabled && isBandEffective(it) } ||
            leftBands.any { it.enabled && isBandEffective(it) } ||
            rightBands.any { it.enabled && isBandEffective(it) }

    val hasActiveProcessing: Boolean
        get() = enabled && (
            swapChannels ||
                effectiveLeftPreampDb != 0f ||
                effectiveRightPreampDb != 0f ||
                hasActiveFilters
            )

    fun sanitized(): ParametricEqConfig = copy(
        globalPreampDb = globalPreampDb.takeIf(Float::isFinite)?.coerceIn(-30f, 20f) ?: 0f,
        lrPreampDb = lrPreampDb.takeIf(Float::isFinite)?.coerceIn(-30f, 20f) ?: 0f,
        leftGainDb = leftGainDb.takeIf(Float::isFinite)?.coerceIn(-30f, 20f) ?: 0f,
        rightGainDb = rightGainDb.takeIf(Float::isFinite)?.coerceIn(-30f, 20f) ?: 0f,
        globalBands = globalBands.take(ParametricEqBand.MAX_BANDS_PER_CHANNEL).map { it.sanitized() },
        leftBands = leftBands.take(ParametricEqBand.MAX_BANDS_PER_CHANNEL).map { it.sanitized() },
        rightBands = rightBands.take(ParametricEqBand.MAX_BANDS_PER_CHANNEL).map { it.sanitized() },
    )

    fun toJsonString(): String = toJson().toString()

    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("swapChannels", swapChannels)
        put("swapBeforeEq", swapBeforeEq)
        put("globalPreampDb", globalPreampDb.toDouble())
        put("lrPreampDb", lrPreampDb.toDouble())
        put("leftGainDb", leftGainDb.toDouble())
        put("rightGainDb", rightGainDb.toDouble())
        put("globalBands", JSONArray().apply { globalBands.forEach { put(it.toJson()) } })
        put("leftBands", JSONArray().apply { leftBands.forEach { put(it.toJson()) } })
        put("rightBands", JSONArray().apply { rightBands.forEach { put(it.toJson()) } })
        if (activePresetId != null) put("activePresetId", activePresetId)
        if (activePresetName != null) put("activePresetName", activePresetName)
        if (sourceFileName != null) put("sourceFileName", sourceFileName)
        if (notes != null) put("notes", notes)
    }

    companion object {
        private fun isBandEffective(band: ParametricEqBand): Boolean = when (band.filterType) {
            ParametricEqFilterType.PEAKING,
            ParametricEqFilterType.LOW_SHELF,
            ParametricEqFilterType.HIGH_SHELF -> band.gainDb != 0f
            else -> true
        }

        fun fromJsonString(raw: String?): ParametricEqConfig? {
            if (raw.isNullOrBlank() || raw.length > MAX_CONFIG_JSON_CHARS) return null
            return runCatching { fromJson(JSONObject(raw)) }.getOrNull()
        }

        fun fromJson(obj: JSONObject): ParametricEqConfig {
            fun parseBands(key: String, defaultIfMissing: List<ParametricEqBand>): List<ParametricEqBand> {
                val arr = obj.optJSONArray(key) ?: return defaultIfMissing
                val list = mutableListOf<ParametricEqBand>()
                for (i in 0 until minOf(arr.length(), ParametricEqBand.MAX_BANDS_PER_CHANNEL)) {
                    val item = arr.optJSONObject(i) ?: continue
                    ParametricEqBand.fromJson(item)?.let(list::add)
                }
                return list
            }
            return ParametricEqConfig(
                enabled = obj.optBoolean("enabled", false),
                swapChannels = obj.optBoolean("swapChannels", false),
                swapBeforeEq = obj.optBoolean("swapBeforeEq", true),
                globalPreampDb = obj.optDouble("globalPreampDb", 0.0).toFloat(),
                lrPreampDb = obj.optDouble("lrPreampDb", 0.0).toFloat(),
                leftGainDb = obj.optDouble("leftGainDb", 0.0).toFloat(),
                rightGainDb = obj.optDouble("rightGainDb", 0.0).toFloat(),
                globalBands = parseBands("globalBands", ParametricEqBand.DEFAULTS),
                leftBands = parseBands("leftBands", emptyList()),
                rightBands = parseBands("rightBands", emptyList()),
                activePresetId = obj.optString("activePresetId", "").takeIf { it.isNotBlank() },
                activePresetName = obj.optString("activePresetName", "").takeIf { it.isNotBlank() },
                sourceFileName = obj.optString("sourceFileName", "").takeIf { it.isNotBlank() },
                notes = obj.optString("notes", "").takeIf { it.isNotBlank() },
            ).sanitized()
        }

        private const val MAX_CONFIG_JSON_CHARS = 1_000_000
    }
}

/**
 * Saved PEQ / EEL preset that can be recalled or deleted at any time.
 */
@Serializable
data class ParametricEqPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val fileName: String? = null,
    val sourceFileName: String? = null,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val swapChannels: Boolean = false,
    val swapBeforeEq: Boolean = true,
    val globalPreampDb: Float = 0f,
    val lrPreampDb: Float = 0f,
    val leftGainDb: Float = 0f,
    val rightGainDb: Float = 0f,
    val globalBands: List<ParametricEqBand> = emptyList(),
    val leftBands: List<ParametricEqBand> = emptyList(),
    val rightBands: List<ParametricEqBand> = emptyList(),
    val rawEelContent: String? = null,
) {
    val totalPreampDb: Float
        get() = globalPreampDb + lrPreampDb

    fun toConfig(enabled: Boolean = true): ParametricEqConfig = ParametricEqConfig(
        enabled = enabled,
        swapChannels = swapChannels,
        swapBeforeEq = swapBeforeEq,
        globalPreampDb = globalPreampDb,
        lrPreampDb = lrPreampDb,
        leftGainDb = leftGainDb,
        rightGainDb = rightGainDb,
        globalBands = globalBands,
        leftBands = leftBands,
        rightBands = rightBands,
        activePresetId = id,
        activePresetName = name,
        sourceFileName = sourceFileName ?: fileName,
        notes = notes,
    ).sanitized()

    fun sanitized(): ParametricEqPreset = copy(
        name = name.trim().take(MAX_NAME_LENGTH).ifEmpty { "Custom PEQ" },
        fileName = fileName?.substringAfterLast('/')?.substringAfterLast('\\')?.take(MAX_FILE_NAME_LENGTH),
        sourceFileName = sourceFileName?.substringAfterLast('/')?.substringAfterLast('\\')?.take(MAX_FILE_NAME_LENGTH),
        notes = notes?.take(MAX_NOTES_LENGTH),
        globalPreampDb = globalPreampDb.takeIf(Float::isFinite)?.coerceIn(-30f, 20f) ?: 0f,
        lrPreampDb = lrPreampDb.takeIf(Float::isFinite)?.coerceIn(-30f, 20f) ?: 0f,
        leftGainDb = leftGainDb.takeIf(Float::isFinite)?.coerceIn(-30f, 20f) ?: 0f,
        rightGainDb = rightGainDb.takeIf(Float::isFinite)?.coerceIn(-30f, 20f) ?: 0f,
        globalBands = globalBands.take(ParametricEqBand.MAX_BANDS_PER_CHANNEL).map { it.sanitized() },
        leftBands = leftBands.take(ParametricEqBand.MAX_BANDS_PER_CHANNEL).map { it.sanitized() },
        rightBands = rightBands.take(ParametricEqBand.MAX_BANDS_PER_CHANNEL).map { it.sanitized() },
        rawEelContent = rawEelContent?.take(MAX_RAW_EEL_CHARS),
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        if (fileName != null) put("fileName", fileName)
        if (sourceFileName != null) put("sourceFileName", sourceFileName)
        if (notes != null) put("notes", notes)
        put("createdAt", createdAt)
        put("swapChannels", swapChannels)
        put("swapBeforeEq", swapBeforeEq)
        put("globalPreampDb", globalPreampDb.toDouble())
        put("lrPreampDb", lrPreampDb.toDouble())
        put("leftGainDb", leftGainDb.toDouble())
        put("rightGainDb", rightGainDb.toDouble())
        put("globalBands", JSONArray().apply { globalBands.forEach { put(it.toJson()) } })
        put("leftBands", JSONArray().apply { leftBands.forEach { put(it.toJson()) } })
        put("rightBands", JSONArray().apply { rightBands.forEach { put(it.toJson()) } })
        if (rawEelContent != null) put("rawEelContent", rawEelContent)
    }

    companion object {
        fun fromJson(obj: JSONObject): ParametricEqPreset? {
            val name = obj.optString("name", "").trim()
            if (name.isEmpty()) return null
            fun parseBands(key: String): List<ParametricEqBand> {
                val arr = obj.optJSONArray(key) ?: return emptyList()
                val list = mutableListOf<ParametricEqBand>()
                for (i in 0 until minOf(arr.length(), ParametricEqBand.MAX_BANDS_PER_CHANNEL)) {
                    val item = arr.optJSONObject(i) ?: continue
                    ParametricEqBand.fromJson(item)?.let(list::add)
                }
                return list
            }
            return ParametricEqPreset(
                id = obj.optString("id", "").ifBlank { UUID.randomUUID().toString() },
                name = name,
                fileName = obj.optString("fileName", "").takeIf { it.isNotBlank() },
                sourceFileName = obj.optString("sourceFileName", "").takeIf { it.isNotBlank() },
                notes = obj.optString("notes", "").takeIf { it.isNotBlank() },
                createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                swapChannels = obj.optBoolean("swapChannels", false),
                swapBeforeEq = obj.optBoolean("swapBeforeEq", true),
                globalPreampDb = obj.optDouble("globalPreampDb", 0.0).toFloat(),
                lrPreampDb = obj.optDouble("lrPreampDb", 0.0).toFloat(),
                leftGainDb = obj.optDouble("leftGainDb", 0.0).toFloat(),
                rightGainDb = obj.optDouble("rightGainDb", 0.0).toFloat(),
                globalBands = parseBands("globalBands"),
                leftBands = parseBands("leftBands"),
                rightBands = parseBands("rightBands"),
                rawEelContent = obj.optString("rawEelContent", "").take(MAX_RAW_EEL_CHARS).takeIf { it.isNotBlank() },
            ).sanitized()
        }

        fun encodeList(presets: List<ParametricEqPreset>): String =
            JSONArray().apply { presets.take(MAX_SAVED_PRESETS).forEach { put(it.sanitized().toJson()) } }.toString()

        fun decodeList(raw: String?): List<ParametricEqPreset> {
            if (raw.isNullOrBlank() || raw.length > MAX_PRESET_JSON_CHARS) return emptyList()
            val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
            val list = mutableListOf<ParametricEqPreset>()
            for (i in 0 until minOf(arr.length(), MAX_SAVED_PRESETS)) {
                val obj = arr.optJSONObject(i) ?: continue
                fromJson(obj)?.let(list::add)
            }
            return list
        }

        private const val MAX_SAVED_PRESETS = 32
        private const val MAX_PRESET_JSON_CHARS = 4_000_000
        private const val MAX_RAW_EEL_CHARS = 64 * 1024
        private const val MAX_NAME_LENGTH = 80
        private const val MAX_FILE_NAME_LENGTH = 255
        private const val MAX_NOTES_LENGTH = 2_000
    }
}
