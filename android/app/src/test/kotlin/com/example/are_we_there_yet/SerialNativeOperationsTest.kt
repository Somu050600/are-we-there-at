package com.example.are_we_there_yet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SerialNativeOperationsTest {
    @Test
    fun `a second native mutation waits for the first callback`() {
        val queue = SerialNativeOperations()
        val events = mutableListOf<String>()
        var finishFirst: (() -> Unit)? = null

        queue.submit { done ->
            events += "first-start"
            finishFirst = {
                events += "first-end"
                done()
            }
        }
        queue.submit { done ->
            events += "second"
            done()
        }

        assertEquals(listOf("first-start"), events)
        finishFirst?.invoke()
        assertEquals(listOf("first-start", "first-end", "second"), events)
    }

    @Test
    fun `a synchronous operation exception does not jam the queue`() {
        val queue = SerialNativeOperations()
        val events = mutableListOf<String>()

        queue.submit(onFailure = { events += "failed:${it.message}" }) {
            throw IllegalStateException("broken")
        }
        queue.submit { done ->
            events += "second"
            done()
        }

        assertEquals(listOf("failed:broken", "second"), events)
        assertTrue(queue.isIdle())
    }
}
