package com.itantra.voicemesh.app.persistence

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Durable backing for :core-reliability's outbox contract (spec §7/§10: "local
 * outbox/store-and-forward" must survive an app restart, not just a process
 * lifetime). Schema is the flattened form of [com.itantra.voicemesh.messaging.Message]
 * plus the retry bookkeeping [com.itantra.voicemesh.reliability.OutboxEntry] needs.
 */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val messageId: Long,
    val sourceNodeId: Int,
    val destinationNodeId: Int,
    val languageWireId: Byte,
    val priorityWireId: Byte,
    val originTimestampMillis: Long,
    val text: String,
    val enqueuedAtMillis: Long,
    val lastSentAtMillis: Long,
    val attempts: Int,
)

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox")
    fun getAll(): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE messageId = :messageId LIMIT 1")
    fun getById(messageId: Long): OutboxEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: OutboxEntity)

    @Query("DELETE FROM outbox WHERE messageId = :messageId")
    fun deleteById(messageId: Long)
}

@Database(entities = [OutboxEntity::class], version = 1, exportSchema = false)
abstract class VoiceMeshDatabase : RoomDatabase() {
    abstract fun outboxDao(): OutboxDao

    companion object {
        @Volatile private var instance: VoiceMeshDatabase? = null

        fun getInstance(context: Context): VoiceMeshDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context, VoiceMeshDatabase::class.java, "voicemesh.db")
                    // TODO: MeshSession currently drives ReliabilityLayer.tick() off a
                    // main-thread Handler loop, so DAO calls happen on the main thread.
                    // Fine at prototype message volumes; move tick() to a background
                    // thread and drop this before relying on it at scale.
                    .allowMainThreadQueries()
                    .build()
                    .also { instance = it }
            }
    }
}
