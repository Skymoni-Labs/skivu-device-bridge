package ai.nidhoggfgg.phoneuse

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServiceRestorationTest {
    private fun awaitState(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!check() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
        assertTrue("Service did not reach expected state", check())
    }

    @Test fun reopeningRestoresEnabledServiceAndPauseButHonorsExplicitStop() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = context.getSharedPreferences("service_state", Context.MODE_PRIVATE)
        val enabled = settings.getBoolean("enabled", false)
        val paused = settings.getBoolean("paused", false)
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 33) add("android.permission.POST_NOTIFICATIONS")
            if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
        }
        permissions.forEach { permission ->
            instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} $permission").use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
        }
        try {
            // Other device tests may have opened the dashboard and restored the service.
            context.stopService(Intent(context, PhoneUseService::class.java))
            awaitState { PhoneUseService.current == null }
            assertTrue(settings.edit().putBoolean("enabled", true).putBoolean("paused", true).commit())
            ActivityScenario.launch(MainActivity::class.java).use {
                awaitState { PhoneUseService.current != null }
                assertTrue(PhoneUseService.current!!.controller.gate.paused)
                // External destruction preserves the user's enabled setting.
                context.stopService(Intent(context, PhoneUseService::class.java))
                awaitState { PhoneUseService.current == null }
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                awaitState { PhoneUseService.current != null }
                assertTrue(PhoneUseService.current!!.controller.gate.paused)
                instrumentation.runOnMainSync {
                    PhoneUseService.current!!.setPaused(false)
                    PhoneUseService.current!!.stopEndpoint()
                }
                awaitState { PhoneUseService.current == null }
                assertFalse(settings.getBoolean("enabled", true))
                assertFalse(settings.getBoolean("paused", true))
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                instrumentation.waitForIdleSync()
                assertNull(PhoneUseService.current)
                assertFalse(settings.getBoolean("enabled", true))
            }
        } finally {
            context.stopService(Intent(context, PhoneUseService::class.java))
            awaitState { PhoneUseService.current == null }
            settings.edit().putBoolean("enabled", enabled).putBoolean("paused", paused).commit()
        }
    }
}
