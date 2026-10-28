package me.zhanghai.android.files.viewer.media

import android.app.Application
import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class PlaybackActionsTest {
    @Test fun adjacentItemRecoversIdlePlaybackAndPreservesThePausedOrPlayingIntent() {
        for (next in listOf(false, true)) {
            for (play in listOf(false, true)) {
                val fake = RecordingPlayer(index = 1, playWhenReady = play)
                PlaybackActions.seekToAdjacentMediaItem(fake.player, next)
                assertEquals(if (next) 2 else 0, fake.index)
                assertEquals(listOf(if (next) "seekToNextMediaItem" else "seekToPreviousMediaItem",
                    "prepare"), fake.calls)
                assertEquals(Player.STATE_BUFFERING, fake.state)
                assertEquals(play, fake.playWhenReady)
            }
        }
    }

    @Test fun aPreparedPlaylistDoesNotRestartWhenChangingItems() {
        for (state in listOf(Player.STATE_BUFFERING, Player.STATE_READY, Player.STATE_ENDED)) {
            val fake = RecordingPlayer(index = 1).apply { this.state = state }
            PlaybackActions.seekToAdjacentMediaItem(fake.player, next = true)
            assertEquals(listOf("seekToNextMediaItem"), fake.calls)
            assertFalse(fake.playWhenReady)
        }
    }

    @Test fun unavailableSeekDoesNotMoveOrPrepareThePlaylist() {
        for (next in listOf(false, true)) {
            val fake = RecordingPlayer(index = 1).apply {
                commands.remove(if (next) Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
                    else Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            }
            PlaybackActions.seekToAdjacentMediaItem(fake.player, next)
            assertEquals(1, fake.index)
            assertTrue(fake.calls.isEmpty())
        }
    }

    @Test fun unavailablePrepareDoesNotDispatchAForbiddenCommand() {
        val fake = RecordingPlayer(index = 1).apply { commands.remove(Player.COMMAND_PREPARE) }
        PlaybackActions.seekToAdjacentMediaItem(fake.player, next = true)
        assertEquals(listOf("seekToNextMediaItem"), fake.calls)
        assertEquals(Player.STATE_IDLE, fake.state)
    }
}
