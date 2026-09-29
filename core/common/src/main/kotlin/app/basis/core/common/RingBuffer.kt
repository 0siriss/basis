package app.basis.core.common

/** Fixed-capacity FIFO; oldest elements are dropped. Not thread-safe. */
class RingBuffer<T>(private val capacity: Int) {
    init {
        require(capacity > 0) { "capacity must be > 0" }
    }

    private val items = ArrayDeque<T>(capacity)

    val size: Int get() = items.size

    fun add(item: T) {
        if (items.size == capacity) items.removeFirst()
        items.addLast(item)
    }

    fun clear() = items.clear()

    fun toList(): List<T> = items.toList()
}
