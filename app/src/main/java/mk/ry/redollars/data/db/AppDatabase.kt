package mk.ry.redollars.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [MessageEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "redollars.db",
                ).addMigrations(
                    object : androidx.room.migration.Migration(1, 2) { override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {} },
                    object : androidx.room.migration.Migration(2, 3) { override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {} },
                    object : androidx.room.migration.Migration(1, 3) { override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {} }
                )
                // The message cache is disposable (rebuilt by syncNewer), so a missed
                // migration must cost a cache rebuild, never a startup crash. The
                // downgrade fallback also keeps installing an older release APK over a
                // newer one survivable.
                .fallbackToDestructiveMigration(true)
                .fallbackToDestructiveMigrationOnDowngrade(true).build().also { INSTANCE = it }
            }
    }
}
