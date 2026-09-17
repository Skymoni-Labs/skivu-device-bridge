package ai.nidhoggfgg.phoneuse

import ai.nidhoggfgg.phoneuse.device.localControlLabels
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalizationTest {
    private fun localized(tags: String): Context {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(tags))
        })
    }

    @Test fun resourcesResolveEnglishChineseAndFallbackWithFormattedValues() {
        val english = localized("en-US")
        val chinese = localized("zh-CN")
        assertEquals("Device name", english.getString(R.string.device_name))
        assertEquals("设备名称", chinese.getString(R.string.device_name))
        assertEquals("Device name", localized("de-DE").getString(R.string.device_name))
        assertEquals("Verification code: 123456", english.getString(R.string.verification_code, "123456"))
        assertEquals("核对码：123456", chinese.getString(R.string.verification_code, "123456"))
        assertEquals("1 second remaining", english.resources.getQuantityString(R.plurals.pairing_seconds_remaining, 1, 1))
        assertEquals("2 seconds remaining", english.resources.getQuantityString(R.plurals.pairing_seconds_remaining, 2, 2))
        assertEquals("剩余 2 秒", chinese.resources.getQuantityString(R.plurals.pairing_seconds_remaining, 2, 2))
    }

    @Test fun notificationProtectionIncludesBothLocalesRegardlessOfCurrentLanguage() {
        val english = localized("en-US")
        val chinese = localized("zh-CN")
        val labels = localControlLabels(english)
        assertEquals(labels, localControlLabels(chinese))
        for (id in listOf(R.string.notification_pause, R.string.notification_resume, R.string.notification_stop)) {
            assertNotEquals(english.getString(id), chinese.getString(id))
            assertTrue(labels.contains(english.getString(id)))
            assertTrue(labels.contains(chinese.getString(id)))
        }
        assertEquals(6, labels.size)
    }
}
