package ai.nidhoggfgg.phoneuse

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import ai.nidhoggfgg.phoneuse.server.DeviceIdentity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DashboardSettingsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val servicePreferences = context.getSharedPreferences("service_state", Context.MODE_PRIVATE)
    private val identityPreferences = context.getSharedPreferences("device_identity", Context.MODE_PRIVATE)
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var originalLocales: LocaleListCompat
    private var originalName: String? = null
    private var originalEnabled = false
    private var originalPaused = false
    private val connections = ai.nidhoggfgg.phoneuse.server.ConnectionSettings(context)
    private var originalHttp = false
    private var originalPublicHost = ""

    @Before fun launch() {
        originalLocales = LocaleManagerCompat.getApplicationLocales(context)
        originalName = identityPreferences.getString("name", null)
        originalEnabled = servicePreferences.getBoolean("enabled", false)
        originalPaused = servicePreferences.getBoolean("paused", false)
        originalHttp = connections.allowInsecureHttp
        originalPublicHost = connections.publicHost
        connections.allowInsecureHttp = false
        connections.publicHost = ""
        context.stopService(Intent(context, PhoneUseService::class.java))
        compose.waitUntil(10_000) { PhoneUseService.current == null }
        servicePreferences.edit().putBoolean("enabled", false).putBoolean("paused", false).commit()
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 33) add("android.permission.POST_NOTIFICATIONS")
            if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
        }
        permissions.forEach {
            instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} $it").use { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes()
            }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en")) }
        waitForText("This device")
    }

    @After fun restore() {
        context.stopService(Intent(context, PhoneUseService::class.java))
        compose.waitUntil(10_000) { PhoneUseService.current == null }
        instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(originalLocales) }
        instrumentation.waitForIdleSync()
        scenario.close()
        identityPreferences.edit().apply {
            if (originalName == null) remove("name") else putString("name", originalName)
        }.commit()
        servicePreferences.edit().putBoolean("enabled", originalEnabled).putBoolean("paused", originalPaused).commit()
        connections.allowInsecureHttp = originalHttp
        connections.publicHost = originalPublicHost
    }

    @Test fun deviceNameEditsAreExplicitValidatedAndPersistWithoutChangingIdentity() {
        val identity = DeviceIdentity(context)
        val originalId = identity.id
        val name = identity.name
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("Device ID: $originalId").assertDoesNotExist()
        screenshot("dashboard-en")
        compose.onNodeWithText("Edit").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(" ")
        compose.onNodeWithText("Save name").performClick()
        compose.onNodeWithText("Device name must contain 1–80 printable characters.").assertIsDisplayed()
        assertEquals(name, identity.name)
        compose.onNode(hasSetTextAction()).performTextReplacement("x".repeat(81))
        compose.onNodeWithText("Save name").performClick()
        compose.onNodeWithText("Device name must contain 1–80 printable characters.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText(name).assertIsDisplayed()
        compose.onNodeWithText("Edit").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("  工作手机  ")
        scenario.recreate()
        compose.onNode(hasSetTextAction()).assertTextContains("  工作手机  ")
        compose.onNodeWithText("Save name").performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        scenario.recreate()
        compose.onNodeWithText("工作手机").assertIsDisplayed()
        assertEquals(originalId, DeviceIdentity(context).id)
        assertEquals("工作手机", DeviceIdentity(context).name)
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Device ID: $originalId").assertIsDisplayed()
    }

    @Test fun languageSwitchKeepsSettingsOpenAndPreservesRunningServiceAndPairing() {
        compose.onNodeWithText("Start service").performClick()
        compose.waitUntil(10_000) { PhoneUseService.current != null }
        val service = PhoneUseService.current!!
        val pairing = service.identities.pairing.create("Settings test client", "localhost")
        var clientId: String? = null
        try {
            waitForText("Settings test client")
            compose.onNodeWithText("Approve").performScrollTo().performClick()
            val credential = service.identities.pairing.poll(pairing.getString("pairing_id"), pairing.getString("pairing_secret"))
            clientId = credential.getString("client_id")
            compose.onNodeWithText("Pause control").performScrollTo().performClick()
            compose.waitUntil(5_000) { service.controller.gate.paused }
            compose.onNodeWithContentDescription("Settings").performClick()
            screenshot("settings-en")
            compose.onNodeWithText("中文").performClick()
            waitForText("语言")
            compose.onNodeWithText("中文").assertIsSelected()
            screenshot("settings-zh")
            assertSame(service, PhoneUseService.current)
            assertTrue(service.controller.gate.paused)
            assertEquals(clientId, service.identities.authenticate(credential.getString("token")))
            compose.waitUntil(10_000) {
                context.getSystemService(NotificationManager::class.java).activeNotifications.any { notification ->
                    notification.notification.actions?.any { it.title.toString() == "Phone Use：恢复" } == true
                }
            }
            scenario.recreate()
            waitForText("语言")
            compose.onNodeWithText("中文").assertIsSelected()
            compose.onNodeWithContentDescription("返回").performClick()
            waitForText("本机")
            screenshot("dashboard-zh-running")
            scenario.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            waitForText("本机")
            assertSame(service, PhoneUseService.current)
            compose.onNodeWithContentDescription("设置").performClick()
            compose.onNodeWithText("English").performClick()
            waitForText("Language")
            compose.onNodeWithText("English").assertIsSelected()
            // The theme and language groups both contain this label; choose the language row.
            compose.onAllNodesWithText("Follow system")[1].performScrollTo().performClick()
            compose.waitUntil(5_000) { AppCompatDelegate.getApplicationLocales().isEmpty }
            // Both the toolbar and system Back return from the settings page to the dashboard.
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onAllNodes(isSelectable()).assertCountEquals(0)
        } finally {
            clientId?.let { service.revokeClient(it) }
            service.identities.pairing.clear()
        }
    }


    @Test fun insecureHttpAndPublicAddressPersistAndUpdateTheRunningService() {
        val remote = java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<java.net.Inet4Address>()
            .first { !it.isLoopbackAddress }.hostAddress!!
        fun status(address: String, host: String = address): Int = java.net.Socket(address, 8443).use { socket ->
            socket.soTimeout = 5000
            socket.outputStream.write("GET /identity HTTP/1.1\r\nHost: $host:8443\r\n\r\n".toByteArray())
            socket.inputStream.bufferedReader().readLine().split(' ')[1].toInt()
        }
        compose.onNodeWithText("Start service").performClick()
        compose.waitUntil(10_000) { PhoneUseService.current != null }
        val running = PhoneUseService.current
        assertEquals(200, status("127.0.0.1"))
        assertEquals(403, status(remote))
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Allow insecure HTTP connections").performScrollTo().assertIsOff().performClick()
        assertTrue(connections.allowInsecureHttp)
        assertEquals(200, status(remote))
        assertSame(running, PhoneUseService.current)
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("phone.example")
        compose.onNodeWithText("Save address").performScrollTo().performClick()
        assertEquals("phone.example", connections.publicHost)
        assertEquals(200, status(remote, "phone.example"))
        screenshot("http-settings-en")
        instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh")) }
        waitForText("允许不安全的 HTTP 连接")
        compose.onNodeWithText("保存地址").performScrollTo()
        screenshot("http-settings-zh")
        instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en")) }
        waitForText("Allow insecure HTTP connections")
        scenario.recreate()
        compose.onNodeWithText("Allow insecure HTTP connections").performScrollTo().assertIsOn()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("https://$remote:8443", substring = true).performScrollTo().assertTextContains("http://$remote:8443", substring = true)
        compose.onNodeWithText("https://phone.example:8443", substring = true).assertTextContains("http://phone.example:8443", substring = true)
        compose.onAllNodesWithText("127.0.0.1", substring = true).assertCountEquals(0)
        screenshot("http-addresses-en")
        context.stopService(Intent(context, PhoneUseService::class.java))
        compose.waitUntil(10_000) { PhoneUseService.current == null }
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(10_000) { PhoneUseService.current != null }
        assertEquals(200, status(remote, "phone.example"))
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Allow insecure HTTP connections").performScrollTo().assertIsOn().performClick()
        assertEquals(403, status(remote))
        assertEquals(200, status("127.0.0.1"))
        scenario.recreate()
        compose.onNodeWithText("Allow insecure HTTP connections").performScrollTo().assertIsOff()
        compose.onNodeWithContentDescription("Back").performClick()
        val addresses = compose.onNodeWithText("https://phone.example:8443", substring = true).performScrollTo()
        assertFalse(addresses.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString().contains("http://"))
    }

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun screenshot(name: String) {
        // Only the instrumentation window temporarily permits screenshots; production stays secure.
        scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        try {
            compose.waitForIdle()
            val image = compose.onRoot().captureToImage().asAndroidBitmap()
            val directory = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
    }
}
