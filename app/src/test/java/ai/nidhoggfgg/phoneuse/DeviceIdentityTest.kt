package ai.nidhoggfgg.phoneuse

import ai.nidhoggfgg.phoneuse.server.DeviceIdentity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityTest {
    @Test fun emulatorMarkerRecognizesAndroidVirtualDevices() {
        assertTrue(DeviceIdentity.isEmulator("google/sdk_gphone64_x86_64/emu64xa:16", "sdk_gphone64_x86_64", "sdk_gphone64_x86_64", "ranchu"))
        assertTrue(DeviceIdentity.isEmulator("generic_x86/sdk", "Android SDK built for x86", "sdk", "goldfish"))
        assertFalse(DeviceIdentity.isEmulator("google/husky/husky:15", "Pixel 8 Pro", "husky", "husky"))
        assertFalse(DeviceIdentity.isEmulator("samsung/device/release", "SM-S9280", "e3qzcx", "qcom"))
    }
}
