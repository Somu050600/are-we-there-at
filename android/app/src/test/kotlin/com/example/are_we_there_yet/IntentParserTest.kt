package com.example.are_we_there_yet

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// IntentParser uses android.net.Uri, which needs Robolectric's implementation.
@RunWith(RobolectricTestRunner::class)
class IntentParserTest {
    @Test
    fun `encoded geo query supplies coordinates and label`() = runBlocking {
        val parsed = IntentParser.parseText(
            "Shared place\ngeo:0,0?q=37.422%2C-122.084%28Googleplex%29",
            NoRedirects,
        )

        assertEquals(37.422, parsed?.lat ?: 0.0, 0.0)
        assertEquals(-122.084, parsed?.lng ?: 0.0, 0.0)
        assertEquals("Googleplex", parsed?.name)
    }

    @Test
    fun `opaque geo URI reads its encoded query without throwing`() = runBlocking {
        val parsed = IntentParser.parseText("geo:0,0?q=51.5%2C-0.12%28Waterloo%29", NoRedirects)

        assertEquals(51.5, parsed?.lat ?: 0.0, 0.0)
        assertEquals(-0.12, parsed?.lng ?: 0.0, 0.0)
        assertEquals("Waterloo", parsed?.name)
    }

    @Test
    fun `search-only geo URI does not turn its zero placeholder into a destination`() = runBlocking {
        assertNull(IntentParser.parseText("geo:0,0?q=London", NoRedirects))
        assertNull(IntentParser.parseText("geo:0,0?q=999,999", NoRedirects))
    }

    @Test
    fun `destination data coordinates win over viewport coordinates`() = runBlocking {
        val parsed = IntentParser.parseText(
            "https://www.google.com/maps/place/Cafe/@51.0,-1.0,12z/data=!4m5!3d40!4d-73",
            NoRedirects,
        )

        assertEquals(40.0, parsed?.lat ?: 0.0, 0.0)
        assertEquals(-73.0, parsed?.lng ?: 0.0, 0.0)
        assertEquals("Cafe", parsed?.name)
    }

    @Test
    fun `invalid explicit destination does not fall back to a valid viewport`() = runBlocking {
        assertNull(
            IntentParser.parseText(
                "https://www.google.com/maps/place/Cafe/@51.0,-1.0,12z/data=!3d999!4d-73",
                NoRedirects,
            ),
        )
    }

    @Test
    fun `coordinates elsewhere in an invalid maps URL are not treated as plain shared text`() = runBlocking {
        assertNull(IntentParser.parseText("https://www.google.com/maps/place/91,181", NoRedirects))
    }

    @Test
    fun `integer and zero coordinates are valid`() = runBlocking {
        val origin = IntentParser.parseText("0, 0", NoRedirects)
        val boundary = IntentParser.parseText("90,-180", NoRedirects)

        assertEquals(0.0, origin?.lat ?: 1.0, 0.0)
        assertEquals(0.0, origin?.lng ?: 1.0, 0.0)
        assertEquals(90.0, boundary?.lat ?: 0.0, 0.0)
        assertEquals(-180.0, boundary?.lng ?: 0.0, 0.0)
    }

    @Test
    fun `out of bounds and non finite coordinates are rejected`() = runBlocking {
        assertNull(IntentParser.parseText("91,0", NoRedirects))
        assertNull(IntentParser.parseText("0,181", NoRedirects))
        assertNull(IntentParser.parseText("NaN,0", NoRedirects))
    }

    @Test
    fun `only a trusted short maps URL is sent to redirect resolver`() = runBlocking {
        val requested = mutableListOf<String>()
        val resolver = RedirectResolver { url ->
            requested += url
            "https://www.google.com/maps/@12,34,10z"
        }

        assertNull(IntentParser.parseText("https://evil.example/secret", resolver))
        val parsed = IntentParser.parseText("See\nhttps://maps.app.goo.gl/abc.", resolver)

        assertEquals(listOf("https://maps.app.goo.gl/abc"), requested)
        assertEquals(12.0, parsed?.lat ?: 0.0, 0.0)
        assertEquals(34.0, parsed?.lng ?: 0.0, 0.0)
    }

    private object NoRedirects : RedirectResolver {
        override suspend fun resolve(url: String): String? = null
    }
}
