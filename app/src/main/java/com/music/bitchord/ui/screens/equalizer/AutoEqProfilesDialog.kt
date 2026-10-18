package com.music.bitchord.ui.screens.equalizer

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.ln

/**
 * AutoEQ profile picker adapted to MusicBeat's five-band platform EQ.
 * The bundled headphone index and the GraphicEQ text files are from AutoEq;
 * the original Echo-Music loader this follows is GPL-3.0 licensed.
 */
@Composable
fun AutoEqProfilesDialog(
    onDismiss: () -> Unit,
    onApplyEq: (List<Short>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val offlineMode by AppSettings.offlineMode.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var profiles by remember { mutableStateOf<List<AutoEqProfile>>(emptyList()) }
    var loadingCatalog by remember { mutableStateOf(true) }
    var applying by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(context) {
        val result = runCatching { withContext(Dispatchers.IO) { loadCatalog(context) } }
        profiles = result.getOrDefault(emptyList())
        if (result.isFailure) errorMessage = context.getString(R.string.autoeq_failed)
        loadingCatalog = false
    }

    val filteredProfiles = remember(profiles, query) {
        val needle = query.trim()
        (if (needle.isEmpty()) profiles else profiles.filter { it.name.contains(needle, ignoreCase = true) })
            .take(MAX_VISIBLE_PROFILES)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.82f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.autoeq_profiles),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.autoeq_profiles_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.autoeq_search_headphones)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    enabled = !applying,
                )

                errorMessage?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (offlineMode) {
                    Text(
                        text = stringResource(R.string.autoeq_requires_online),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                when {
                    loadingCatalog || applying -> Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = stringResource(if (applying) R.string.autoeq_fetching else R.string.autoeq_catalog_loading),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    filteredProfiles.isEmpty() -> Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(R.string.autoeq_no_profiles), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(filteredProfiles, key = AutoEqProfile::path) { profile ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !offlineMode && !applying) {
                                        errorMessage = null
                                        if (AppSettings.offlineMode.value) {
                                            errorMessage = context.getString(R.string.autoeq_requires_online)
                                        } else {
                                            applying = true
                                            scope.launch {
                                                val result = runCatching {
                                                    withContext(Dispatchers.IO) { downloadProfileGains(profile) }
                                                }
                                                result.onSuccess { gains ->
                                                    onApplyEq(gains)
                                                    Toast.makeText(context, profile.name, Toast.LENGTH_SHORT).show()
                                                    onDismiss()
                                                }.onFailure {
                                                    applying = false
                                                    errorMessage = context.getString(R.string.autoeq_failed)
                                                }
                                            }
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    text = profile.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = profile.path.substringBefore('/'),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                if (!applying) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text(
                            text = stringResource(android.R.string.cancel),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable(onClick = onDismiss).padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}

private data class AutoEqProfile(val name: String, val path: String)

private fun loadCatalog(context: Context): List<AutoEqProfile> {
    val json = context.resources.openRawResource(R.raw.autoeq_index)
        .bufferedReader(StandardCharsets.UTF_8)
        .use { it.readText() }
    val array = JSONArray(json)
    return buildList(array.length()) {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optString("name").trim()
            val path = item.optString("path").trim()
            if (name.isNotEmpty() && path.isNotEmpty() && path.split('/').none { it == "." || it == ".." }) {
                add(AutoEqProfile(name, path))
            }
        }
    }
}

private fun downloadProfileGains(profile: AutoEqProfile): List<Short> {
    val encodedName = URLEncoder.encode(profile.name, StandardCharsets.UTF_8.name()).replace("+", "%20")
    val url = URL("https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/${profile.path}/${encodedName}%20GraphicEQ.txt")
    val connection = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = NETWORK_TIMEOUT_MS
        readTimeout = NETWORK_TIMEOUT_MS
        requestMethod = "GET"
        setRequestProperty("Accept", "text/plain")
        setRequestProperty("User-Agent", "MusicBeatSP-AutoEQ")
    }
    val body = try {
        if (connection.responseCode !in 200..299) throw IllegalStateException("AutoEQ server returned an error")
        connection.inputStream.use(::readCappedUtf8)
    } finally {
        connection.disconnect()
    }
    val line = body.lineSequence().firstOrNull { it.trim().startsWith("GraphicEQ:", ignoreCase = true) }
        ?: throw IllegalStateException("AutoEQ profile has no GraphicEQ data")
    val points = line.substringAfter(':')
        .split(';')
        .mapNotNull { entry ->
            val pair = entry.trim().split(WHITESPACE)
            if (pair.size < 2) return@mapNotNull null
            val frequency = pair[0].toFloatOrNull()?.takeIf { it > 0f && it.isFinite() } ?: return@mapNotNull null
            val gain = pair[1].toFloatOrNull()?.takeIf(Float::isFinite) ?: return@mapNotNull null
            frequency to gain
        }
        .sortedBy { it.first }
    if (points.size < 10) throw IllegalStateException("AutoEQ profile data is incomplete")

    val sampled = TARGET_FREQUENCIES_HZ.map { target -> interpolateAt(points, target) }
    // Remove positive pre-gain before applying the platform EQ, which has no
    // matching preamp control. This keeps the profile from clipping peaks.
    val headroom = (sampled.maxOrNull() ?: 0f).coerceAtLeast(0f)
    return sampled.map { gainDb ->
        ((gainDb - headroom) * 100f).toInt().coerceIn(MIN_EQ_MILLIBELS, MAX_EQ_MILLIBELS).toShort()
    }
}

private fun interpolateAt(points: List<Pair<Float, Float>>, targetHz: Float): Float {
    val lower = points.lastOrNull { it.first <= targetHz } ?: return points.first().second
    val upper = points.firstOrNull { it.first >= targetHz } ?: return points.last().second
    if (lower.first == upper.first) return lower.second
    val span = ln(upper.first / lower.first)
    if (!span.isFinite() || span <= 0f) return lower.second
    val fraction = (ln(targetHz / lower.first) / span).coerceIn(0f, 1f)
    return lower.second + (upper.second - lower.second) * fraction
}

private fun readCappedUtf8(input: java.io.InputStream): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        if (total > MAX_PROFILE_BYTES) throw IllegalStateException("AutoEQ profile is too large")
        output.write(buffer, 0, count)
    }
    return output.toString(StandardCharsets.UTF_8.name())
}

private val WHITESPACE = Regex("\\s+")
private val TARGET_FREQUENCIES_HZ = listOf(60f, 230f, 910f, 3_600f, 14_000f)
private const val NETWORK_TIMEOUT_MS = 12_000
private const val MAX_PROFILE_BYTES = 256 * 1024
private const val MAX_VISIBLE_PROFILES = 100
private const val MIN_EQ_MILLIBELS = -1_500
private const val MAX_EQ_MILLIBELS = 1_500
