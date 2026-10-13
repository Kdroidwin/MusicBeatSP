package com.music.bitchord.feature.localmusic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.music.bitchord.ui.components.FastScroller
import com.music.bitchord.ui.components.SectionIndexer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.bitchord.feature.localmusic.domain.model.LocalFolder

/**
 * Tab content rendering storage folders discovered on the device.
 */
@Composable
fun LocalFoldersTab(
    folders: List<LocalFolder>,
    onFolderClick: (LocalFolder) -> Unit,
    onToggleBlacklist: (String, Boolean) -> Unit,
    onOpenManageFolders: () -> Unit,
    contentPadding: PaddingValues,
    hierarchical: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (folders.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Rounded.FolderOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(56.dp),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "No audio folders found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    val listState = rememberLazyListState()
    val displayFolders = remember(folders, hierarchical) {
        if (hierarchical) folders.sortedBy { it.path.replace('\\', '/').lowercase() } else folders
    }
    val scrollPadding = PaddingValues(
        start = 16.dp,
        end = 16.dp,
        top = contentPadding.calculateTopPadding() + 8.dp,
        bottom = contentPadding.calculateBottomPadding() + 16.dp,
    )
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = scrollPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${displayFolders.size} ${if (displayFolders.size == 1) "Folder" else "Folders"}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = onOpenManageFolders,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FolderSpecial,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Manage Blacklist", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            items(displayFolders, key = { it.path }) { folder ->
                val depth = if (hierarchical) {
                    displayFolders.count { ancestor ->
                        val parent = ancestor.path.trimEnd('/', '\\')
                        parent != folder.path.trimEnd('/', '\\') &&
                            folder.path.replace('\\', '/').startsWith(parent.replace('\\', '/') + "/")
                    }
                } else 0
                LocalFolderItem(
                    folder = folder,
                    depth = depth,
                    onClick = { onFolderClick(folder) },
                    onToggleBlacklist = { isBlacklisted ->
                        onToggleBlacklist(folder.path, isBlacklisted)
                    },
                )
            }
        }
        FastScroller(
            listState = listState,
            itemCount = displayFolders.size,
            sectionNameForIndex = { index -> SectionIndexer.getSectionName(displayFolders[index].name) },
            contentPadding = scrollPadding,
        )
    }
}
