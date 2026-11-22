package com.androiduse.core

import com.androiduse.protocol.AppsListResponse
import com.androiduse.protocol.LaunchAppRequest

/**
 * Abstraction for enumerating launchable apps and launching apps or URIs.
 */
interface AppController {
    suspend fun listLaunchableApps(query: String? = null): AppsListResponse
    suspend fun launchApp(request: LaunchAppRequest)
}
