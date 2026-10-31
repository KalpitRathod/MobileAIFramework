package com.mobileai.framework.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class UiElement(
    val index: Int,
    val id: String?,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val bounds: Rect,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean
) {
    val displayLabel: String
        get() = text?.takeIf { it.isNotBlank() }
            ?: contentDescription?.takeIf { it.isNotBlank() }
            ?: id?.substringAfterLast('/')
            ?: className?.substringAfterLast('.')
            ?: "Element_$index"

    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()
}

/**
 * Android Accessibility Service acting as the device control bridge for the AI Agent.
 * Performs screen hierarchy inspection, click/swipe gesture dispatch, and global device navigation.
 */
@Suppress("DEPRECATION")
class PhoneControllerAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PhoneControllerService"
        var instance: PhoneControllerAccessibilityService? = null
            private set

        val isServiceRunning: Boolean
            get() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "PhoneControllerAccessibilityService connected successfully")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        Log.i(TAG, "PhoneControllerAccessibilityService destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Can optionally monitor window state changes
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    /**
     * Dumps the current active window's interactive elements into a clean structured list.
     */
    fun dumpScreenElements(): List<UiElement> {
        val root = rootInActiveWindow ?: return emptyList()
        val elements = mutableListOf<UiElement>()
        var counter = 0

        fun walkTree(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (!node.isVisibleToUser) {
                node.recycle()
                return
            }

            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            val isClickable = node.isClickable
            val isEditable = node.isEditable
            val isScrollable = node.isScrollable

            val hasContent = !text.isNullOrBlank() || !desc.isNullOrBlank()
            val isInteractive = isClickable || isEditable || isScrollable

            if (hasContent || isInteractive) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    elements.add(
                        UiElement(
                            index = counter++,
                            id = node.viewIdResourceName,
                            text = text,
                            contentDescription = desc,
                            className = node.className?.toString(),
                            bounds = rect,
                            isClickable = isClickable,
                            isEditable = isEditable,
                            isScrollable = isScrollable
                        )
                    )
                }
            }

            for (i in 0 until node.childCount) {
                walkTree(node.getChild(i))
            }
            node.recycle()
        }

        walkTree(root)
        return elements
    }

    /**
     * Dispatches a tap/click gesture at the specified screen coordinates.
     */
    suspend fun performClick(x: Float, y: Float): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, null)

        if (!dispatched && cont.isActive) {
            cont.resume(false)
        }
    }

    /**
     * Dispatches a swipe gesture between two points over durationMs.
     */
    suspend fun performSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 300
    ): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(100))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, null)

        if (!dispatched && cont.isActive) {
            cont.resume(false)
        }
    }

    /**
     * Inputs text into an editable view.
     */
    fun inputText(text: String, targetElementId: String? = null): Boolean {
        val root = rootInActiveWindow ?: return false

        // Try finding by element ID first if provided
        if (!targetElementId.isNullOrBlank()) {
            val nodes = root.findAccessibilityNodeInfosByViewId(targetElementId)
            for (node in nodes) {
                if (node.isEditable) {
                    val arguments = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    }
                    val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                    node.recycle()
                    root.recycle()
                    return success
                }
                node.recycle()
            }
        }

        // Fallback: Find currently focused editable node
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val success = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            focused.recycle()
            root.recycle()
            return success
        }

        root.recycle()
        return false
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun pressNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun launchApp(packageName: String): Boolean {
        return try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                true
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "Error launching app: $packageName", e)
            false
        }
    }
}

