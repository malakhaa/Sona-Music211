package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.HardwareProfileEntity
import com.example.hardware.ActiveAudioRoute
import com.example.hardware.BeztonCommand
import com.example.hardware.HardwareRouteType
import com.example.hardware.UsbDacState
import com.example.ui.theme.SonaAmber
import com.example.ui.theme.SonaCyan
import com.example.ui.theme.SonaCyanGlow
import com.example.ui.theme.SonaEmerald
import com.example.ui.theme.SonaObsidianBg
import com.example.ui.theme.SonaRose
import com.example.ui.theme.SonaTextPrimary
import com.example.ui.theme.SonaTextSecondary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DspDacGlassScreen(
    viewModel: SonaViewModel,
    activeRoute: ActiveAudioRoute,
    activeProfile: HardwareProfileEntity,
    usbDacState: UsbDacState
) {
    val bands = activeProfile.eqBandsList()
    val freqLabels = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("AUDIOPHILE DSP & USB DAC", style = MaterialTheme.typography.labelMedium, color = SonaCyan)
        Text("Hardware Chain & 10-Band EQ", style = MaterialTheme.typography.headlineSmall, color = SonaTextPrimary)

        // 1. USB DAC Bit-Perfect UAC 1.0 / 2.0 Bypass Card
        FrostedGlassPanel(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 22.dp,
            accentColor = if (usbDacState.isBitPerfectBypassActive) SonaEmerald else SonaCyan
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
                        Icon(Icons.Filled.Usb, contentDescription = "USB DAC", tint = SonaCyan)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(usbDacState.deviceName, style = MaterialTheme.typography.titleMedium, color = SonaTextPrimary)
                            Text(
                                text = "UAC: ${usbDacState.uacSpec} • EP 0x${"%02X".format(usbDacState.endpointAddress)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = SonaTextSecondary
                            )
                        }
                    }
                    Button(
                        onClick = {
                            if (usbDacState.isBitPerfectBypassActive) {
                                viewModel.usbDacManager.releaseUsbDac()
                            } else {
                                viewModel.usbDacManager.scanConnectedDacs(requestPermissionIfMissing = true)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (usbDacState.isBitPerfectBypassActive) SonaEmerald else SonaCyan,
                            contentColor = SonaObsidianBg
                        ),
                        modifier = Modifier.testTag("claim_usb_dac_button")
                    ) {
                        Text(if (usbDacState.isBitPerfectBypassActive) "Release DAC" else "Claim USB DAC")
                    }
                }
                Text(
                    text = usbDacState.statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (usbDacState.isBitPerfectBypassActive) SonaEmerald else SonaTextSecondary
                )
            }
        }

        // 2. AudioDeviceCallback Auto-Switching Profile Card
        FrostedGlassPanel(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.SettingsInputComponent, contentDescription = "Route", tint = SonaCyan)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Active Output: ${activeRoute.deviceName}", style = MaterialTheme.typography.titleMedium, color = SonaTextPrimary)
                        Text(
                            "Auto-Switch Profile: ${activeProfile.displayName} • Rates: ${activeRoute.supportedSampleRates.joinToString { "${it / 1000}k" }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = SonaTextSecondary
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HardwareRouteType.entries.forEach { route ->
                        FilterChip(
                            selected = activeProfile.deviceKey == route.profileKey,
                            onClick = { viewModel.selectProfileManually(route.profileKey) },
                            label = { Text(route.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SonaCyan.copy(alpha = 0.22f),
                                selectedLabelColor = SonaCyan
                            )
                        )
                    }
                }
            }
        }

        // 3. 10-Band Parametric EQ & Pre-Amp
        FrostedGlassPanel(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Tune, contentDescription = "EQ", tint = SonaCyan)
                        Spacer(Modifier.width(8.dp))
                        Text("10-Band Parametric EQ", style = MaterialTheme.typography.titleMedium, color = SonaTextPrimary)
                    }
                    OutlinedButton(
                        onClick = { viewModel.resetEqFlat() },
                        border = BorderStroke(1.dp, SonaCyan.copy(alpha = 0.45f)),
                        modifier = Modifier.testTag("reset_eq_button")
                    ) {
                        Text("Flat 0 dB")
                    }
                }

                // Smooth EQ Response Curve Canvas
                EqCurveCanvas(
                    bandsDb = bands,
                    preampDb = activeProfile.preampDb,
                    bypassed = activeProfile.bitPerfectEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(84.dp)
                )

                // Pre-Amp Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Pre-Amp Gain", style = MaterialTheme.typography.bodyMedium, color = SonaTextPrimary)
                    Text("${"%+.1f".format(activeProfile.preampDb)} dB", style = MaterialTheme.typography.labelMedium, color = SonaAmber)
                }
                Slider(
                    value = activeProfile.preampDb,
                    onValueChange = { viewModel.updatePreamp(it) },
                    valueRange = -15f..15f,
                    colors = SliderDefaults.colors(thumbColor = SonaAmber, activeTrackColor = SonaAmber)
                )

                // 10-Band Grid
                bands.forEachIndexed { idx, db ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${freqLabels[idx]}Hz",
                            style = MaterialTheme.typography.labelMedium,
                            color = SonaTextSecondary,
                            modifier = Modifier.width(46.dp)
                        )
                        Slider(
                            value = db,
                            onValueChange = { viewModel.updateEqBand(idx, it) },
                            valueRange = -12f..12f,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("eq_band_$idx"),
                            colors = SliderDefaults.colors(
                                thumbColor = SonaCyan,
                                activeTrackColor = SonaCyan,
                                inactiveTrackColor = Color.White.copy(alpha = 0.14f)
                            )
                        )
                        Text(
                            text = "${"%+.1f".format(db)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (db == 0f) SonaTextSecondary else SonaCyanGlow,
                            modifier = Modifier.width(44.dp)
                        )
                    }
                }
            }
        }

        // 4. Advanced DSP: SoX PCM-to-DSD, Crossfeed, Crossfade, Speed/Pitch, Bit-Perfect
        FrostedGlassPanel(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 22.dp,
            accentColor = SonaAmber
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Spatial & Resampling Pipeline", style = MaterialTheme.typography.titleMedium, color = SonaTextPrimary)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Bit-Perfect Direct Bypass", style = MaterialTheme.typography.bodyLarge, color = SonaTextPrimary)
                        Text("Bypasses EQ, Crossfeed & Mixer", style = MaterialTheme.typography.bodyMedium, color = SonaTextSecondary)
                    }
                    Switch(
                        checked = activeProfile.bitPerfectEnabled,
                        onCheckedChange = { viewModel.toggleBitPerfect(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = SonaObsidianBg, checkedTrackColor = SonaEmerald),
                        modifier = Modifier.testTag("bit_perfect_switch")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Direct Volume Control (DVC)", style = MaterialTheme.typography.bodyLarge, color = SonaTextPrimary)
                        Text("64-bit float attenuation (${"%.1f".format(activeProfile.dvcAttenuationDb)} dB)", style = MaterialTheme.typography.bodyMedium, color = SonaTextSecondary)
                    }
                    Switch(
                        checked = activeProfile.dvcEnabled,
                        onCheckedChange = { viewModel.toggleDvc(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = SonaObsidianBg, checkedTrackColor = SonaCyan)
                    )
                }

                Text("SoX PCM-to-DSD Oversampling", style = MaterialTheme.typography.bodyMedium, color = SonaTextPrimary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Native PCM", 64 to "DSD64", 128 to "DSD128", 256 to "DSD256").forEach { (factor, label) ->
                        FilterChip(
                            selected = activeProfile.dsdOversamplingFactor == factor,
                            onClick = { viewModel.updateDsdOversampling(factor) },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SonaAmber.copy(alpha = 0.24f),
                                selectedLabelColor = SonaAmber
                            )
                        )
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Bauer Crossfeed (Room Spatial)", style = MaterialTheme.typography.bodyMedium, color = SonaTextPrimary)
                    Text("${(activeProfile.crossfeedLevel * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = SonaCyan)
                }
                Slider(
                    value = activeProfile.crossfeedLevel,
                    onValueChange = { viewModel.updateCrossfeed(it) },
                    colors = SliderDefaults.colors(thumbColor = SonaCyan, activeTrackColor = SonaCyan)
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Crossfade Transition", style = MaterialTheme.typography.bodyMedium, color = SonaTextPrimary)
                    Text("${activeProfile.crossfadeMs / 1000f}s", style = MaterialTheme.typography.labelMedium, color = SonaCyan)
                }
                Slider(
                    value = activeProfile.crossfadeMs.toFloat(),
                    onValueChange = { viewModel.updateCrossfadeMs(it.toInt()) },
                    valueRange = 0f..10000f,
                    colors = SliderDefaults.colors(thumbColor = SonaCyan, activeTrackColor = SonaCyan)
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Playback Speed & Pitch", style = MaterialTheme.typography.bodyMedium, color = SonaTextPrimary)
                    Text("${"%.2f".format(activeProfile.playbackSpeed)}x / ${"%.2f".format(activeProfile.playbackPitch)}p", style = MaterialTheme.typography.labelMedium, color = SonaAmber)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Slider(
                        value = activeProfile.playbackSpeed,
                        onValueChange = { viewModel.updateSpeedAndPitch(it, activeProfile.playbackPitch) },
                        valueRange = 0.5f..1.5f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = SonaAmber, activeTrackColor = SonaAmber)
                    )
                    Slider(
                        value = activeProfile.playbackPitch,
                        onValueChange = { viewModel.updateSpeedAndPitch(activeProfile.playbackSpeed, it) },
                        valueRange = 0.5f..1.5f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = SonaCyan, activeTrackColor = SonaCyan)
                    )
                }
            }
        }
    }
}

@Composable
private fun EqCurveCanvas(
    bandsDb: List<Float>,
    preampDb: Float,
    bypassed: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val midY = size.height / 2f
        drawLine(
            color = Color.White.copy(alpha = 0.14f),
            start = Offset(0f, midY),
            end = Offset(size.width, midY),
            strokeWidth = 1.dp.toPx()
        )
        if (bandsDb.isEmpty()) return@Canvas

        val path = Path()
        val points = bandsDb.mapIndexed { idx, db ->
            val x = (idx.toFloat() / (bandsDb.size - 1).coerceAtLeast(1)) * size.width
            val effectiveDb = if (bypassed) 0f else (db + preampDb * 0.35f).coerceIn(-15f, 15f)
            val y = midY - (effectiveDb / 15f) * (size.height * 0.42f)
            Offset(x, y)
        }
        path.moveTo(points.first().x, points.first().y)
        for (i in 0 until points.size - 1) {
            val p0 = points[i]
            val p1 = points[i + 1]
            val cx = (p0.x + p1.x) / 2f
            path.cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
        }

        drawPath(
            path = path,
            color = if (bypassed) SonaTextSecondary else SonaCyan,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )
        points.forEach { pt ->
            drawCircle(
                color = if (bypassed) SonaTextSecondary else SonaCyanGlow,
                radius = 4.dp.toPx(),
                center = pt
            )
        }
    }
}

@Composable
fun BeztonRemoteGlassScreen(viewModel: SonaViewModel) {
    val irStatus by viewModel.irStatus.collectAsStateWithLifecycle()
    var customHex by rememberSaveable { mutableStateOf("807F02FD") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("CONSUMER IR HARDWARE REMOTE", style = MaterialTheme.typography.labelMedium, color = SonaAmber)
        Text("Bezton 4.1 / 5.1 Home Theater", style = MaterialTheme.typography.headlineSmall, color = SonaTextPrimary)

        // IR Status & Live 38 kHz NEC Pulse Visualizer
        FrostedGlassPanel(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 22.dp,
            accentColor = if (irStatus.hasHardwareIrEmitter) SonaEmerald else SonaAmber
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
                        Icon(Icons.Filled.SettingsRemote, contentDescription = "IR Emitter", tint = SonaAmber)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (irStatus.hasHardwareIrEmitter) "IR Blaster Ready" else "NEC Frame Generator",
                            style = MaterialTheme.typography.titleMedium,
                            color = SonaTextPrimary
                        )
                    }
                    Text(
                        text = "${irStatus.lastNecHex} • TX #${irStatus.transmissionCount}",
                        style = MaterialTheme.typography.labelMedium,
                        color = SonaCyan
                    )
                }

                Text(
                    text = "${irStatus.lastCommandLabel} (${irStatus.supportedCarrierRangeText})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SonaTextSecondary
                )

                IrPulseTrainCanvas(
                    pulsesMicros = irStatus.lastPulseTrainMicros,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                )
            }
        }

        // System & Surround Controls
        FrostedGlassPanel(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { viewModel.sendBeztonIr(BeztonCommand.POWER) },
                        colors = ButtonDefaults.buttonColors(containerColor = SonaRose, contentColor = SonaObsidianBg),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("ir_power_button")
                    ) {
                        Icon(Icons.Filled.PowerSettingsNew, contentDescription = "Power", modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Power")
                    }
                    OutlinedButton(
                        onClick = { viewModel.sendBeztonIr(BeztonCommand.MUTE) },
                        border = BorderStroke(1.dp, SonaAmber.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("ir_mute_button")
                    ) {
                        Icon(Icons.Filled.VolumeOff, contentDescription = "Mute", tint = SonaAmber, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Mute", color = SonaTextPrimary)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.sendBeztonIr(BeztonCommand.INPUT_CYCLE) },
                        border = BorderStroke(1.dp, SonaCyan.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                    ) {
                        Text("Input (AUX/BT/USB)", color = SonaTextPrimary)
                    }
                    OutlinedButton(
                        onClick = { viewModel.sendBeztonIr(BeztonCommand.MODE_5_1) },
                        border = BorderStroke(1.dp, SonaCyan.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Filled.SurroundSound, contentDescription = "5.1 Mode", tint = SonaCyan, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("5.1 / 2.1", color = SonaTextPrimary)
                    }
                }

                // Master Volume + Channel Trims
                IrChannelPairRow(
                    title = "Master Volume",
                    onMinus = { viewModel.sendBeztonIr(BeztonCommand.VOL_DOWN) },
                    onPlus = { viewModel.sendBeztonIr(BeztonCommand.VOL_UP) },
                    accent = SonaCyan,
                    tagPrefix = "ir_master"
                )
                IrChannelPairRow(
                    title = "Subwoofer (LFE)",
                    onMinus = { viewModel.sendBeztonIr(BeztonCommand.SUB_DOWN) },
                    onPlus = { viewModel.sendBeztonIr(BeztonCommand.SUB_UP) },
                    accent = SonaAmber,
                    tagPrefix = "ir_sub"
                )
                IrChannelPairRow(
                    title = "Center Channel",
                    onMinus = { viewModel.sendBeztonIr(BeztonCommand.CENTER_DOWN) },
                    onPlus = { viewModel.sendBeztonIr(BeztonCommand.CENTER_UP) },
                    accent = SonaCyan,
                    tagPrefix = "ir_center"
                )
                IrChannelPairRow(
                    title = "Rear Surrounds",
                    onMinus = { viewModel.sendBeztonIr(BeztonCommand.REAR_DOWN) },
                    onPlus = { viewModel.sendBeztonIr(BeztonCommand.REAR_UP) },
                    accent = SonaCyan,
                    tagPrefix = "ir_rear"
                )
                IrChannelPairRow(
                    title = "Bass / Treble",
                    onMinus = { viewModel.sendBeztonIr(BeztonCommand.BASS_DOWN) },
                    onPlus = { viewModel.sendBeztonIr(BeztonCommand.BASS_UP) },
                    accent = SonaEmerald,
                    tagPrefix = "ir_bass"
                )
            }
        }

        // Custom NEC 32-bit Hex Transmitter
        FrostedGlassPanel(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Custom 38 kHz NEC Hex Frame", style = MaterialTheme.typography.titleMedium, color = SonaTextPrimary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = customHex,
                        onValueChange = { customHex = it.take(10) },
                        label = { Text("NEC 32-bit Hex") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SonaAmber,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                            focusedTextColor = SonaTextPrimary,
                            unfocusedTextColor = SonaTextPrimary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("custom_nec_hex_input")
                    )
                    Button(
                        onClick = { viewModel.sendCustomNecHex(customHex) },
                        colors = ButtonDefaults.buttonColors(containerColor = SonaAmber, contentColor = SonaObsidianBg),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("send_custom_nec_button")
                    ) {
                        Text("Transmit")
                    }
                }
            }
        }
    }
}

@Composable
private fun IrChannelPairRow(
    title: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    accent: Color,
    tagPrefix: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = title, tint = accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.bodyLarge, color = SonaTextPrimary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onMinus,
                border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("${tagPrefix}_minus")
            ) {
                Icon(Icons.Filled.Remove, contentDescription = "$title down", tint = accent)
            }
            Button(
                onClick = onPlus,
                colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = 0.22f), contentColor = accent),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("${tagPrefix}_plus")
            ) {
                Icon(Icons.Filled.Add, contentDescription = "$title up")
            }
        }
    }
}

@Composable
private fun IrPulseTrainCanvas(
    pulsesMicros: List<Int>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val midY = size.height * 0.8f
        val topY = size.height * 0.15f
        if (pulsesMicros.isEmpty()) {
            drawLine(
                color = Color.White.copy(alpha = 0.2f),
                start = Offset(0f, midY),
                end = Offset(size.width, midY),
                strokeWidth = 2.dp.toPx()
            )
            return@Canvas
        }
        val totalUs = pulsesMicros.sum().coerceAtLeast(1).toFloat()
        var x = 0f
        val path = Path().apply { moveTo(0f, midY) }
        pulsesMicros.forEachIndexed { idx, us ->
            val dx = (us / totalUs) * size.width
            val y = if (idx % 2 == 0) topY else midY
            path.lineTo(x, y)
            path.lineTo(x + dx, y)
            x += dx
        }
        drawPath(path = path, color = SonaAmber, style = Stroke(width = 1.6.dp.toPx()))
    }
}
