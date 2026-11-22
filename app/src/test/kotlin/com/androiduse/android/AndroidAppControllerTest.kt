package com.androiduse.android

import androidx.test.core.app.ApplicationProvider
import com.androiduse.core.AndroidUseException
import com.androiduse.protocol.ErrorCode
import com.androiduse.protocol.LaunchAppRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAppControllerTest {

    @Test
    fun `listLaunchableApps returns valid list and launchApp throws 404 APP_NOT_FOUND for unknown package`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val controller = AndroidAppController(context = context)

        val apps = controller.listLaunchableApps()
        assertEquals(apps.apps.size, apps.count)
        assertTrue(apps.count >= 0)

        try {
            controller.launchApp(LaunchAppRequest(packageName = "com.nonexistent.unknown.pkg"))
            fail("Expected AndroidUseException(APP_NOT_FOUND)")
        } catch (e: AndroidUseException) {
            assertEquals(ErrorCode.APP_NOT_FOUND, e.code)
            assertEquals(404, e.httpStatus)
        }
    }
}
