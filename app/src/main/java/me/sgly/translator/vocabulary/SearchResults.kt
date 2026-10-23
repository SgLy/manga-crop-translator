package me.sgly.translator.vocabulary

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import me.sgly.translator.R

@Composable
internal fun SearchResults(keyword: String) {
    val context = LocalContext.current
    val summaries by
        remember(keyword) {
            VocabularyDatabase.get(context).vocabularyDao().search(keyword)
        }.collectAsState(initial = null)

    summaries?.let { list ->
        if (list.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.search_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                VocabularyListScreen(list)
            }
        }
    }
}
