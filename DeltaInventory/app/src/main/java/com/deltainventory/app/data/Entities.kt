package com.deltainventory.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "items", indices = [Index("name"), Index("category")])
data class ItemEntity(
    @PrimaryKey val itemId: String,
    val name: String,
    val shortName: String? = null,
    val width: Int,
    val height: Int,
    val category: String = "normal",
    val baseValueCents: Long? = null,
    val iconPath: String? = null,
    val source: String = "wasm_extract"
)

@Entity(tableName = "item_images", indices = [Index("itemId")])
data class ItemImageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: String,
    val localPath: String,
    val remoteUrl: String? = null,
    val phash: Long? = null,
    val width: Int = 0,
    val height: Int = 0,
    @ColumnInfo(defaultValue = "") val license: String = "unknown"
)

@Entity(tableName = "item_prices", indices = [Index("itemId"), Index("updatedAt")])
data class ItemPriceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: String,
    val valueCents: Long,
    val source: String,
    val updatedAt: Long,
    val version: Int,
    val validDays: Int = 7
)

@Entity(tableName = "containers")
data class ContainerEntity(
    @PrimaryKey val containerId: String,
    val name: String,
    val type: String,   // bag / ground / safe_box / pocket
    val gridWidth: Int,
    val gridHeight: Int,
    val sessionId: String
)

@Entity(
    tableName = "item_instances",
    indices = [Index("sessionId"), Index("itemTypeId"), Index("containerId"), Index("lastSeenFrame")],
    foreignKeys = [ForeignKey(ContainerEntity::class, ["containerId"], ["containerId"])]
)
data class ItemInstanceEntity(
    @PrimaryKey val instanceId: String,
    val sessionId: String,
    val itemTypeId: String,
    val containerId: String,
    val regionId: String,
    val posX: Int,
    val posY: Int,
    val viewportOffsetY: Int,
    val phash: Long,
    val colorHist: String,
    val firstSeenFrame: Long,
    val lastSeenFrame: Long,
    val confidence: Float,
    val trackingStatus: String,
    val quantity: Int,
    val humanConfirmed: Boolean = false
)

@Entity(tableName = "scan_sessions", indices = [Index("startedAt")])
data class ScanSessionEntity(
    @PrimaryKey val sessionId: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val frameCount: Long = 0,
    val status: String = "active"
)

@Entity(tableName = "frame_records", indices = [Index("sessionId"), Index("frameIndex")])
data class FrameRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val frameIndex: Long,
    val capturedAt: Long,
    val imagePath: String? = null,
    val changeScore: Float = 0f,
    val inferenceMs: Long = 0
)

@Entity(tableName = "recognition_events", indices = [Index("sessionId"), Index("frameIndex")])
data class RecognitionEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val frameIndex: Long,
    val eventType: String,   // created / matched / pending / merged / split / discarded
    val instanceId: String,
    val itemTypeId: String,
    val confidence: Float,
    val details: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "optimization_results", indices = [Index("sessionId")])
data class OptimizationResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val algorithm: String,
    val gridWidth: Int,
    val gridHeight: Int,
    val resultJson: String,
    val totalValueCents: Long,
    val placedCount: Int,
    val unplacedCount: Int,
    val elapsedMs: Long,
    val createdAt: Long = System.currentTimeMillis()
)

// ---------- DAO ----------

@Dao
interface ItemDao {
    @Query("SELECT * FROM items ORDER BY name")
    fun all(): Flow<List<ItemEntity>>
    @Query("SELECT * FROM items WHERE itemId = :id")
    suspend fun byId(id: String): ItemEntity?
    @Query("SELECT * FROM items WHERE name LIKE '%'||:q||'%' OR shortName LIKE '%'||:q||'%' LIMIT 100")
    suspend fun search(q: String): List<ItemEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ItemEntity>)
    @Query("SELECT COUNT(*) FROM items")
    suspend fun count(): Int
}

@Dao
interface PriceDao {
    @Query("SELECT * FROM item_prices WHERE itemId = :id ORDER BY updatedAt DESC LIMIT 5")
    suspend fun history(id: String): List<ItemPriceEntity>
    @Query("SELECT * FROM item_prices WHERE itemId = :id ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latest(id: String): ItemPriceEntity?
    @Insert
    suspend fun insert(p: ItemPriceEntity)
    @Query("SELECT MAX(version) FROM item_prices")
    suspend fun maxVersion(): Int?
}

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(s: ScanSessionEntity)
    @Query("UPDATE scan_sessions SET endedAt = :end, status = :status WHERE sessionId = :id")
    suspend fun end(id: String, end: Long, status: String)
    @Query("SELECT * FROM scan_sessions ORDER BY startedAt DESC")
    fun all(): Flow<List<ScanSessionEntity>>
}

@Dao
interface InstanceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(i: ItemInstanceEntity)
    @Query("SELECT * FROM item_instances WHERE sessionId = :sid AND trackingStatus != 'Discarded'")
    fun active(sid: String): Flow<List<ItemInstanceEntity>>
    @Query("SELECT * FROM item_instances WHERE sessionId = :sid AND trackingStatus = 'PendingReview'")
    fun pending(sid: String): Flow<List<ItemInstanceEntity>>
    @Query("UPDATE item_instances SET itemTypeId=:type, quantity=:qty, posX=:x, posY=:y, humanConfirmed=1, confidence=1.0, trackingStatus='Confirmed' WHERE instanceId=:id")
    suspend fun confirm(id: String, type: String, qty: Int, x: Int, y: Int)
    @Query("UPDATE item_instances SET trackingStatus='Discarded' WHERE instanceId=:id")
    suspend fun discard(id: String)
    @Query("DELETE FROM item_instances WHERE sessionId = :sid")
    suspend fun deleteSession(sid: String)
}

@Dao
interface EventDao {
    @Insert
    suspend fun insert(e: RecognitionEventEntity)
    @Query("SELECT * FROM recognition_events WHERE sessionId = :sid ORDER BY createdAt DESC LIMIT 500")
    fun forSession(sid: String): Flow<List<RecognitionEventEntity>>
}

@Dao
interface FrameDao {
    @Insert
    suspend fun insert(f: FrameRecordEntity)
    @Query("SELECT COUNT(*) FROM frame_records WHERE sessionId = :sid")
    suspend fun count(sid: String): Int
}

@Dao
interface ContainerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(list: List<ContainerEntity>)
    @Query("SELECT * FROM containers WHERE sessionId = :sid")
    fun forSession(sid: String): Flow<List<ContainerEntity>>
}

@Dao
interface OptimizationDao {
    @Insert
    suspend fun insert(r: OptimizationResultEntity)
    @Query("SELECT * FROM optimization_results WHERE sessionId = :sid ORDER BY totalValueCents DESC")
    fun forSession(sid: String): Flow<List<OptimizationResultEntity>>
}

@Database(
    entities = [
        ItemEntity::class, ItemImageEntity::class, ItemPriceEntity::class,
        ContainerEntity::class, ItemInstanceEntity::class, ScanSessionEntity::class,
        FrameRecordEntity::class, RecognitionEventEntity::class, OptimizationResultEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class DeltaDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    abstract fun priceDao(): PriceDao
    abstract fun sessionDao(): SessionDao
    abstract fun instanceDao(): InstanceDao
    abstract fun eventDao(): EventDao
    abstract fun frameDao(): FrameDao
    abstract fun containerDao(): ContainerDao
    abstract fun optimizationDao(): OptimizationDao

    companion object {
        const val DB_NAME = "delta_inventory.db"
    }
}
