package io.github.jqssun.airplay.renderer

/** FIFO with backpressure, session invalidation, and polling while no input arrives. */
internal class BoundedFrameWorker<T>(
    private val capacity: Int,
    private val consume: (T, Long) -> Unit,
    private val idle: () -> Unit,
) : AutoCloseable {
    private val monitor = Object()
    private val queue = ArrayDeque<Pair<T, Long>>()
    private var generation = 0L
    private var accepting = false
    private var closed = false
    private val thread: Thread

    init {
        require(capacity > 0)
        thread = Thread({ loop() }, "AirPlayDecoder").apply { start() }
    }

    fun session(active: Boolean) = synchronized(monitor) {
        generation++
        accepting = active && !closed
        queue.clear()
        monitor.notifyAll()
    }

    fun current(token: Long): Boolean = synchronized(monitor) {
        !closed && accepting && token == generation
    }

    fun submit(value: T): Boolean = synchronized(monitor) {
        val token = generation
        while (!closed && accepting && token == generation && queue.size >= capacity) monitor.wait()
        if (closed || !accepting || token != generation) return@synchronized false
        queue.addLast(value to token)
        monitor.notifyAll()
        true
    }

    private fun loop() {
        while (true) {
            val item = synchronized(monitor) {
                if (!closed && queue.isEmpty()) monitor.wait(2)
                if (closed) return
                queue.removeFirstOrNull().also { monitor.notifyAll() }
            }
            if (item != null) consume(item.first, item.second) else idle()
        }
    }

    override fun close() {
        synchronized(monitor) {
            closed = true
            accepting = false
            queue.clear()
            monitor.notifyAll()
        }
        thread.join()
    }
}
