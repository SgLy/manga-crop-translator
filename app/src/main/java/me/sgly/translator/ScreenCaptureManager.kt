package me.sgly.translator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class ScreenCaptureManager(private val context: Context) {
    private var windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var isCallbackRegistered = false
    private var captureRequested = false
    
    private val _screenCaptureFlow = MutableSharedFlow<Bitmap>(extraBufferCapacity = 1)
    val screenCaptureFlow = _screenCaptureFlow.asSharedFlow()

    fun setMediaProjection(projection: MediaProjection) {
        mediaProjection = projection
    }

    /** Returns true if capture was initiated, false if mediaProjection is not ready. */
    fun captureScreen(): Boolean {
        val mp = mediaProjection ?: return false

        if (!isCallbackRegistered) {
            mp.registerCallback(object : MediaProjection.Callback() {}, null)
            isCallbackRegistered = true
        }

        captureRequested = true

        if (virtualDisplay == null) {
            val metrics = DisplayMetrics()
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val display = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            @Suppress("DEPRECATION")
            display?.getRealMetrics(metrics)

            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels
            val screenDensity = metrics.densityDpi

            // Create ImageReader
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            
            imageReader?.setOnImageAvailableListener({ reader ->
                // Always acquire and close to keep the buffer free; only process when requested.
                val image: Image? = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                image?.use {
                    if (!captureRequested) return@use
                    captureRequested = false

                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * screenWidth

                    val bitmap = Bitmap.createBitmap(
                        screenWidth + rowPadding / pixelStride,
                        screenHeight,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)

                    val finalBitmap = if (rowPadding > 0) {
                        Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                    } else {
                        bitmap
                    }

                    _screenCaptureFlow.tryEmit(finalBitmap)
                }
            }, null)

            virtualDisplay = mp.createVirtualDisplay(
                "ScreenCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )
        }
        // Surface stays attached; captureRequested=true is enough to capture the next frame.
        return true
    }

    fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        isCallbackRegistered = false
    }
}