package com.example.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.concurrent.futures.await
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.example.audio.AudiophileService
import com.example.audio.NativeAudioEngine
import com.example.data.HardwareProfileEntity
import com.example.data.LibraryRepository
import com.example.data.SonaDatabase
import com.example.data.TrackEntity
import com.example.hardware.ActiveAudioRoute
import com.example.hardware.BeztonCommand
import com.example.hardware.BeztonIrRemote
import com.example.hardware.HardwareAudioRouter
import com.example.hardware.HardwareRouteType
import com.example.hardware.IrTransmissionStatus
import com.example.hardware.UsbDacManager
import com.example.hardware.UsbDacState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class LibraryFilter { ALL, HI_RES, DSD }

data class PlaybackUiState(
    val isPlaying: Boolean = false,
    val currentTrack: TrackEntity? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 4000L,
    val queueSize: Int = 0,
    val isShuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_ALL,
    val engineCapabilities: String = NativeAudioEngine.getEngineCapabilities(),
    val autoSwitchNotice: String? = null
)

class SonaViewModel(application: Application) : AndroidViewModel(application) {

    private val db = SonaDatabase.getInstance(application)
    private val repository = LibraryRepository(application, db)

    val usbDacManager = UsbDacManager(application)
    val beztonIrRemote = BeztonIrRemote(application)
    private val hardwareRouter = HardwareAudioRouter(application) { routeType, deviceName ->
        onHardwareRouteAutoSwitched(routeType, deviceName)
    }

    private var mediaController: MediaController? = null

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _libraryFilter = MutableStateFlow(LibraryFilter.ALL)
    val libraryFilter: StateFlow<LibraryFilter> = _libraryFilter.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _playbackState = MutableStateFlow(PlaybackUiState())
    val playbackState: StateFlow<PlaybackUiState> = _playbackState.asStateFlow()

    private val _activeProfile = MutableStateFlow(HardwareProfileEntity.defaultFor("SPEAKER"))
    val activeProfile: StateFlow<HardwareProfileEntity> = _activeProfile.asStateFlow()

    val usbDacState: StateFlow<UsbDacState> = usbDacManager.state
    val activeAudioRoute: StateFlow<ActiveAudioRoute> = hardwareRouter.activeRoute
    val irStatus: StateFlow<IrTransmissionStatus> = beztonIrRemote.status

    val allTracks: StateFlow<List<TrackEntity>> = repository.allTracks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredTracks: StateFlow<List<TrackEntity>> = combine(
        repository.allTracks,
        _searchQuery,
        _libraryFilter
    ) { tracks, query, filter ->
        val q = query.trim().lowercase()
        tracks.filter { track ->
            val matchesQuery = q.isEmpty() ||
                track.title.lowercase().contains(q) ||
                track.artist.lowercase().contains(q) ||
                track.album.lowercase().contains(q) ||
                track.codec.lowercase().contains(q)
            val matchesFilter = when (filter) {
                LibraryFilter.ALL -> true
                LibraryFilter.HI_RES -> track.isHiRes
                LibraryFilter.DSD -> track.isDsd
            }
            matchesQuery && matchesFilter
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedProfiles: StateFlow<List<HardwareProfileEntity>> = repository.allProfiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        usbDacManager.register()
        hardwareRouter.register()

        viewModelScope.launch {
            repository.ensureDefaultsSeeded()
            val initialRoute = hardwareRouter.activeRoute.value.routeType
            loadProfileForRoute(initialRoute)
            connectMediaController()
        }

        viewModelScope.launch {
            while (isActive) {
                val controller = mediaController
                if (controller != null) {
                    _playbackState.update {
                        it.copy(
                            isPlaying = controller.isPlaying,
                            positionMs = controller.currentPosition.coerceAtLeast(0L),
                            durationMs = controller.duration.takeIf { d -> d > 0 } ?: it.currentTrack?.durationMs ?: 4000L,
                            queueSize = controller.mediaItemCount
                        )
                    }
                }
                delay(120L)
            }
        }
    }

    private suspend fun connectMediaController() {
        val ctx = getApplication<Application>()
        val token = SessionToken(ctx, ComponentName(ctx, AudiophileService::class.java))
        runCatching {
            val controller = MediaController.Builder(ctx, token).buildAsync().await()
            mediaController = controller
            controller.repeatMode = Player.REPEAT_MODE_ALL

            controller.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    syncCurrentTrackFromMediaItem(mediaItem)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _playbackState.update { it.copy(isPlaying = isPlaying) }
                }

                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    _playbackState.update { it.copy(isShuffleEnabled = shuffleModeEnabled) }
                }

                override fun onRepeatModeChanged(repeatMode: Int) {
                    _playbackState.update { it.copy(repeatMode = repeatMode) }
                }
            })

            val tracks = allTracks.value
            if (controller.mediaItemCount == 0 && tracks.isNotEmpty()) {
                queueTracks(tracks, startIndex = 0, playImmediately = false)
            } else {
                syncCurrentTrackFromMediaItem(controller.currentMediaItem)
            }
            pushProfileToService(_activeProfile.value)
        }
    }

    private fun syncCurrentTrackFromMediaItem(mediaItem: MediaItem?) {
        val id = mediaItem?.mediaId ?: return
        val match = allTracks.value.firstOrNull { it.uri == id }
        if (match != null) {
            _playbackState.update {
                it.copy(currentTrack = match, durationMs = match.durationMs)
            }
        }
    }

    fun playTrack(track: TrackEntity) {
        val tracks = filteredTracks.value.ifEmpty { allTracks.value }
        val index = tracks.indexOfFirst { it.uri == track.uri }.coerceAtLeast(0)
        queueTracks(tracks, startIndex = index, playImmediately = true)
    }

    private fun queueTracks(tracks: List<TrackEntity>, startIndex: Int, playImmediately: Boolean) {
        val controller = mediaController ?: return
        val items = tracks.map { t ->
            MediaItem.Builder()
                .setMediaId(t.uri)
                .setUri(Uri.parse(t.uri))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(t.title)
                        .setArtist(t.artist)
                        .setAlbumTitle(t.album)
                        .build()
                )
                .build()
        }
        controller.setMediaItems(items, startIndex, 0L)
        controller.prepare()
        if (playImmediately) {
            controller.play()
        }
        _playbackState.update {
            it.copy(
                currentTrack = tracks.getOrNull(startIndex),
                durationMs = tracks.getOrNull(startIndex)?.durationMs ?: 4000L,
                queueSize = items.size
            )
        }
    }

    fun togglePlayPause() {
        val controller = mediaController ?: return
        if (controller.mediaItemCount == 0 && allTracks.value.isNotEmpty()) {
            queueTracks(allTracks.value, 0, true)
            return
        }
        if (controller.isPlaying) controller.pause() else controller.play()
    }

    fun skipNext() {
        mediaController?.seekToNextMediaItem()
    }

    fun skipPrevious() {
        mediaController?.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) {
        mediaController?.seekTo(positionMs)
        _playbackState.update { it.copy(positionMs = positionMs) }
    }

    fun toggleShuffle() {
        val controller = mediaController ?: return
        controller.shuffleModeEnabled = !controller.shuffleModeEnabled
    }

    fun cycleRepeatMode() {
        val controller = mediaController ?: return
        controller.repeatMode = when (controller.repeatMode) {
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
            else -> Player.REPEAT_MODE_ALL
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateLibraryFilter(filter: LibraryFilter) {
        _libraryFilter.value = filter
    }

    fun scanLocalLibrary() {
        viewModelScope.launch {
            _isScanning.value = true
            runCatching { repository.scanDeviceMediaStore() }
            _isScanning.value = false
        }
    }

    fun importAudioFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _isScanning.value = true
            repository.importAudioUris(uris)
            _isScanning.value = false
        }
    }

    private fun onHardwareRouteAutoSwitched(routeType: HardwareRouteType, deviceName: String) {
        viewModelScope.launch {
            loadProfileForRoute(routeType)
            _playbackState.update {
                it.copy(autoSwitchNotice = "Auto-loaded ${routeType.label} DSP profile ($deviceName)")
            }
        }
    }

    fun dismissAutoSwitchNotice() {
        _playbackState.update { it.copy(autoSwitchNotice = null) }
    }

    fun selectProfileManually(deviceKey: String) {
        viewModelScope.launch {
            val profile = repository.getOrCreateProfile(deviceKey)
            _activeProfile.value = profile
            pushProfileToService(profile)
        }
    }

    private suspend fun loadProfileForRoute(routeType: HardwareRouteType) {
        val profile = repository.getOrCreateProfile(routeType.profileKey)
        _activeProfile.value = profile
        pushProfileToService(profile)
    }

    fun updateEqBand(index: Int, gainDb: Float) {
        val current = _activeProfile.value
        val updatedBands = current.eqBandsList().toMutableList()
        if (index in updatedBands.indices) {
            updatedBands[index] = gainDb.coerceIn(-12f, 12f)
        }
        saveAndPushProfile(current.copy(eqBandsCsv = updatedBands.joinToString(",") { "%.1f".format(it) }))
    }

    fun resetEqFlat() {
        val current = _activeProfile.value
        saveAndPushProfile(current.copy(preampDb = 0f, eqBandsCsv = List(10) { "0.0" }.joinToString(",")))
    }

    fun updatePreamp(db: Float) {
        saveAndPushProfile(_activeProfile.value.copy(preampDb = db.coerceIn(-15f, 15f)))
    }

    fun updateCrossfeed(level: Float) {
        saveAndPushProfile(_activeProfile.value.copy(crossfeedLevel = level.coerceIn(0f, 1f)))
    }

    fun toggleDvc(enabled: Boolean) {
        saveAndPushProfile(_activeProfile.value.copy(dvcEnabled = enabled))
    }

    fun updateDvcAttenuation(db: Float) {
        saveAndPushProfile(_activeProfile.value.copy(dvcAttenuationDb = db.coerceIn(-60f, 0f)))
    }

    fun setHardwareVolumePercent(percent: Int) {
        hardwareRouter.setHardwareVolumePercent(percent)
    }

    fun updateCrossfadeMs(ms: Int) {
        saveAndPushProfile(_activeProfile.value.copy(crossfadeMs = ms.coerceIn(0, 12000)))
    }

    fun updateDsdOversampling(factor: Int) {
        saveAndPushProfile(_activeProfile.value.copy(dsdOversamplingFactor = factor))
    }

    fun toggleBitPerfect(enabled: Boolean) {
        saveAndPushProfile(_activeProfile.value.copy(bitPerfectEnabled = enabled))
    }

    fun updateSpeedAndPitch(speed: Float, pitch: Float) {
        saveAndPushProfile(
            _activeProfile.value.copy(
                playbackSpeed = speed.coerceIn(0.5f, 2.0f),
                playbackPitch = pitch.coerceIn(0.5f, 2.0f)
            )
        )
    }

    private fun saveAndPushProfile(updated: HardwareProfileEntity) {
        _activeProfile.value = updated
        pushProfileToService(updated)
        viewModelScope.launch {
            repository.saveProfile(updated)
        }
    }

    private fun pushProfileToService(profile: HardwareProfileEntity) {
        val controller = mediaController ?: return
        val args = Bundle().apply {
            putString(AudiophileService.ARG_DEVICE_KEY, profile.deviceKey)
            putFloat(AudiophileService.ARG_PREAMP_DB, profile.preampDb)
            putString(AudiophileService.ARG_EQ_CSV, profile.eqBandsCsv)
            putFloat(AudiophileService.ARG_CROSSFEED, profile.crossfeedLevel)
            putBoolean(AudiophileService.ARG_DVC_ENABLED, profile.dvcEnabled)
            putFloat(AudiophileService.ARG_DVC_DB, profile.dvcAttenuationDb)
            putInt(AudiophileService.ARG_DSD_MODE, profile.dsdOversamplingFactor)
            putBoolean(AudiophileService.ARG_BIT_PERFECT, profile.bitPerfectEnabled)
            putFloat(AudiophileService.ARG_SPEED, profile.playbackSpeed)
            putFloat(AudiophileService.ARG_PITCH, profile.playbackPitch)
            putInt(AudiophileService.ARG_CROSSFADE_MS, profile.crossfadeMs)
        }
        controller.sendCustomCommand(
            SessionCommand(AudiophileService.CMD_APPLY_DSP_PROFILE, Bundle.EMPTY),
            args
        )
    }

    fun sendBeztonIr(command: BeztonCommand) {
        beztonIrRemote.sendCommand(command)
    }

    fun sendCustomNecHex(hexString: String) {
        val cleaned = hexString.trim().removePrefix("0x").removePrefix("0X")
        val code = cleaned.toLongOrNull(16) ?: return
        beztonIrRemote.transmitNecHex(code, "Custom 0x${cleaned.uppercase()}")
    }

    override fun onCleared() {
        usbDacManager.unregister()
        hardwareRouter.unregister()
        mediaController?.release()
        super.onCleared()
    }
}
