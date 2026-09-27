package com.ohljx.fanfan.ui

import com.ohljx.fanfan.data.NoteEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteThreadingTest {

    @Test
    fun repliesNestUnderTheirParentWithIncreasingDepth() {
        val notes = listOf(
            note(1L),
            note(2L, parentId = 1L),
            note(3L, parentId = 2L),
            note(4L),
            note(5L, parentId = 1L),
        )

        // notesFor 按时间升序返回；线程化后是父→子的先序，深度最多到 2
        assertEquals(
            listOf(1L to 0, 2L to 1, 3L to 2, 5L to 1, 4L to 0),
            threadNotes(notes).map { (note, depth) -> note.id to depth },
        )
    }

    @Test
    fun orphanRepliesFallBackToTopLevel() {
        val notes = listOf(note(1L), note(2L, parentId = 99L))

        assertEquals(
            listOf(1L to 0, 2L to 0),
            threadNotes(notes).map { (note, depth) -> note.id to depth },
        )
    }

    private fun note(id: Long, parentId: Long? = null) =
        NoteEntity(id = id, mediaId = 1L, text = "n$id", createdAt = id, parentId = parentId)
}
