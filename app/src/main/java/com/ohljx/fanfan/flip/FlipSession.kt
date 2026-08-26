package com.ohljx.fanfan.flip

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ohljx.fanfan.media.MediaItem
import java.util.TimeZone
import kotlin.random.Random

internal const val FLIP_VISIT_STACK_LIMIT = 512

enum class FlipDirection { UP, DOWN, LEFT, RIGHT }

/**
 * A durable description of the current round.
 *
 * [remainingIds] is an already shuffled deck. Its first item is the next random target, so
 * restoring a process never reshuffles or starts from the newest photo again. [visitStack] and
 * [forwardStack] are ordered from oldest/farthest to newest/nearest, so their last items are the
 * next DOWN and replayed UP targets respectively.
 */
data class FlipSessionSnapshot(
    val currentId: Long?,
    val remainingIds: List<Long>,
    val visitStack: List<Long>,
    val roundComplete: Boolean,
    val forwardStack: List<Long> = emptyList(),
)

/**
 * One persistence event emitted after the in-memory state is fully updated.
 *
 * - [shownId] must be upserted into `seen` in the same transaction as [snapshot].
 * - A non-null [resetSeenTo] replaces the whole seen table in that same transaction.
 */
class FlipSessionUpdate(
    snapshot: FlipSessionSnapshot,
    val shownId: Long? = null,
    val resetSeenTo: Set<Long>? = null,
) {
    @Volatile
    private var cachedSnapshot: FlipSessionSnapshot? = snapshot
    private var snapshotFactory: (() -> FlipSessionSnapshot)? = null

    internal var persistence: FlipSessionPersistence = FlipSessionPersistence.Replace
        private set
    internal var currentId: Long? = snapshot.currentId
        private set
    internal var roundComplete: Boolean = snapshot.roundComplete
        private set
    /** Frozen when the update is created so the asynchronous writer cannot observe later swipes. */
    internal var forwardIds: List<Long> = snapshot.forwardStack.toList()
        private set

    /**
     * Immediate compatibility view for callers that need a complete snapshot. Ordinary swipes
     * defer its O(library size) list copy; retained updates must use the frozen compact fields that
     * Room persists instead.
     */
    val snapshot: FlipSessionSnapshot
        get() = cachedSnapshot ?: synchronized(this) {
            cachedSnapshot ?: checkNotNull(snapshotFactory)().also {
                cachedSnapshot = it
                snapshotFactory = null
            }
        }

    internal constructor(
        currentId: Long?,
        roundComplete: Boolean,
        shownId: Long?,
        resetSeenTo: Set<Long>?,
        persistence: FlipSessionPersistence,
        forwardIds: List<Long>,
        snapshotFactory: () -> FlipSessionSnapshot,
    ) : this(
        snapshot = FlipSessionSnapshot(currentId, emptyList(), emptyList(), roundComplete),
        shownId = shownId,
        resetSeenTo = resetSeenTo,
    ) {
        cachedSnapshot = null
        this.snapshotFactory = snapshotFactory
        this.persistence = persistence
        this.currentId = currentId
        this.roundComplete = roundComplete
        this.forwardIds = forwardIds.toList()
    }

    internal val isSnapshotMaterialized: Boolean
        get() = cachedSnapshot != null
}

/** Compact Room mutation used for the steady-state swipe path. */
internal sealed interface FlipSessionPersistence {
    /** Replace the normalized deck/stack. Used only for initialization, restart and reconciliation. */
    data object Replace : FlipSessionPersistence

    /** Update the singleton row, remove [shownId] from the deck and mutate the stack once. */
    data class Incremental(val visit: VisitMutation = VisitMutation.None) : FlipSessionPersistence
}

internal sealed interface VisitMutation {
    data object None : VisitMutation
    data class Push(val mediaId: Long) : VisitMutation
    data object Pop : VisitMutation
}

/** Small Android-free record used by the indexed session engine and its JVM tests. */
internal data class FlipMediaRecord(val id: Long, val dateTaken: Long)

/**
 * Indexed, Android-free round engine. The UI wrapper below only mirrors its state into Compose.
 * All steady-state lookups are O(1); rebuilding the indexes happens only when the media set changes.
 */
internal class FlipSessionState(
    media: List<FlipMediaRecord>,
    initialSeen: Set<Long> = emptySet(),
    snapshot: FlipSessionSnapshot? = null,
    private val random: Random = Random.Default,
    private val dayKey: (Long) -> Long = ::localDayKey,
) {
    private var orderedMedia = normalize(media)
    private var recordById: Map<Long, FlipMediaRecord> = emptyMap()
    private var dayIdsByMediaId: Map<Long, List<Long>> = emptyMap()
    private var dayPositionByMediaId: Map<Long, Int> = emptyMap()

    private val seenIds = initialSeen.toMutableSet()
    private val remainingIds = linkedSetOf<Long>()
    private val visitStack = ArrayDeque<Long>()
    private val forwardStack = ArrayDeque<Long>()
    private var activeSeenCount = 0

    var currentId: Long? = null
        private set
    var roundComplete: Boolean = false
        private set

    val seenCount: Int
        get() = activeSeenCount

    val initialUpdate: FlipSessionUpdate

    init {
        rebuildIndexes()
        if (snapshot == null) {
            startWithoutSnapshot()
        } else {
            restore(snapshot)
        }
        initialUpdate = if (snapshot == null) {
            replacementUpdate(shownId = currentId)
        } else {
            // The restored deck is already stored in Room. Only replace it when reconciliation
            // filtered stale rows or appended newly discovered media.
            if (matches(snapshot)) {
                incrementalUpdate(shownId = currentId)
            } else {
                replacementUpdate(shownId = currentId)
            }
        }
    }

    fun snapshot(): FlipSessionSnapshot = FlipSessionSnapshot(
        currentId = currentId,
        remainingIds = remainingIds.toList(),
        visitStack = visitStack.toList(),
        roundComplete = roundComplete,
        forwardStack = forwardStack.toList(),
    )

    fun contains(id: Long): Boolean = id in recordById

    fun sameDayIds(id: Long): List<Long> = dayIdsByMediaId[id].orEmpty()

    fun targetFor(direction: FlipDirection): Long? = when (direction) {
        FlipDirection.UP -> forwardTarget() ?: remainingIds.firstOrNull()
        FlipDirection.DOWN -> visitStack.lastOrNull { it != currentId && contains(it) }
        FlipDirection.LEFT -> dayNeighbor(+1)
        FlipDirection.RIGHT -> dayNeighbor(-1)
    }

    /** Returns null when [targetId] is stale or is not the currently advertised target. */
    fun commit(direction: FlipDirection, targetId: Long): FlipSessionUpdate? {
        if (targetFor(direction) != targetId) return null

        val previousId = currentId
        val replayingForward = direction == FlipDirection.UP && forwardTarget() == targetId
        when (direction) {
            FlipDirection.DOWN -> {
                while (visitStack.isNotEmpty()) {
                    if (visitStack.removeLast() == targetId) break
                }
                previousId?.let(::pushForward)
            }
            FlipDirection.UP -> {
                previousId?.let(::pushVisit)
                if (replayingForward) {
                    while (forwardStack.isNotEmpty()) {
                        if (forwardStack.removeLast() == targetId) break
                    }
                } else {
                    // Invalid/stale forward entries must not survive a newly generated branch.
                    forwardStack.clear()
                }
            }
            FlipDirection.LEFT, FlipDirection.RIGHT -> {
                previousId?.let(::pushVisit)
                // Like browser history, navigating sideways from an older item starts a new branch.
                forwardStack.clear()
            }
        }

        currentId = targetId
        markShown(targetId)
        val visitMutation = if (direction == FlipDirection.DOWN) {
            VisitMutation.Pop
        } else {
            previousId?.let(VisitMutation::Push) ?: VisitMutation.None
        }
        return incrementalUpdate(shownId = targetId, visit = visitMutation)
    }

    /**
     * Reconciles additions, removals and reordering without discarding the current round.
     * Existing deck order is preserved; newly discovered unseen IDs are shuffled once and appended.
     */
    fun reconcile(newMedia: List<FlipMediaRecord>): FlipSessionUpdate {
        val previousCurrent = currentId
        // 被删照片原属的同一天分组和位置，用于删除后优先落在当天的相邻照片上。
        val removedDayIds = previousCurrent?.let(dayIdsByMediaId::get)
        val removedDayIndex = previousCurrent?.let(dayPositionByMediaId::get)
        orderedMedia = normalize(newMedia)
        rebuildIndexes()

        retainValidVisitStack()
        retainValidForwardStack()
        retainValidRemainingIds()

        var shownId: Long? = null
        if (currentId !in recordById) {
            currentId = sameDayNeighborFallback(removedDayIds, removedDayIndex)
                ?: forwardTarget()
                ?: remainingIds.firstOrNull()
                ?: popVisitFallback()
                ?: popForwardFallback()
                ?: orderedMedia.firstOrNull()?.id
            shownId = currentId
        }

        currentId?.let {
            val wasSeen = it in seenIds
            markShown(it)
            if (!wasSeen) shownId = it
        }
        appendMissingUnseen()

        if (orderedMedia.isEmpty()) {
            currentId = null
            roundComplete = false
        } else if (remainingIds.isNotEmpty() || forwardTarget() != null) {
            // A newly added/unseen item reopens an otherwise completed round.
            roundComplete = false
        }

        // Persist a corrected snapshot even when only metadata/order changed.
        return replacementUpdate(shownId = shownId.takeIf { it != previousCurrent })
    }

    fun roundFinished(): Boolean =
        orderedMedia.isNotEmpty() && remainingIds.isEmpty() && forwardTarget() == null

    fun completeRound(): FlipSessionUpdate {
        if (roundFinished()) roundComplete = true
        return incrementalUpdate()
    }

    fun restartRound(): FlipSessionUpdate {
        roundComplete = false
        visitStack.clear()
        forwardStack.clear()
        seenIds.clear()

        val validCurrent = currentId?.takeIf(::contains)
        val shuffled = orderedMedia.map { it.id }.shuffled(random)
        currentId = validCurrent ?: shuffled.firstOrNull()
        remainingIds.clear()
        shuffled.asSequence()
            .filter { it != currentId }
            .forEach(remainingIds::add)
        activeSeenCount = 0
        currentId?.let(::markShown)

        val keep = currentId?.let(::setOf).orEmpty()
        return replacementUpdate(shownId = currentId, resetSeenTo = keep)
    }

    private fun startWithoutSnapshot() {
        visitStack.clear()
        forwardStack.clear()
        remainingIds.clear()

        val unseenDeck = orderedMedia.asSequence()
            .map { it.id }
            .filter { it !in seenIds }
            .toList()
            .shuffled(random)

        if (unseenDeck.isNotEmpty()) {
            currentId = unseenDeck.first()
            unseenDeck.drop(1).forEach(remainingIds::add)
            markShown(unseenDeck.first())
            roundComplete = false
            return
        }

        // v1 databases can already contain a completed seen set but no session row.
        currentId = orderedMedia.map { it.id }.shuffled(random).firstOrNull()
        currentId?.let(::markShown)
        roundComplete = orderedMedia.isNotEmpty()
    }

    private fun restore(snapshot: FlipSessionSnapshot) {
        visitStack.clear()
        snapshot.visitStack.asSequence()
            .filter(::contains)
            .forEach(::pushVisit)

        forwardStack.clear()
        snapshot.forwardStack.asSequence()
            .filter(::contains)
            .forEach(::pushForward)

        remainingIds.clear()
        snapshot.remainingIds.asSequence()
            .filter(::contains)
            .filter { it !in seenIds }
            .forEach(remainingIds::add)

        currentId = snapshot.currentId?.takeIf(::contains)
            ?: popVisitFallback()
            ?: popForwardFallback()
            ?: remainingIds.firstOrNull()
            ?: orderedMedia.firstOrNull()?.id

        currentId?.let(::markShown)
        appendMissingUnseen()
        roundComplete = snapshot.roundComplete && roundFinished()
    }

    private fun appendMissingUnseen() {
        val missing = orderedMedia.asSequence()
            .map { it.id }
            .filter { it != currentId && it !in seenIds && it !in remainingIds }
            .toList()
            .shuffled(random)
        missing.forEach(remainingIds::add)
    }

    private fun markShown(id: Long) {
        if (!contains(id)) return
        if (seenIds.add(id)) activeSeenCount += 1
        remainingIds -= id
    }

    private fun dayNeighbor(offset: Int): Long? {
        val id = currentId ?: return null
        val ids = dayIdsByMediaId[id] ?: return null
        val index = dayPositionByMediaId[id] ?: return null
        return ids.getOrNull(index + offset)
    }

    /**
     * 删除当前照片后的落点：优先同一天右侧（时间更晚）的一张，右侧没有再看左侧，
     * 都由近及远跳过已不存在的条目；当天没有了才走常规的下一张/历史兜底。
     */
    private fun sameDayNeighborFallback(dayIds: List<Long>?, index: Int?): Long? {
        if (dayIds == null || index == null) return null
        for (position in index + 1 until dayIds.size) {
            val id = dayIds[position]
            if (contains(id)) return id
        }
        for (position in index - 1 downTo 0) {
            val id = dayIds[position]
            if (contains(id)) return id
        }
        return null
    }

    private fun pushVisit(id: Long) {
        if (!contains(id)) return
        visitStack.addLast(id)
        while (visitStack.size > FLIP_VISIT_STACK_LIMIT) visitStack.removeFirst()
    }

    private fun pushForward(id: Long) {
        if (!contains(id)) return
        forwardStack.addLast(id)
        while (forwardStack.size > FLIP_VISIT_STACK_LIMIT) forwardStack.removeFirst()
    }

    private fun forwardTarget(): Long? =
        forwardStack.lastOrNull { it != currentId && contains(it) }

    private fun popVisitFallback(): Long? {
        while (visitStack.isNotEmpty()) {
            val id = visitStack.removeLast()
            if (contains(id) && id != currentId) return id
        }
        return null
    }

    private fun popForwardFallback(): Long? {
        while (forwardStack.isNotEmpty()) {
            val id = forwardStack.removeLast()
            if (contains(id) && id != currentId) return id
        }
        return null
    }

    private fun retainValidVisitStack() {
        val valid = visitStack.filter(::contains).takeLast(FLIP_VISIT_STACK_LIMIT)
        visitStack.clear()
        valid.forEach(visitStack::addLast)
    }

    private fun retainValidForwardStack() {
        val valid = forwardStack.filter(::contains).takeLast(FLIP_VISIT_STACK_LIMIT)
        forwardStack.clear()
        valid.forEach(forwardStack::addLast)
    }

    private fun retainValidRemainingIds() {
        val valid = remainingIds.filter { contains(it) && it != currentId && it !in seenIds }
        remainingIds.clear()
        valid.forEach(remainingIds::add)
    }

    private fun rebuildIndexes() {
        recordById = orderedMedia.associateBy { it.id }
        activeSeenCount = orderedMedia.count { it.id in seenIds }
        val groups = linkedMapOf<Long, MutableList<Long>>()
        orderedMedia.forEach { item -> groups.getOrPut(dayKey(item.dateTaken)) { mutableListOf() } += item.id }

        val byId = HashMap<Long, List<Long>>(orderedMedia.size)
        val positions = HashMap<Long, Int>(orderedMedia.size)
        groups.values.forEach { ids ->
            // 同一天内按拍摄时间升序：向左翻（屏幕左侧）是当天更早的照片，向右翻是更晚的。
            val stableIds = ids.sortedWith(compareBy({ recordById.getValue(it).dateTaken }, { it }))
            stableIds.forEachIndexed { index, id ->
                byId[id] = stableIds
                positions[id] = index
            }
        }
        dayIdsByMediaId = byId
        dayPositionByMediaId = positions
    }

    private fun replacementUpdate(
        shownId: Long? = null,
        resetSeenTo: Set<Long>? = null,
    ) = FlipSessionUpdate(snapshot(), shownId, resetSeenTo)

    private fun incrementalUpdate(
        shownId: Long? = null,
        visit: VisitMutation = VisitMutation.None,
    ) = FlipSessionUpdate(
        currentId = currentId,
        roundComplete = roundComplete,
        shownId = shownId,
        resetSeenTo = null,
        persistence = FlipSessionPersistence.Incremental(visit),
        forwardIds = forwardStack.toList(),
        snapshotFactory = ::snapshot,
    )

    private fun matches(snapshot: FlipSessionSnapshot): Boolean =
        snapshot.currentId == currentId &&
            snapshot.roundComplete == roundComplete &&
            orderedEquals(snapshot.remainingIds, remainingIds) &&
            orderedEquals(snapshot.visitStack, visitStack) &&
            orderedEquals(snapshot.forwardStack, forwardStack)

    private fun orderedEquals(left: List<Long>, right: Collection<Long>): Boolean {
        if (left.size != right.size) return false
        val rightIterator = right.iterator()
        return left.all { rightIterator.hasNext() && it == rightIterator.next() }
    }

    private companion object {
        fun normalize(media: List<FlipMediaRecord>): List<FlipMediaRecord> = media.distinctBy { it.id }
    }
}

/**
 * Compose-facing browsing session.
 *
 * New callers should pass [initialSnapshot] and persist every [onStateChanged] event with
 * `LibraryRepository.enqueueFlipSessionUpdate`.
 */
class FlipSession(
    media: List<MediaItem>,
    initialSeen: Set<Long> = emptySet(),
    initialSnapshot: FlipSessionSnapshot? = null,
    random: Random = Random.Default,
    private val onStateChanged: (FlipSessionUpdate) -> Unit = {},
) {
    private var itemById: Map<Long, MediaItem>
    private val state: FlipSessionState

    var media by mutableStateOf(normalize(media))
        private set
    var currentId by mutableStateOf<Long?>(null)
        private set
    var roundComplete by mutableStateOf(false)
        private set
    var seenCount by mutableIntStateOf(0)
        private set

    init {
        itemById = this.media.associateBy { it.id }
        state = FlipSessionState(
            media = this.media.map { FlipMediaRecord(it.id, it.dateTaken) },
            initialSeen = initialSeen,
            snapshot = initialSnapshot,
            random = random,
        )
        publish(state.initialUpdate)
    }

    val current: MediaItem?
        get() = currentId?.let(itemById::get)

    fun sameDayItems(item: MediaItem? = current): List<MediaItem> {
        val id = item?.id ?: return emptyList()
        return state.sameDayIds(id).mapNotNull(itemById::get)
    }

    fun targetFor(direction: FlipDirection): Long? = state.targetFor(direction)

    fun commit(direction: FlipDirection, targetId: Long) {
        state.commit(direction, targetId)?.let(::publish)
    }

    /** Remove an item while preserving the deck, history and seen membership for a later restore. */
    fun remove(id: Long) {
        if (id !in itemById) return
        applyMedia(media.filter { it.id != id })
    }

    /** Reconcile a fresh MediaStore result without resetting the active round. */
    fun reconcileMedia(newMedia: List<MediaItem>) {
        applyMedia(newMedia)
    }

    fun roundFinished(): Boolean = state.roundFinished()

    fun completeRound() {
        publish(state.completeRound())
    }

    fun restartRound() {
        publish(state.restartRound())
    }

    private fun applyMedia(newMedia: List<MediaItem>) {
        media = normalize(newMedia)
        itemById = media.associateBy { it.id }
        publish(state.reconcile(media.map { FlipMediaRecord(it.id, it.dateTaken) }))
    }

    private fun publish(update: FlipSessionUpdate) {
        currentId = update.currentId
        roundComplete = update.roundComplete
        seenCount = state.seenCount

        onStateChanged(update)
    }

    private companion object {
        fun normalize(media: List<MediaItem>): List<MediaItem> = media.distinctBy { it.id }
    }
}

/** Local calendar day without allocating SimpleDateFormat/Date for every lookup or frame. */
private fun localDayKey(timestamp: Long): Long {
    val offset = TimeZone.getDefault().getOffset(timestamp).toLong()
    return Math.floorDiv(timestamp + offset, MILLIS_PER_DAY)
}

private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
