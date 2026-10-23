package me.sgly.translator.settings

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.sgly.translator.R

@Composable
internal fun LanguageSection() {
    val context = LocalContext.current
    val localeManager = remember(context) { context.getSystemService(LocaleManager::class.java) }
    var choosing by remember { mutableStateOf(false) }

    val language = localeManager.currentLanguage()
    val label =
        when (language) {
            "zh" -> R.string.language_zh_hans
            "en" -> R.string.language_english
            else -> R.string.language_follow_system
        }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { choosing = true }
                .padding(vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.language_section),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            painter = painterResource(R.drawable.ic_arrow_back),
            contentDescription = null,
            modifier = Modifier.rotate(180f),
        )
    }

    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.language_section)) },
            text = {
                Column {
                    LanguageOption(
                        label = stringResource(R.string.language_follow_system),
                        selected = language == null,
                        onClick = {
                            localeManager.applicationLocales = LocaleList.getEmptyLocaleList()
                            choosing = false
                        },
                    )
                    LanguageOption(
                        label = stringResource(R.string.language_english),
                        selected = language == "en",
                        onClick = {
                            localeManager.applicationLocales = LocaleList.forLanguageTags("en")
                            choosing = false
                        },
                    )
                    LanguageOption(
                        label = stringResource(R.string.language_zh_hans),
                        selected = language == "zh",
                        onClick = {
                            localeManager.applicationLocales = LocaleList.forLanguageTags("zh-Hans")
                            choosing = false
                        },
                    )
                }
            },
            confirmButton = {},
        )
    }
}

@Composable
private fun LanguageOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

private fun LocaleManager.currentLanguage(): String? {
    val locales = applicationLocales
    return if (locales.isEmpty) null else locales.get(0).language
}
