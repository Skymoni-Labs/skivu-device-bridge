package ai.nidhoggfgg.phoneuse

import ai.nidhoggfgg.phoneuse.server.DeviceIdentity
import ai.nidhoggfgg.phoneuse.server.readableDeviceName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityTest {
    @Test fun friendlySystemNameReplacesModelCode() {
        assertEquals("Xiaomi 15", readableDeviceName(" Xiaomi 15 ", null, "Xiaomi", "24129PN74C", false))
        assertEquals("我的手机", readableDeviceName("我的手机", "Xiaomi 15", "Xiaomi", "24129PN74C", false))
    }

    @Test fun manufacturerNameIsUsedWhenGlobalNameIsMissingOrOnlyTheModel() {
        assertEquals("Xiaomi 15", readableDeviceName(null, "Xiaomi 15", "Xiaomi", "24129PN74C", false))
        assertEquals("Xiaomi 15", readableDeviceName("24129PN74C", "Xiaomi 15", "Xiaomi", "24129PN74C", false))
    }

    @Test fun unavailableOrInvalidNamesFallBackWithoutInventingAMarketingName() {
        assertEquals("Google Pixel 8", readableDeviceName(" ", "bad\nname", "Google", "Pixel 8", false))
        assertEquals("Xiaomi 15", readableDeviceName(null, null, "Xiaomi", "Xiaomi 15", false))
        assertEquals("Samsung SM-S9280", readableDeviceName("unknown", "x".repeat(81), "samsung", "SM-S9280", false))
        assertEquals("Android", readableDeviceName(null, null, "unknown", "", false))
    }

    @Test fun emulatorDefaultHidesBuildIdentifierButKeepsSystemNickname() {
        assertEquals("Android Emulator", readableDeviceName(null, null, "Google", "sdk_gphone16k_x86_64", true))
        assertEquals("Android Emulator", readableDeviceName("sdk_gphone16k_x86_64", null, "Google", "sdk_gphone16k_x86_64", true))
        assertEquals("Pixel test device", readableDeviceName("Pixel test device", null, "Google", "sdk_gphone16k_x86_64", true))
    }

    @Test fun emulatorMarkerRecognizesAndroidVirtualDevices() {
        assertTrue(DeviceIdentity.isEmulator("google/sdk_gphone64_x86_64/emu64xa:16", "sdk_gphone64_x86_64", "sdk_gphone64_x86_64", "ranchu"))
        assertTrue(DeviceIdentity.isEmulator("generic_x86/sdk", "Android SDK built for x86", "sdk", "goldfish"))
        assertFalse(DeviceIdentity.isEmulator("google/husky/husky:15", "Pixel 8 Pro", "husky", "husky"))
        assertFalse(DeviceIdentity.isEmulator("samsung/device/release", "SM-S9280", "e3qzcx", "qcom"))
    }
}
