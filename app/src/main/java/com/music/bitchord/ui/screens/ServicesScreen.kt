package com.music.bitchord.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.spotify.SpItem
import com.music.bitchord.data.spotify.SpotifyApi
import com.music.bitchord.data.spotify.SpotifyAuth
import com.music.bitchord.external.ExternalMedia
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ServicesScreen(modifier: Modifier = Modifier, allowSpotify: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val externalEnabled by AppSettings.externalMediaControlEnabled.collectAsStateWithLifecycle()
    val spotifyEnabled by AppSettings.spotifyIntegrationEnabled.collectAsStateWithLifecycle()
    var clientId by rememberSaveable { mutableStateOf(SpotifyAuth.clientId(context)) }
    var loggedIn by remember { mutableStateOf(SpotifyAuth.isLoggedIn(context)) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingExternalEnable by rememberSaveable { mutableStateOf(false) }
    val results = remember { mutableStateListOf<SpItem>() }
    val savedTracks = remember { mutableStateListOf<SpItem>() }
    var remote by remember { mutableStateOf<SpotifyAppRemote?>(null) }

    val loginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val response = AuthorizationClient.getResponse(result.resultCode, result.data)
        busy = true
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { SpotifyAuth.finishLogin(context, response) }.exceptionOrNull()
            }
            loggedIn = SpotifyAuth.isLoggedIn(context)
            message = error?.message ?: if (loggedIn) context.getString(R.string.spotify_login_succeeded) else null
            busy = false
        }
    }

    val notificationSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        ExternalMedia.setEnabled(context, AppSettings.externalMediaControlEnabled.value)
        if (pendingExternalEnable) {
            if (ExternalMedia.permitted) AppSettings.setExternalMediaControlEnabled(true)
            pendingExternalEnable = false
        }
    }
    val beginSpotifyLogin: () -> Unit = {
        val activity = context.findActivity()
        when {
            clientId.isBlank() -> message = context.getString(R.string.spotify_enter_client_id)
            activity == null -> message = context.getString(R.string.spotify_activity_unavailable)
            else -> runCatching {
                SpotifyAuth.setClientId(context, clientId)
                AppSettings.setSpotifyIntegrationEnabled(true)
                val request = SpotifyAuth.request(context)
                busy = true
                loginLauncher.launch(AuthorizationClient.createLoginActivityIntent(activity, request))
            }.onFailure {
                busy = false
                message = it.message ?: context.getString(R.string.spotify_activity_unavailable)
            }
        }
    }
    val openNotificationSettings: (Boolean) -> Unit = { enableAfterGrant ->
        pendingExternalEnable = enableAfterGrant
        var launched = false
        for (intent in ExternalMedia.permissionSettingsIntents(context)) {
            if (runCatching { notificationSettingsLauncher.launch(intent) }.isSuccess) {
                launched = true
                break
            }
        }
        if (!launched) {
            pendingExternalEnable = false
            Toast.makeText(context, R.string.notification_access_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(externalEnabled) { ExternalMedia.setEnabled(context, externalEnabled) }
    LaunchedEffect(spotifyEnabled, loggedIn) {
        if (spotifyEnabled && loggedIn) {
            busy = true
            val result = withContext(Dispatchers.IO) { runCatching { SpotifyApi.savedTracks(context, 0).items } }
            savedTracks.clear()
            result.getOrNull()?.let(savedTracks::addAll)
            message = result.exceptionOrNull()?.message
            busy = false
        } else if (!spotifyEnabled) {
            results.clear()
            savedTracks.clear()
        }
    }

    DisposableEffect(remote) {
        onDispose { remote?.let { SpotifyAppRemote.disconnect(it) } }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(stringResource(R.string.services), style = MaterialTheme.typography.headlineSmall)
        }
        item {
            ExternalMediaCard(
                enabled = externalEnabled,
                onToggle = AppSettings::setExternalMediaControlEnabled,
                onEnable = {
                    if (ExternalMedia.permitted) AppSettings.setExternalMediaControlEnabled(true)
                    else openNotificationSettings(true)
                },
                onPermission = { openNotificationSettings(false) },
            )
        }
        if (allowSpotify) item {
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.spotify_integration), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.spotify_integration_description), style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { AppSettings.setSpotifyIntegrationEnabled(!spotifyEnabled) }) {
                            Text(stringResource(if (spotifyEnabled) R.string.service_disable else R.string.service_enable))
                        }
                        Text(stringResource(if (spotifyEnabled) R.string.service_enabled else R.string.service_disabled))
                    }
                    HorizontalDivider()
                    Text(stringResource(R.string.spotify_dashboard_setup), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.spotify_dashboard_setup_description), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://developer.spotify.com/dashboard"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.onFailure { message = context.getString(R.string.spotify_dashboard_open_failed) }
                    }) { Text(stringResource(R.string.spotify_dashboard_open)) }
                    OutlinedTextField(
                        value = clientId,
                        onValueChange = { clientId = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.spotify_client_id)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            SpotifyAuth.setClientId(context, clientId)
                            loggedIn = SpotifyAuth.isLoggedIn(context)
                            message = context.getString(R.string.spotify_client_id_saved)
                        }) { Text(stringResource(R.string.save)) }
                        Button(enabled = clientId.isNotBlank() && !busy, onClick = beginSpotifyLogin) {
                            Text(stringResource(if (loggedIn) R.string.spotify_reauthorize else R.string.spotify_login))
                        }
                        if (busy) CircularProgressIndicator()
                    }
                    CopyValueRow(stringResource(R.string.spotify_redirect_uri), SpotifyAuth.REDIRECT_URI) {
                        clipboard.setText(AnnotatedString(SpotifyAuth.REDIRECT_URI))
                    }
                    CopyValueRow(stringResource(R.string.spotify_package_name), context.packageName) {
                        clipboard.setText(AnnotatedString(context.packageName))
                    }
                    val signingSha1 = remember(context) { SpotifyAuth.signingSha1(context) }
                    CopyValueRow(stringResource(R.string.spotify_signing_sha1), signingSha1.ifBlank { stringResource(R.string.service_unavailable) }) {
                        if (signingSha1.isNotBlank()) clipboard.setText(AnnotatedString(signingSha1))
                    }
                    Text(stringResource(R.string.spotify_premium_required), style = MaterialTheme.typography.bodySmall)
                    message?.let {
                        val isStatus = it in listOf(
                            stringResource(R.string.spotify_login_succeeded),
                            stringResource(R.string.spotify_client_id_saved),
                            stringResource(R.string.spotify_logged_out),
                        )
                        Text(
                            it,
                            color = if (isStatus) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (loggedIn) {
                        Text(stringResource(R.string.spotify_account_connected), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        OutlinedButton(onClick = {
                            remote?.let(SpotifyAppRemote::disconnect)
                            remote = null
                            SpotifyAuth.logout(context)
                            loggedIn = false
                            message = context.getString(R.string.spotify_logged_out)
                        }) { Text(stringResource(R.string.spotify_logout)) }
                    }
                    if (spotifyEnabled && loggedIn) {
                        HorizontalDivider()
                        Text(stringResource(R.string.spotify_library), style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = searchText,
                            onValueChange = { searchText = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.search_spotify)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !busy && searchText.isNotBlank(), onClick = {
                                busy = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { runCatching { SpotifyApi.search(context, searchText).tracks } }
                                    results.clear()
                                    result.getOrNull()?.let(results::addAll)
                                    message = result.exceptionOrNull()?.message
                                    busy = false
                                }
                            }) { Text(stringResource(R.string.search)) }
                            OutlinedButton(enabled = !busy, onClick = {
                                busy = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { runCatching { SpotifyApi.savedTracks(context, 0).items } }
                                    savedTracks.clear()
                                    result.getOrNull()?.let(savedTracks::addAll)
                                    message = result.exceptionOrNull()?.message
                                    busy = false
                                }
                            }) { Text(stringResource(R.string.service_refresh)) }
                            if (busy) CircularProgressIndicator()
                        }
                    }
                }
            }
        }
        if (allowSpotify && spotifyEnabled && loggedIn) {
            if (results.isNotEmpty()) item { Text(stringResource(R.string.spotify_search_results), style = MaterialTheme.typography.titleMedium) }
            items(results, key = { "search-${it.uri}" }) { item ->
                SpotifyTrackRow(item, onPlay = { playWithSpotify(context, clientId, item.uri, { remote = it }, { message = it }) })
            }
            if (savedTracks.isNotEmpty()) item { Text(stringResource(R.string.spotify_saved_tracks), style = MaterialTheme.typography.titleMedium) }
            items(savedTracks, key = { "saved-${it.uri}" }) { item ->
                SpotifyTrackRow(item, onPlay = { playWithSpotify(context, clientId, item.uri, { remote = it }, { message = it }) })
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ExternalMediaCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onEnable: () -> Unit,
    onPermission: () -> Unit,
) {
    val context = LocalContext.current
    val now = ExternalMedia.now
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.external_media_control), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.external_media_description), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (enabled) onToggle(false) else onEnable() }) {
                    Text(stringResource(if (enabled) R.string.service_disable else R.string.service_enable))
                }
                OutlinedButton(onClick = onPermission) {
                    Text(stringResource(if (ExternalMedia.permitted) R.string.notification_access_settings else R.string.notification_access_grant))
                }
            }
            Text(
                stringResource(if (ExternalMedia.permitted) R.string.notification_access_granted else R.string.notification_access_required),
                style = MaterialTheme.typography.bodySmall,
            )
            if (enabled && now == null) Text(stringResource(R.string.no_external_media_session), style = MaterialTheme.typography.bodyMedium)
            if (enabled && now != null) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    now.art?.let {
                        androidx.compose.foundation.Image(
                            bitmap = it,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(now.appName, style = MaterialTheme.typography.labelMedium)
                        Text(now.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(now.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    if (now.canPrev) IconButton(onClick = ExternalMedia::previous) { Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.widget_previous)) }
                    IconButton(onClick = ExternalMedia::playPause) { Icon(if (now.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null) }
                    if (now.canNext) IconButton(onClick = ExternalMedia::next) { Icon(Icons.Rounded.SkipNext, stringResource(R.string.widget_next)) }
                    IconButton(onClick = { ExternalMedia.openApp(context) }) { Icon(Icons.Rounded.OpenInNew, stringResource(R.string.service_open_app)) }
                }
            }
        }
    }
}

@Composable
private fun CopyValueRow(label: String, value: String, onCopy: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onCopy) { Text(stringResource(R.string.service_copy)) }
    }
}

@Composable
private fun SpotifyTrackRow(item: SpItem, onPlay: () -> Unit) {
    Surface(onClick = onPlay, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (item.image != null) AsyncImage(model = item.image, contentDescription = null, modifier = Modifier.width(48.dp).height(48.dp))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(item.sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Rounded.PlayArrow, stringResource(R.string.play))
        }
    }
}

private fun playWithSpotify(
    context: Context,
    clientId: String,
    uri: String,
    onConnected: (SpotifyAppRemote) -> Unit,
    onError: (String) -> Unit,
) {
    val activity = context.findActivity() ?: run { onError(context.getString(R.string.spotify_activity_unavailable)); return }
    val params = ConnectionParams.Builder(clientId)
        .setRedirectUri(SpotifyAuth.REDIRECT_URI)
        .showAuthView(true)
        .build()
    SpotifyAppRemote.connect(activity, params, object : Connector.ConnectionListener {
        override fun onConnected(appRemote: SpotifyAppRemote) {
            onConnected(appRemote)
            appRemote.playerApi.play(uri)
                .setErrorCallback { onError(it.message ?: context.getString(R.string.spotify_play_failed)) }
        }
        override fun onFailure(error: Throwable) {
            onError(error.message ?: context.getString(R.string.spotify_connect_failed))
        }
    })
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
