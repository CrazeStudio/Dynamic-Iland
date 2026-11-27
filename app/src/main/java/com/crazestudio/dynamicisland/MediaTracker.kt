package com.crazestudio.dynamicisland

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

/** Follows the most relevant active media session (needs notification access). */
class MediaTracker(private val ctx: Context, private val onChange: (MediaInfo?) -> Unit) {

    private val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val comp = ComponentName(ctx, IslandNotificationListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var ctl: MediaController? = null
    private var started = false

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = emit()
        override fun onMetadataChanged(metadata: MediaMetadata?) = emit()
        override fun onSessionDestroyed() = pick(sessions())
    }

    fun start() {
        if (started) return
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, comp, handler)
            started = true
            pick(sessions())
        } catch (e: SecurityException) {
            started = false // notification access not granted yet
        }
    }

    fun stop() {
        if (started) {
            try {
                msm.removeOnActiveSessionsChangedListener(sessionsListener)
            } catch (_: Exception) {
            }
            started = false
        }
        ctl?.unregisterCallback(callback)
        ctl = null
    }

    private fun sessions(): List<MediaController> =
        try {
            msm.getActiveSessions(comp)
        } catch (e: SecurityException) {
            emptyList()
        }

    private fun pick(list: List<MediaController>?) {
        val best = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()
        if (best?.sessionToken != ctl?.sessionToken) {
            ctl?.unregisterCallback(callback)
            ctl = best
            best?.registerCallback(callback, handler)
        }
        emit()
    }

    private fun emit() {
        val c = ctl ?: return onChange(null)
        val md = c.metadata ?: return onChange(null)
        val st = c.playbackState ?: return onChange(null)
        if (st.state == PlaybackState.STATE_NONE || st.state == PlaybackState.STATE_STOPPED) return onChange(null)

        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (title.isNullOrBlank()) return onChange(null)

        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val playing = st.state == PlaybackState.STATE_PLAYING || st.state == PlaybackState.STATE_BUFFERING

        onChange(
            MediaInfo(
                pkg = c.packageName,
                title = title,
                artist = artist,
                art = art,
                playing = playing,
                duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
                basePos = st.position,
                baseTime = st.lastPositionUpdateTime,
                speed = if (st.playbackSpeed == 0f) 1f else st.playbackSpeed,
                controller = c
            )
        )
    }
}
