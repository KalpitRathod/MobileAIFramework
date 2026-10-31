package com.mobileai.framework.hardware

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log

enum class ThermalLevel {
    NOMINAL,   // Cool, full performance
    LIGHT,     // Slightly warm, normal performance
    MODERATE,  // Warm, monitor closely
    SEVERE,    // Hot, kernel will throttle soon; scale back threads
    CRITICAL   // Dangerously hot, minimize compute immediately
}

/**
 * Monitors device thermal status via Android's PowerManager API.
 * Dynamically scales inference thread counts and duty cycles to prevent
 * abrupt hardware clock throttling and maintain continuous high throughput.
 */
class ThermalGovernor(private val context: Context) {

    private val tag = "ThermalGovernor"
    private var powerManager: PowerManager? = null
    private var thermalListener: Any? = null

    var currentThermalLevel: ThermalLevel = ThermalLevel.NOMINAL
        private set

    var onThermalLevelChanged: ((ThermalLevel) -> Unit)? = null

    fun startMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.let { pm ->
                val listener = PowerManager.OnThermalStatusChangedListener { status ->
                    val level = when (status) {
                        PowerManager.THERMAL_STATUS_NONE -> ThermalLevel.NOMINAL
                        PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.LIGHT
                        PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.MODERATE
                        PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.SEVERE
                        PowerManager.THERMAL_STATUS_CRITICAL,
                        PowerManager.THERMAL_STATUS_EMERGENCY,
                        PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalLevel.CRITICAL
                        else -> ThermalLevel.NOMINAL
                    }
                    if (level != currentThermalLevel) {
                        currentThermalLevel = level
                        Log.i(tag, "Thermal status changed: $level (raw: $status)")
                        onThermalLevelChanged?.invoke(level)
                    }
                }
                pm.addThermalStatusListener(listener)
                thermalListener = listener
                Log.d(tag, "Thermal monitoring active on Android 10+")
            }
        }
    }

    fun stopMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (thermalListener as? PowerManager.OnThermalStatusChangedListener)?.let { listener ->
                powerManager?.removeThermalStatusListener(listener)
            }
            thermalListener = null
        }
    }

    /**
     * Calculates the dynamically throttled thread count according to current thermal headroom.
     * Prevents the OS kernel from dropping SoC clock frequencies from 2.8GHz to 800MHz.
     */
    fun getAdjustedThreads(baseThreads: Int): Int {
        return when (currentThermalLevel) {
            ThermalLevel.NOMINAL, ThermalLevel.LIGHT -> baseThreads
            ThermalLevel.MODERATE -> (baseThreads - 1).coerceAtLeast(2)
            ThermalLevel.SEVERE -> 2.coerceAtMost(baseThreads)
            ThermalLevel.CRITICAL -> 1
        }
    }

    fun getThermalStatusDescription(): String {
        return when (currentThermalLevel) {
            ThermalLevel.NOMINAL -> "Nominal (Cool) • 100% Performance"
            ThermalLevel.LIGHT -> "Light Warmth • 100% Performance"
            ThermalLevel.MODERATE -> "Moderate Heat • Adaptive Thread Scaling Active"
            ThermalLevel.SEVERE -> "Severe Heat • Throttled to 2 Threads (Cooling down)"
            ThermalLevel.CRITICAL -> "Critical Heat • Throttled to 1 Thread"
        }
    }
}

