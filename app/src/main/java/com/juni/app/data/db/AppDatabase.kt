package com.juni.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        VaultDocEntity::class,
        VaultMetaEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun vaultIndex(): VaultIndexDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "juni.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        /**
         * Adds the FTS4 vault search index. Pure additive change — no existing
         * tables are touched, so conversations and messages survive untouched.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `vault_docs` USING FTS4(" +
                        "`path` TEXT NOT NULL, " +
                        "`content` TEXT NOT NULL, " +
                        "`lastModified` INTEGER NOT NULL, " +
                        "tokenize=unicode61, " +
                        "notindexed=`lastModified`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `vault_meta` (" +
                        "`id` INTEGER NOT NULL, " +
                        "`vaultUri` TEXT NOT NULL, " +
                        "`lastFullSyncAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
            }
        }
    }
}
