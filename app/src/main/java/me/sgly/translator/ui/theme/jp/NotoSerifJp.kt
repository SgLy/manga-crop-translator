package me.sgly.translator.ui.theme.jp

import android.content.Context
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.sgly.translator.logs.AppLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Noto Serif JP, downloaded on request instead of bundled: 12 MB is a lot to ship for one face, and
 * the system already has a Japanese serif. The file is used as it arrives — the mirrors publish no
 * digest, and the URL deliberately carries no version so that the newest one is fetched — so only the
 * cheapest guard is kept: a response that cannot possibly be this font is rejected.
 *
 * Mirrors, both verified to serve a real 11.9 MB OTF:
 * - GitHub, which needs the raw host; `github.com/.../blob/...` answers with an HTML page.
 * - jsDelivr, which resolves the branch to the same bytes and caches them for 12 hours. Its range
 *   requests are answered compressed, which is why nothing here probes partial content.
 */
object NotoSerifJp {
    enum class Mirror(
        val url: String,
    ) {
        GitHub(
            "https://raw.githubusercontent.com/notofonts/noto-cjk/main/Serif/Variable/OTF/Subset/NotoSerifJP-VF.otf",
        ),
        JsDelivr(
            "https://cdn.jsdelivr.net/gh/notofonts/noto-cjk@main/Serif/Variable/OTF/Subset/NotoSerifJP-VF.otf",
        ),
    }

    sealed interface State {
        data object Unknown : State

        data object Absent : State

        /** [total] is the length the server announced, or -1 when it announced none. */
        data class Downloading(
            val received: Long,
            val total: Long,
        ) : State

        data class Ready(
            val size: Long,
        ) : State

        data class Failed(
            val reason: String,
        ) : State
    }

    private const val TAG = "NotoSerifJp"
    private const val FILE_NAME = "NotoSerifJP-VF.otf"
    private const val MIN_BYTES = 1L shl 20
    private const val BUFFER_SIZE = 64 * 1024

    /** The `wght` range of this file; its named instances sit at the nominal weights. */
    private const val MIN_AXIS = 200
    private const val MAX_AXIS = 900

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    private val families = ConcurrentHashMap<Int, FontFamily>()
    private val inFlight = AtomicBoolean(false)

    private val _state = MutableStateFlow<State>(State.Unknown)
    val state: StateFlow<State> = _state.asStateFlow()

    private var readyFile: File? = null

    /** Looks for an already downloaded file; idempotent, meant for app start. */
    fun refresh(context: Context) {
        if (_state.value != State.Unknown) return
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (file.isFile && file.length() >= MIN_BYTES) {
            adopt(file)
        } else {
            _state.value = State.Absent
        }
    }

    fun download(
        context: Context,
        mirror: Mirror,
    ) {
        if (!inFlight.compareAndSet(false, true)) return
        val target = File(context.applicationContext.filesDir, FILE_NAME)
        _state.value = State.Downloading(received = 0, total = -1)
        scope.launch {
            val part = File(target.parentFile, "$FILE_NAME.part")
            try {
                val received = fetch(mirror.url, part)
                if (received < MIN_BYTES) error("response too small: $received bytes")
                if (!part.renameTo(target)) error("could not move the font into place")
                adopt(target)
                AppLog.i(TAG, "downloaded from ${mirror.name}: ${target.length()} bytes")
            } catch (throwable: Throwable) {
                part.delete()
                AppLog.e(TAG, "download from ${mirror.name} failed", throwable)
                _state.value = State.Failed(throwable.message ?: throwable::class.java.simpleName)
            } finally {
                inFlight.set(false)
            }
        }
    }

    fun delete(context: Context) {
        File(context.applicationContext.filesDir, FILE_NAME).delete()
        readyFile = null
        families.clear()
        _state.value = State.Absent
    }

    /** The file at [weight], or null when it is not downloaded yet. */
    fun fontFamily(weight: Int): FontFamily? {
        val file = readyFile ?: return null
        val axis = weight.coerceIn(MIN_AXIS, MAX_AXIS)
        return families.getOrPut(axis) {
            val font =
                Font(
                    file = file,
                    weight = FontWeight(axis),
                    variationSettings = FontVariation.Settings(FontVariation.weight(axis)),
                )
            FontFamily(font)
        }
    }

    private fun adopt(file: File) {
        readyFile = file
        _state.value = State.Ready(file.length())
    }

    private fun fetch(
        url: String,
        target: File,
    ): Long {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val total = response.body.contentLength()
            var received = 0L
            response.body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        received += read
                        output.write(buffer, 0, read)
                        _state.value = State.Downloading(received = received, total = total)
                    }
                }
            }
            return received
        }
    }
}
