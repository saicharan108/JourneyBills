package com.journeybills.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [FriendBalanceEntity::class, RecentActivityEntity::class, TripEntity::class, ExpenseEntity::class, TagEntity::class], version = 9, exportSchema = false)
abstract class JourneyDatabase : RoomDatabase() {
    abstract fun dao(): JourneyDao

    companion object {
        @Volatile
        private var INSTANCE: JourneyDatabase? = null

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `tags` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `emoji` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("ALTER TABLE `expenses` ADD COLUMN `tagId` TEXT DEFAULT NULL")
            }
        }

        fun getDatabase(context: Context): JourneyDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    JourneyDatabase::class.java,
                    "journey_database_plain"
                )
                .addMigrations(MIGRATION_8_9)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                
                INSTANCE = instance
                instance
            }
        }
    }
}
