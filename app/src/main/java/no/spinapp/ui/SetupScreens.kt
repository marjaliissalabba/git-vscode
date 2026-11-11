package no.spinapp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.TextButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import no.spinapp.AppState
import no.spinapp.MainActivity
import no.spinapp.core.NormRect
import no.spinapp.core.SourceType
import no.spinapp.core.StagesConsoleLayout
import no.spinapp.sources.BleScanner
import no.spinapp.sources.CameraSource
import kotlin.math.max

private fun blePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

@Composable
fun BleSetupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val activity = context as MainActivity
    val scanner = remember { BleScanner(context.applicationContext) }
    val devices by scanner.devices.collectAsState()
    val error by scanner.error.collectAsState()
    var granted by remember {
        mutableStateOf(blePermissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        granted = res.values.all { it }
    }

    LaunchedEffect(granted) {
        if (!granted) launcher.launch(blePermissions()) else scanner.start()
    }
    DisposableEffect(Unit) { onDispose { scanner.stop() } }

    // Lista oppdateres hvert 2. sekund (eller fryses), sortert etter utjevnet signal, så den ikke hopper
    var showAll by remember { mutableStateOf(false) }
    var frozen by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(listOf<no.spinapp.sources.FoundDevice>()) }
    var hidden by remember { mutableStateOf(0) }
    var testing by remember { mutableStateOf<no.spinapp.sources.FoundDevice?>(null) }
    val last = remember { AppState.bleAddress }
    LaunchedEffect(showAll, frozen) {
        while (true) {
            if (!frozen) {
                val now = System.currentTimeMillis()
                val all = scanner.devices.value.values.filter { now - it.lastSeenMs < 10_000 }
                val list = if (showAll) all else all.filter { it.isFitness }
                hidden = all.size - list.size
                shown = list.sortedByDescending { it.smoothRssi }
            } else {
                // Frosset: bare oppdater signalet for enhetene som vises
                val cur = scanner.devices.value
                shown = shown.map { cur[it.address] ?: it }
            }
            kotlinx.coroutines.delay(2000)
        }
    }
    val nearest = shown.maxByOrNull { it.smoothRssi }?.takeIf { top ->
        val second = shown.filter { it.address != top.address }.maxOfOrNull { it.smoothRssi }
        second == null || top.smoothRssi - second >= 8
    }

    Column(
        Modifier.fillMaxSize().background(C.bg)
            .statusBarsPadding().navigationBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Finn sykkelen din", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = C.n100, fontFamily = Inter)
        Text(
            "1. Tråkk, så sykkelen våkner.\n" +
                "2. Hold telefonen helt inntil konsollet noen sekunder. Den som merkes «Nærmest deg» er som regel din.\n" +
                "3. Trykk på den og tråkk: følger tallene deg, er det riktig sykkel.",
            color = C.n300, fontSize = 14.sp, fontFamily = Inter, lineHeight = 20.sp,
        )
        if (!granted) {
            Button(onClick = { launcher.launch(blePermissions()) }) { Text("Gi tilgang til Bluetooth") }
        }
        error?.let { Text(it, color = Color(0xFFE74C3C)) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(if (frozen) "▶ Oppdater lista" else "⏸ Frys lista", frozen) { frozen = !frozen }
            Pill(if (showAll) "Bare sykler" else "Vis alle" + if (hidden > 0) " ($hidden til)" else "", showAll) { showAll = !showAll }
        }
        if (shown.isEmpty()) Muted("Søker … Tråkk på sykkelen.", 14, C.n400)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(shown, key = { it.address }) { d ->
                val isNearest = d.address == nearest?.address
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (isNearest) C.accentTint else C.surface)
                        .border(1.dp, if (isNearest) C.accent else C.n800, RoundedCornerShape(12.dp))
                        .clickable { testing = d }.padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(d.name ?: "Ukjent · …${d.address.takeLast(5)}", Modifier.weight(1f), color = C.n100,
                            fontWeight = FontWeight.SemiBold, fontSize = 16.sp, fontFamily = Inter)
                        if (isNearest) Text("Nærmest deg", color = C.accent200, fontSize = 13.sp, fontFamily = Inter, fontWeight = FontWeight.SemiBold)
                        else if (d.address == last) Text("Valgt sist", color = C.n400, fontSize = 13.sp, fontFamily = Inter)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        SignalBars(d.smoothRssi)
                        Text("  ${d.smoothRssi.toInt()} dBm", color = C.n400, fontSize = 13.sp, fontFamily = Inter)
                        if (d.services.isNotEmpty()) Text("  ·  " + d.services.joinToString(" · "), color = C.good, fontSize = 13.sp, fontFamily = Inter)
                    }
                }
            }
        }
        OutlinedButton(onClick = onDone) { Text("Tilbake") }
    }

    testing?.let { d -> BleTestDialog(d, onUse = {
        AppState.bleAddress = d.address
        AppState.bleName = d.name ?: d.address
        AppState.sourceType = SourceType.BLUETOOTH
        testing = null
        activity.resetSource()
        onDone()
    }, onCancel = { testing = null }) }
}

/** Fire streker for signalstyrke (−95 dBm = ingen, −45 dBm = full). */
@Composable
private fun SignalBars(rssi: Double) {
    val level = ((rssi + 95) / 12.5).toInt().coerceIn(0, 4)
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..4) {
            Box(Modifier.width(4.dp).height((4 + i * 3).dp).clip(RoundedCornerShape(1.dp)).background(if (i <= level) C.good else C.n700))
        }
    }
}

/** Kobler til enheten og viser watt og rpm live, så du kan sjekke at det er din sykkel før du lagrer. */
@Composable
private fun BleTestDialog(d: no.spinapp.sources.FoundDevice, onUse: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val source = remember(d.address) { no.spinapp.sources.BleSource(context.applicationContext, d.address) }
    DisposableEffect(d.address) {
        source.start()
        onDispose { source.stop() }
    }
    val status by source.status.collectAsState()
    val sample by source.latest.collectAsState()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        containerColor = C.surface,
        title = { Text(d.name ?: "Ukjent · …${d.address.takeLast(5)}", color = C.n100, fontFamily = Inter) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Tråkk litt og varier farten. Følger tallene deg, er dette sykkelen din.", color = C.n300, fontSize = 14.sp, fontFamily = Inter)
                Text(status, color = C.n400, fontSize = 13.sp, fontFamily = Inter)
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text("${sample?.powerW ?: "–"} W", color = C.n100, fontSize = 28.sp, fontFamily = Inter, fontWeight = FontWeight.Medium)
                    Text("${sample?.cadenceRpm ?: "–"} rpm", color = C.n100, fontSize = 28.sp, fontFamily = Inter, fontWeight = FontWeight.Medium)
                }
            }
        },
        confirmButton = { TextButton(onClick = { source.stop(); onUse() }) { Text("Bruk denne", color = C.accent200) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Prøv en annen", color = C.n400) } },
    )
}

@Composable
fun CameraSetupScreen(activity: MainActivity, onDone: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    Column(
        Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Juster kamera", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Slå på lyset på konsollet. Dra den blå rammen over LCD-glasset, og knip for å endre størrelse. " +
                "De gule feltene skal ligge over tallene for watt, RPM og fart.",
            color = Color.Gray, fontSize = 13.sp,
        )
        if (!granted) {
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Gi tilgang til kamera") }
            OutlinedButton(onClick = onDone) { Text("Tilbake") }
            return@Column
        }
        CameraCalibration(activity, Modifier.weight(1f), onDone)
    }
}

@Composable
private fun CameraCalibration(activity: MainActivity, modifier: Modifier, onDone: () -> Unit) {
    val camera = remember {
        (AppState.source.value as? CameraSource) ?: CameraSource(activity)
    }
    val debug by camera.debug.collectAsState()
    val aspect by camera.imageAspect.collectAsState()
    val status by camera.status.collectAsState()
    var rect by remember { mutableStateOf(AppState.lcdRect) }
    var ratio by remember { mutableStateOf(StagesConsoleLayout.ASPECT) }
    val previewView = remember { PreviewView(activity).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }

    DisposableEffect(Unit) {
        camera.bind(previewView)
        onDispose { camera.stop() }
    }
    LaunchedEffect(rect) { camera.lcdRect = rect }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val viewW = constraints.maxWidth.toFloat()
        val viewH = constraints.maxHeight.toFloat()
        // Bildet vises med FIT_CENTER: finn hvor det havner i visningen
        val imgW: Float
        val imgH: Float
        if (viewW / viewH > aspect) { imgH = viewH; imgW = viewH * aspect } else { imgW = viewW; imgH = viewW / aspect }
        val offX = (viewW - imgW) / 2
        val offY = (viewH - imgH) / 2

        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Canvas(
            Modifier.fillMaxSize().pointerInput(aspect) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val r = rect
                    val cx = (r.left + r.right) / 2 + pan.x / imgW
                    val cy = (r.top + r.bottom) / 2 + pan.y / imgH
                    val hh = (r.height / 2 * zoom).coerceIn(0.05f, 0.5f)
                    val hw = hh * imgH / imgW * ratio
                    rect = NormRect(cx - hw, cy - hh, cx + hw, cy + hh)
                }
            }
        ) {
            fun toView(r: NormRect) = Offset(offX + r.left * imgW, offY + r.top * imgH) to
                Size(r.width * imgW, r.height * imgH)
            val (o, s) = toView(rect)
            drawRect(Color(0xFF4FC3F7), o, s, style = Stroke(width = 5f))
            for (f in StagesConsoleLayout.fields) {
                val (fo, fs) = toView(rect.sub(f.rect))
                drawRect(Color(0xFFF5B800), fo, fs, style = Stroke(width = 3f))
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text(status, color = Color.Gray, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (f in StagesConsoleLayout.fields) {
                    Column {
                        Text(f.label, color = Color.Gray, fontSize = 12.sp)
                        Text(debug[f.key] ?: "–", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text("Bredde på rammen", fontSize = 12.sp, color = Color.Gray)
            Slider(
                value = ratio,
                onValueChange = { v ->
                    ratio = v
                    val r = rect
                    val cx = (r.left + r.right) / 2
                    val hw = r.height / 2 / max(0.1f, camera.imageAspect.value) * v
                    rect = NormRect(cx - hw, r.top, cx + hw, r.bottom)
                },
                valueRange = 0.35f..0.70f,
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = {
            AppState.lcdRect = rect
            AppState.sourceType = SourceType.CAMERA
            activity.resetSource()
            onDone()
        }) { Text("Lagre") }
        OutlinedButton(onClick = onDone) { Text("Avbryt") }
    }
}
