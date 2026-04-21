package com.aiassistant.domain.tool

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Environment
import android.util.DisplayMetrics
import com.aiassistant.domain.service.ToolManager
import javax.inject.Inject

class DeviceInfoTool @Inject constructor(
    private val context: Context
) {
    init {
        register()
    }

    fun register() {
        ToolManager.registerTool(
            ToolManager.ToolDefinition(
                name = "device_info",
                description = "Get detailed information about the current Android device including model, OS version, battery status, storage, display, and memory.",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "category" to mapOf(
                            "type" to "string",
                            "description" to "Information category: 'all' (default), 'battery', 'storage', 'display', 'system', 'memory'",
                            "enum" to listOf("all", "battery", "storage", "display", "system", "memory")
                        )
                    ),
                    "required" to listOf<Any>()
                ),
                executor = { arguments ->
                    runCatching {
                        val args = com.google.gson.Gson().fromJson(arguments, Map::class.java)
                        val category = args["category"] as? String ?: "all"
                        getDeviceInfo(category)
                    }
                }
            )
        )
    }

    fun getDeviceInfo(category: String): String {
        return try {
            buildString {
                when (category.lowercase()) {
                    "battery" -> append(getBatteryInfo())
                    "storage" -> append(getStorageInfo())
                    "display" -> append(getDisplayInfo())
                    "system" -> append(getSystemInfo())
                    "memory" -> append(getMemoryInfo())
                    else -> {
                        append(getSystemInfo())
                        append("\n\n")
                        append(getBatteryInfo())
                        append("\n\n")
                        append(getStorageInfo())
                        append("\n\n")
                        append(getDisplayInfo())
                        append("\n\n")
                        append(getMemoryInfo())
                    }
                }
            }
        } catch (e: Exception) {
            val errorMsg = e.message ?: e.javaClass.simpleName
            "Error getting device info: $errorMsg"
        }
    }

    private fun getSystemInfo(): String {
        return buildString {
            append("=== System Information ===\n")
            append("Device: ${Build.DEVICE}\n")
            append("Model: ${Build.MODEL}\n")
            append("Brand: ${Build.BRAND}\n")
            append("Manufacturer: ${Build.MANUFACTURER}\n")
            append("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("Security Patch: ${Build.VERSION.SECURITY_PATCH}\n")
            append("Hardware: ${Build.HARDWARE}\n")
            append("Board: ${Build.BOARD}\n")
            append("CPU ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}\n")
            append("Product: ${Build.PRODUCT}\n")
            append("Fingerprint: ${Build.FINGERPRINT}\n")
        }
    }

    private fun getBatteryInfo(): String {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryIntent = context.registerReceiver(null, intentFilter)

        var level = -1
        var scale = -1
        var statusInt = -1
        var healthInt = -1
        var voltage = -1
        var temperature = -1

        if (batteryIntent != null) {
            level = batteryIntent.getIntExtra("android.intent.extra.LEVEL", -1)
            scale = batteryIntent.getIntExtra("android.intent.extra.SCALE", -1)
            statusInt = batteryIntent.getIntExtra("android.intent.extra.STATUS", -1)
            healthInt = batteryIntent.getIntExtra("android.intent.extra.HEALTH", -1)
            voltage = batteryIntent.getIntExtra("android.intent.extra.VOLTAGE", -1)
            temperature = batteryIntent.getIntExtra("android.intent.extra.TEMPERATURE", -1)
        }

        if (level < 0 || statusInt < 0) {
            try {
                val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
                if (level < 0) level = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                if (statusInt < 0) statusInt = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_STATUS)
                if (voltage < 0) voltage = batteryManager.getIntProperty(11)
                if (temperature < 0) temperature = batteryManager.getIntProperty(10)
            } catch (e: SecurityException) {
                // BATTERY_STATS permission not granted
            } catch (e: Exception) {
                // BatteryManager API not available
            }
        }

        val percentage = if (level >= 0 && scale > 0) (level * 100 / scale) else -1

        val statusString = when (statusInt) {
            android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            android.os.BatteryManager.BATTERY_STATUS_FULL -> "Full"
            android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
            android.os.BatteryManager.BATTERY_STATUS_UNKNOWN -> "Unknown"
            else -> "Unknown"
        }

        val healthString = when (healthInt) {
            android.os.BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            android.os.BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            android.os.BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
            android.os.BatteryManager.BATTERY_HEALTH_UNKNOWN -> "Unknown"
            android.os.BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "Unknown"
        }

        val chargeType = if (batteryIntent != null) {
            when (batteryIntent.getIntExtra("android.intent.extra.PLUGGED", -1)) {
                1 -> "USB"
                2 -> "AC"
                4 -> "Wireless"
                64 -> "Dock"
                else -> "Not plugged"
            }
        } else "Not plugged"

        val batteryTech = if (batteryIntent != null) {
            batteryIntent.getStringExtra("android.intent.extra.TECHNOLOGY") ?: "Unknown"
        } else "Unknown"

        val batteryPresent = if (batteryIntent != null) {
            batteryIntent.getBooleanExtra("android.intent.extra.PRESENT", false)
        } else false

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val isPowerSave = powerManager.isPowerSaveMode

        return buildString {
            append("=== Battery Information ===\n")
            append("Level: ${if (percentage >= 0) "$percentage%" else "Unknown"}\n")
            append("Status: $statusString\n")
            append("Health: $healthString\n")
            append("Voltage: ${if (voltage >= 0) "$voltage mV" else "Unknown"}\n")
            append("Temperature: ${if (temperature >= 0) "${temperature / 10.0}°C" else "Unknown"}\n")
            append("Charging: $chargeType\n")
            append("Technology: $batteryTech\n")
            append("Present: $batteryPresent\n")
            append("Power Save Mode: $isPowerSave\n")
        }
    }

    private fun getStorageInfo(): String {
        return try {
            val dataDir = context.filesDir
            val totalSpace = dataDir.totalSpace
            val usableSpace = dataDir.usableSpace
            val usedSpace = totalSpace - usableSpace

            fun formatSize(bytes: Long): String {
                return when {
                    bytes < 1024 -> "$bytes B"
                    bytes < 1024 * 1024 -> String.format("%.2f KB", bytes.toDouble() / 1024)
                    bytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", bytes.toDouble() / (1024 * 1024))
                    else -> String.format("%.2f GB", bytes.toDouble() / (1024 * 1024 * 1024))
                }
            }

            val externalStorage = Environment.getExternalStorageState()
            val externalDir = Environment.getExternalStorageDirectory()

            buildString {
                append("=== Storage Information ===\n")
                append("Internal Storage:\n")
                append("  Total: ${formatSize(totalSpace)}\n")
                append("  Used: ${formatSize(usedSpace)}\n")
                append("  Available: ${formatSize(usableSpace)}\n")
                append("  Usage: ${String.format("%.1f", (usedSpace.toDouble() / totalSpace * 100))}%\n")

                if (externalStorage == Environment.MEDIA_MOUNTED || externalStorage == Environment.MEDIA_MOUNTED_READ_ONLY) {
                    if (externalDir != null) {
                        val extTotal = externalDir.totalSpace
                        val extUsable = externalDir.usableSpace
                        append("External Storage:\n")
                        append("  Total: ${formatSize(extTotal)}\n")
                        append("  Available: ${formatSize(extUsable)}\n")
                    }
                } else {
                    append("External Storage: Not available\n")
                }

                append("App Data Directory: ${context.filesDir.path}\n")
                append("App Cache Directory: ${context.cacheDir.path}\n")
                append("App External Files: ${context.getExternalFilesDir(null)?.path}\n")
            }
        } catch (e: Exception) {
            val errorMsg = e.message ?: e.javaClass.simpleName
            "Error getting storage info: $errorMsg"
        }
    }

    private fun getDisplayInfo(): String {
        val display = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val metrics = DisplayMetrics()
        display.defaultDisplay.getMetrics(metrics)

        val densityDpi = metrics.densityDpi
        val density = metrics.density
        val widthPx = metrics.widthPixels
        val heightPx = metrics.heightPixels

        fun pxToDp(px: Float): Float = px / density

        return buildString {
            append("=== Display Information ===\n")
            append("Resolution: ${widthPx} x ${heightPx} px\n")
            append("Density: $densityDpi dpi (${String.format("%.2f", density)}x)\n")
            append("Screen Width: ${String.format("%.1f", pxToDp(widthPx.toFloat()))} dp\n")
            append("Screen Height: ${String.format("%.1f", pxToDp(heightPx.toFloat()))} dp\n")

            val diagonalPx = Math.sqrt(
                (widthPx.toDouble() * widthPx + heightPx.toDouble() * heightPx)
            )
            val diagonalInches = diagonalPx / densityDpi
            append("Diagonal: ${String.format("%.2f", diagonalInches)} inches\n")
        }
    }

    private fun getMemoryInfo(): String {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memoryInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)

            val runtime = Runtime.getRuntime()
            val totalMemory = runtime.totalMemory()
            val freeMemory = runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            val usedMemory = totalMemory - freeMemory

            fun formatBytes(bytes: Long): String {
                return when {
                    bytes < 1024 -> "$bytes B"
                    bytes < 1024 * 1024 -> String.format("%.2f MB", bytes.toDouble() / (1024 * 1024))
                    else -> String.format("%.2f GB", bytes.toDouble() / (1024 * 1024 * 1024))
                }
            }

            buildString {
                append("=== Memory Information ===\n")
                append("Device Memory:\n")
                append("  Total: ${formatBytes(memoryInfo.totalMem)}\n")
                append("  Available: ${formatBytes(memoryInfo.availMem)}\n")
                append("  Threshold: ${formatBytes(memoryInfo.threshold)}\n")
                append("  Low Memory: ${if (memoryInfo.lowMemory) "Yes" else "No"}\n")
                append("\n")
                append("App JVM Memory:\n")
                append("  Used: ${formatBytes(usedMemory)}\n")
                append("  Total: ${formatBytes(totalMemory)}\n")
                append("  Max: ${formatBytes(maxMemory)}\n")
            }
        } catch (e: Exception) {
            val errorMsg = e.message ?: e.javaClass.simpleName
            "Error getting memory info: $errorMsg"
        }
    }

    
}
