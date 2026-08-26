package com.ohljx.fanfan.data

import android.content.Context
import com.ohljx.fanfan.flip.FLIP_VISIT_STACK_LIMIT
import com.ohljx.fanfan.flip.FlipSessionPersistence
import com.ohljx.fanfan.flip.FlipSessionSnapshot
import com.ohljx.fanfan.flip.FlipSessionUpdate
import com.ohljx.fanfan.flip.VisitMutation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

data class FlipSessionBootState(
    val seenIds: Set<Long>,
    val snapshot: FlipSessionSnapshot?,
)

/** 喜欢 / 留言 / 回收站 / 已看记录的访问入口。 */
class LibraryRepository(private val dao: LibraryDao) {
    private val sessionWriteMutex = Mutex()
    private val sessionCommands = Channel<SessionWriteCommand>(Channel.UNLIMITED)
    private val sessionWriterScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionWriterFailure = AtomicReference<Throwable?>(null)

    init {
        // A single consumer preserves the exact order in which the UI commits swipes.
        sessionWriterScope.launch {
            for (command in sessionCommands) {
                when (command) {
                    is SessionWriteCommand.Save -> {
                        if (sessionWriterFailure.get() != null) continue
                        var retryDelayMs = 50L
                        var saved = false
                        var lastFailure: Throwable? = null
                        for (attempt in 0 until SESSION_WRITE_ATTEMPTS) {
                            try {
                                saveFlipSessionUpdate(command.update)
                                saved = true
                                break
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                lastFailure = error
                                // Never let a newer cursor overtake a failed seen/session transaction.
                                if (attempt < SESSION_WRITE_ATTEMPTS - 1) {
                                    delay(retryDelayMs)
                                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(800L)
                                }
                            }
                        }
                        if (!saved) {
                            sessionWriterFailure.compareAndSet(
                                null,
                                lastFailure ?: IllegalStateException("Unable to save flip session"),
                            )
                        }
                    }
                    is SessionWriteCommand.Barrier -> {
                        val failure = sessionWriterFailure.get()
                        if (failure == null) command.completed.complete(Unit)
                        else command.completed.completeExceptionally(failure)
                    }
                }
            }
        }
    }

    suspend fun favoriteIds(): Set<Long> = dao.favoriteIds().toSet()

    suspend fun addFavorite(mediaId: Long) =
        dao.addFavorite(FavoriteEntity(mediaId, System.currentTimeMillis()))

    suspend fun removeFavorite(mediaId: Long) = dao.removeFavorite(mediaId)

    suspend fun notesFor(mediaId: Long): List<NoteEntity> = dao.notesFor(mediaId)

    suspend fun addNote(mediaId: Long, text: String) =
        dao.addNote(NoteEntity(mediaId = mediaId, text = text, createdAt = System.currentTimeMillis()))

    suspend fun removeNote(id: Long) = dao.removeNote(id)

    suspend fun noteCounts(): Map<Long, Int> = dao.noteCounts().associate { it.mediaId to it.count }

    suspend fun trashIds(): Set<Long> = dao.trashIds().toSet()

    suspend fun moveToTrash(mediaId: Long) =
        dao.addTrash(TrashEntity(mediaId, System.currentTimeMillis()))

    suspend fun restoreFromTrash(mediaId: Long) = dao.removeTrash(mediaId)

    suspend fun restoreFromTrash(mediaIds: Collection<Long>): Int {
        val ids = mediaIds.distinct()
        return if (ids.isEmpty()) 0 else dao.removeTrashIdsChunked(ids)
    }

    suspend fun trashEntries(): List<TrashEntity> = dao.trashEntries()

    /**
     * Remove stale app-trash markers only for IDs positively returned by the active MediaStore query.
     * Never infer restoration from an ID missing in a system-trash query: partial media permission makes
     * that unsafe.
     */
    suspend fun clearTrashForActive(activeIds: Set<Long>): Int =
        if (activeIds.isEmpty()) 0 else dao.clearTrashForActiveChunked(activeIds.toList())

    /** 彻底删除：清掉四张表里和这些媒体相关的所有记录。 */
    suspend fun permanentlyDelete(ids: List<Long>) {
        if (ids.isNotEmpty()) dao.permanentlyDelete(ids)
    }

    /** Load seen IDs and the durable cursor/deck under one Room read transaction. */
    suspend fun flipSessionBootState(): FlipSessionBootState = withContext(Dispatchers.IO) {
        awaitSessionWrites()
        val stored = dao.loadFlipSessionState()
        FlipSessionBootState(
            seenIds = stored.seenIds.toSet(),
            snapshot = stored.session?.toSnapshot(stored.remainingIds, stored.visitStack),
        )
    }

    /**
     * Persist one session mutation. Ordinary swipes use bounded normalized-table updates; full deck
     * replacement is reserved for initialization, reconciliation and round restart.
     */
    suspend fun saveFlipSessionUpdate(update: FlipSessionUpdate) {
        // UI callbacks can launch quickly; preserve their order so an older snapshot cannot win last.
        sessionWriteMutex.withLock {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val entity = update.toEntity(now)
                when (val persistence = update.persistence) {
                    FlipSessionPersistence.Replace -> {
                        val snapshot = update.snapshot
                        val reset = update.resetSeenTo
                        dao.replaceFlipSessionState(
                            session = entity,
                            remaining = snapshot.remainingIds.mapIndexed { index, mediaId ->
                                FlipRemainingEntity(index.toLong(), mediaId)
                            },
                            visits = snapshot.visitStack.mapIndexed { index, mediaId ->
                                FlipVisitEntity(index.toLong(), mediaId)
                            },
                            replaceSeen = reset != null,
                            seen = reset.orEmpty().map { SeenEntity(it, now) },
                            shown = update.shownId?.let { SeenEntity(it, now) },
                        )
                    }

                    is FlipSessionPersistence.Incremental -> {
                        val visit = persistence.visit
                        dao.applyFlipSessionIncrement(
                            session = entity,
                            seen = update.shownId?.let { SeenEntity(it, now) },
                            removeRemainingId = update.shownId,
                            pushVisitId = (visit as? VisitMutation.Push)?.mediaId,
                            popVisit = visit is VisitMutation.Pop,
                            maxVisitCount = FLIP_VISIT_STACK_LIMIT,
                        )
                    }
                }
            }
        }
    }

    /** Non-suspending UI entry point; updates are persisted by one ordered IO consumer. */
    fun enqueueFlipSessionUpdate(update: FlipSessionUpdate) {
        check(sessionCommands.trySend(SessionWriteCommand.Save(update)).isSuccess) {
            "Flip session writer is closed"
        }
    }

    /** Wait until every session update queued before this call has reached Room. */
    private suspend fun awaitSessionWrites() {
        val completed = CompletableDeferred<Unit>()
        sessionCommands.send(SessionWriteCommand.Barrier(completed))
        completed.await()
    }

    private sealed interface SessionWriteCommand {
        data class Save(val update: FlipSessionUpdate) : SessionWriteCommand
        data class Barrier(val completed: CompletableDeferred<Unit>) : SessionWriteCommand
    }

    companion object {
        private const val SESSION_WRITE_ATTEMPTS = 6

        @Volatile
        private var instance: LibraryRepository? = null

        fun get(context: Context): LibraryRepository =
            instance ?: synchronized(this) {
                instance ?: LibraryRepository(
                    FanFanDatabase.get(context.applicationContext).libraryDao(),
                ).also { instance = it }
            }
    }
}

internal fun FlipSessionUpdate.toEntity(updatedAt: Long) = FlipSessionEntity(
    currentId = currentId,
    remainingIds = "",
    // v3 normalized the unbounded deck/back stack; reuse this bounded legacy column for redo IDs.
    visitStack = LongIdListCodec.encode(forwardIds),
    roundComplete = roundComplete,
    updatedAt = updatedAt,
)

internal fun FlipSessionEntity.toSnapshot(
    remainingIds: List<Long>,
    visitStack: List<Long>,
) = FlipSessionSnapshot(
    currentId = currentId,
    remainingIds = remainingIds,
    visitStack = visitStack,
    roundComplete = roundComplete,
    forwardStack = LongIdListCodec.decode(this.visitStack),
)

/** Tolerant codec: malformed tokens are ignored so a damaged row can still be reconciled. */
internal object LongIdListCodec {
    fun encode(ids: List<Long>): String = ids.joinToString(separator = ",")

    fun decode(encoded: String): List<Long> = if (encoded.isBlank()) {
        emptyList()
    } else {
        encoded.split(',').mapNotNull { it.toLongOrNull() }
    }
}
