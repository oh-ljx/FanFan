package com.ohljx.fanfan.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Index
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val SQLITE_BIND_CHUNK_SIZE = 900

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val mediaId: Long,
    val likedAt: Long,
)

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: Long,
    val text: String,
    val createdAt: Long,
    /** 被回复的评论 id；null 为顶层评论。回复最多两层（评论→回复→回复的回复）。 */
    val parentId: Long? = null,
)

@Entity(tableName = "trash")
data class TrashEntity(
    @PrimaryKey val mediaId: Long,
    val deletedAt: Long,
)

@Entity(tableName = "seen")
data class SeenEntity(
    @PrimaryKey val mediaId: Long,
    val seenAt: Long,
)

/** 在理理页被隐藏的相簿（MediaStore bucket）；隐藏后对翻翻/全部/计数都不可见。 */
@Entity(tableName = "hidden_album")
data class HiddenAlbumEntity(
    @PrimaryKey val bucketId: Long,
    val hiddenAt: Long,
)

/**
 * Singleton metadata for the durable browsing round.
 *
 * [remainingIds] remains only so Room can migrate existing v2 databases without rebuilding this
 * table. Since v3 the normalized child tables hold the ordered deck/back stack, while the legacy
 * [visitStack] column stores the bounded forward stack without requiring another schema migration.
 */
@Entity(tableName = "flip_session")
data class FlipSessionEntity(
    @PrimaryKey val sessionKey: Int = SINGLETON_KEY,
    val currentId: Long?,
    val remainingIds: String,
    val visitStack: String,
    val roundComplete: Boolean,
    val updatedAt: Long,
) {
    companion object {
        const val SINGLETON_KEY = 0
    }
}

@Entity(
    tableName = "flip_remaining",
    indices = [Index(value = ["mediaId"], unique = true)],
)
data class FlipRemainingEntity(
    @PrimaryKey val position: Long,
    val mediaId: Long,
)

@Entity(tableName = "flip_visit")
data class FlipVisitEntity(
    @PrimaryKey val position: Long,
    val mediaId: Long,
)

/** notes 分组计数的投影。 */
data class NoteCount(
    val mediaId: Long,
    val count: Int,
)

/** Transactionally loaded boot state; the snapshot row can be absent after a v1 migration. */
data class StoredFlipSessionState(
    val session: FlipSessionEntity?,
    val seenIds: List<Long>,
    val remainingIds: List<Long>,
    val visitStack: List<Long>,
)

@Dao
abstract class LibraryDao {
    @Query("SELECT mediaId FROM favorites")
    abstract suspend fun favoriteIds(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun addFavorite(entity: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE mediaId = :id")
    abstract suspend fun removeFavorite(id: Long)

    @Query("SELECT * FROM notes WHERE mediaId = :mediaId ORDER BY createdAt ASC")
    abstract suspend fun notesFor(mediaId: Long): List<NoteEntity>

    @Insert
    abstract suspend fun addNote(entity: NoteEntity)

    /** 删除评论并级联删掉它的回复；回复最多两层，一条语句覆盖到第三层 id。 */
    @Query(
        "DELETE FROM notes WHERE id = :id OR parentId = :id " +
            "OR parentId IN (SELECT id FROM notes WHERE parentId = :id)",
    )
    abstract suspend fun removeNote(id: Long)

    @Query("SELECT mediaId, COUNT(*) AS count FROM notes GROUP BY mediaId")
    abstract suspend fun noteCounts(): List<NoteCount>

    @Query("SELECT mediaId FROM trash")
    abstract suspend fun trashIds(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun addTrash(entity: TrashEntity)

    @Query("DELETE FROM trash WHERE mediaId = :id")
    abstract suspend fun removeTrash(id: Long)

    @Query("SELECT * FROM trash ORDER BY deletedAt DESC")
    abstract suspend fun trashEntries(): List<TrashEntity>

    @Query("DELETE FROM trash WHERE mediaId IN (:ids)")
    abstract suspend fun removeTrashIds(ids: List<Long>): Int

    /** Safe reconciliation: only IDs positively observed in the active MediaStore query are cleared. */
    @Query("DELETE FROM trash WHERE mediaId IN (:activeIds)")
    abstract suspend fun clearTrashForActive(activeIds: List<Long>): Int

    /** Keep every generated IN clause below the conservative SQLite bind-variable limit. */
    @Transaction
    open suspend fun clearTrashForActiveChunked(activeIds: List<Long>): Int {
        var removed = 0
        activeIds.chunked(SQLITE_BIND_CHUNK_SIZE).forEach { chunk ->
            removed += clearTrashForActive(chunk)
        }
        return removed
    }

    /** Batch restore metadata without issuing one Room transaction per media item. */
    @Transaction
    open suspend fun removeTrashIdsChunked(ids: List<Long>): Int {
        var removed = 0
        ids.chunked(SQLITE_BIND_CHUNK_SIZE).forEach { chunk ->
            removed += removeTrashIds(chunk)
        }
        return removed
    }

    @Query("DELETE FROM favorites WHERE mediaId IN (:ids)")
    abstract suspend fun removeFavoriteIds(ids: List<Long>)

    @Query("DELETE FROM notes WHERE mediaId IN (:ids)")
    abstract suspend fun removeNotesForIds(ids: List<Long>)

    @Query("DELETE FROM seen WHERE mediaId IN (:ids)")
    abstract suspend fun removeSeenIds(ids: List<Long>)

    @Query("SELECT mediaId FROM seen")
    abstract suspend fun seenIds(): List<Long>

    @Query("SELECT bucketId FROM hidden_album")
    abstract suspend fun hiddenAlbumIds(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun hideAlbum(entity: HiddenAlbumEntity)

    @Query("DELETE FROM hidden_album WHERE bucketId = :bucketId")
    abstract suspend fun unhideAlbum(bucketId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun addSeen(entity: SeenEntity)

    @Query("DELETE FROM seen")
    abstract suspend fun clearSeen()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun addSeenAll(entities: List<SeenEntity>)

    @Query("SELECT * FROM flip_session WHERE sessionKey = :key LIMIT 1")
    abstract suspend fun flipSession(key: Int = FlipSessionEntity.SINGLETON_KEY): FlipSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertFlipSession(entity: FlipSessionEntity)

    @Query("SELECT mediaId FROM flip_remaining ORDER BY position ASC")
    abstract suspend fun flipRemainingIds(): List<Long>

    @Query("SELECT mediaId FROM flip_visit ORDER BY position ASC")
    abstract suspend fun flipVisitIds(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertFlipRemaining(entities: List<FlipRemainingEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertFlipVisits(entities: List<FlipVisitEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertFlipVisit(entity: FlipVisitEntity)

    @Query("DELETE FROM flip_remaining WHERE mediaId = :mediaId")
    abstract suspend fun removeFlipRemaining(mediaId: Long): Int

    @Query("DELETE FROM flip_remaining")
    abstract suspend fun clearFlipRemaining()

    @Query("DELETE FROM flip_visit")
    abstract suspend fun clearFlipVisits()

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM flip_visit")
    abstract suspend fun nextFlipVisitPosition(): Long

    @Query("DELETE FROM flip_visit WHERE position = (SELECT MAX(position) FROM flip_visit)")
    abstract suspend fun popFlipVisit(): Int

    @Query("SELECT COUNT(*) FROM flip_visit")
    abstract suspend fun flipVisitCount(): Int

    @Query(
        "DELETE FROM flip_visit WHERE position IN " +
            "(SELECT position FROM flip_visit ORDER BY position ASC LIMIT :count)",
    )
    abstract suspend fun removeOldestFlipVisits(count: Int): Int

    @Transaction
    open suspend fun loadFlipSessionState(): StoredFlipSessionState =
        StoredFlipSessionState(
            session = flipSession(),
            seenIds = seenIds(),
            remainingIds = flipRemainingIds(),
            visitStack = flipVisitIds(),
        )

    /**
     * A normal swipe is a bounded transaction: one seen upsert, one deck delete, one metadata
     * upsert and at most one visit-stack insert/delete. It never rewrites the full deck.
     */
    @Transaction
    open suspend fun applyFlipSessionIncrement(
        session: FlipSessionEntity,
        seen: SeenEntity?,
        removeRemainingId: Long?,
        pushVisitId: Long?,
        popVisit: Boolean,
        maxVisitCount: Int,
    ) {
        if (seen != null) addSeen(seen)
        if (removeRemainingId != null) removeFlipRemaining(removeRemainingId)
        when {
            popVisit -> popFlipVisit()
            pushVisitId != null -> {
                insertFlipVisit(FlipVisitEntity(nextFlipVisitPosition(), pushVisitId))
                val overflow = flipVisitCount() - maxVisitCount
                if (overflow > 0) removeOldestFlipVisits(overflow)
            }
        }
        upsertFlipSession(session)
    }

    /** Initialization/reconcile/restart replace the normalized ordered state atomically. */
    @Transaction
    open suspend fun replaceFlipSessionState(
        session: FlipSessionEntity,
        remaining: List<FlipRemainingEntity>,
        visits: List<FlipVisitEntity>,
        replaceSeen: Boolean,
        seen: List<SeenEntity>,
        shown: SeenEntity?,
    ) {
        if (replaceSeen) {
            clearSeen()
            if (seen.isNotEmpty()) addSeenAll(seen)
        } else if (shown != null) {
            addSeen(shown)
        }
        clearFlipRemaining()
        clearFlipVisits()
        if (remaining.isNotEmpty()) insertFlipRemaining(remaining)
        if (visits.isNotEmpty()) insertFlipVisits(visits)
        upsertFlipSession(session)
    }

    @Transaction
    open suspend fun permanentlyDelete(ids: List<Long>) {
        ids.chunked(SQLITE_BIND_CHUNK_SIZE).forEach { chunk ->
            removeTrashIds(chunk)
            removeFavoriteIds(chunk)
            removeNotesForIds(chunk)
            removeSeenIds(chunk)
        }
    }
}

@Database(
    entities = [
        FavoriteEntity::class,
        NoteEntity::class,
        TrashEntity::class,
        SeenEntity::class,
        HiddenAlbumEntity::class,
        FlipSessionEntity::class,
        FlipRemainingEntity::class,
        FlipVisitEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class FanFanDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `flip_session` (
                        `sessionKey` INTEGER NOT NULL,
                        `currentId` INTEGER,
                        `remainingIds` TEXT NOT NULL,
                        `visitStack` TEXT NOT NULL,
                        `roundComplete` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`sessionKey`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `flip_remaining` (
                        `position` INTEGER NOT NULL,
                        `mediaId` INTEGER NOT NULL,
                        PRIMARY KEY(`position`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_flip_remaining_mediaId` " +
                        "ON `flip_remaining` (`mediaId`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `flip_visit` (
                        `position` INTEGER NOT NULL,
                        `mediaId` INTEGER NOT NULL,
                        PRIMARY KEY(`position`)
                    )
                    """.trimIndent(),
                )

                db.query(
                    "SELECT remainingIds, visitStack FROM flip_session " +
                        "WHERE sessionKey = ${FlipSessionEntity.SINGLETON_KEY} LIMIT 1",
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        val remaining = LongIdListCodec.decode(cursor.getString(0).orEmpty())
                        val visits = LongIdListCodec.decode(cursor.getString(1).orEmpty())
                        remaining.forEachIndexed { index, mediaId ->
                            db.execSQL(
                                "INSERT OR REPLACE INTO flip_remaining(position, mediaId) VALUES(?, ?)",
                                arrayOf(index.toLong(), mediaId),
                            )
                        }
                        visits.forEachIndexed { index, mediaId ->
                            db.execSQL(
                                "INSERT OR REPLACE INTO flip_visit(position, mediaId) VALUES(?, ?)",
                                arrayOf(index.toLong(), mediaId),
                            )
                        }
                    }
                }
                db.execSQL("UPDATE flip_session SET remainingIds = '', visitStack = ''")
            }
        }

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN parentId INTEGER")
            }
        }

        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `hidden_album` (
                        `bucketId` INTEGER NOT NULL,
                        `hiddenAt` INTEGER NOT NULL,
                        PRIMARY KEY(`bucketId`)
                    )
                    """.trimIndent(),
                )
            }
        }

        @Volatile
        private var instance: FanFanDatabase? = null

        fun get(context: Context): FanFanDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FanFanDatabase::class.java,
                    "fanfan.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { instance = it }
            }
    }
}
