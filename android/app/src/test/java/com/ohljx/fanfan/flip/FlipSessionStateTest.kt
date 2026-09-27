package com.ohljx.fanfan.flip

import com.ohljx.fanfan.data.toEntity
import com.ohljx.fanfan.data.toSnapshot
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlipSessionStateTest {

    @Test
    fun freshInstallBuildsOneShuffledDeckAndUsesItsHeadAsCurrent() {
        val media = records(1L, 2L, 3L, 4L, 5L, 6L)
        val expectedDeck = media.map { it.id }.shuffled(Random(7341))

        val state = FlipSessionState(
            media = media,
            random = Random(7341),
            dayKey = { it },
        )

        assertEquals(expectedDeck.first(), state.currentId)
        assertEquals(expectedDeck.drop(1), state.snapshot().remainingIds)
        assertEquals(state.currentId, state.initialUpdate.shownId)
        assertEquals(1, state.seenCount)
        assertFalse(state.roundComplete)
    }

    @Test
    fun coldStartRestoresCurrentDeckAndBackStackWithoutReshuffling() {
        val saved = FlipSessionSnapshot(
            currentId = 3L,
            remainingIds = listOf(4L, 2L, 5L),
            visitStack = listOf(1L),
            roundComplete = false,
        )
        val state = FlipSessionState(
            media = records(1L, 2L, 3L, 4L, 5L),
            initialSeen = setOf(1L, 3L),
            snapshot = saved,
            random = Random(999),
            dayKey = { it },
        )

        assertEquals(saved, state.snapshot())
        assertEquals(4L, state.targetFor(FlipDirection.UP))
        assertEquals(1L, state.targetFor(FlipDirection.DOWN))

        val committed = requireNotNull(state.commit(FlipDirection.UP, 4L))
        assertEquals(4L, committed.shownId)
        assertEquals(4L, committed.snapshot.currentId)
        assertEquals(listOf(2L, 5L), committed.snapshot.remainingIds)
        assertEquals(listOf(1L, 3L), committed.snapshot.visitStack)

        val restoredAgain = FlipSessionState(
            media = records(1L, 2L, 3L, 4L, 5L),
            initialSeen = setOf(1L, 3L, 4L),
            snapshot = committed.snapshot,
            random = Random(123),
            dayKey = { it },
        )
        assertEquals(committed.snapshot, restoredAgain.snapshot())
    }

    @Test
    fun backThenForwardReplaysTheExactItemBeforeConsumingTheRandomDeck() {
        val state = FlipSessionState(
            media = records(1L, 2L, 3L, 4L, 5L),
            initialSeen = setOf(1L, 2L, 3L),
            snapshot = FlipSessionSnapshot(
                currentId = 3L,
                remainingIds = listOf(4L, 5L),
                visitStack = listOf(1L, 2L),
                roundComplete = false,
            ),
            dayKey = { it },
        )

        val backUpdate = requireNotNull(state.commit(FlipDirection.DOWN, 2L))
        assertFalse(backUpdate.isSnapshotMaterialized)
        assertEquals(2L, state.currentId)
        assertEquals(listOf(4L, 5L), state.snapshot().remainingIds)
        assertEquals(listOf(1L), state.snapshot().visitStack)
        assertEquals(listOf(3L), state.snapshot().forwardStack)
        assertEquals(3L, state.targetFor(FlipDirection.UP))

        val forwardUpdate = requireNotNull(state.commit(FlipDirection.UP, 3L))
        // The older queued update keeps its own forward cursor after the live state moves on.
        assertEquals(listOf(3L), backUpdate.forwardIds)
        assertEquals(3L, state.currentId)
        assertEquals(listOf(4L, 5L), state.snapshot().remainingIds)
        assertEquals(listOf(1L, 2L), state.snapshot().visitStack)
        assertTrue(state.snapshot().forwardStack.isEmpty())
        assertTrue(forwardUpdate.forwardIds.isEmpty())
        assertEquals(4L, state.targetFor(FlipDirection.UP))
    }

    @Test
    fun repeatedBackAndForwardTraversalPreservesTheWholeOrder() {
        val state = FlipSessionState(
            media = records(1L, 2L, 3L, 4L, 5L, 6L),
            initialSeen = setOf(1L, 2L, 3L, 4L),
            snapshot = FlipSessionSnapshot(
                currentId = 4L,
                remainingIds = listOf(5L, 6L),
                visitStack = listOf(1L, 2L, 3L),
                roundComplete = false,
            ),
            dayKey = { it },
        )

        requireNotNull(state.commit(FlipDirection.DOWN, 3L))
        requireNotNull(state.commit(FlipDirection.DOWN, 2L))
        assertEquals(listOf(4L, 3L), state.snapshot().forwardStack)

        requireNotNull(state.commit(FlipDirection.UP, 3L))
        requireNotNull(state.commit(FlipDirection.UP, 4L))
        assertEquals(listOf(1L, 2L, 3L), state.snapshot().visitStack)
        assertTrue(state.snapshot().forwardStack.isEmpty())
        assertEquals(listOf(5L, 6L), state.snapshot().remainingIds)
        assertEquals(5L, state.targetFor(FlipDirection.UP))
    }

    @Test
    fun coldStartRestoresForwardStackAndReplaysItBeforeTheDeck() {
        val saved = FlipSessionSnapshot(
            currentId = 2L,
            remainingIds = listOf(4L, 5L),
            visitStack = listOf(1L),
            roundComplete = false,
            forwardStack = listOf(3L),
        )
        val state = FlipSessionState(
            media = records(1L, 2L, 3L, 4L, 5L),
            initialSeen = setOf(1L, 2L, 3L),
            snapshot = saved,
            random = Random(44),
            dayKey = { it },
        )

        assertEquals(saved, state.snapshot())
        assertEquals(listOf(3L), state.initialUpdate.forwardIds)
        assertEquals(3L, state.targetFor(FlipDirection.UP))

        requireNotNull(state.commit(FlipDirection.UP, 3L))
        assertEquals(3L, state.currentId)
        assertEquals(listOf(1L, 2L), state.snapshot().visitStack)
        assertTrue(state.snapshot().forwardStack.isEmpty())
        assertEquals(listOf(4L, 5L), state.snapshot().remainingIds)
    }

    @Test
    fun forwardStackRoundTripsThroughTheBoundedLegacySessionColumn() {
        val saved = FlipSessionSnapshot(
            currentId = 2L,
            remainingIds = listOf(5L),
            visitStack = listOf(1L),
            roundComplete = false,
            forwardStack = listOf(3L, 4L),
        )

        val entity = FlipSessionUpdate(saved).toEntity(updatedAt = 99L)
        assertEquals("", entity.remainingIds)
        assertEquals("3,4", entity.visitStack)
        assertEquals(saved, entity.toSnapshot(saved.remainingIds, saved.visitStack))
    }

    @Test
    fun sidewaysBrowsingLeavesTheBackAndForwardStacksUntouched() {
        val state = FlipSessionState(
            media = listOf(
                FlipMediaRecord(1L, 10L),
                FlipMediaRecord(2L, 10L),
                FlipMediaRecord(4L, 10L),
                FlipMediaRecord(3L, 11L),
                FlipMediaRecord(5L, 12L),
            ),
            initialSeen = setOf(1L, 2L, 3L),
            snapshot = FlipSessionSnapshot(
                currentId = 2L,
                remainingIds = listOf(4L, 5L),
                visitStack = listOf(1L),
                roundComplete = false,
                forwardStack = listOf(3L),
            ),
            dayKey = { it },
        )

        assertEquals(4L, state.targetFor(FlipDirection.LEFT))
        requireNotNull(state.commit(FlipDirection.LEFT, 4L))

        // 左右翻不回看栈、不清前进栈：回看仍是竖直方向的上一张，前进重放也保留
        assertEquals(listOf(1L), state.snapshot().visitStack)
        assertEquals(listOf(3L), state.snapshot().forwardStack)
        assertEquals(listOf(5L), state.snapshot().remainingIds)
        assertEquals(1L, state.targetFor(FlipDirection.DOWN))
        assertEquals(3L, state.targetFor(FlipDirection.UP))

        // 横向翻回之前那张，回看落点依然不受影响
        requireNotNull(state.commit(FlipDirection.RIGHT, 2L))
        assertEquals(listOf(1L), state.snapshot().visitStack)
        assertEquals(1L, state.targetFor(FlipDirection.DOWN))
    }

    @Test
    fun backAndForwardStacksKeepOnlyTheNearest512Ids() {
        val ids = (1L..1_200L).toList()
        val savedBack = (2L..600L).toList()
        val savedForward = (601L..1_200L).toList()
        val state = FlipSessionState(
            media = records(*ids.toLongArray()),
            initialSeen = ids.toSet(),
            snapshot = FlipSessionSnapshot(
                currentId = 1L,
                remainingIds = emptyList(),
                visitStack = savedBack,
                roundComplete = false,
                forwardStack = savedForward,
            ),
            dayKey = { it },
        )

        assertEquals(savedBack.takeLast(FLIP_VISIT_STACK_LIMIT), state.snapshot().visitStack)
        assertEquals(savedForward.takeLast(FLIP_VISIT_STACK_LIMIT), state.snapshot().forwardStack)
        assertEquals(600L, state.targetFor(FlipDirection.DOWN))
        assertEquals(1_200L, state.targetFor(FlipDirection.UP))
    }

    @Test
    fun aBackedUpCompletedDeckFinishesOnlyAfterTheForwardStackIsReplayed() {
        val state = FlipSessionState(
            media = records(1L, 2L),
            initialSeen = setOf(1L, 2L),
            snapshot = FlipSessionSnapshot(
                currentId = 2L,
                remainingIds = emptyList(),
                visitStack = listOf(1L),
                roundComplete = false,
            ),
            dayKey = { it },
        )

        assertTrue(state.roundFinished())
        requireNotNull(state.commit(FlipDirection.DOWN, 1L))
        assertFalse(state.roundFinished())
        assertEquals(2L, state.targetFor(FlipDirection.UP))

        requireNotNull(state.commit(FlipDirection.UP, 2L))
        assertTrue(state.roundFinished())
    }

    @Test
    fun steadyStateSwipeDoesNotMaterializeTheFullDeckSnapshot() {
        val ids = (1L..50_000L).toList()
        val state = FlipSessionState(
            media = records(*ids.toLongArray()),
            random = Random(91),
            dayKey = { it },
        )

        val target = requireNotNull(state.targetFor(FlipDirection.UP))
        val update = requireNotNull(state.commit(FlipDirection.UP, target))

        assertFalse(update.isSnapshotMaterialized)
        assertEquals(target, update.currentId)
        assertTrue(update.persistence is FlipSessionPersistence.Incremental)

        // The compatibility snapshot is still exact when an explicit caller asks for it.
        assertEquals(target, update.snapshot.currentId)
        assertEquals(49_998, update.snapshot.remainingIds.size)
        assertTrue(update.isSnapshotMaterialized)
    }

    @Test
    fun mediaReconcileKeepsDeckOrderFiltersMissingIdsAndAppendsNewUnseen() {
        val state = FlipSessionState(
            media = records(1L, 2L, 3L, 4L),
            initialSeen = setOf(1L, 3L),
            snapshot = FlipSessionSnapshot(
                currentId = 3L,
                remainingIds = listOf(4L, 2L),
                visitStack = listOf(1L),
                roundComplete = false,
            ),
            random = Random(1),
            dayKey = { it },
        )

        val update = state.reconcile(records(2L, 3L, 5L))

        assertEquals(3L, update.snapshot.currentId)
        assertEquals(listOf(2L, 5L), update.snapshot.remainingIds)
        assertTrue(update.snapshot.visitStack.isEmpty())
        assertNull(update.shownId)
    }

    @Test
    fun deletingCurrentAdvancesToNextDeckItemAndRestoreDoesNotRepeatSeenItem() {
        val state = FlipSessionState(
            media = records(1L, 2L, 3L, 4L),
            initialSeen = setOf(1L, 2L, 3L),
            snapshot = FlipSessionSnapshot(
                currentId = 3L,
                remainingIds = listOf(4L),
                visitStack = listOf(1L, 2L),
                roundComplete = false,
            ),
            random = Random(5),
            dayKey = { it },
        )

        // 被删照片当天没有其它照片：显示牌组里的下一张，而不是回退历史栈
        val deleted = state.reconcile(records(1L, 2L, 4L))
        assertEquals(4L, deleted.snapshot.currentId)
        assertEquals(4L, deleted.shownId)
        assertEquals(listOf(1L, 2L), deleted.snapshot.visitStack)

        val restored = state.reconcile(records(1L, 2L, 3L, 4L))
        assertTrue(restored.snapshot.remainingIds.isEmpty())
        assertFalse(3L in restored.snapshot.remainingIds)
    }

    @Test
    fun sameDayPhotosAreOrderedEarlierOnTheLeftAndLaterOnTheRight() {
        val state = FlipSessionState(
            media = listOf(
                FlipMediaRecord(1L, 1300L),
                FlipMediaRecord(2L, 1100L),
                FlipMediaRecord(3L, 1200L),
                FlipMediaRecord(4L, 500L),
            ),
            initialSeen = setOf(2L, 3L),
            snapshot = FlipSessionSnapshot(
                currentId = 3L,
                remainingIds = listOf(4L),
                visitStack = listOf(2L),
                roundComplete = false,
            ),
            dayKey = { it / 1000L },
        )

        // 同一天内按拍摄时间升序：左侧更早、右侧更晚，与输入顺序无关
        assertEquals(listOf(2L, 3L, 1L), state.sameDayIds(3L))
        assertEquals(1L, state.targetFor(FlipDirection.LEFT))
        assertEquals(2L, state.targetFor(FlipDirection.RIGHT))
    }

    @Test
    fun deletingCurrentPrefersSameDayRightNeighborThenLeftThenNextDeckItem() {
        fun state(currentId: Long) = FlipSessionState(
            media = listOf(
                FlipMediaRecord(1L, 1100L),
                FlipMediaRecord(2L, 1200L),
                FlipMediaRecord(3L, 1300L),
                FlipMediaRecord(4L, 1400L),
                FlipMediaRecord(5L, 500L),
            ),
            initialSeen = setOf(currentId),
            snapshot = FlipSessionSnapshot(
                currentId = currentId,
                remainingIds = listOf(5L),
                visitStack = emptyList(),
                roundComplete = false,
            ),
            dayKey = { it / 1000L },
        )

        // 删除 2：优先同一天右侧（时间更晚）的 3
        val removedMiddle = state(2L).reconcile(
            listOf(
                FlipMediaRecord(1L, 1100L),
                FlipMediaRecord(3L, 1300L),
                FlipMediaRecord(4L, 1400L),
                FlipMediaRecord(5L, 500L),
            ),
        )
        assertEquals(3L, removedMiddle.snapshot.currentId)

        // 删除当天最晚的 4：右侧没有了，落回左侧最近的 3
        val removedLatest = state(4L).reconcile(
            listOf(
                FlipMediaRecord(1L, 1100L),
                FlipMediaRecord(2L, 1200L),
                FlipMediaRecord(3L, 1300L),
                FlipMediaRecord(5L, 500L),
            ),
        )
        assertEquals(3L, removedLatest.snapshot.currentId)

        // 当天一张照片都不剩：显示下一张（牌组头），不回退历史栈
        val removedWholeDay = state(1L).reconcile(listOf(FlipMediaRecord(5L, 500L)))
        assertEquals(5L, removedWholeDay.snapshot.currentId)
    }

    @Test
    fun restartClearsHistoryAndAtomicallyDescribesReplacementSeenSet() {
        val state = FlipSessionState(
            media = records(1L, 2L, 3L, 4L),
            initialSeen = setOf(1L, 2L),
            snapshot = FlipSessionSnapshot(
                currentId = 2L,
                remainingIds = listOf(3L, 4L),
                visitStack = listOf(1L),
                roundComplete = false,
            ),
            random = Random(11),
            dayKey = { it },
        )

        val update = state.restartRound()

        assertEquals(setOf(2L), update.resetSeenTo)
        assertEquals(2L, update.shownId)
        assertTrue(update.snapshot.visitStack.isEmpty())
        assertEquals(setOf(1L, 3L, 4L), update.snapshot.remainingIds.toSet())
        assertEquals(1, state.seenCount)
        assertFalse(update.snapshot.roundComplete)
    }

    @Test
    fun persistedCompletionSurvivesColdStartButNewMediaReopensRound() {
        val state = FlipSessionState(
            media = records(1L, 2L),
            initialSeen = setOf(1L, 2L),
            snapshot = FlipSessionSnapshot(
                currentId = 2L,
                remainingIds = emptyList(),
                visitStack = listOf(1L),
                roundComplete = true,
            ),
            random = Random(2),
            dayKey = { it },
        )
        assertTrue(state.roundComplete)

        val update = state.reconcile(records(1L, 2L, 3L))

        assertFalse(update.snapshot.roundComplete)
        assertEquals(listOf(3L), update.snapshot.remainingIds)
    }

    @Test
    fun dayNavigationUsesPrecomputedGroupsAndRejectsStaleCommit() {
        val state = FlipSessionState(
            media = listOf(
                FlipMediaRecord(1L, 10L),
                FlipMediaRecord(2L, 10L),
                FlipMediaRecord(3L, 11L),
            ),
            initialSeen = setOf(1L, 2L),
            snapshot = FlipSessionSnapshot(
                currentId = 1L,
                remainingIds = listOf(3L),
                visitStack = emptyList(),
                roundComplete = false,
            ),
            random = Random(3),
            dayKey = { it },
        )

        assertEquals(listOf(1L, 2L), state.sameDayIds(1L))
        assertEquals(2L, state.targetFor(FlipDirection.LEFT))
        assertNull(state.targetFor(FlipDirection.RIGHT))

        val before = state.snapshot()
        assertNull(state.commit(FlipDirection.LEFT, 3L))
        assertEquals(before, state.snapshot())

        requireNotNull(state.commit(FlipDirection.LEFT, 2L))
        assertEquals(1L, state.targetFor(FlipDirection.RIGHT))
        assertNull(state.targetFor(FlipDirection.LEFT))
    }

    private fun records(vararg ids: Long): List<FlipMediaRecord> =
        ids.map { FlipMediaRecord(id = it, dateTaken = it) }
}
