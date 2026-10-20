package me.sgly.translator.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.sgly.translator.R

@Composable
internal fun ModelSection(settingsManager: SettingsManager) {
    var useVisualModelForTranslation by remember { mutableStateOf(settingsManager.useVisualModelForTranslation) }

    SectionTitle(stringResource(R.string.image_recognition_model))
    Text(
        text = stringResource(R.string.visual_model_hint),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(12.dp))
    VisualModelFields(settingsManager)

    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

    SwitchRow(
        label = stringResource(R.string.use_visual_model_for_translation),
        checked = useVisualModelForTranslation,
        onCheckedChange = {
            useVisualModelForTranslation = it
            settingsManager.useVisualModelForTranslation = it
        },
    )

    if (!useVisualModelForTranslation) {
        Spacer(modifier = Modifier.height(12.dp))
        SectionTitle(stringResource(R.string.translation_model))
        Spacer(modifier = Modifier.height(12.dp))
        TranslateModelFields(settingsManager)
    }
}

@Composable
private fun VisualModelFields(settingsManager: SettingsManager) {
    var visualModelApiBase by remember { mutableStateOf(settingsManager.visualModelApiBase) }
    var visualModelApiKey by remember { mutableStateOf(settingsManager.visualModelApiKey) }
    var visualModel by remember { mutableStateOf(settingsManager.visualModel) }

    ModelFields(
        apiBase = visualModelApiBase,
        apiKey = visualModelApiKey,
        model = visualModel,
        onApiBaseChange = {
            visualModelApiBase = it
            settingsManager.visualModelApiBase = it
        },
        onApiKeyChange = {
            visualModelApiKey = it
            settingsManager.visualModelApiKey = it
        },
        onModelChange = {
            visualModel = it
            settingsManager.visualModel = it
        },
    )
}

@Composable
private fun TranslateModelFields(settingsManager: SettingsManager) {
    var translateModelApiBase by remember { mutableStateOf(settingsManager.translateModelApiBase) }
    var translateModelApiKey by remember { mutableStateOf(settingsManager.translateModelApiKey) }
    var translateModel by remember { mutableStateOf(settingsManager.translateModel) }

    ModelFields(
        apiBase = translateModelApiBase,
        apiKey = translateModelApiKey,
        model = translateModel,
        onApiBaseChange = {
            translateModelApiBase = it
            settingsManager.translateModelApiBase = it
        },
        onApiKeyChange = {
            translateModelApiKey = it
            settingsManager.translateModelApiKey = it
        },
        onModelChange = {
            translateModel = it
            settingsManager.translateModel = it
        },
    )
}

@Composable
private fun ModelFields(
    apiBase: String,
    apiKey: String,
    model: String,
    onApiBaseChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsField(
            label = stringResource(R.string.label_base_url),
            value = apiBase,
            onValueChange = onApiBaseChange,
        )
        SettingsField(
            label = stringResource(R.string.label_api_key),
            value = apiKey,
            password = true,
            onValueChange = onApiKeyChange,
        )
        SettingsField(
            label = stringResource(R.string.label_model),
            value = model,
            onValueChange = onModelChange,
        )
    }
}
