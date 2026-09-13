package me.sgly.translator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.sgly.translator.ui.theme.CropTranslatorTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Full-screen viewer for the in-memory [AppLog] ring buffer. */
class LogsActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CropTranslatorTheme {
                var lines by remember { mutableStateOf<List<AppLog.Line>>(emptyList()) }
                // Resolving lazy messages can be costly, so keep it off the main thread.
                LaunchedEffect(Unit) {
                    lines = withContext(Dispatchers.Default) { AppLog.snapshotNewestFirst() }
                }
                val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(R.string.action_logs)) },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = stringResource(R.string.cd_back)
                                    )
                                }
                            }
                        )
                    }
                ) { padding ->
                    if (lines.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.logs_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                        ) {
                            items(lines) { line ->
                                LogRow(line, timeFormat.format(Date(line.timeMs)))
                            }
                            item { Spacer(modifier = Modifier.height(24.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(line: AppLog.Line, time: String) {
    val levelColor = when (line.level) {
        AppLog.Level.Debug -> MaterialTheme.colorScheme.onSurfaceVariant
        AppLog.Level.Info -> MaterialTheme.colorScheme.primary
        AppLog.Level.Warn -> Color(0xFFB26A00)
        AppLog.Level.Error -> MaterialTheme.colorScheme.error
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = time,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = line.level.name.take(1),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = levelColor
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = line.tag,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = line.message,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
}
