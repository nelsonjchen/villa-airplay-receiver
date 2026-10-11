package io.github.jqssun.airplay.renderer

import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BoundedFrameWorkerTest {
    @Test fun preservesFrameOrderAndDrainsWhileIdle() {
        val received = Collections.synchronizedList(mutableListOf<Int>())
        val done = CountDownLatch(100)
        val idle = CountDownLatch(1)
        val worker = BoundedFrameWorker<Int>(2, { value, _ -> received.add(value); done.countDown() }, { idle.countDown() })
        try {
            worker.session(true)
            repeat(100) { assertTrue(worker.submit(it)) }
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertTrue(idle.await(3, TimeUnit.SECONDS))
            assertEquals((0..99).toList(), received)
        } finally { worker.close() }
    }

    @Test fun sessionChangeInvalidatesQueuedFramesAndUnblocksProducer() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val producerDone = CountDownLatch(1)
        val accepted = AtomicBoolean(true)
        var token = -1L
        val worker = BoundedFrameWorker<Int>(1, { _, generation -> token = generation; entered.countDown(); release.await() }, {})
        try {
            worker.session(true)
            worker.submit(1)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertTrue(worker.submit(2))
            val producer = Thread { accepted.set(worker.submit(3)); producerDone.countDown() }.apply { start() }
            awaitBlocked(producer)
            worker.session(false)
            assertFalse(worker.current(token))
            assertTrue(producerDone.await(3, TimeUnit.SECONDS))
            assertFalse(accepted.get())
            assertFalse(worker.submit(4))
        } finally { release.countDown(); worker.close() }
    }

    @Test fun closingUnblocksProducerAndWaitsForConsumer() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val producerDone = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val accepted = AtomicBoolean(true)
        val worker = BoundedFrameWorker<Int>(1, { _, _ -> entered.countDown(); release.await() }, {})
        worker.session(true)
        worker.submit(1)
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        worker.submit(2)
        val producer = Thread { accepted.set(worker.submit(3)); producerDone.countDown() }.apply { start() }
        try {
            awaitBlocked(producer)
            Thread { worker.close(); closed.countDown() }.start()
            assertTrue(producerDone.await(3, TimeUnit.SECONDS))
            assertFalse(accepted.get())
            assertEquals(1L, closed.count)
        } finally { release.countDown(); worker.close() }
        assertTrue(closed.await(3, TimeUnit.SECONDS))
        assertFalse(worker.submit(4))
    }

    private fun awaitBlocked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (thread.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.yield()
        assertEquals(Thread.State.WAITING, thread.state)
    }
}
