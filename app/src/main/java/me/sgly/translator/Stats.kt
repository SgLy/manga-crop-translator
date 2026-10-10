package me.sgly.translator

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
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    val createdAt: Long,
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
    /** Name of the RequestKind, text for the same reason as [InferenceRound.reason]. */
    val kind: String,
    val model: String,
    val thinkingEffort: String,
    val inputTokens: Int,
    val cachedInputTokens: Int,
    val outputTokens: Int,
    val reasoningTokens: Int,
    val ttftMillis: Long,
    val elapsedMillis: Long,
)

/** The sums of everything recorded, each at its own level: translations, rounds, requests, then tokens. */
data class StatsTotals(
    val translations: Long,
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

/** One day of the activity grid: translations started that day. [day] is local, "YYYY-MM-DD". */
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

    @Transaction
    suspend fun insertRound(
        round: InferenceRound,
        requests: List<InferenceRequest>,
    ): Long {
        val roundId = insertRoundRow(round)
        insertRequests(requests.map { it.copy(roundId = roundId) })
        return roundId
    }

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
               (SELECT COUNT(*) FROM inference_round) AS rounds,
               (SELECT COUNT(*) FROM inference_request) AS requests,
               (SELECT COALESCE(SUM(recordedWordCount), 0) FROM translation) AS recordedWordCount,
               (SELECT COALESCE(SUM(recordedLoanwordCount), 0) FROM translation) AS recordedLoanwordCount,
               (SELECT COALESCE(SUM(inputTokens), 0) FROM inference_request) AS inputTokens,
               (SELECT COALESCE(SUM(cachedInputTokens), 0) FROM inference_request) AS cachedInputTokens,
               (SELECT COALESCE(SUM(outputTokens), 0) FROM inference_request) AS outputTokens,
               (SELECT COALESCE(SUM(reasoningTokens), 0) FROM inference_request) AS reasoningTokens,
               (SELECT COALESCE(SUM(ttftMillis), 0) FROM inference_request) AS totalTtftMillis,
               (SELECT COALESCE(SUM(elapsedMillis), 0) FROM inference_request) AS elapsedMillis
        """,
    )
    fun totals(): Flow<StatsTotals?>

    @Query("SELECT kind, COUNT(*) AS count FROM inference_request GROUP BY kind")
    fun countsByKind(): Flow<List<KindCount>>

    @Query(
        """
        SELECT DATE(createdAt / 1000, 'unixepoch', 'localtime') AS day, COUNT(*) AS runs
        FROM translation
        GROUP BY day
        """,
    )
    fun dailyRuns(): Flow<List<DailyRuns>>
}

/**
 * Statistics live in their own database: they never join against the vocabulary tables, so the two
 * schemas can move independently.
 */
@Database(entities = [Translation::class, InferenceRound::class, InferenceRequest::class], version = 3, exportSchema = false)
abstract class StatsDatabase : RoomDatabase() {
    abstract fun statsDao(): StatsDao

    companion object {
        /** Drops the single table of version 1 and creates the split schema. Version 1 rows are lost. */
        private val Migration1To2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("DROP TABLE IF EXISTS translation_run")
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `translation` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`createdAt` INTEGER NOT NULL, `recordedWordCount` INTEGER NOT NULL, " +
                            "`recordedLoanwordCount` INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `inference_round` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`translationId` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `reason` TEXT NOT NULL, " +
                            "`model` TEXT NOT NULL, `thinkingEffort` TEXT NOT NULL, `visionRequests` INTEGER NOT NULL, " +
                            "`parseRequests` INTEGER NOT NULL, `inputTokens` INTEGER NOT NULL, " +
                            "`cachedInputTokens` INTEGER NOT NULL, `outputTokens` INTEGER NOT NULL, " +
                            "`reasoningTokens` INTEGER NOT NULL, `totalTtftMillis` INTEGER NOT NULL, " +
                            "`elapsedMillis` INTEGER NOT NULL, " +
                            "FOREIGN KEY(`translationId`) REFERENCES `translation`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_inference_round_translationId` ON `inference_round` (`translationId`)",
                    )
                }
            }

        /** Rounds lose their round-level columns; the rows that carried them go with the dropped table. */
        private val Migration2To3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("DROP TABLE IF EXISTS inference_round")
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `inference_round` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`translationId` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `reason` TEXT NOT NULL, " +
                            "FOREIGN KEY(`translationId`) REFERENCES `translation`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_inference_round_translationId` ON `inference_round` (`translationId`)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `inference_request` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`roundId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `model` TEXT NOT NULL, " +
                            "`thinkingEffort` TEXT NOT NULL, `inputTokens` INTEGER NOT NULL, " +
                            "`cachedInputTokens` INTEGER NOT NULL, `outputTokens` INTEGER NOT NULL, " +
                            "`reasoningTokens` INTEGER NOT NULL, `ttftMillis` INTEGER NOT NULL, " +
                            "`elapsedMillis` INTEGER NOT NULL, " +
                            "FOREIGN KEY(`roundId`) REFERENCES `inference_round`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_inference_request_roundId` ON `inference_request` (`roundId`)",
                    )
                }
            }

        @Volatile
        private var instance: StatsDatabase? = null

        fun get(context: Context): StatsDatabase =
            instance ?: synchronized(this) {
                instance ?: Room
                    .databaseBuilder(
                        context.applicationContext,
                        StatsDatabase::class.java,
                        "stats.db",
                    ).addMigrations(Migration1To2, Migration2To3)
                    .build()
                    .also { instance = it }
            }
    }
}
