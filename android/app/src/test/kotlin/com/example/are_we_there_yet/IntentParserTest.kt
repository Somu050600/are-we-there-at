package com.example.are_we_there_yet

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.SocketTimeoutException

// IntentParser uses android.net.Uri, which needs Robolectric's implementation.
@RunWith(RobolectricTestRunner::class)
class IntentParserTest {
    @Test
    fun `unencoded geo line after a title line keeps its coordinates and label`() = runBlocking {
        val parsed = IntentParser.parseText("Majestic\ngeo:0,0?q=12.9767,77.5713(Majestic)", NoRedirects)

        assertEquals(12.9767, parsed?.lat ?: 0.0, 0.0)
        assertEquals(77.5713, parsed?.lng ?: 0.0, 0.0)
        assertEquals("Majestic", parsed?.name)
    }

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

    @Test
    fun `google maps app place link without coordinates is looked up by its address`() = runBlocking {
        val expanded = "https://www.google.com/maps/place/Namma+Shuttle,+27th+Cross+Rd,+near+Nagarjuna+Green+Ridge," +
            "+19th+Mail,+ITI+Layout,+Sector+2,+HSR+Layout,+Bengaluru,+Karnataka+560102/data=!4m2!3m1!1s0x3bae1498cc255553:" +
            "0x7e8124532043ed0e!18m1!1e1?utm_source=mstt_1&entry=gps&g_st=ac"
        val queries = mutableListOf<String>()
        val geocoder = PlaceGeocoder { query ->
            queries += query
            IntentParser.ParsedLocation(12.9116, 77.6474)
        }

        val parsed = IntentParser.parseText(
            "https://maps.app.goo.gl/nuBeRNTAVMWHRRCk7?g_st=ac",
            RedirectResolver { expanded },
            geocoder,
        )

        assertEquals(12.9116, parsed?.lat ?: 0.0, 0.0)
        assertEquals(77.6474, parsed?.lng ?: 0.0, 0.0)
        assertEquals("Namma Shuttle", parsed?.name)
        assertTrue(parsed?.approximate == true)
        assertTrue(queries.first().startsWith("Namma Shuttle, 27th Cross Rd"))
    }

    @Test
    fun `a named place is found exactly by its Maps id before any guess`() = runBlocking {
        val expanded = "https://www.google.com/maps/place/Beyond+Bajji,+Elite+wine+walk+in+Store,+opp.+SITE," +
            "+near+K+V+R+PETROL+BUNK,+Saraswathi+Nagar,+Dargamitta,+Nellore,+Andhra+Pradesh+524003/data=!4m2!3m1" +
            "!1s0x3a4cf32101cd9b9b:0xf4638626f70b8787!18m1!1e1?utm_source=mstt_1&entry=gps"
        val asked = mutableListOf<Pair<String, String?>>()
        val finder = PlaceFinder { query, cid ->
            asked += query to cid
            IntentParser.ParsedLocation(14.4372674, 79.9705642)
        }
        var geocoded = false

        val parsed = IntentParser.parseText(
            "https://maps.app.goo.gl/CbSikGiBXYpUyJxw7",
            RedirectResolver { expanded },
            PlaceGeocoder { geocoded = true; null },
            finder,
        )

        assertEquals(14.4372674, parsed?.lat ?: 0.0, 0.0)
        assertEquals(79.9705642, parsed?.lng ?: 0.0, 0.0)
        assertEquals("Beyond Bajji", parsed?.name)
        assertFalse(parsed!!.approximate)
        assertFalse(geocoded)
        assertEquals("0x3a4cf32101cd9b9b:0xf4638626f70b8787", asked.single().second)
        assertTrue(asked.single().first.startsWith("Beyond Bajji, Elite wine walk in Store"))
    }

    @Test
    fun `without an answer from Google the place is geocoded as before`() = runBlocking {
        val parsed = IntentParser.parseText(
            "https://www.google.com/maps/place/Blue+Cafe,+MG+Road,+Bengaluru/data=!4m2!3m1!1s0x1:0x2",
            NoRedirects,
            PlaceGeocoder { IntentParser.ParsedLocation(12.97, 77.6) },
            PlaceFinder { _, _ -> null },
        )

        assertEquals(12.97, parsed?.lat ?: 0.0, 0.0)
        assertTrue(parsed?.approximate == true)
    }

    @Test
    fun `links with coordinates never ask Google`() = runBlocking {
        var asked = false
        val parsed = IntentParser.parseText(
            "https://www.google.com/maps/place/Cafe/@12.9,77.5,17z/data=!3m1!4b1!4m6!3m5!1s0x1:0x2!8m2!3d12.91!4d77.51",
            NoRedirects,
            PlaceGeocoder.None,
            PlaceFinder { _, _ -> asked = true; null },
        )

        assertEquals(12.91, parsed?.lat ?: 0.0, 0.0)
        assertFalse(asked)
    }

    @Test
    fun `lookup retries with the address alone when the full name finds nothing`() = runBlocking {
        val queries = mutableListOf<String>()
        val geocoder = PlaceGeocoder { query ->
            queries += query
            if (query.startsWith("MG Road")) IntentParser.ParsedLocation(12.97, 77.6) else null
        }

        val parsed = IntentParser.parseText(
            "https://www.google.com/maps/place/Blue+Cafe,+MG+Road,+Bengaluru/data=!4m2!3m1!1s0x1:0x2",
            NoRedirects,
            geocoder,
        )

        assertEquals(listOf("Blue Cafe, MG Road, Bengaluru", "MG Road, Bengaluru"), queries)
        assertEquals("Blue Cafe, MG Road, Bengaluru", parsed?.name)
        assertTrue(parsed?.approximate == true)
    }

    @Test
    fun `place lookup failure still rejects the share`() = runBlocking {
        assertNull(
            IntentParser.parseText(
                "https://www.google.com/maps/place/Nowhere+Special/data=!4m2!3m1!1s0x1:0x2",
                NoRedirects,
                PlaceGeocoder.None,
            ),
        )
    }

    @Test
    fun `shared name and address lines are looked up when the link has no place`() = runBlocking {
        var asked: String? = null
        val parsed = IntentParser.parseText(
            "Namma Shuttle\n27th Cross Rd, HSR Layout, Bengaluru\nhttps://maps.app.goo.gl/abc",
            NoRedirects,
            PlaceGeocoder { query -> asked = query; IntentParser.ParsedLocation(12.9, 77.6) },
        )

        assertEquals("Namma Shuttle, 27th Cross Rd, HSR Layout, Bengaluru", asked)
        assertTrue(parsed?.approximate == true)
    }

    @Test
    fun `address numbers next to a named place are not read as coordinates`() = runBlocking {
        val parsed = IntentParser.parseText(
            "Block 2, 19 Main Road\nhttps://www.google.com/maps/place/Block+2,+19+Main+Road/data=!4m2!3m1!1s0x1:0x2",
            NoRedirects,
            PlaceGeocoder { IntentParser.ParsedLocation(12.5, 77.5) },
        )

        assertEquals(12.5, parsed?.lat ?: 0.0, 0.0)
        assertTrue(parsed?.approximate == true)
    }

    @Test
    fun `google link variants with coordinates`() = runBlocking {
        val cases = mapOf(
            "https://maps.google.com/?q=12.9716,77.5946" to (12.9716 to 77.5946),
            "https://www.google.co.in/maps/@12.9716,77.5946,15z" to (12.9716 to 77.5946),
            "https://www.google.com/maps/search/?api=1&query=12.9716%2C77.5946" to (12.9716 to 77.5946),
            "https://www.google.com/maps/dir/?api=1&destination=12.9716,77.5946" to (12.9716 to 77.5946),
            "https://www.google.com/maps/dir/Home/12.9716,77.5946/@12.9,77.5,12z" to (12.9716 to 77.5946),
            "https://www.google.com/maps/place/12.9716,77.5946" to (12.9716 to 77.5946),
            "https://www.google.com/maps/search/12.9716,+77.5946" to (12.9716 to 77.5946),
            "https://maps.google.com/maps?daddr=12.9716,77.5946" to (12.9716 to 77.5946),
            "https://www.google.com/maps/place/12%C2%B058'17.8%22N+77%C2%B035'40.6%22E/@12.9,77.5,17z" to
                (12.97161 to 77.59461),
            "https://www.google.com/maps?q=12%C2%B058'17.8%22N+77%C2%B035'40.6%22E" to (12.97161 to 77.59461),
            "https://www.google.com/url?q=https://maps.google.com/?q%3D12.9716,77.5946&sa=D" to (12.9716 to 77.5946),
            "https://consent.google.com/ml?continue=https://www.google.com/maps/@12.9716,77.5946,15z" to
                (12.9716 to 77.5946),
        )
        for ((link, expected) in cases) assertLocation(link, expected)
    }

    @Test
    fun `other map providers`() = runBlocking {
        val cases = mapOf(
            "https://maps.apple.com/?ll=12.9716,77.5946&q=Cubbon%20Park" to (12.9716 to 77.5946),
            "https://maps.apple.com/?daddr=12.9716,77.5946" to (12.9716 to 77.5946),
            "https://maps.apple.com/place?coordinate=12.9716,77.5946&name=Park" to (12.9716 to 77.5946),
            "https://www.openstreetmap.org/?mlat=12.9716&mlon=77.5946#map=17/12.9716/77.5946" to (12.9716 to 77.5946),
            "https://www.openstreetmap.org/#map=17/12.9716/77.5946" to (12.9716 to 77.5946),
            "https://waze.com/ul?ll=12.9716,77.5946&navigate=yes" to (12.9716 to 77.5946),
            "https://www.waze.com/live-map/directions?to=ll.12.9716%2C77.5946" to (12.9716 to 77.5946),
            "https://www.bing.com/maps?cp=12.9716~77.5946&lvl=16" to (12.9716 to 77.5946),
            "https://bing.com/maps/default.aspx?sp=point.12.9716_77.5946_Park" to (12.9716 to 77.5946),
            "https://share.here.com/l/12.9716,77.5946,Park" to (12.9716 to 77.5946),
            "https://wego.here.com/?map=12.9716,77.5946,15,normal" to (12.9716 to 77.5946),
            "https://yandex.com/maps/?pt=77.5946,12.9716&z=16" to (12.9716 to 77.5946),
            "https://tracker.example/live?lat=12.9716&lng=77.5946" to (12.9716 to 77.5946),
        )
        for ((link, expected) in cases) assertLocation(link, expected)
        assertEquals("Cubbon Park", IntentParser.parseText(cases.keys.first(), NoRedirects)?.name)
        assertEquals("Park", IntentParser.parseText("https://share.here.com/l/12.9716,77.5946,Park", NoRedirects)?.name)
    }

    @Test
    fun `coordinates written in degrees, hemispheres and plus codes`() = runBlocking {
        val cases = mapOf(
            "12°58'17.8\"N 77°35'40.6\"E" to (12.97161 to 77.59461),
            "12°58′17.8″S, 77°35′40.6″W" to (-12.97161 to -77.59461),
            "12.9716° N, 77.5946° E" to (12.9716 to 77.5946),
            "N 12.9716 E 77.5946" to (12.9716 to 77.5946),
            "Meet here: 7J4VXHCV+JR" to (12.9716 to 77.5946),
            "-33.8688, 151.2093" to (-33.8688 to 151.2093),
        )
        for ((text, expected) in cases) assertLocation(text, expected, tolerance = 0.0002)
        assertEquals("Cubbon Park", IntentParser.parseText("Cubbon Park 12°58'35.6\"N 77°35'45.1\"E", NoRedirects)?.name)
    }

    @Test
    fun `extra text, invisible characters and wrapping punctuation are cleaned`() = runBlocking {
        val cases = listOf(
            "Check this out!\n​https://maps.google.com/?q=12.9716,77.5946​",
            "Location: <https://maps.google.com/?q=12.9716,77.5946>",
            "\"https://maps.google.com/?q=12.9716,77.5946\".",
            "[Office](https://maps.google.com/?q=12.9716,77.5946)",
            "Here — maps.google.com/?q=12.9716,77.5946 — see you",
            "https://maps.google.com/?q=12.9716,77.5946 🙂",
        )
        for (text in cases) assertLocation(text, 12.9716 to 77.5946)
        assertEquals("Office", IntentParser.parseText("Office\nhttps://maps.google.com/?q=12.9716,77.5946", NoRedirects)?.name)
        assertNull(IntentParser.parseText("Dropped pin\nhttps://maps.google.com/?q=12.9716,77.5946", NoRedirects)?.name)
    }

    @Test
    fun `plain address text is looked up but text around an unrelated link is not`() = runBlocking {
        val geocoder = PlaceGeocoder { IntentParser.ParsedLocation(12.9, 77.6) }

        val address = IntentParser.parseText("27th Cross Rd, HSR Layout, Bengaluru", NoRedirects, geocoder)
        val geoSearch = IntentParser.parseText("geo:0,0?q=Cubbon+Park", NoRedirects, geocoder)
        val article = IntentParser.parseText("Great read about Bengaluru https://news.example/story", NoRedirects, geocoder)

        assertTrue(address?.approximate == true)
        assertEquals("Cubbon Park", geoSearch?.name)
        assertNull(article)
    }

    @Test
    fun `an unreachable short link is reported instead of guessed from the name`() = runBlocking {
        var looked = false
        val failure = runCatching {
            IntentParser.parseText(
                "Beyond Bajji\nNellore, Andhra Pradesh\nhttps://maps.app.goo.gl/CbSikGiBXYpUyJxw7",
                RedirectResolver { throw SocketTimeoutException("failed to connect") },
                PlaceGeocoder { looked = true; IntentParser.ParsedLocation(14.4, 79.9) },
                PlaceFinder { _, _ -> looked = true; IntentParser.ParsedLocation(14.4, 79.9) },
            )
        }.exceptionOrNull()

        assertTrue(failure is ShareUnreachableException)
        assertFalse(looked)
    }

    @Test
    fun `coordinates in the text are used when the short link is unreachable`() = runBlocking {
        val parsed = IntentParser.parseText(
            "12.9716, 77.5946\nhttps://maps.app.goo.gl/abc",
            RedirectResolver { throw SocketTimeoutException("failed to connect") },
        )

        assertEquals(12.9716, parsed?.lat ?: 0.0, 0.0)
        assertEquals(77.5946, parsed?.lng ?: 0.0, 0.0)
    }

    private suspend fun assertLocation(text: String, expected: Pair<Double, Double>, tolerance: Double = 0.0001) {
        val parsed = IntentParser.parseText(text, NoRedirects)
        assertNotNull("No location in $text", parsed)
        assertEquals(text, expected.first, parsed!!.lat, tolerance)
        assertEquals(text, expected.second, parsed.lng, tolerance)
        assertFalse(text, parsed.approximate)
    }

    private object NoRedirects : RedirectResolver {
        override suspend fun resolve(url: String): String? = null
    }
}
