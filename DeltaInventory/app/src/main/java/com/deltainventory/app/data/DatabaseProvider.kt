package com.deltainventory.app.data

import android.content.Context
import androidx.room.DatabaseConfiguration
import androidx.room.InvalidationTracker
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import java.io.File

object DatabaseProvider {
    @Volatile private var instance: DeltaDatabase? = null

    fun get(context: Context): DeltaDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(
            context.applicationContext,
            DeltaDatabase::class.java,
            DeltaDatabase.DB_NAME
        )
            .fallbackToDestructiveMigration() // 版本升级时重建；正式迁移见 docs/database.md
            .build()
            .also { instance = it }
    }

    fun close() { instance?.close(); instance = null }

    /** 备份数据库到指定文件（含 WAL checkpoint） */
    fun backup(context: Context, dest: File) {
        val db = get(context)
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToNext() }
        val src = File(context.applicationContext.getDatabasePath(DeltaDatabase.DB_NAME).absolutePath)
        src.copyTo(dest, overwrite = true)
    }

    /** 从备份恢复 */
    fun restore(context: Context, from: File) {
        close()
        val dest = context.applicationContext.getDatabasePath(DeltaDatabase.DB_NAME)
        from.copyTo(dest, overwrite = true)
    }
}
