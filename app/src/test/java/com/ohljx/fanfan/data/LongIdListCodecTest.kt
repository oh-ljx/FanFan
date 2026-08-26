package com.ohljx.fanfan.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LongIdListCodecTest {

    @Test
    fun roundTripsOrderedIdsIncludingDuplicates() {
        val ids = listOf(9L, 3L, 3L, Long.MAX_VALUE)
        assertEquals(ids, LongIdListCodec.decode(LongIdListCodec.encode(ids)))
    }

    @Test
    fun emptyAndMalformedValuesDecodeSafely() {
        assertEquals(emptyList<Long>(), LongIdListCodec.decode(""))
        assertEquals(listOf(1L, 2L), LongIdListCodec.decode("1,oops,2,"))
    }
}
