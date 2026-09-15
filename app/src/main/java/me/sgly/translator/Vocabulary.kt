package me.sgly.translator

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

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

/** One card of the vocabulary list: the headword, the meanings it has carried, and how often it was recorded. */
data class VocabularySummary(
    val id: Long,
    val lemma: String,
    /** Up to three meanings joined by '、', the most frequently recorded one first. */
    val definitions: String,
    val entryCount: Int
)

/** A headword together with every sighting of it. [entries] comes back unordered; callers sort it. */
data class VocabularyWordWithEntries(
    @Embedded val word: VocabularyWord,
    @Relation(parentColumn = "id", entityColumn = "wordId")
    val entries: List<VocabularyEntry>
)

@Dao
abstract class VocabularyDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    internal abstract suspend fun insertWordIfAbsent(word: VocabularyWord): Long

    @Query("SELECT id FROM vocabulary_word WHERE lemma = :lemma")
    internal abstract suspend fun wordIdOf(lemma: String): Long?

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
            // -1 means the unique lemma index rejected the insert, so the headword row already exists.
            val wordId = if (inserted != -1L) {
                inserted
            } else {
                wordIdOf(occurrence.lemma)
                    ?: error("vocabulary_word row for '${occurrence.lemma}' vanished mid-transaction")
            }
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

    /**
     * Every headword once, most recently recorded first, so a word jumps back to the front each time
     * it is recorded again. Its meanings are the three it has carried most often, most frequent first:
     * that order survives the GROUP_CONCAT only because SQLite materialises the ordered subquery first.
     */
    @Query(
        """
        SELECT w.id AS id,
               w.lemma AS lemma,
               (SELECT GROUP_CONCAT(m.definition, '、')
                  FROM (SELECT e.definition AS definition
                          FROM vocabulary_entry e
                         WHERE e.wordId = w.id
                         GROUP BY e.definition
                         ORDER BY COUNT(*) DESC, MAX(e.createdAt) DESC
                         LIMIT 3) m) AS definitions,
               (SELECT COUNT(*) FROM vocabulary_entry e WHERE e.wordId = w.id) AS entryCount
        FROM vocabulary_word w
        ORDER BY (SELECT MAX(e.createdAt) FROM vocabulary_entry e WHERE e.wordId = w.id) DESC
        """
    )
    abstract fun summaries(): Flow<List<VocabularySummary>>

    /** The detail page of one headword, or null if the word is gone. */
    @Transaction
    @Query("SELECT * FROM vocabulary_word WHERE id = :wordId")
    abstract fun wordWithEntries(wordId: Long): Flow<VocabularyWordWithEntries?>
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
