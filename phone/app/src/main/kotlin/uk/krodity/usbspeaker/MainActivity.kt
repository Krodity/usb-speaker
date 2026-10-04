package uk.krodity.usbspeaker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SpeakerScreen()
                }
            }
        }
    }
}

@Composable
private fun SpeakerScreen() {
    val pump = SpeakerService.pump
    val state by pump.state.collectAsStateWithLifecycle()
    val level by pump.level.collectAsStateWithLifecycle()
    val error by pump.error.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasRoot by remember { mutableStateOf<Boolean?>(null) }
    var gadgetMode by remember { mutableStateOf("?") }
    var card by remember { mutableStateOf<Int?>(null) }
    var rate by remember { mutableStateOf<Int?>(null) }
    var linked by remember { mutableStateOf(false) }

    // Poll the gadget rather than trusting cached state: the cable can be
    // pulled at any moment, and sys.usb.config is also changed by the system
    // (and by the host script) behind our back.
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) {
                if (hasRoot == null) hasRoot = RootShell.isAvailable()
                gadgetMode = Gadget.currentMode()
                card = Gadget.cardIndex()
                rate = Gadget.hostRate()
                linked = Gadget.isUsbConfigured()
            }
            delay(1500)
        }
    }

    val audioMode = gadgetMode == Gadget.MODE_AUDIO

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            "USB Speaker",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "Plays audio sent over USB from the PC",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (hasRoot == false) {
            ErrorCard("Root not available. This app needs su to reach the USB gadget and its ALSA card.")
        }

        StatusRow("USB gadget", if (audioMode) "audio mode" else gadgetMode, audioMode)
        StatusRow("Gadget card", card?.let { "hw:$it,0" } ?: "not bound", card != null)
        StatusRow("USB link", if (linked) "configured" else "not connected", linked)
        StatusRow("Host rate", rate?.let { "$it Hz" } ?: "-", rate != null)
        StatusRow(
            "Pump",
            when (state) {
                AudioPump.State.RUNNING -> "playing"
                AudioPump.State.WAITING -> "waiting for PC"
                AudioPump.State.ERROR -> "error"
                AudioPump.State.STOPPED -> "stopped"
            },
            state == AudioPump.State.RUNNING
        )

        error?.let { ErrorCard(it) }

        LevelMeter(level)

        var changingRate by remember { mutableStateOf(false) }
        Column {
            Text("Sample rate", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Gadget.RATES.forEach { r ->
                    FilterChip(
                        selected = rate == r,
                        enabled = hasRoot == true && audioMode && !changingRate,
                        onClick = {
                            changingRate = true
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    // The capture device vanishes while the
                                    // gadget rebuilds, so the pump has to come
                                    // down first or it just errors out.
                                    val wasRunning = pump.state.value != AudioPump.State.STOPPED
                                    if (wasRunning) SpeakerService.stop(ctx)
                                    Thread.sleep(400)
                                    Gadget.setHostRate(r)
                                    if (wasRunning) {
                                        Thread.sleep(1200)
                                        SpeakerService.start(ctx)
                                    }
                                }
                                changingRate = false
                            }
                        },
                        label = { Text("${r / 1000}k") }
                    )
                }
                if (changingRate) {
                    Spacer(Modifier.width(4.dp))
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            Text(
                "Changing this reconnects USB; the PC may re-select the device.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        var auto by remember { mutableStateOf(Prefs.autoStart(ctx)) }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Start when plugged in", color = MaterialTheme.colorScheme.onBackground)
                Text(
                    "Switches to audio mode on USB connect",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = auto,
                onCheckedChange = { auto = it; Prefs.setAutoStart(ctx, it) }
            )
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = {
                if (audioMode) Gadget.setAudioMode(false) else Gadget.setAudioMode(true)
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = hasRoot == true
        ) {
            Text(if (audioMode) "Disable USB audio mode" else "Enable USB audio mode")
        }

        Button(
            onClick = {
                if (state == AudioPump.State.STOPPED || state == AudioPump.State.ERROR)
                    SpeakerService.start(ctx)
                else SpeakerService.stop(ctx)
            },
            modifier = Modifier.fillMaxWidth().height(64.dp),
            enabled = hasRoot == true && audioMode,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state == AudioPump.State.RUNNING)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                if (state == AudioPump.State.STOPPED || state == AudioPump.State.ERROR)
                    "Start speaker" else "Stop speaker",
                fontSize = 18.sp
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(9.dp).clip(RoundedCornerShape(50))
                    .background(if (ok) Color(0xFF4CAF50) else Color(0xFF757575))
            )
            Spacer(Modifier.width(8.dp))
            Text(
                value,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

@Composable
private fun LevelMeter(level: Float) {
    val shown by animateFloatAsState(level, label = "level")
    Column {
        Text("Level", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.fillMaxWidth().height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(shown)
                    .background(
                        if (shown > 0.95f) Color(0xFFE53935) else MaterialTheme.colorScheme.primary
                    )
            )
        }
    }
}

@Composable
private fun ErrorCard(msg: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Text(
            msg,
            Modifier.padding(14.dp),
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}
