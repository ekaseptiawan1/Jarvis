package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class JarvisAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    companion object {
        @Volatile private var instance: JarvisAccessibilityService? = null
        fun global(action: String): Boolean {
            val service = instance ?: return false
            val id = when (action) {
                "back" -> GLOBAL_ACTION_BACK
                "home" -> GLOBAL_ACTION_HOME
                "recents" -> GLOBAL_ACTION_RECENTS
                "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
                "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
                else -> return false
            }
            return service.performGlobalAction(id)
        }
    }
}