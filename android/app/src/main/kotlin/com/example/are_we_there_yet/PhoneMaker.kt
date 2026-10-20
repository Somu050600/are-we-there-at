package com.example.are_we_there_yet

import android.content.ComponentName
import android.content.Intent
import java.util.Locale

/**
 * Some phone makers stop background apps on top of Android's battery
 * optimization. These are their own settings screens for allowing an app to
 * run (autostart, background activity). The screens move between versions,
 * so each is tried in turn, then the app's details page.
 */
object PhoneMaker {
    private val SCREENS = mapOf(
        "xiaomi" to listOf(
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
        ),
        "oppo" to listOf(
            "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter/com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe/com.oppo.safe.permission.startup.StartupAppListActivity",
        ),
        "vivo" to listOf(
            "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        ),
        "huawei" to listOf(
            "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager/com.huawei.systemmanager.optimize.process.ProtectActivity",
        ),
        "samsung" to listOf(
            "com.samsung.android.lool/com.samsung.android.sm.battery.ui.BatteryActivity",
            "com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity",
        ),
        "oneplus" to listOf(
            "com.oneplus.security/com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        ),
    )

    /** Brands that share their parent's software. */
    private val FAMILIES = mapOf(
        "redmi" to "xiaomi", "poco" to "xiaomi",
        "realme" to "oppo",
        "iqoo" to "vivo",
        "honor" to "huawei",
    )

    /** The maker's family name, lowercased, e.g. "xiaomi" for a Redmi phone. */
    fun family(manufacturer: String): String {
        val maker = manufacturer.trim().lowercase(Locale.ROOT)
        return FAMILIES[maker] ?: maker
    }

    fun settingsIntents(manufacturer: String): List<Intent> = SCREENS[family(manufacturer)].orEmpty().map {
        Intent().setComponent(ComponentName.unflattenFromString(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
