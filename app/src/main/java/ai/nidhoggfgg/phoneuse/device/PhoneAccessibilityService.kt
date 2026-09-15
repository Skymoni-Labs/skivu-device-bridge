package ai.nidhoggfgg.phoneuse.device

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.atomic.AtomicLong

class PhoneAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { current = this; version.incrementAndGet() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null && event.eventType and (
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_VIEW_SCROLLED or
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
        ) != 0) version.incrementAndGet()
    }
    override fun onInterrupt() { version.incrementAndGet() }
    override fun onDestroy() {
        if (current === this) current = null
        version.incrementAndGet()
        super.onDestroy()
    }
    companion object {
        @Volatile var current: PhoneAccessibilityService? = null
            private set
        val version = AtomicLong()
    }
}
