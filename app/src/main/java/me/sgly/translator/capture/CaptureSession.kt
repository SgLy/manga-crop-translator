package me.sgly.translator.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.Display
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import me.sgly.translator.logs.AppLog
import kotlin.coroutines.resume

/** Frames follow a surface swap within milliseconds; a wait this long means none is coming. */
private const val FRAME_TIMEOUT_MILLIS = 2_000L

private data class ScreenSpec(
    val width: Int,
    val height: Int,
    val density: Int,
)

/**
 * One screen capture lease and everything created under it: the projection, its virtual display and
 * the reader frames are taken from. A session lives from the moment consent is granted until the
 * lease is gone, whether this app let go of it or the system took it back.
 *
 * Every member belongs to that one lease, and a new lease always means a new session — so an event
 * from an older lease can never reach a newer session, and a dead virtual display can never be
 * captured from.
 */
class CaptureSession(
    context: Context,
    private val projection: MediaProjection,
) {
    private val spec = screenSpec(context)

    private val _capturedContentVisible = MutableStateFlow(false)
    val capturedContentVisible: StateFlow<Boolean> = _capturedContentVisible.asStateFlow()

    private val _leaseLost = MutableStateFlow(false)
    val leaseLost: StateFlow<Boolean> = _leaseLost.asStateFlow()

    private val callback =
        object : MediaProjection.Callback() {
            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                AppLog.d("CaptureSession") { "visible=$isVisible" }
                _capturedContentVisible.value = isVisible
            }

            override fun onStop() {
                AppLog.i("CaptureSession", "lease gone")
                _leaseLost.value = true
            }
        }

    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var closed = false

    init {
        try {
            // Registering before createVirtualDisplay is required; the other order throws.
            projection.registerCallback(callback, null)
            val first = newReader()
            reader = first
            display =
                projection.createVirtualDisplay(
                    "ScreenCapture",
                    spec.width,
                    spec.height,
                    spec.density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    first.surface,
                    null,
                    null,
                )
            AppLog.d("CaptureSession") { "display ${spec.width}x${spec.height}" }
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    suspend fun captureFrame(): Bitmap? {
        val target = display ?: return null
        // A fresh reader per frame: re-attaching a surface forces SurfaceFlinger to push a new one,
        // while a reader that already delivered its frame stays silent (static screens especially).
        val next = newReader()
        target.setSurface(next.surface)
        reader?.close()
        reader = next
        AppLog.d("CaptureSession") { "waiting for a frame" }
        return withTimeoutOrNull(FRAME_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                next.setOnImageAvailableListener({ onImageAvailable(it, continuation) }, null)
                continuation.invokeOnCancellation { next.close() }
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        _leaseLost.value = true
        display?.release()
        display = null
        reader?.close()
        reader = null
        projection.stop()
    }

    private fun newReader(): ImageReader = ImageReader.newInstance(spec.width, spec.height, PixelFormat.RGBA_8888, 2)

    private fun onImageAvailable(
        source: ImageReader,
        continuation: CancellableContinuation<Bitmap?>,
    ) {
        val image: Image = source.acquireLatestImage() ?: return
        if (!continuation.isActive) {
            image.close()
            return
        }
        image.use { continuation.resume(toBitmap(it)) }
    }

    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val rowPadding = plane.rowStride - plane.pixelStride * spec.width
        val padded = createBitmap(spec.width + rowPadding / plane.pixelStride, spec.height)
        padded.copyPixelsFromBuffer(plane.buffer)
        if (rowPadding == 0) return padded
        return Bitmap.createBitmap(padded, 0, 0, spec.width, spec.height)
    }
}

/** The virtual display mirrors the whole screen, so frames always arrive in screen pixels. */
private fun screenSpec(context: Context): ScreenSpec {
    val metrics = DisplayMetrics()
    val displayManager = context.getSystemService(DisplayManager::class.java)
    @Suppress("DEPRECATION")
    displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(metrics)
    return ScreenSpec(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
}
