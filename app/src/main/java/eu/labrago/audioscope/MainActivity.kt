package eu.labrago.audioscope

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.labrago.audioscope.model.TestMode
import eu.labrago.audioscope.ui.AudioScopeTheme
import eu.labrago.audioscope.ui.ScopeBg
import eu.labrago.audioscope.ui.ScopeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AudioScopeTheme { Root() } }
    }
}

@Composable
private fun Root() {
    val vm: ScopeViewModel = viewModel()
    val state by vm.state.collectAsState()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> vm.setPermission(granted) }

    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        vm.setPermission(granted)
    }

    Surface(modifier = Modifier.fillMaxSize().background(ScopeBg)) {
        ScopeScreen(
            state = state,
            onToggle = vm::toggle,
            onModeChange = vm::setMode,
            onAmplitude = { a ->
                if (state.mode == TestMode.SWEEP) vm.updateSweep { it.copy(amplitude = a) }
                else vm.updateMulti { it.copy(amplitude = a) }
            },
            onResolution = { ppo -> vm.updateSweep { it.copy(pointsPerOctave = ppo) } },
            onToneCount = { n -> vm.updateMulti { it.copy(toneCount = n) } },
            onThreshold = vm::setGate,
            onRequestPermission = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
        )
    }
}
