package ai.nidhoggfgg.phoneuse

import ai.nidhoggfgg.phoneuse.core.DeviceError
import ai.nidhoggfgg.phoneuse.server.DeviceIdentity
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceIdentityInstrumentedTest {
    @Test fun nameUpdatesAreVisibleAcrossStoresWithoutChangingInstallationId() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("device_identity", Context.MODE_PRIVATE)
        val originalName = preferences.getString("name", null)
        val first = DeviceIdentity(context)
        val second = DeviceIdentity(context)
        try {
            assertEquals(first.id, second.id)
            first.rename("  工作手机  ")
            assertEquals("工作手机", second.name)
            assertEquals("工作手机", second.describe("service-one").getString("name"))
            assertEquals(first.id, DeviceIdentity(context).id)
            try { second.rename("\n\t"); fail("Blank names must be rejected") } catch (_: DeviceError) { }
            try { second.rename("bad\nname"); fail("Control characters must be rejected") } catch (_: DeviceError) { }
            assertEquals("工作手机", first.name)
        } finally {
            val editor = preferences.edit()
            if (originalName == null) editor.remove("name") else editor.putString("name", originalName)
            assertTrue(editor.commit())
        }
    }
}
