package com.coco.dense

import org.junit.Assert.assertEquals
import org.junit.Test

class PatternGridTest {
    @Test fun crossesUnselectedMiddlePoint() {
        assertEquals(listOf(0, 1, 2), appendPatternPoint(listOf(0), 2))
        assertEquals(listOf(0, 4, 8), appendPatternPoint(listOf(0), 8))
        assertEquals(listOf(2, 4, 6), appendPatternPoint(listOf(2), 6))
    }

    @Test fun doesNotRepeatSelectedPoint() {
        assertEquals(listOf(0, 1, 2), appendPatternPoint(listOf(0, 1, 2), 1))
        assertEquals(listOf(0, 4, 8), appendPatternPoint(listOf(0, 4), 8))
    }
}
