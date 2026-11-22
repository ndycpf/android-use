package com.androiduse.android

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import com.androiduse.core.AccessibilityServiceRegistry
import com.androiduse.core.DeviceInfoProvider
import com.androiduse.protocol.DeviceInfoResponse

class AndroidDeviceInfoProvider(
    private val context: Context,
    private val accessibilityRegistry: AccessibilityServiceRegistry,
    private val serverVersion: String,
) : DeviceInfoProvider {

    override fun getDeviceInfo(): DeviceInfoResponse {
        val service = accessibilityRegistry.currentService()
        val windowManager = service?.getSystemService(WindowManager::class.java)
            ?: context.getSystemService(WindowManager::class.java)
        val maxBounds = windowManager.maximumWindowMetrics.bounds
        val bounds = if (maxBounds.width() > 0 && maxBounds.height() > 0) {
            maxBounds
        } else {
            windowManager.currentWindowMetrics.bounds
        }
        val displayMetrics = (service ?: context).resources.displayMetrics

        val displayManager = context.getSystemService(DisplayManager::class.java)
        val rotationEnum = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
            ?: Surface.ROTATION_0
        val rotationDegrees = when (rotationEnum) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        val foregroundPackage = runCatching {
            service?.rootInActiveWindow?.packageName?.toString()
        }.getOrNull()

        return DeviceInfoResponse(
            displayWidth = bounds.width(),
            displayHeight = bounds.height(),
            densityDpi = displayMetrics.densityDpi,
            rotation = rotationDegrees,
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            serverVersion = serverVersion,
            accessibilityConnected = accessibilityRegistry.isConnected.value,
            foregroundPackage = foregroundPackage,
        )
    }
}
