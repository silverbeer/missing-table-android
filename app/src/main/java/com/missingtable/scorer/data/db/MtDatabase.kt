package com.missingtable.scorer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [PendingAction::class], version = 1, exportSchema = false)
abstract class MtDatabase : RoomDatabase() {
    abstract fun pendingActionDao(): PendingActionDao

    companion object {
        fun build(context: Context): MtDatabase =
            Room.databaseBuilder(context, MtDatabase::class.java, "mt-scorer.db").build()
    }
}
