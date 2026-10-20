package com.example.are_we_there_yet

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
    fun `builds without a key never ask Google`() {
        // CI test builds have no PLACES_API_KEY; a local key would make this moot.
        assumeTrue(BuildConfig.PLACES_API_KEY.isEmpty())
        assertEquals(PlaceFinder.None, GooglePlaces.finder(androidx.test.core.app.ApplicationProvider.getApplicationContext()))
    }
}
