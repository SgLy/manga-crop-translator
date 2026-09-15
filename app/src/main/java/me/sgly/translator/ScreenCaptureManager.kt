package me.sgly.translator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class ScreenCaptureManager(
    private val context: Context,
) {
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var isCallbackRegistered = false
    private var captureRequested = false

    /**
     * Latest visibility of the captured region (Android 14 app screen sharing). Fires as soon as
     * capture begins and whenever the shared app becomes visible/hidden. Used to show/hide the FAB.
     */
    var onCapturedContentVisibilityChanged: ((Boolean) -> Unit)? = null

    private val projectionCallback =
        object : MediaProjection.Callback() {
            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                AppLog.d("ScreenCapture") { "capturedContentVisible=$isVisible" }
                onCapturedContentVisibilityChanged?.invoke(isVisible)
            }

            override fun onStop() {
                AppLog.d("ScreenCapture") { "MediaProjection stopped" }
            }
        }

    private val _screenCaptureFlow = MutableSharedFlow<Bitmap>(extraBufferCapacity = 1)
    val screenCaptureFlow = _screenCaptureFlow.asSharedFlow()

    fun setMediaProjection(projection: MediaProjection) {
        // The callback must be registered before createVirtualDisplay, or the call throws.
        if (!isCallbackRegistered) {
            projection.registerCallback(projectionCallback, null)
            isCallbackRegistered = true
        }
        mediaProjection = projection
        // Create the VirtualDisplay up front so the system starts reporting captured-content
        // visibility immediately — we need it to show/hide the FAB before the first capture.
        ensureVirtualDisplay()
    }

    private data class ScreenSpec(
        val width: Int,
        val height: Int,
        val density: Int,
    )

    private fun screenSpec(): ScreenSpec {
        val metrics = DisplayMetrics()
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        display?.getRealMetrics(metrics)
        return ScreenSpec(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    private fun newImageReader(spec: ScreenSpec): ImageReader {
        val reader = ImageReader.newInstance(spec.width, spec.height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r -> onImageAvailable(r, spec) }, null)
        return reader
    }

    private fun ensureVirtualDisplay() {
        if (virtualDisplay != null) return
        val mp = mediaProjection ?: return
        val spec = screenSpec()
        val reader = newImageReader(spec)
        imageReader = reader
        virtualDisplay =
            mp.createVirtualDisplay(
                "ScreenCapture",
                spec.width,
                spec.height,
                spec.density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null,
            )
        AppLog.d("ScreenCapture") { "createVirtualDisplay -> ${virtualDisplay != null} (${spec.width}x${spec.height})" }
    }

    /** Returns true if capture was initiated, false if mediaProjection is not ready. */
    fun captureScreen(): Boolean {
        if (mediaProjection == null) return false
        ensureVirtualDisplay()
        val vd = virtualDisplay ?: return false

        val spec = screenSpec()
        captureRequested = true
        AppLog.d("ScreenCapture") { "captureScreen requested" }

        // A fresh ImageReader per capture: re-attaching its Surface to the long-lived
        // VirtualDisplay forces SurfaceFlinger to push a brand-new frame. Reusing the old
        // reader yields no frame on the 2nd+ capture (static screen) and the flow hangs.
        val freshReader = newImageReader(spec)
        vd.setSurface(freshReader.surface)

        val previousReader = imageReader
        imageReader = freshReader
        if (previousReader != null && previousReader !== freshReader) previousReader.close()

        return true
    }

    private fun onImageAvailable(
        reader: ImageReader,
        spec: ScreenSpec,
    ) {
        // Always acquire and close to keep the buffer free; only process when requested.
        val image: Image? = reader.acquireLatestImage()
        if (image == null) {
            if (captureRequested) AppLog.d("ScreenCapture") { "onImageAvailable but acquireLatestImage=null" }
            return
        }
        image.use {
            if (!captureRequested) return
            captureRequested = false

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * spec.width

            val bitmap =
                Bitmap.createBitmap(
                    spec.width + rowPadding / pixelStride,
                    spec.height,
                    Bitmap.Config.ARGB_8888,
                )
            bitmap.copyPixelsFromBuffer(buffer)

            val finalBitmap =
                if (rowPadding > 0) {
                    Bitmap.createBitmap(bitmap, 0, 0, spec.width, spec.height)
                } else {
                    bitmap
                }

            val emitted = _screenCaptureFlow.tryEmit(finalBitmap)
            AppLog.d("ScreenCapture") { "frame ${finalBitmap.width}x${finalBitmap.height} emitted=$emitted" }
        }
    }

    fun release() {
        onCapturedContentVisibilityChanged = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        isCallbackRegistered = false
    }
}
