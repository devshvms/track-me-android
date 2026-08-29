package `in`.shvms.trackme.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OrderedWriteChainTest {

    @Test
    fun `writes finish in enqueue order`() = runTest {
        val firstMayFinish = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val chain = OrderedWriteChain(this)

        chain.enqueue {
            events += "first-started"
            firstMayFinish.await()
            events += "first-finished"
        }
        chain.enqueue { events += "second" }

        runCurrent()
        assertEquals(listOf("first-started"), events)

        firstMayFinish.complete(Unit)
        chain.awaitPending()

        assertEquals(listOf("first-started", "first-finished", "second"), events)
    }

    @Test
    fun `await pending does not return before the captured tail`() = runTest {
        val writeMayFinish = CompletableDeferred<Unit>()
        val chain = OrderedWriteChain(this)
        var drained = false

        chain.enqueue { writeMayFinish.await() }
        val waiter = launch {
            chain.awaitPending()
            drained = true
        }

        runCurrent()
        assertFalse(drained)

        writeMayFinish.complete(Unit)
        waiter.join()
        assertTrue(drained)
    }
}
