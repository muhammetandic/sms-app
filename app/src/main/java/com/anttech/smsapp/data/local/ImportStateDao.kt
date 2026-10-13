package com.anttech.smsapp.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Reads and writes the provider import's high-water mark.
 *
 * A single fixed row, so [save] is an overwrite rather than an insert that has
 * to check for an existing row first.
 */
@Dao
interface ImportStateDao {

    /** The mark, or null before the first import has completed. */
    @Query("SELECT provider_high_water FROM import_state WHERE id = 0")
    suspend fun providerHighWater(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(state: ImportStateEntity)
}
