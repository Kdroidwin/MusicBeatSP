package com.music.bitchord

import com.music.bitchord.data.settings.MainNavigationTab
import com.music.bitchord.data.settings.MainNavigationTabs
import com.music.bitchord.data.settings.PlayerControl
import com.music.bitchord.data.settings.PlayerControlOrdering
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainNavigationTabsTest {
    @Test
    fun `at least one navigation tab stays visible`() {
        val one = setOf(MainNavigationTab.LIBRARY)
        assertEquals(one, MainNavigationTabs.setVisible(one, MainNavigationTab.LIBRARY, false))
        assertTrue(MainNavigationTabs.setVisible(emptySet(), MainNavigationTab.SEARCH, false).isNotEmpty())
    }

    @Test
    fun `hidden current tab falls to next visible tab and keeps order`() {
        val visible = setOf(MainNavigationTab.ARTISTS, MainNavigationTab.SEARCH)
        assertEquals(MainNavigationTab.ARTISTS, MainNavigationTabs.fallback(MainNavigationTab.ALBUMS.index, visible))
        assertEquals(MainNavigationTab.SEARCH, MainNavigationTabs.fallback(MainNavigationTab.SEARCH.index, visible))
        assertEquals(
            setOf(MainNavigationTab.SONGS, MainNavigationTab.ALBUMS),
            MainNavigationTabs.setVisible(
                setOf(MainNavigationTab.SONGS),
                MainNavigationTab.ALBUMS,
                true,
            ),
        )
    }

    @Test
    fun `navigation tab order moves and fallback follows the saved order`() {
        val reordered = MainNavigationTabs.move(
            MainNavigationTab.entries,
            MainNavigationTab.LIBRARY,
            -2,
        )

        assertEquals(
            listOf(
                MainNavigationTab.SONGS,
                MainNavigationTab.LIBRARY,
                MainNavigationTab.ALBUMS,
                MainNavigationTab.ARTISTS,
                MainNavigationTab.SEARCH,
            ),
            reordered,
        )
        assertEquals(
            MainNavigationTab.ALBUMS,
            MainNavigationTabs.fallback(
                MainNavigationTab.LIBRARY.index,
                setOf(MainNavigationTab.ALBUMS, MainNavigationTab.SEARCH),
                reordered,
            ),
        )
    }

    @Test
    fun `player control ordering moves controls without losing entries`() {
        val reordered = PlayerControlOrdering.move(PlayerControl.entries, PlayerControl.SEARCH, -5)
        assertEquals(PlayerControl.SEARCH, reordered.first())
        assertEquals(PlayerControl.entries.size, reordered.distinct().size)
        assertEquals(PlayerControl.entries.toSet(), reordered.toSet())
    }
}
