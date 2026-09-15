package me.sgly.translator

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

/** A word the user noted down. One row per lemma, no matter how often it comes back. */
@Entity(tableName = "vocabulary_word", indices = [Index(value = ["lemma"], unique = true)])
data class VocabularyWord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lemma: String,
    /** Etymology as reported by the model, e.g. "karte[德]"; empty when the word is not a loanword. */
    val origin: String
)

/** One sighting of a [VocabularyWord], in the sentence that produced it. */
@Entity(
    tableName = "vocabulary_entry",
    foreignKeys = [
        ForeignKey(
            entity = VocabularyWord::class,
            parentColumns = ["id"],
            childColumns = ["wordId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("wordId")]
)
data class VocabularyEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val wordId: Long,
    /** The surface form this sighting actually used, which may differ from the lemma. */
    val surface: String,
    val definition: String,
    val sentence: String,
    val translation: String,
    val createdAt: Long
)

/**
 * Everything one 「记一下」 captures about a word: how it appeared here, what it meant here, and the
 * sentence it came from.
 *
 * Difficulty is deliberately left out: it only ranks words against the other words of a single
 * translation, so once that sentence is gone the number has nothing left to mean.
 */
data class VocabularyOccurrence(
    val lemma: String,
    val origin: String,
    val surface: String,
    val definition: String,
    val sentence: String,
    val translation: String
)

@Dao
abstract class VocabularyDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    internal abstract suspend fun insertWordIfAbsent(word: VocabularyWord): Long

    @Query("SELECT id FROM vocabulary_word WHERE lemma = :lemma")
    internal abstract suspend fun wordIdOf(lemma: String): Long

    @Insert
    internal abstract suspend fun insertEntry(entry: VocabularyEntry)

    /**
     * Records every occurrence in one transaction. A lemma met for the first time gets a headword
     * row carved out of the first occurrence; later sightings reuse that row and keep the origin
     * recorded back then.
     */
    @Transaction
    open suspend fun record(occurrences: List<VocabularyOccurrence>) {
        for (occurrence in occurrences) {
            val inserted = insertWordIfAbsent(
                VocabularyWord(lemma = occurrence.lemma, origin = occurrence.origin)
            )
            val wordId = if (inserted != -1L) inserted else wordIdOf(occurrence.lemma)
            insertEntry(
                VocabularyEntry(
                    wordId = wordId,
                    surface = occurrence.surface,
                    definition = occurrence.definition,
                    sentence = occurrence.sentence,
                    translation = occurrence.translation,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }
}

@Database(
    entities = [VocabularyWord::class, VocabularyEntry::class],
    version = 1,
    exportSchema = false
)
abstract class VocabularyDatabase : RoomDatabase() {

    abstract fun vocabularyDao(): VocabularyDao

    companion object {
        @Volatile
        private var instance: VocabularyDatabase? = null

        fun get(context: Context): VocabularyDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    VocabularyDatabase::class.java,
                    "vocabulary.db"
                ).build().also { instance = it }
            }
    }
}
