package app.basis.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

class RingBufferTest {
    @Test
    fun keepsOnlyLastItems() {
        val rb = RingBuffer<Int>(3)
        (1..5).forEach(rb::add)
        assertEquals(listOf(3, 4, 5), rb.toList())
        assertEquals(3, rb.size)
    }

    @Test
    fun clearEmpties() {
        val rb = RingBuffer<Int>(2)
        rb.add(1)
        rb.clear()
        assertEquals(emptyList<Int>(), rb.toList())
    }

    @Test
    fun formatsDurations() {
        assertEquals("45 с", formatDuration(45))
        assertEquals("3 мин 12 с", formatDuration(192))
        assertEquals("1 ч 05 мин", formatDuration(3900))
    }
}
