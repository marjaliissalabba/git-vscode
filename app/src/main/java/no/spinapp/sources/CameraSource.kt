package no.spinapp.sources

import android.util.Size
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import no.spinapp.AppState
import no.spinapp.core.BikeSample
import no.spinapp.core.DecodeResult
import no.spinapp.core.GrayImage
import no.spinapp.core.NormRect
import no.spinapp.core.ReadingFilter
import no.spinapp.core.SevenSegmentDecoder
import no.spinapp.core.SourceType
import no.spinapp.core.StagesConsoleLayout
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Leser Stages-konsollet med bakkameraet.
 * LCD-glassets plassering i bildet settes i kameraoppsettet (AppState.lcdRect).
 */
class CameraSource(private val activity: ComponentActivity) : BikeDataSource {
    override val type = SourceType.CAMERA
    private val _latest = MutableStateFlow<BikeSample?>(null)
    private val _status = MutableStateFlow("Kamera av")
    override val latest: StateFlow<BikeSample?> = _latest
    override val status: StateFlow<String> = _status

    /** Rå avlesning per felt, for visning i oppsettet. */
    val debug = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Bredde/høyde på det stående kamerabildet. */
    val imageAspect = MutableStateFlow(3f / 4f)

    /** LCD-utsnittet som brukes nå (kan endres live i oppsettet). */
    @Volatile var lcdRect: NormRect = AppState.lcdRect

    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var lastAnalysisMs = 0L
    private val wattsFilter = ReadingFilter(maxJump = 150, maxValue = 2500)
    private val rpmFilter = ReadingFilter(maxJump = 30, maxValue = 200)

    override fun start() = bind(null)

    /** Starter kameraet. Med [previewView] vises også bildet (brukes i oppsettet). */
    fun bind(previewView: PreviewView?) {
        _status.value = "Starter kamera …"
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            val p = future.get()
            provider = p
            val selector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1600, 1200), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(selector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { img -> analyze(img) }
            p.unbindAll()
            if (previewView != null) {
                val preview = Preview.Builder().setResolutionSelector(selector).build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                p.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } else {
                p.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            }
            _status.value = "Kamera på – sikter mot konsollet"
        }, ContextCompat.getMainExecutor(activity))
    }

    override fun stop() {
        provider?.unbindAll()
        _status.value = "Kamera av"
    }

    private fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastAnalysisMs < 250) return
            lastAnalysisMs = now

            val plane = image.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixStride = plane.pixelStride
            val w = image.width
            val h = image.height
            val rot = image.imageInfo.rotationDegrees
            val uprightW = if (rot % 180 == 0) w else h
            val uprightH = if (rot % 180 == 0) h else w
            imageAspect.value = uprightW.toFloat() / uprightH

            fun luma(u: Int, v: Int): Int {
                val (x, y) = when (rot) {
                    90 -> v to (h - 1 - u)
                    180 -> (w - 1 - u) to (h - 1 - v)
                    270 -> (w - 1 - v) to u
                    else -> u to v
                }
                return buf.get(y * rowStride + x * pixStride).toInt() and 0xFF
            }

            fun crop(r: NormRect): GrayImage {
                val x0 = r.left * uprightW
                val x1 = r.right * uprightW
                val y0 = r.top * uprightH
                val y1 = r.bottom * uprightH
                val srcH = y1 - y0
                val scale = min(1f, 72f / max(1f, srcH))
                val outH = max(8, (srcH * scale).toInt())
                val outW = max(8, ((x1 - x0) * scale).toInt())
                val px = IntArray(outW * outH)
                for (oy in 0 until outH) {
                    val v = (y0 + (oy + 0.5f) / outH * srcH).toInt().coerceIn(0, uprightH - 1)
                    for (ox in 0 until outW) {
                        val u = (x0 + (ox + 0.5f) / outW * (x1 - x0)).toInt().coerceIn(0, uprightW - 1)
                        px[oy * outW + ox] = luma(u, v)
                    }
                }
                return GrayImage(outW, outH, px)
            }

            val lcd = lcdRect
            val results = mutableMapOf<String, DecodeResult>()
            for (field in StagesConsoleLayout.fields) {
                results[field.key] = SevenSegmentDecoder.decode(crop(lcd.sub(field.rect)))
            }
            debug.value = results.mapValues { (_, r) ->
                if (r.text.isEmpty()) "–" else "${r.text} (${(r.confidence * 100).toInt()} %)"
            }
            fun good(r: DecodeResult?) = r != null && r.isValid && r.confidence >= 0.55f
            val wattsRaw = results["watts"]?.takeIf { good(it) }?.asInt()
            val rpmRaw = results["rpm"]?.takeIf { good(it) }?.asInt()
            val speedRaw = results["speed"]?.takeIf { good(it) }?.asTenths()
            val watts = wattsFilter.accept(wattsRaw)
            val rpm = rpmFilter.accept(rpmRaw)
            if (wattsRaw != null || rpmRaw != null) {
                _latest.value = BikeSample(now, watts, rpm, speedRaw, null, SourceType.CAMERA)
                _status.value = "Leser konsollet"
            } else {
                _status.value = "Finner ikke tallene – sjekk lys og utsnitt"
            }
        } catch (e: Exception) {
            _status.value = "Kamerafeil: ${e.message}"
        } finally {
            image.close()
        }
    }
}
