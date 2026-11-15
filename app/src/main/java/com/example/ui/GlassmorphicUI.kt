package com.example.ui

import android.Manifest
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.example.R
import com.example.data.TrackEntity
import com.example.hardware.HardwareRouteType
import com.example.ui.theme.SonaAmber
import com.example.ui.theme.SonaCyan
import com.example.ui.theme.SonaCyanGlow
import com.example.ui.theme.SonaEmerald
import com.example.ui.theme.SonaObsidianBg
import com.example.ui.theme.SonaTextPrimary
import com.example.ui.theme.SonaTextSecondary
import kotlin.math.PI
import kotlin.math.sin

enum class SonaDestination(
    val label: String,
    val icon: ImageVector,
    val testTag: String
) {
    PLAYER("Player", Icons.Filled.GraphicEq, "nav_player"),
    LIBRARY("Library", Icons.Filled.LibraryMusic, "nav_library"),
    DSP_DAC("DSP & DAC", Icons.Filled.Equalizer, "nav_dsp"),
    IR_REMOTE("Bezton IR", Icons.Filled.SettingsRemote, "nav_ir")
}

@Composable
fun SonaGlassShell(viewModel: SonaViewModel) {
    var selectedTab by rememberSaveable { mutableStateOf(SonaDestination.PLAYER) }
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val activeRoute by viewModel.activeAudioRoute.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val usbDacState by viewModel.usbDacState.collectAsStateWithLifecycle()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(SonaObsidianBg)
    ) {
        val useRail = maxWidth >= 700.dp

        AmbientGlassBackdrop(isPlaying = playbackState.isPlaying)

        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                if (!useRail) {
                    NavigationBar(
                        containerColor = Color(0xCC0A0E1A),
                        tonalElevation = 0.dp,
                        modifier = Modifier.navigationBarsPadding()
                    ) {
                        SonaDestination.entries.forEach { dest ->
                            NavigationBarItem(
                                selected = selectedTab == dest,
                                onClick = { selectedTab = dest },
                                icon = { Icon(dest.icon, contentDescription = dest.label) },
                                label = { Text(dest.label, maxLines = 1) },
                                modifier = Modifier.testTag(dest.testTag),
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = SonaCyan,
                                    selectedTextColor = SonaCyan,
                                    indicatorColor = SonaCyan.copy(alpha = 0.16f),
                                    unselectedIconColor = SonaTextSecondary,
                                    unselectedTextColor = SonaTextSecondary
                                )
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(
                        containerColor = Color(0xCC0A0E1A),
                        modifier = Modifier
                            .fillMaxHeight()
                            .statusBarsPadding()
                    ) {
                        Spacer(Modifier.height(16.dp))
                        SonaDestination.entries.forEach { dest ->
                            NavigationRailItem(
                                selected = selectedTab == dest,
                                onClick = { selectedTab = dest },
                                icon = { Icon(dest.icon, contentDescription = dest.label) },
                                label = { Text(dest.label) },
                                modifier = Modifier.testTag(dest.testTag),
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = SonaCyan,
                                    selectedTextColor = SonaCyan,
                                    indicatorColor = SonaCyan.copy(alpha = 0.16f),
                                    unselectedIconColor = SonaTextSecondary,
                                    unselectedTextColor = SonaTextSecondary
                                )
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AnimatedVisibility(visible = playbackState.autoSwitchNotice != null) {
                        playbackState.autoSwitchNotice?.let { notice ->
                            FrostedGlassPanel(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = 680.dp)
                                    .padding(top = 8.dp, bottom = 4.dp)
                                    .clickable { viewModel.dismissAutoSwitchNotice() },
                                cornerRadius = 16.dp,
                                accentColor = SonaEmerald
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Filled.SettingsInputComponent,
                                        contentDescription = "Auto profile switched",
                                        tint = SonaEmerald,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = notice,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = SonaTextPrimary,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(max = 720.dp)
                            .fillMaxWidth()
                    ) {
                        when (selectedTab) {
                            SonaDestination.PLAYER -> NowPlayingGlassScreen(
                                viewModel = viewModel,
                                onOpenDsp = { selectedTab = SonaDestination.DSP_DAC }
                            )
                            SonaDestination.LIBRARY -> LibraryGlassScreen(viewModel = viewModel)
                            SonaDestination.DSP_DAC -> DspDacGlassScreen(
                                viewModel = viewModel,
                                activeRoute = activeRoute,
                                activeProfile = activeProfile,
                                usbDacState = usbDacState
                            )
                            SonaDestination.IR_REMOTE -> BeztonRemoteGlassScreen(viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Multi-layered vibrant gradient backdrop blurred via Android 12+ RenderEffect
 * so translucent glass panels render genuine frosted optical depth at 120Hz.
 */
@Composable
fun AmbientGlassBackdrop(isPlaying: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "ambient_backdrop")
    val drift by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb_drift"
    )

    val glowScale by animateFloatAsState(
        targetValue = if (isPlaying) 1.15f else 0.92f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "glow_scale"
    )

    val blurModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Modifier.graphicsLayer {
            renderEffect = RenderEffect.createBlurEffect(
                90f,
                90f,
                Shader.TileMode.CLAMP
            ).asComposeRenderEffect()
        }
    } else {
        Modifier
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .then(blurModifier)
    ) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF060810), Color(0xFF0B1222), Color(0xFF060810))
            )
        )
        drawCircle(
            color = SonaCyan.copy(alpha = 0.28f),
            radius = size.minDimension * 0.45f * glowScale,
            center = Offset(
                x = size.width * (0.22f + 0.16f * drift),
                y = size.height * (0.20f + 0.08f * drift)
            )
        )
        drawCircle(
            color = SonaAmber.copy(alpha = 0.20f),
            radius = size.minDimension * 0.40f * glowScale,
            center = Offset(
                x = size.width * (0.82f - 0.14f * drift),
                y = size.height * (0.48f - 0.10f * drift)
            )
        )
        drawCircle(
            color = Color(0xFF6366F1).copy(alpha = 0.22f),
            radius = size.minDimension * 0.48f,
            center = Offset(
                x = size.width * 0.5f,
                y = size.height * (0.85f - 0.06f * drift)
            )
        )
    }
}

@Composable
fun FrostedGlassPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    accentColor: Color = SonaCyan,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    Surface(
        modifier = modifier,
        shape = shape,
        color = Color(0x16FFFFFF),
        border = BorderStroke(
            width = 1.dp,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.28f),
                    accentColor.copy(alpha = 0.18f),
                    Color.White.copy(alpha = 0.08f)
                )
            )
        ),
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = Modifier.background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.09f),
                        Color(0xFF0A0F1D).copy(alpha = 0.52f)
                    )
                )
            ),
            content = content
        )
    }
}

@Composable
fun NowPlayingGlassScreen(
    viewModel: SonaViewModel,
    onOpenDsp: () -> Unit
) {
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val profile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val route by viewModel.activeAudioRoute.collectAsStateWithLifecycle()
    val usbState by viewModel.usbDacState.collectAsStateWithLifecycle()
    val track = playback.currentTrack

    val artRes = when (track?.artworkResName) {
        "img_album_aurora_vinyl" -> R.drawable.img_album_aurora_vinyl_1794732322175
        else -> R.drawable.img_album_obsidian_waves_1794732322329
    }

    val routeIcon = when (route.routeType) {
        HardwareRouteType.USB_DAC -> Icons.Filled.Usb
        HardwareRouteType.BLUETOOTH, HardwareRouteType.WIRED_AUX -> Icons.Filled.Headphones
        HardwareRouteType.SPEAKER -> Icons.Filled.Speaker
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header + Active Hardware Path Pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "SONA MUSIC",
                    style = MaterialTheme.typography.labelMedium,
                    color = SonaCyan
                )
                Text(
                    text = "Master Signal Chain",
                    style = MaterialTheme.typography.headlineSmall,
                    color = SonaTextPrimary
                )
            }
            FrostedGlassPanel(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onOpenDsp)
                    .minimumInteractiveComponentSize(),
                cornerRadius = 50.dp,
                accentColor = if (usbState.isBitPerfectBypassActive) SonaEmerald else SonaCyan
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        routeIcon,
                        contentDescription = "Active output route",
                        tint = if (usbState.isBitPerfectBypassActive) SonaEmerald else SonaCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (usbState.isBitPerfectBypassActive) "USB DIRECT" else route.routeType.badge,
                        style = MaterialTheme.typography.labelMedium,
                        color = SonaTextPrimary
                    )
                }
            }
        }

        // Main Frosted Glass Player Hero Card
        FrostedGlassPanel(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 28.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.76f)
                        .widthIn(max = 290.dp)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(22.dp))
                ) {
                    Image(
                        painter = painterResource(id = artRes),
                        contentDescription = track?.title ?: "Album artwork",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    // Signal telemetry overlay badge
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(10.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xB3060810),
                        border = BorderStroke(1.dp, SonaCyan.copy(alpha = 0.45f))
                    ) {
                        val formatStr = if (track != null) {
                            val khz = "%.1f".format(track.sampleRateHz / 1000f)
                            "${track.codec} • ${track.bitDepth}-BIT / $khz kHz"
                        } else {
                            "PCM • 24-BIT / 48.0 kHz"
                        }
                        Text(
                            text = formatStr,
                            style = MaterialTheme.typography.labelMedium,
                            color = SonaCyanGlow,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = track?.title ?: "Select a Track",
                    style = MaterialTheme.typography.headlineSmall,
                    color = SonaTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${track?.artist ?: "Sona Reference"} — ${track?.album ?: "Lossless Master"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SonaTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(14.dp))

                // 120Hz Real-time Harmonic Spectrum Canvas
                HarmonicSpectrumCanvas(
                    isPlaying = playback.isPlaying,
                    dsdMode = profile.dsdOversamplingFactor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                )

                Spacer(Modifier.height(10.dp))

                // Seekbar
                val duration = playback.durationMs.coerceAtLeast(1L)
                val progress = (playback.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                Slider(
                    value = progress,
                    onValueChange = { fraction -> viewModel.seekTo((fraction * duration).toLong()) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("player_seek_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = SonaCyan,
                        activeTrackColor = SonaCyan,
                        inactiveTrackColor = Color.White.copy(alpha = 0.16f)
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatMs(playback.positionMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = SonaTextSecondary
                    )
                    val dsdBadge = when {
                        profile.bitPerfectEnabled -> "BIT-PERFECT BYPASS"
                        profile.dsdOversamplingFactor > 0 -> "SoX -> DSD${profile.dsdOversamplingFactor}"
                        else -> "GAPLESS • ${profile.playbackSpeed}x"
                    }
                    Text(
                        text = dsdBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = SonaAmber
                    )
                    Text(
                        text = formatMs(duration),
                        style = MaterialTheme.typography.labelMedium,
                        color = SonaTextSecondary
                    )
                }

                Spacer(Modifier.height(14.dp))

                // Transport Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.toggleShuffle() },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("shuffle_button")
                    ) {
                        Icon(
                            Icons.Filled.Shuffle,
                            contentDescription = "Toggle shuffle",
                            tint = if (playback.isShuffleEnabled) SonaCyan else SonaTextSecondary
                        )
                    }

                    IconButton(
                        onClick = { viewModel.skipPrevious() },
                        modifier = Modifier
                            .size(52.dp)
                            .testTag("prev_button")
                    ) {
                        Icon(
                            Icons.Filled.SkipPrevious,
                            contentDescription = "Previous track",
                            tint = SonaTextPrimary,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Surface(
                        onClick = { viewModel.togglePlayPause() },
                        shape = CircleShape,
                        color = SonaCyan,
                        modifier = Modifier
                            .size(66.dp)
                            .testTag("play_pause_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (playback.isPlaying) "Pause" else "Play",
                                tint = SonaObsidianBg,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = { viewModel.skipNext() },
                        modifier = Modifier
                            .size(52.dp)
                            .testTag("next_button")
                    ) {
                        Icon(
                            Icons.Filled.SkipNext,
                            contentDescription = "Next track",
                            tint = SonaTextPrimary,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    IconButton(
                        onClick = { viewModel.cycleRepeatMode() },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("repeat_button")
                    ) {
                        Icon(
                            imageVector = if (playback.repeatMode == Player.REPEAT_MODE_ONE) {
                                Icons.Filled.RepeatOne
                            } else {
                                Icons.Filled.Repeat
                            },
                            contentDescription = "Cycle repeat mode",
                            tint = if (playback.repeatMode != Player.REPEAT_MODE_OFF) SonaCyan else SonaTextSecondary
                        )
                    }
                }
            }
        }

        // Hardware Volume + DVC + Engine Telemetry Card
        FrostedGlassPanel(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 22.dp,
            accentColor = SonaAmber
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.VolumeUp,
                            contentDescription = "Hardware volume",
                            tint = SonaAmber,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (profile.dvcEnabled) "Direct Volume Control (DVC)" else "Hardware Volume",
                            style = MaterialTheme.typography.titleMedium,
                            color = SonaTextPrimary
                        )
                    }
                    Text(
                        text = "${route.hardwareVolumePercent}% • ${"%.1f".format(profile.dvcAttenuationDb)} dB",
                        style = MaterialTheme.typography.labelMedium,
                        color = SonaAmber
                    )
                }

                Slider(
                    value = route.hardwareVolumePercent / 100f,
                    onValueChange = { viewModel.setHardwareVolumePercent((it * 100).toInt()) },
                    colors = SliderDefaults.colors(
                        thumbColor = SonaAmber,
                        activeTrackColor = SonaAmber,
                        inactiveTrackColor = Color.White.copy(alpha = 0.14f)
                    )
                )

                Text(
                    text = "Engine: ${playback.engineCapabilities}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SonaTextSecondary
                )
            }
        }
    }
}

@Composable
fun HarmonicSpectrumCanvas(
    isPlaying: Boolean,
    dsdMode: Int,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "spectrum")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )
    val activity by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.18f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "activity"
    )

    Canvas(modifier = modifier) {
        val barCount = 36
        val spacing = 4.dp.toPx()
        val barWidth = ((size.width - spacing * (barCount - 1)) / barCount).coerceAtLeast(2f)
        val primaryColor = if (dsdMode > 0) SonaAmber else SonaCyan

        for (i in 0 until barCount) {
            val norm = i / barCount.toFloat()
            val wave = 0.5f + 0.5f * sin(phase * (1.2f + norm * 2.4f) + i * 0.45f)
            val heightFrac = (0.14f + 0.86f * wave * activity).coerceIn(0.08f, 1f)
            val barH = size.height * heightFrac
            val x = i * (barWidth + spacing)
            val y = (size.height - barH) / 2f

            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(primaryColor, SonaEmerald.copy(alpha = 0.7f))
                ),
                topLeft = Offset(x, y),
                size = Size(barWidth, barH),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

@Composable
fun LibraryGlassScreen(viewModel: SonaViewModel) {
    val tracks by viewModel.filteredTracks.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val filter by viewModel.libraryFilter.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()

    val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.scanLocalLibrary()
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        viewModel.importAudioFiles(uris)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("TAGLIB + ROOM CACHE", style = MaterialTheme.typography.labelMedium, color = SonaCyan)
                Text("${tracks.size} Master Tracks", style = MaterialTheme.typography.headlineSmall, color = SonaTextPrimary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { filePickerLauncher.launch(arrayOf("audio/*", "*/*")) },
                    border = BorderStroke(1.dp, SonaCyan.copy(alpha = 0.5f)),
                    modifier = Modifier.testTag("import_audio_button")
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = "Import audio", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Import")
                }
                Button(
                    onClick = { permissionLauncher.launch(audioPermission) },
                    colors = ButtonDefaults.buttonColors(containerColor = SonaCyan, contentColor = SonaObsidianBg),
                    modifier = Modifier.testTag("scan_library_button")
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Scan storage", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Scan")
                }
            }
        }

        if (isScanning) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = SonaCyan,
                trackColor = Color.White.copy(alpha = 0.1f)
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.updateSearchQuery(it) },
            placeholder = { Text("Search tracks, artists, FLAC, DSD...", color = SonaTextSecondary) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Search", tint = SonaCyan) },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = SonaCyan,
                unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
                focusedTextColor = SonaTextPrimary,
                unfocusedTextColor = SonaTextPrimary,
                focusedContainerColor = Color(0x18FFFFFF),
                unfocusedContainerColor = Color(0x12FFFFFF)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("library_search_input")
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LibraryFilter.entries.forEach { item ->
                val label = when (item) {
                    LibraryFilter.ALL -> "All Formats"
                    LibraryFilter.HI_RES -> "Hi-Res (24-bit+)"
                    LibraryFilter.DSD -> "DSD / SACD"
                }
                FilterChip(
                    selected = filter == item,
                    onClick = { viewModel.updateLibraryFilter(item) },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SonaCyan.copy(alpha = 0.22f),
                        selectedLabelColor = SonaCyan
                    )
                )
            }
        }

        if (tracks.isEmpty()) {
            FrostedGlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Filled.Album,
                        contentDescription = "No tracks",
                        tint = SonaCyan,
                        modifier = Modifier.size(44.dp)
                    )
                    Text("No matching tracks", style = MaterialTheme.typography.titleMedium, color = SonaTextPrimary)
                    Text(
                        "Tap Scan to index local audio via TagLib/MediaStore or Import FLAC/DSF files.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SonaTextSecondary
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(tracks, key = { it.id }) { track ->
                    val isCurrent = playback.currentTrack?.id == track.id
                    TrackGlassRow(
                        track = track,
                        isCurrent = isCurrent,
                        onClick = { viewModel.playTrack(track) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackGlassRow(
    track: TrackEntity,
    isCurrent: Boolean,
    onClick: () -> Unit
) {
    FrostedGlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag("track_row_${track.id}"),
        cornerRadius = 18.dp,
        accentColor = if (isCurrent) SonaCyan else Color.White
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isCurrent) SonaCyan.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.08f),
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isCurrent) Icons.Filled.GraphicEq else Icons.Filled.Album,
                        contentDescription = track.title,
                        tint = if (isCurrent) SonaCyan else SonaTextSecondary
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isCurrent) SonaCyan else SonaTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${track.artist} • ${track.album}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SonaTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                val badgeColor = if (track.isHiRes) SonaAmber else SonaTextSecondary
                Text(
                    text = "${track.codec} ${track.bitDepth}B",
                    style = MaterialTheme.typography.labelMedium,
                    color = badgeColor,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${track.sampleRateHz / 1000} kHz • ${formatMs(track.durationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SonaTextSecondary
                )
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return "%d:%02d".format(min, sec)
}
