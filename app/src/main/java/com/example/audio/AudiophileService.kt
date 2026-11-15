package com.example.audio

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.example.MainActivity
import com.example.data.HardwareProfileEntity
import com.example.data.SonaDatabase
import com.example.data.TrackEntity
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.pow

/**
 * Media3 MediaLibraryService powering Sona Music's background playback, Android Auto browsing,
 * lock-screen controls, gapless queuing, crossfade tapering, pitch/speed hooks, and live DSP.
 */
@OptIn(UnstableApi::class)
class AudiophileService : MediaLibraryService() {

    companion object {
        const val ROOT_ID = "sona_root"
        const val NODE_ALL_TRACKS = "sona_all_tracks"
        const val NODE_HI_RES = "sona_hi_res"

        const val CMD_APPLY_DSP_PROFILE = "com.example.sona.CMD_APPLY_DSP_PROFILE"
        const val CMD_SET_PITCH_SPEED = "com.example.sona.CMD_SET_PITCH_SPEED"
        const val CMD_SET_CROSSFADE = "com.example.sona.CMD_SET_CROSSFADE"

        const val ARG_DEVICE_KEY = "device_key"
        const val ARG_PREAMP_DB = "preamp_db"
        const val ARG_EQ_CSV = "eq_csv"
        const val ARG_CROSSFEED = "crossfeed"
        const val ARG_DVC_ENABLED = "dvc_enabled"
        const val ARG_DVC_DB = "dvc_db"
        const val ARG_DSD_MODE = "dsd_mode"
        const val ARG_BIT_PERFECT = "bit_perfect"
        const val ARG_SPEED = "speed"
        const val ARG_PITCH = "pitch"
        const val ARG_CROSSFADE_MS = "crossfade_ms"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var exoPlayer: ExoPlayer
    private var mediaLibrarySession: MediaLibrarySession? = null
    private val dspController = DspController()

    private var crossfadeMs: Int = 0
    private var bitPerfectMode: Boolean = false
    private var dvcGainScalar: Float = 1.0f
    private var crossfadeMonitorJob: Job? = null

    private val librarySessionCallback = object : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(CMD_APPLY_DSP_PROFILE, Bundle.EMPTY))
                .add(SessionCommand(CMD_SET_PITCH_SPEED, Bundle.EMPTY))
                .add(SessionCommand(CMD_SET_CROSSFADE, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_APPLY_DSP_PROFILE -> {
                    val profile = HardwareProfileEntity(
                        deviceKey = args.getString(ARG_DEVICE_KEY, "SPEAKER"),
                        displayName = args.getString(ARG_DEVICE_KEY, "SPEAKER"),
                        preampDb = args.getFloat(ARG_PREAMP_DB, 0f),
                        eqBandsCsv = args.getString(ARG_EQ_CSV, "0,0,0,0,0,0,0,0,0,0"),
                        crossfeedLevel = args.getFloat(ARG_CROSSFEED, 0f),
                        dvcEnabled = args.getBoolean(ARG_DVC_ENABLED, true),
                        dvcAttenuationDb = args.getFloat(ARG_DVC_DB, -3f),
                        crossfadeMs = args.getInt(ARG_CROSSFADE_MS, 0),
                        dsdOversamplingFactor = args.getInt(ARG_DSD_MODE, 0),
                        bitPerfectEnabled = args.getBoolean(ARG_BIT_PERFECT, false),
                        playbackSpeed = args.getFloat(ARG_SPEED, 1f),
                        playbackPitch = args.getFloat(ARG_PITCH, 1f)
                    )
                    applyProfileToPlayback(profile)
                }
                CMD_SET_PITCH_SPEED -> {
                    val speed = args.getFloat(ARG_SPEED, 1.0f).coerceIn(0.5f, 2.0f)
                    val pitch = args.getFloat(ARG_PITCH, 1.0f).coerceIn(0.5f, 2.0f)
                    exoPlayer.playbackParameters = PlaybackParameters(speed, pitch)
                }
                CMD_SET_CROSSFADE -> {
                    crossfadeMs = args.getInt(ARG_CROSSFADE_MS, 0).coerceIn(0, 12000)
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootItem = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Sona Music")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch(Dispatchers.IO) {
                val tracks = SonaDatabase.getInstance(this@AudiophileService)
                    .trackDao()
                    .getAllTracks()
                    .first()

                val items = when (parentId) {
                    ROOT_ID -> listOf(
                        browsableFolderItem(NODE_ALL_TRACKS, "All Master Tracks"),
                        browsableFolderItem(NODE_HI_RES, "Hi-Res & Lossless")
                    )
                    NODE_HI_RES -> tracks.filter { it.isHiRes }.map { it.toMediaItem() }
                    else -> tracks.map { it.toMediaItem() }
                }

                val safeStart = (page * pageSize).coerceAtMost(items.size)
                val safeEnd = ((page + 1) * pageSize).coerceAtMost(items.size)
                val paged = if (pageSize > 0) items.subList(safeStart, safeEnd) else items
                future.set(LibraryResult.ofItemList(ImmutableList.copyOf(paged), params))
            }
            return future
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val updated = mediaItems.map { item ->
                if (item.localConfiguration != null) {
                    item
                } else {
                    item.buildUpon().setUri(Uri.parse(item.mediaId)).build()
                }
            }.toMutableList()
            return Futures.immediateFuture(updated)
        }
    }

    override fun onCreate() {
        super.onCreate()

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(true)
                    .setEnableAudioTrackPlaybackParams(true)
                    .build()
            }
        }.forceEnableMediaCodecAsynchronousQueueing()

        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, extractorsFactory))
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                dspController.attachAudioSession(audioSessionId)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) startCrossfadeMonitor() else crossfadeMonitorJob?.cancel()
            }
        })

        if (exoPlayer.audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            dspController.attachAudioSession(exoPlayer.audioSessionId)
        }

        val sessionActivityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaLibrarySession = MediaLibrarySession.Builder(this, exoPlayer, librarySessionCallback)
            .setSessionActivity(sessionActivityIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    private fun applyProfileToPlayback(profile: HardwareProfileEntity) {
        bitPerfectMode = profile.bitPerfectEnabled
        crossfadeMs = if (profile.bitPerfectEnabled) 0 else profile.crossfadeMs
        dvcGainScalar = if (profile.dvcEnabled) {
            10.0f.pow(profile.dvcAttenuationDb.coerceIn(-60f, 0f) / 20.0f)
        } else {
            1.0f
        }
        exoPlayer.volume = dvcGainScalar.coerceIn(0.02f, 1.0f)
        exoPlayer.playbackParameters = PlaybackParameters(
            profile.playbackSpeed.coerceIn(0.5f, 2.0f),
            profile.playbackPitch.coerceIn(0.5f, 2.0f)
        )
        dspController.applyProfile(profile)
    }

    /**
     * Gapless playback is handled natively by ExoPlayer's consecutive MediaItem queue.
     * When crossfadeMs > 0 and Bit-Perfect bypass is off, smoothly attenuates track tail & fade-in.
     */
    private fun startCrossfadeMonitor() {
        crossfadeMonitorJob?.cancel()
        crossfadeMonitorJob = serviceScope.launch {
            while (isActive) {
                val duration = exoPlayer.duration
                val position = exoPlayer.currentPosition
                if (!bitPerfectMode && crossfadeMs > 0 && duration > 0 && exoPlayer.hasNextMediaItem()) {
                    val remaining = duration - position
                    when {
                        remaining in 1..crossfadeMs.toLong() -> {
                            val factor = (remaining.toFloat() / crossfadeMs.toFloat()).coerceIn(0.05f, 1.0f)
                            exoPlayer.volume = (dvcGainScalar * factor).coerceIn(0.02f, 1.0f)
                        }
                        position < crossfadeMs -> {
                            val factor = (position.toFloat() / crossfadeMs.toFloat()).coerceIn(0.15f, 1.0f)
                            exoPlayer.volume = (dvcGainScalar * factor).coerceIn(0.02f, 1.0f)
                        }
                        else -> {
                            exoPlayer.volume = dvcGainScalar.coerceIn(0.02f, 1.0f)
                        }
                    }
                } else {
                    exoPlayer.volume = dvcGainScalar.coerceIn(0.02f, 1.0f)
                }
                delay(40L)
            }
        }
    }

    private fun browsableFolderItem(mediaId: String, title: String): MediaItem {
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS)
                    .build()
            )
            .build()
    }

    private fun TrackEntity.toMediaItem(): MediaItem {
        val extras = Bundle().apply {
            putInt("sampleRateHz", sampleRateHz)
            putInt("bitDepth", bitDepth)
            putString("codec", codec)
        }
        return MediaItem.Builder()
            .setMediaId(uri)
            .setUri(Uri.parse(uri))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setGenre(genre)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaLibrarySession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        crossfadeMonitorJob?.cancel()
        serviceScope.cancel()
        dspController.releaseEffects()
        NativeAudioEngine.stopLowLatencyStream()
        mediaLibrarySession?.run {
            player.release()
            release()
            mediaLibrarySession = null
        }
        super.onDestroy()
    }
}
