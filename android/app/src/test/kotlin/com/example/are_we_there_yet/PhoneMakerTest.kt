package com.example.are_we_there_yet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PhoneMakerTest {
    @Test
    fun `sub-brands map to their maker family`() {
        assertEquals("xiaomi", PhoneMaker.family("Redmi"))
        assertEquals("xiaomi", PhoneMaker.family(" POCO "))
        assertEquals("oppo", PhoneMaker.family("realme"))
        assertEquals("huawei", PhoneMaker.family("HONOR"))
        assertEquals("google", PhoneMaker.family("Google"))
    }

    @Test
    fun `known makers have settings screens to try, others none`() {
        val xiaomi = PhoneMaker.settingsIntents("Xiaomi")
        assertEquals("com.miui.securitycenter", xiaomi.single().component!!.packageName)
        assertTrue(PhoneMaker.settingsIntents("samsung").isNotEmpty())
        assertTrue(PhoneMaker.settingsIntents("Google").isEmpty())
    }
}
