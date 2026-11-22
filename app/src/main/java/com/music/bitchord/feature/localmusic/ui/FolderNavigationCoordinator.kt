package com.music.bitchord.feature.localmusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.feature.localmusic.data.FolderNavigationRequests

/** Routes a player folder request to the in-app Folders destination. */
@Composable
internal fun FolderNavigationCoordinator(onNavigate: () -> Unit) {
    val request by FolderNavigationRequests.pending.collectAsStateWithLifecycle()
    val currentNavigate by rememberUpdatedState(onNavigate)
    LaunchedEffect(request?.id) {
        if (request != null) currentNavigate()
    }
}
