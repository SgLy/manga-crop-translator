package me.sgly.translator.stats

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object StatsMigrations {
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

    /** Adds the request timestamp; rows already recorded inherit the time of their round. */
    private val Migration3To4 =
        object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `inference_request_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`roundId` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `kind` TEXT NOT NULL, " +
                        "`model` TEXT NOT NULL, `thinkingEffort` TEXT NOT NULL, `inputTokens` INTEGER NOT NULL, " +
                        "`cachedInputTokens` INTEGER NOT NULL, `outputTokens` INTEGER NOT NULL, " +
                        "`reasoningTokens` INTEGER NOT NULL, `ttftMillis` INTEGER NOT NULL, " +
                        "`elapsedMillis` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`roundId`) REFERENCES `inference_round`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "INSERT INTO `inference_request_new` (`id`, `roundId`, `createdAt`, `kind`, `model`, `thinkingEffort`, " +
                        "`inputTokens`, `cachedInputTokens`, `outputTokens`, `reasoningTokens`, `ttftMillis`, `elapsedMillis`) " +
                        "SELECT r.`id`, r.`roundId`, " +
                        "(SELECT w.`createdAt` FROM `inference_round` w WHERE w.`id` = r.`roundId`), " +
                        "r.`kind`, r.`model`, r.`thinkingEffort`, r.`inputTokens`, r.`cachedInputTokens`, " +
                        "r.`outputTokens`, r.`reasoningTokens`, r.`ttftMillis`, r.`elapsedMillis` " +
                        "FROM `inference_request` r",
                )
                db.execSQL("DROP TABLE `inference_request`")
                db.execSQL("ALTER TABLE `inference_request_new` RENAME TO `inference_request`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_inference_request_roundId` ON `inference_request` (`roundId`)",
                )
            }
        }

    /** Adds the request outcome; rows written before this column existed were all successes. */
    private val Migration4To5 =
        object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `inference_request_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`roundId` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `kind` TEXT NOT NULL, " +
                        "`state` TEXT NOT NULL, `model` TEXT NOT NULL, `thinkingEffort` TEXT NOT NULL, " +
                        "`inputTokens` INTEGER NOT NULL, `cachedInputTokens` INTEGER NOT NULL, " +
                        "`outputTokens` INTEGER NOT NULL, `reasoningTokens` INTEGER NOT NULL, " +
                        "`ttftMillis` INTEGER NOT NULL, `elapsedMillis` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`roundId`) REFERENCES `inference_round`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "INSERT INTO `inference_request_new` (`id`, `roundId`, `createdAt`, `kind`, `state`, `model`, " +
                        "`thinkingEffort`, `inputTokens`, `cachedInputTokens`, `outputTokens`, `reasoningTokens`, " +
                        "`ttftMillis`, `elapsedMillis`) " +
                        "SELECT `id`, `roundId`, `createdAt`, `kind`, 'Success', `model`, `thinkingEffort`, " +
                        "`inputTokens`, `cachedInputTokens`, `outputTokens`, `reasoningTokens`, `ttftMillis`, " +
                        "`elapsedMillis` FROM `inference_request`",
                )
                db.execSQL("DROP TABLE `inference_request`")
                db.execSQL("ALTER TABLE `inference_request_new` RENAME TO `inference_request`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_inference_request_roundId` ON `inference_request` (`roundId`)",
                )
            }
        }

    /** Round time follows from its requests. Dropped in place: rebuilding the table would cascade them away. */
    private val Migration5To6 =
        object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `inference_round` DROP COLUMN `createdAt`")
            }
        }

    val all: Array<Migration> =
        arrayOf(Migration1To2, Migration2To3, Migration3To4, Migration4To5, Migration5To6)
}
