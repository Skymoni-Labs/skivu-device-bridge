package ai.nidhoggfgg.phoneuse

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import ai.nidhoggfgg.phoneuse.ui.PhoneUseApp
import ai.nidhoggfgg.phoneuse.ui.theme.PhoneUseTheme

class MainActivity : AppCompatActivity() {
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) startEndpoint()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            PhoneUseTheme {
                PhoneUseApp(
                    onStartService = ::startEndpoint,
                    onAccessibilitySettings = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    onOpenWebConsole = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:8443/"))) }
                )
            }
        }
        if (getSharedPreferences("service_state", MODE_PRIVATE).getBoolean("enabled", false)) startEndpoint()
    }

    private fun startEndpoint() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED)
            permissions.add("android.permission.ACCESS_LOCAL_NETWORK")
        if (permissions.isNotEmpty()) { permission.launch(permissions.toTypedArray()); return }
        if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)); return
        }
        startForegroundService(Intent(this, PhoneUseService::class.java))
    }
}
