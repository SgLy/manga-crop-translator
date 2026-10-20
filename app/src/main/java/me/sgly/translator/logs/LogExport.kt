package me.sgly.translator.logs

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.sgly.translator.R
import me.sgly.translator.api.OcrResult
import me.sgly.translator.api.RequestStats
import me.sgly.translator.api.TranslationResult
import me.sgly.translator.state.TranslatorState
import me.sgly.translator.ui.theme.jp.JapaneseFontPreferences
import me.sgly.translator.ui.theme.jp.MiuiFontWeight
import me.sgly.translator.ui.theme.jp.NotoSerifJp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogExport {
    @Serializable
    private data class AppInfo(
        val name: String,
        val versionName: String,
        val versionCode: Long,
    )

    @Serializable
    private data class DeviceInfo(
        val model: String,
        val androidSdk: Int,
    )

    @Serializable
    private data class Entry(
        val timeMs: Long,
        val level: String,
        val tag: String,
        val message: String,
    )

    @Serializable
    private data class BitmapSize(
        val width: Int,
        val height: Int,
    )

    @Serializable
    private data class StreamGridInfo(
        val rows: Int,
        val filledCells: Int,
    )

    @Serializable
    private data class RequestInfo(
        val kind: String,
        val state: String,
        val model: String,
        val input: Int,
        val output: Int,
        val cached: Int,
        val reasoning: Int,
        val total: Int,
        val startedAtMillis: Long,
        val ttftMillis: Long,
        val elapsedMillis: Long,
    )

    @Serializable
    private data class TranslatorInfo(
        val phase: String,
        val sheetOnScreen: Boolean,
        val firstTokenReceived: Boolean,
        val runReason: String?,
        val thinkingEffort: String,
        val thinkingLevelIndex: Int,
        val errorText: String,
        val streamGrid: StreamGridInfo,
        val capturedBitmap: BitmapSize?,
        val croppedBitmap: BitmapSize?,
        val translationId: Long?,
        val roundId: Long?,
        val phaseBeforeLogs: String?,
        val hasLastTranslation: Boolean,
        val runRequests: List<RequestInfo>,
        val reasoning: String,
        val ocrResult: OcrResult?,
        val translationResult: TranslationResult?,
    )

    @Serializable
    private data class DownloadedFontInfo(
        val state: String,
        val sizeBytes: Long? = null,
        val receivedBytes: Long? = null,
        val totalBytes: Long? = null,
        val reason: String? = null,
    )

    @Serializable
    private data class FontsInfo(
        val miuiAdaptation: Boolean,
        val variableFont: Boolean,
        val miuiWeightScale: Int?,
        val downloadedFont: DownloadedFontInfo,
    )

    @Serializable
    private data class AppLogInfo(
        val forwardToLogcat: Boolean,
        val entryCount: Int,
    )

    @Serializable
    private data class State(
        val translator: TranslatorInfo,
        val fonts: FontsInfo,
        val appLog: AppLogInfo,
    )

    @Serializable
    private data class Payload(
        val app: AppInfo,
        val device: DeviceInfo,
        val exportedAtMs: Long,
        val state: State,
        val entries: List<Entry>,
    )

    private val json = Json { prettyPrint = true }

    suspend fun share(
        activity: Activity,
        entries: List<AppLog.Line>,
    ) {
        val file = withContext(Dispatchers.IO) { write(activity, entries) }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        val chooser = Intent.createChooser(send, activity.getString(R.string.share_logs_title))
        withContext(Dispatchers.Main) { activity.startActivity(chooser) }
    }

    private fun write(
        activity: Activity,
        entries: List<AppLog.Line>,
    ): File {
        val dir = File(activity.cacheDir, "logs")
        dir.mkdirs()
        // Only the newest export is kept so the cache never piles up.
        dir.listFiles()?.forEach { it.delete() }
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "logs-$timestamp.json")
        val packageInfo =
            activity.packageManager.getPackageInfo(
                activity.packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        // Font-state flows are only live once observed; make sure the snapshot does not report stale defaults.
        JapaneseFontPreferences.observe(activity)
        MiuiFontWeight.observe(activity)
        val payload =
            Payload(
                app =
                    AppInfo(
                        name = activity.getString(R.string.app_name),
                        versionName = packageInfo.versionName.orEmpty(),
                        versionCode = packageInfo.longVersionCode,
                    ),
                device = DeviceInfo(model = Build.MODEL, androidSdk = Build.VERSION.SDK_INT),
                exportedAtMs = System.currentTimeMillis(),
                state = snapshot(entries.size),
                entries = entries.map { Entry(it.timeMs, it.level.name, it.tag, it.message) },
            )
        file.writeText(json.encodeToString(payload))
        return file
    }

    private fun snapshot(entryCount: Int): State =
        State(
            translator =
                TranslatorInfo(
                    phase = TranslatorState.phase.value.name,
                    sheetOnScreen = TranslatorState.sheetOnScreen.value,
                    firstTokenReceived = TranslatorState.firstTokenReceived.value,
                    runReason = TranslatorState.runReason?.name,
                    thinkingEffort = TranslatorState.currentThinkingEffort(),
                    thinkingLevelIndex = TranslatorState.thinkingLevelIndex.value,
                    errorText = TranslatorState.errorText.value,
                    streamGrid = streamGridInfo(),
                    capturedBitmap = bitmapSize(TranslatorState.capturedBitmap),
                    croppedBitmap = bitmapSize(TranslatorState.croppedBitmap),
                    translationId = TranslatorState.translationId,
                    roundId = TranslatorState.roundId,
                    phaseBeforeLogs = TranslatorState.phaseBeforeLogs?.name,
                    hasLastTranslation = TranslatorState.lastTranslation != null,
                    runRequests = TranslatorState.runRequests.value.map { it.toInfo() },
                    reasoning = TranslatorState.reasoning.value,
                    ocrResult = TranslatorState.ocrResult.value,
                    translationResult = TranslatorState.translationResult.value,
                ),
            fonts =
                FontsInfo(
                    miuiAdaptation = JapaneseFontPreferences.flags.value.miuiAdaptation,
                    variableFont = JapaneseFontPreferences.flags.value.variableFont,
                    miuiWeightScale = MiuiFontWeight.scale.value,
                    downloadedFont = NotoSerifJp.state.value.toInfo(),
                ),
            appLog = AppLogInfo(forwardToLogcat = AppLog.forwardToLogcat, entryCount = entryCount),
        )

    // Only the dimensions are exported; the bitmaps themselves are far too large to embed.
    private fun bitmapSize(bitmap: Bitmap?): BitmapSize? = bitmap?.let { BitmapSize(it.width, it.height) }

    private fun streamGridInfo(): StreamGridInfo {
        val fill = TranslatorState.streamGrid.fill()
        return StreamGridInfo(rows = fill.rows, filledCells = fill.filledCells)
    }

    private fun RequestStats.toInfo(): RequestInfo =
        RequestInfo(
            kind = kind.name,
            state = state.name,
            model = model,
            input = usage.input,
            output = usage.output,
            cached = usage.cached,
            reasoning = usage.reasoning,
            total = usage.total,
            startedAtMillis = startedAtMillis,
            ttftMillis = ttftMillis,
            elapsedMillis = elapsedMillis,
        )

    private fun NotoSerifJp.State.toInfo(): DownloadedFontInfo =
        when (this) {
            NotoSerifJp.State.Unknown -> {
                DownloadedFontInfo(state = "Unknown")
            }

            NotoSerifJp.State.Absent -> {
                DownloadedFontInfo(state = "Absent")
            }

            is NotoSerifJp.State.Downloading -> {
                DownloadedFontInfo(state = "Downloading", receivedBytes = received, totalBytes = total)
            }

            is NotoSerifJp.State.Ready -> {
                DownloadedFontInfo(state = "Ready", sizeBytes = size)
            }

            is NotoSerifJp.State.Failed -> {
                DownloadedFontInfo(state = "Failed", reason = reason)
            }
        }
}
