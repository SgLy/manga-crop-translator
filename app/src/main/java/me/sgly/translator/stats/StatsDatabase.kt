package me.sgly.translator.stats

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** One translation the user asked for, however many rounds of inference it took. */
@Entity(tableName = "translation")
data class Translation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    /** Words saved from this translation with 「记一下」; filled in after the fact. */
    val recordedWordCount: Int = 0,
    val recordedLoanwordCount: Int = 0,
)

@Entity(
    tableName = "inference_round",
    foreignKeys = [
        ForeignKey(
            entity = Translation::class,
            parentColumns = ["id"],
            childColumns = ["translationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("translationId")],
)
data class InferenceRound(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val translationId: Long,
    /**
     * Name of the TransitionReason that started this round. Text rather than the enum: Room's
     * built-in converter throws on a name it no longer knows, so renaming a constant would make
     * every round recorded before the rename unreadable.
     */
    val reason: String,
)

@Entity(
    tableName = "inference_request",
    foreignKeys = [
        ForeignKey(
            entity = InferenceRound::class,
            parentColumns = ["id"],
            childColumns = ["roundId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("roundId")],
)
data class InferenceRequest(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val roundId: Long,
    val createdAt: Long,
    /** Name of the RequestKind, text for the same reason as [InferenceRound.reason]. */
    val kind: String,
    val state: String,
    val model: String,
    val thinkingEffort: String,
    val inputTokens: Int,
    val cachedInputTokens: Int,
    val outputTokens: Int,
    val reasoningTokens: Int,
    val ttftMillis: Long,
    val elapsedMillis: Long,
)

/** Counts cover every attempt; the token and time sums cover the successful requests only. */
data class StatsTotals(
    val translations: Long,
    val successfulRounds: Long,
    val rounds: Long,
    val requests: Long,
    val recordedWordCount: Long,
    val recordedLoanwordCount: Long,
    val inputTokens: Long,
    val cachedInputTokens: Long,
    val outputTokens: Long,
    val reasoningTokens: Long,
    val totalTtftMillis: Long,
    val elapsedMillis: Long,
)

/** One day of the activity grid: translations that produced a result that day. [day] is local, "YYYY-MM-DD". */
data class DailyRuns(
    val day: String,
    val runs: Int,
)

/** Requests of one [RequestKind]. [kind] is the enum's name. */
data class KindCount(
    val kind: String,
    val count: Long,
)

@Dao
interface StatsDao {
    @Insert
    suspend fun insertTranslation(translation: Translation): Long

    @Insert
    suspend fun insertRoundRow(round: InferenceRound): Long

    @Insert
    suspend fun insertRequests(requests: List<InferenceRequest>)

    @Query("UPDATE translation SET recordedWordCount = :words, recordedLoanwordCount = :loanwords WHERE id = :translationId")
    suspend fun setRecordedWords(
        translationId: Long,
        words: Int,
        loanwords: Int,
    )

    @Query(
        """
        SELECT (SELECT COUNT(*) FROM translation) AS translations,
               (SELECT COUNT(*) FROM inference_round r
                 WHERE NOT EXISTS (SELECT 1 FROM inference_request q
                                    WHERE q.roundId = r.id AND q.state <> :success)) AS successfulRounds,
               (SELECT COUNT(*) FROM inference_round) AS rounds,
               (SELECT COUNT(*) FROM inference_request) AS requests,
               (SELECT COALESCE(SUM(recordedWordCount), 0) FROM translation) AS recordedWordCount,
               (SELECT COALESCE(SUM(recordedLoanwordCount), 0) FROM translation) AS recordedLoanwordCount,
               (SELECT COALESCE(SUM(inputTokens), 0) FROM inference_request WHERE state = :success) AS inputTokens,
               (SELECT COALESCE(SUM(cachedInputTokens), 0) FROM inference_request WHERE state = :success) AS cachedInputTokens,
               (SELECT COALESCE(SUM(outputTokens), 0) FROM inference_request WHERE state = :success) AS outputTokens,
               (SELECT COALESCE(SUM(reasoningTokens), 0) FROM inference_request WHERE state = :success) AS reasoningTokens,
               (SELECT COALESCE(SUM(ttftMillis), 0) FROM inference_request WHERE state = :success) AS totalTtftMillis,
               (SELECT COALESCE(SUM(elapsedMillis), 0) FROM inference_request WHERE state = :success) AS elapsedMillis
        """,
    )
    fun totals(success: String): Flow<StatsTotals?>

    @Query("SELECT kind, COUNT(*) AS count FROM inference_request GROUP BY kind")
    fun countsByKind(): Flow<List<KindCount>>

    @Query(
        """
        SELECT DATE(t.createdAt / 1000, 'unixepoch', 'localtime') AS day, COUNT(*) AS runs
        FROM translation t
        WHERE EXISTS (SELECT 1 FROM inference_round r
                       WHERE r.translationId = t.id
                         AND NOT EXISTS (SELECT 1 FROM inference_request q
                                          WHERE q.roundId = r.id AND q.state <> :success))
        GROUP BY day
        """,
    )
    fun dailyRuns(success: String): Flow<List<DailyRuns>>
}

/**
 * Statistics live in their own database: they never join against the vocabulary tables, so the two
 * schemas can move independently.
 */
@Database(entities = [Translation::class, InferenceRound::class, InferenceRequest::class], version = 6, exportSchema = false)
abstract class StatsDatabase : RoomDatabase() {
    abstract fun statsDao(): StatsDao

    companion object {
        @Volatile
        private var instance: StatsDatabase? = null

        fun get(context: Context): StatsDatabase =
            instance ?: synchronized(this) {
                instance ?: Room
                    .databaseBuilder(
                        context.applicationContext,
                        StatsDatabase::class.java,
                        "stats.db",
                    ).addMigrations(*StatsMigrations.all)
                    .build()
                    .also { instance = it }
            }
    }
}
