package me.sgly.translator.vocabulary

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import me.sgly.translator.ui.theme.CropTranslatorTheme

class VocabularyDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wordId = intent.getLongExtra(EXTRA_WORD_ID, -1L)
        val dao = VocabularyDatabase.get(this).vocabularyDao()

        setContent {
            CropTranslatorTheme {
                VocabularyDetailScreen(
                    wordId = wordId,
                    dao = dao,
                    onFinish = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_WORD_ID = "wordId"
    }
}
