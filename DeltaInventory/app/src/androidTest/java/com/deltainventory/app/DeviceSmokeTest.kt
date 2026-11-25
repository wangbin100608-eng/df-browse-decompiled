package com.deltainventory.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

/**
 * 真机冒烟测试：需在有屏幕采集与悬浮窗权限的设备上运行。
 * 本机无 Android 设备环境，仅提供测试代码，未实际执行。
 */
@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    @Test fun packageInstalledAndContextValid() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.deltainventory.app", ctx.packageName)
    }

    @Test fun featureLibraryLoaded() {
        // 在真机上验证 1663 件特征库可加载
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val json = ctx.assets.open("item-features.json").use { it.readBytes().toString(Charsets.UTF_8) }
        assertTrue(json.contains("\"features\""))
        assertTrue(json.length > 100000)
    }
}
