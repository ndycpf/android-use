package com.androiduse.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.androiduse.core.AndroidUseException
import com.androiduse.core.AppController
import com.androiduse.protocol.AppInfo
import com.androiduse.protocol.AppsListResponse
import com.androiduse.protocol.LaunchAppRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [AppController] backed by Android [PackageManager] and [Context.startActivity].
 */
class AndroidAppController(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : AppController {

    override suspend fun listLaunchableApps(query: String?): AppsListResponse =
        withContext(ioDispatcher) {
            val pm = context.packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolved = pm.queryIntentActivities(
                launcherIntent,
                PackageManager.ResolveInfoFlags.of(0),
            )

            val filter = query?.trim()?.takeIf { it.isNotEmpty() }
            val byPackage = LinkedHashMap<String, AppInfo>()
            for (info in resolved) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (pkg in byPackage) continue
                val label = info.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
                if (filter == null ||
                    label.contains(filter, ignoreCase = true) ||
                    pkg.contains(filter, ignoreCase = true)
                ) {
                    byPackage[pkg] = AppInfo(packageName = pkg, label = label)
                }
            }

            val sorted = byPackage.values.sortedWith(
                compareBy<AppInfo> { it.label.lowercase() }.thenBy { it.packageName },
            )
            AppsListResponse(count = sorted.size, apps = sorted)
        }

    override suspend fun launchApp(request: LaunchAppRequest) {
        val pkg = request.packageName?.trim()?.takeIf { it.isNotEmpty() }
        val uriString = request.uri?.trim()?.takeIf { it.isNotEmpty() }

        val intent = if (uriString != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
                if (pkg != null) {
                    setPackage(pkg)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else if (pkg != null) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                ?: throw AndroidUseException.appNotFound(pkg)
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launchIntent
        } else {
            throw AndroidUseException.invalidRequest("Either 'packageName' or 'uri' must be provided.")
        }

        withContext(mainDispatcher) {
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                throw AndroidUseException.appNotFound(pkg ?: uriString.orEmpty())
            } catch (t: Throwable) {
                throw AndroidUseException.actionFailed(
                    message = "Failed to launch app/uri: ${t.message}",
                    cause = t,
                )
            }
        }
    }
}
