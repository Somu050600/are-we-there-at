package com.example.are_we_there_yet

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// org.json needs Robolectric's implementation.
@RunWith(RobolectricTestRunner::class)
class GooglePlacesTest {
    private val beyondBajji = "https://www.google.com/maps/place/Beyond+Bajji,+Elite+wine+walk+in+Store,+opp.+SITE," +
        "+near+K+V+R+PETROL+BUNK,+Saraswathi+Nagar,+Dargamitta,+Nellore,+Andhra+Pradesh+524003/data=!4m2!3m1" +
        "!1s0x3a4cf32101cd9b9b:0xf4638626f70b8787!18m1!1e1?utm_source=mstt_1&entry=gps"

    private fun place(cid: String?, lat: Double, lng: Double): String {
        val link = if (cid == null) "" else ",\"googleMapsUri\":\"https://maps.google.com/?cid=$cid\""
        return "{\"location\":{\"latitude\":$lat,\"longitude\":$lng}$link}"
    }

    private fun response(vararg places: String) = """{"places":[${places.joinToString(",")}]}"""

    @Test
    fun `the Maps id is the unsigned second half of the feature id`() {
        assertEquals("17610066469860378503", GooglePlaces.cid(beyondBajji))
        assertEquals("2", GooglePlaces.cid("https://maps.google.com/?q=Cafe&ftid=0x1:0x2"))
        assertNull(GooglePlaces.cid("https://www.google.com/maps/place/Cafe/@12.9,77.5,17z"))
    }

    @Test
    fun `the result with the link's Maps id is exact even when it is not first`() {
        val picked = GooglePlaces.pick(
            response(
                place("111", 14.41, 79.96),
                place("17610066469860378503", 14.4372674, 79.9705642),
            ),
            "17610066469860378503",
        )!!
        assertEquals(14.4372674, picked.lat, 0.0)
        assertEquals(79.9705642, picked.lng, 0.0)
        assertFalse(picked.approximate)
    }

    @Test
    fun `without a matching id the top result is approximate`() {
        val other = GooglePlaces.pick(response(place("111", 14.41, 79.96), place("222", 14.5, 80.0)), "999")!!
        assertEquals(14.41, other.lat, 0.0)
        assertTrue(other.approximate)
        assertTrue(GooglePlaces.pick(response(place(null, 14.41, 79.96)), null)!!.approximate)
    }

    @Test
    fun `empty, broken or location-less responses find nothing`() {
        assertNull(GooglePlaces.pick("{}", "1"))
        assertNull(GooglePlaces.pick("""{"places":[]}""", "1"))
        assertNull(GooglePlaces.pick("not json", "1"))
        assertNull(GooglePlaces.pick("""{"places":[{"googleMapsUri":"https://maps.google.com/?cid=1"}]}""", "1"))
    }

    @Test
    fun `a feature id converts to the place id Google uses`() {
        // Google's own example: Google Sydney, ?cid=10281119596374313554.
        assertEquals("ChIJN1t_tDeuEmsRUsoyG83frY4", GooglePlaces.placeId("0x6b12ae37b47f5b37:0x8eaddfcd1b32ca52"))
        assertEquals("ChIJm5vNASHzTDoRh4cL9yaGY_Q", GooglePlaces.placeId(GooglePlaces.feature(beyondBajji)!!))
        assertEquals("0x3a4cf32101cd9b9b:0xf4638626f70b8787", GooglePlaces.feature(beyondBajji))
        assertNull(GooglePlaces.placeId("Cafe"))
    }

    @Test
    fun `place details and suggestions are read from Google's responses`() {
        val details = GooglePlaces.location("""{"location":{"latitude":14.4372674,"longitude":79.9705642}}""")!!
        assertEquals(14.4372674, details.lat, 0.0)
        assertNull(GooglePlaces.location("{}"))

        val suggestions = GooglePlaces.suggestions(
            """{"suggestions":[
              {"placePrediction":{"placeId":"ChIJa","text":{"text":"Beyond Bajji, Nellore"},
                "structuredFormat":{"mainText":{"text":"Beyond Bajji"},"secondaryText":{"text":"Nellore, Andhra Pradesh"}}}},
              {"queryPrediction":{"text":{"text":"bajji near me"}}},
              {"placePrediction":{"placeId":"ChIJb","text":{"text":"Bajji Corner"}}}
            ]}""",
        )
        assertEquals(
            listOf(
                PlaceSuggestion("ChIJa", "Beyond Bajji", "Nellore, Andhra Pradesh"),
                PlaceSuggestion("ChIJb", "Bajji Corner", null),
            ),
            suggestions,
        )
        assertTrue(GooglePlaces.suggestions("not json").isEmpty())
    }

    private class FakeLookup(
        private val details: GooglePlaces.Details,
        private val search: String? = null,
    ) : GooglePlaces.Lookup {
        val asked = mutableListOf<String>()
        override fun details(placeId: String, session: String?): GooglePlaces.Details {
            asked += "details $placeId"
            return details
        }
        override fun search(query: String): String? {
            asked += "search"
            return search
        }
    }

    private val cache get() = PlaceCache(ApplicationProvider.getApplicationContext())
    private val feature = "0x3a4cf32101cd9b9b:0xf4638626f70b8787"
    private val bajji = IntentParser.ParsedLocation(14.4372674, 79.9705642)

    @Test
    fun `a shared place costs one details request, then none`() = runBlocking {
        val lookup = FakeLookup(GooglePlaces.Details.Found(bajji))
        val finder = GooglePlaces.finder(lookup, cache, searchFallback = true)

        assertEquals(bajji, finder.find("Beyond Bajji", feature))
        assertEquals(bajji, finder.find("Beyond Bajji", feature))
        assertEquals(listOf("details ChIJm5vNASHzTDoRh4cL9yaGY_Q"), lookup.asked)
    }

    @Test
    fun `a place Google doesn't know by id is searched for, unless that is off`() = runBlocking {
        val response = response(place("17610066469860378503", 14.4372674, 79.9705642))
        val lookup = FakeLookup(GooglePlaces.Details.Missing, response)
        assertEquals(bajji, GooglePlaces.finder(lookup, cache, searchFallback = true).find("Beyond Bajji", feature))
        assertEquals("search", lookup.asked.last())

        val strict = FakeLookup(GooglePlaces.Details.Missing, response)
        val failure = runCatching {
            GooglePlaces.finder(strict, PlaceCache(ApplicationProvider.getApplicationContext()) { 0 }, false)
                .find("Beyond Bajji", "0x1:0x2")
        }.exceptionOrNull()
        assertTrue(failure is PlaceNotFoundException)
        assertFalse("search" in strict.asked)
    }

    @Test
    fun `without a feature id only the search can find the place`() = runBlocking {
        val lookup = FakeLookup(GooglePlaces.Details.Missing, response(place(null, 12.9, 77.6)))
        val found = GooglePlaces.finder(lookup, cache, searchFallback = true).find("Blue Cafe, Bengaluru", null)!!
        assertTrue(found.approximate)
        assertEquals(listOf("search"), lookup.asked)
        assertNull(GooglePlaces.finder(lookup, cache, searchFallback = false).find("Blue Cafe", null))
    }

    @Test
    fun `builds without a key never ask Google`() {
        // CI test builds have no PLACES_API_KEY; a local key would make this moot.
        assumeTrue(BuildConfig.PLACES_API_KEY.isEmpty())
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals(PlaceFinder.None, GooglePlaces.finder(context, PlaceCache(context), true))
        assertNull(GooglePlaces.client(context))
    }
}
