package com.music.bitchord.playback.cast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.music.bitchord.R

@Composable
fun CastActionItem(onUnavailable: () -> Unit) {
    val context = LocalContext.current
    val castContext = remember(context) { runCatching { CastContext.getSharedInstance(context) }.getOrNull() }
    if (castContext == null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onUnavailable)
                .padding(horizontal = 22.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Cast, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(18.dp))
            Text(stringResource(R.string.chromecast), style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Cast, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(18.dp))
        Text(stringResource(R.string.chromecast), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        AndroidView(
            factory = { viewContext ->
                MediaRouteButton(viewContext).also { CastButtonFactory.setUpMediaRouteButton(viewContext, it) }
            },
            modifier = Modifier.size(48.dp),
        )
    }
}

@Composable
fun CastQuickActionButton(onUnavailable: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val castContext = remember(context) { runCatching { CastContext.getSharedInstance(context) }.getOrNull() }
    if (castContext == null) {
        IconButton(onClick = onUnavailable, modifier = modifier.size(48.dp)) {
            Icon(Icons.Rounded.Cast, contentDescription = stringResource(R.string.chromecast), tint = MaterialTheme.colorScheme.onSurface)
        }
    } else {
        AndroidView(
            factory = { viewContext ->
                MediaRouteButton(viewContext).also {
                    it.contentDescription = viewContext.getString(R.string.chromecast)
                    CastButtonFactory.setUpMediaRouteButton(viewContext, it)
                }
            },
            modifier = modifier.size(48.dp),
        )
    }
}
