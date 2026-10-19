package com.example.are_we_there_yet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedLocationInboxTest {
    @Test
    fun `shares are consumed once in FIFO order including malformed entries`() {
        val inbox = SharedLocationInbox()
        val malformed = IncomingShare("not a location", "not a location")
        val valid = IncomingShare("geo:1,2", "geo:1,2")
        inbox.add(malformed)
        inbox.add(valid)

        assertEquals(malformed, inbox.take())
        assertEquals(valid, inbox.take())
        assertNull(inbox.take())
    }
}
