package eu.labrago.audioscope.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.labrago.audioscope.model.ScopeUiState
import eu.labrago.audioscope.model.TestMode
import kotlin.math.roundToInt

@Composable
fun ScopeScreen(
    state: ScopeUiState,
    onToggle: () -> Unit,
    onModeChange: (TestMode) -> Unit,
    onAmplitude: (Double) -> Unit,
    onResolution: (Int) -> Unit,
    onToneCount: (Int) -> Unit,
    onThreshold: (Double) -> Unit,
    onRequestPermission: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding() // keep content clear of the status bar + nav buttons
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!state.hasMicPermission) {
            PermissionCard(onRequestPermission)
            return@Column
        }

        ModeSelector(state.mode, enabled = !state.running, onModeChange)

        ResponseChart(
            points = state.points,
            mode = state.mode,
            minHz = 20.0,
            maxHz = 20_000.0,
            gateVolume = state.gateVolume,
            modifier = Modifier.fillMaxWidth(),
        )

        when (state.mode) {
            TestMode.LIVE -> LiveLegend()
            else -> SweepLegend()
        }

        StartButton(state, onToggle)

        StatusBlock(state)

        ControlsPanel(state, onThreshold, onAmplitude, onResolution, onToneCount)
    }
}

@Composable
private fun ModeSelector(mode: TestMode, enabled: Boolean, onModeChange: (TestMode) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TestMode.entries.forEach { m ->
            val selected = m == mode
            Button(
                onClick = { if (enabled) onModeChange(m) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected) ScopeAccent else ScopeSurface,
                ),
            ) {
                Text(
                    when (m) {
                        TestMode.SWEEP -> "Sweep"
                        TestMode.MULTITONE -> "Multi-tone"
                        TestMode.LIVE -> "Live"
                    },
                    color = if (selected) androidx.compose.ui.graphics.Color.Black else ScopeText,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun SweepLegend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LegendItem(ScopeAccent2, "perfect")
        LegendItem(ScopeAccent, "on target")
        LegendItem(Color(0xFFE5484D), "wrong freq")
        LegendItem(ScopeAccent.copy(alpha = 0.30f), "volume")
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier
                .size(11.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Text(label, color = ScopeMuted, fontSize = 11.sp)
    }
}

// One card holding the input meter, the gate, and the mode-specific controls, so
// every row shares the same spacing (no large double-card gap between groups).
@Composable
private fun ControlsPanel(
    state: ScopeUiState,
    onThreshold: (Double) -> Unit,
    onAmplitude: (Double) -> Unit,
    onResolution: (Int) -> Unit,
    onToneCount: (Int) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = ScopeSurface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LabeledSlider(
                label = "Ignore frequencies quieter than",
                value = "${state.gateVolume.roundToInt()}",
                position = state.gateVolume.toFloat(),
                range = 0f..100f,
                enabled = true, // adjustable any time — re-filters instantly, all modes
                onChange = { onThreshold(it.toDouble()) },
            )
            if (state.mode == TestMode.LIVE) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Loudest frequency", color = ScopeText, fontSize = 13.sp)
                    Text(
                        state.peakHz?.let { "${it.roundToInt()} Hz" } ?: "—",
                        color = ScopeAccent2,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            } else {
                val amp = if (state.mode == TestMode.SWEEP) state.sweep.amplitude else state.multi.amplitude
                LabeledSlider(
                    label = "Output level",
                    value = "${(amp * 100).roundToInt()}%",
                    position = amp.toFloat(),
                    range = 0.1f..1.0f,
                    enabled = !state.running,
                    onChange = { onAmplitude(it.toDouble()) },
                )
                if (state.mode == TestMode.SWEEP) {
                    LabeledSlider(
                        label = "Resolution",
                        value = "${state.sweep.pointsPerOctave}/oct",
                        position = state.sweep.pointsPerOctave.toFloat(),
                        range = 1f..12f,
                        steps = 10,
                        enabled = !state.running,
                        onChange = { onResolution(it.roundToInt()) },
                    )
                } else {
                    LabeledSlider(
                        label = "Tones",
                        value = "${state.multi.toneCount}",
                        position = state.multi.toneCount.toFloat(),
                        range = 3f..32f,
                        steps = 28,
                        enabled = !state.running,
                        onChange = { onToneCount(it.roundToInt()) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveLegend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LegendItem(ScopeAccent, "volume")
        LegendItem(ScopeAccent2, "loudest / gate")
    }
}

// The START/STOP/LISTEN button doubles as the input-volume meter: on Sweep and
// Live its green fills left-to-right with the live input volume (0..100). On
// Multi-tone (no live volume) it's a plain solid button.
@Composable
private fun StartButton(state: ScopeUiState, onToggle: () -> Unit) {
    val live = state.mode == TestMode.LIVE
    val showVolume = state.running && state.mode != TestMode.MULTITONE
    val target = if (showVolume) (state.inputVolume / 100.0).toFloat().coerceIn(0f, 1f) else 0f
    val fill = animateFloatAsState(target, label = "inputVolume").value
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(50))
            .background(ScopeAccent)
            .clickable { onToggle() },
        contentAlignment = Alignment.Center,
    ) {
        // Volume fills from the left as a lighter band over the green button.
        if (fill > 0f) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .fillMaxWidth(fill)
                    .background(Color.White.copy(alpha = 0.30f)),
            )
        }
        Text(
            if (state.running) "STOP" else if (live) "LISTEN" else "START TEST",
            color = androidx.compose.ui.graphics.Color.Black,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun StatusBlock(state: ScopeUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.running && state.mode != TestMode.LIVE) {
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth(),
                color = ScopeAccent,
                trackColor = ScopeGrid,
            )
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: String,
    position: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = ScopeText, fontSize = 13.sp)
            Text(value, color = ScopeAccent, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = position,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
        )
    }
}

@Composable
private fun PermissionCard(onRequestPermission: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = ScopeSurface)) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Microphone access is required to capture the played-back tones.",
                color = ScopeText,
                fontSize = 14.sp,
            )
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(containerColor = ScopeAccent),
            ) {
                Text("Grant microphone permission", color = androidx.compose.ui.graphics.Color.Black)
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}
