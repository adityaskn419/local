package com.devtools.remoteagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Full accessibility control surface: global navigation actions, arbitrary
 * gestures, node-level UI automation (find/click/set-text/scroll), screen
 * reading, foreground-app tracking, and notification capture.
 *
 * Persists across reboot and auto-starts once enabled — no root, no accounts.
 */
class AccessibilityControlService : AccessibilityService() {

    companion object {
        @Volatile var instance: AccessibilityControlService? = null

        // name -> performGlobalAction code (integer literals so they compile on
        // any SDK; unsupported codes simply return false at runtime).
        val GLOBAL = mapOf(
            "back" to 1, "home" to 2, "recents" to 3, "notifications" to 4,
            "quick_settings" to 5, "power_dialog" to 6, "split_screen" to 7,
            "lock" to 8, "screenshot" to 9, "headset_hook" to 10,
            "a11y_button" to 11, "a11y_button_chooser" to 12, "a11y_shortcut" to 13,
            "all_apps" to 14, "dismiss_shade" to 15,
            "dpad_up" to 16, "dpad_down" to 17, "dpad_left" to 18,
            "dpad_right" to 19, "dpad_center" to 20, "menu" to 21, "media_play_pause" to 22
        )
    }

    @Volatile private var foregroundApp: String = ""
    private val notifs = ArrayDeque<JSONObject>()

    override fun onServiceConnected() { instance = this }
    override fun onInterrupt() {}
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString()
                if (!pkg.isNullOrBlank()) foregroundApp = pkg
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val text = event.text.joinToString(" ") { it?.toString() ?: "" }.trim()
                if (text.isNotEmpty()) {
                    synchronized(notifs) {
                        notifs.addLast(JSONObject()
                            .put("package", event.packageName?.toString() ?: "")
                            .put("text", text)
                            .put("time", System.currentTimeMillis()))
                        while (notifs.size > 50) notifs.removeFirst()
                    }
                }
            }
        }
    }

    // ---- global actions ----
    fun global(name: String): Boolean {
        val code = GLOBAL[name] ?: return false
        return performGlobalAction(code)
    }

    // ---- gestures ----
    fun tap(x: Int, y: Int) = gesture(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 0, 50)
    fun longPress(x: Int, y: Int, ms: Long) = gesture(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 0, ms.coerceIn(200, 5000))
    fun doubleTap(x: Int, y: Int): Boolean { val a = tap(x, y); Thread.sleep(120); val b = tap(x, y); return a || b }
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) =
        gesture(Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }, 0, ms.coerceIn(50, 8000))

    private fun gesture(path: Path, startMs: Long, durMs: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, startMs, durMs)
        val g = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(g, null, null)
    }

    // ---- node / UI automation ----
    fun clickByText(text: String): Boolean = clickNode(rootInActiveWindow?.findAccessibilityNodeInfosByText(text))
    fun clickById(viewId: String): Boolean = clickNode(rootInActiveWindow?.findAccessibilityNodeInfosByViewId(viewId))
    fun clickByDesc(desc: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val match = ArrayList<AccessibilityNodeInfo>()
        collect(root) { it.contentDescription?.toString()?.contains(desc, true) == true && it.isVisibleToUser }.let(match::addAll)
        return clickNode(match)
    }

    private fun clickNode(nodes: List<AccessibilityNodeInfo>?): Boolean {
        val node = nodes?.firstOrNull { it.isVisibleToUser } ?: nodes?.firstOrNull() ?: return false
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun setText(text: String): Boolean {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val scrollable = ArrayList<AccessibilityNodeInfo>()
        collect(root) { it.isScrollable }.let(scrollable::addAll)
        return scrollable.firstOrNull()?.performAction(action) ?: false
    }

    // ---- reads ----
    fun currentApp(): String = foregroundApp
    fun notifications(): JSONArray = synchronized(notifs) { JSONArray().apply { notifs.forEach { put(it) } } }

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        dump(root, 0, sb)
        return sb.toString().take(6000)
    }

    private fun dump(node: AccessibilityNodeInfo?, depth: Int, sb: StringBuilder) {
        node ?: return
        if (sb.length > 6000) return
        val t = node.text?.toString()?.trim()
        val d = node.contentDescription?.toString()?.trim()
        if (!t.isNullOrEmpty() || !d.isNullOrEmpty()) {
            repeat(depth) { sb.append("  ") }
            if (!t.isNullOrEmpty()) sb.append(t)
            if (!d.isNullOrEmpty()) sb.append(if (t.isNullOrEmpty()) d else "  [$d]")
            if (node.isClickable) sb.append("  ·click")
            sb.append('\n')
        }
        for (i in 0 until node.childCount) dump(node.getChild(i), depth + 1, sb)
    }

    private fun collect(node: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) {
            n ?: return
            if (pred(n)) out.add(n)
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(node)
        return out
    }
}
