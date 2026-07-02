package com.powerpoppalace.subwaveauto.playback

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.powerpoppalace.subwaveauto.net.StationApi
import com.powerpoppalace.subwaveauto.prefs.StationPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The playback service (ANDROID_AUTO_PLAN.md §2 WP2 — "the heart").
 *
 * One ExoPlayer wrapped in a live-edge [ForwardingPlayer], one [MediaLibrarySession]
 * serving ALL controllers: Android Auto, Bluetooth/AVRCP, the media notification
 * (provided automatically by MediaSessionService — never hand-rolled), and the phone
 * UI's MediaController (WP4).
 *
 * Product invariant: this is a shared live broadcast. Play/Pause/Stop only — the
 * seven COMMAND_SEEK_* are stripped, and "pause → play" after a stale gap reloads at
 * the live edge with a fresh cache-buster instead of resuming a stale buffer
 * (mirrors app/service.ts RemotePlay and app/src/audio/player.ts:77).
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    /**
     * Station client, rebuilt on base-URL change.
     * WP3: constructor arg for `BrowseTree(stationApi)` and `LiveMetadata(player, stationApi, serviceScope)`.
     */
    private lateinit var stationApi: StationApi

    /**
     * Service-owned scope (main thread — media3's threading contract), cancelled in [onDestroy].
     * WP3: pass to `LiveMetadata(player, stationApi, serviceScope)`.
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * The live-edge ForwardingPlayer — the only player handle anything outside this
     * service (session, controllers, WP3's LiveMetadata) ever sees.
     */
    private lateinit var player: LiveEdgePlayer

    private lateinit var session: MediaLibrarySession

    /** WP3: the AA browse tree — the session's MediaLibrarySession.Callback. */
    private lateinit var browseTree: BrowseTree

    /** WP3: now-playing → MediaMetadata sync; started in [onCreate], stopped in [onDestroy]. */
    private lateinit var liveMetadata: LiveMetadata

    /** Pending error auto-retry (WP2 step 5). */
    private var retryJob: Job? = null

    /** True once [onPlayerError] has spent its single auto-retry; reset when playback reaches READY. */
    private var retriedAfterError = false

    /** Guards the process-lifetime StationPrefs listener (never unregistered) against a dead service instance. */
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()

        stationApi = StationApi(StationPrefs.baseUrl(this))

        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        player = LiveEdgePlayer(exoPlayer)
        player.addListener(retryListener)

        // Set (but do not prepare) the live item so an empty-queue play() from the
        // phone UI just works — the player is in STATE_IDLE, so the live-edge
        // intercept prepares a fresh cache-busted item on the first play().
        player.setMediaItem(freshLiveItem())

        browseTree = BrowseTree(stationApi)
        session = MediaLibrarySession.Builder(this, player, browseTree).build()

        liveMetadata = LiveMetadata(player, stationApi, serviceScope)
        liveMetadata.start()

        // Base-URL change (§1 construction contract): stop playback, rebuild the API
        // client, swap in a fresh live item. Left unprepared — the next play() goes
        // through the live-edge reload path. WP3's components take the rebuilt client
        // via their mutable `api` (the session callback can't be swapped post-build).
        StationPrefs.registerListener(this) {
            if (destroyed) return@registerListener
            player.stop()
            stationApi = StationApi(StationPrefs.baseUrl(this))
            browseTree.api = stationApi
            liveMetadata.api = stationApi
            player.setMediaItem(freshLiveItem())
        }
    }

    /** One session for ALL controllers — AA, Bluetooth, notification, phone UI. */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        destroyed = true
        liveMetadata.stop()
        retryJob?.cancel()
        serviceScope.cancel()
        session.release()
        player.release()
        super.onDestroy()
    }

    /**
     * The live MediaItem (§1 invariants — fresh cache-busted URI on every call).
     * Delegates to [liveMediaItem] in BrowseTree.kt, the single builder shared with
     * the browse/onAddMediaItems callbacks.
     */
    private fun freshLiveItem(): MediaItem = liveMediaItem(stationApi)

    /**
     * Error resilience (WP2 step 5): on the first error, one automatic retry after
     * 3 s with a fresh cache-busted item. A second consecutive failure (no READY in
     * between) is NOT retried — the error state surfaces to controllers (AA shows it).
     */
    private val retryListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            if (retriedAfterError) return // second consecutive failure — surface the error state
            retriedAfterError = true
            retryJob?.cancel()
            retryJob = serviceScope.launch {
                delay(RETRY_DELAY_MS)
                player.reloadLiveItem()
                player.play()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) retriedAfterError = false
        }
    }

    /**
     * Live-edge ForwardingPlayer (WP2 steps 3 + 4).
     *
     * Intercepts play() (and setPlayWhenReady(true) — some controllers route resume
     * through it) when the paused buffer is stale: **paused > 30 s, or
     * STATE_IDLE/STATE_ENDED** → swap in a fresh cache-busted live item + prepare()
     * instead of resuming. 30 s, not lower, so a short phone call or a transient
     * audio-focus duck never triggers a needless reload-and-rebuffer. Everything here
     * is state work on the app thread (media3's threading contract) — NO network
     * calls; streamUrl() is pure string building, ExoPlayer connects after prepare().
     *
     * Also strips the seven COMMAND_SEEK_* so AA/BT/notification render play/pause only.
     */
    private inner class LiveEdgePlayer(wrapped: Player) : ForwardingPlayer(wrapped) {

        /** Wall-clock ms of the last transition to not-playing; 0 while playing. */
        private var pausedAtMs = 0L

        init {
            wrapped.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    pausedAtMs = if (isPlaying) 0L else System.currentTimeMillis()
                }
            })
        }

        override fun play() {
            if (isStaleForResume()) reloadLiveItem()
            super.play()
        }

        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (playWhenReady && isStaleForResume()) reloadLiveItem()
            super.setPlayWhenReady(playWhenReady)
        }

        /** Swap in a fresh cache-busted live item and prepare it (state swap only — no I/O here). */
        fun reloadLiveItem() {
            setMediaItem(freshLiveItem())
            prepare()
            pausedAtMs = 0L
        }

        private fun isStaleForResume(): Boolean {
            if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) return true
            if (isPlaying) return false
            val pausedAt = pausedAtMs
            return pausedAt != 0L && System.currentTimeMillis() - pausedAt > STALE_PAUSE_MS
        }

        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands()
                .buildUpon()
                .removeAll(
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_SEEK_BACK,
                    Player.COMMAND_SEEK_FORWARD,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                )
                .build()

        override fun isCommandAvailable(command: Int): Boolean =
            getAvailableCommands().contains(command)
    }

    private companion object {
        /**
         * Pause-staleness threshold (WP2 step 3): 30 s, deliberately not lower — a
         * brief pause (short call, transient focus duck) must NOT reload-and-rebuffer.
         */
        const val STALE_PAUSE_MS = 30_000L

        /** Delay before the single automatic error retry (WP2 step 5). */
        const val RETRY_DELAY_MS = 3_000L
    }
}
