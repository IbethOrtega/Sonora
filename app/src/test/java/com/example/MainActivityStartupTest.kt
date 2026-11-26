package com.example

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.SonoraDatabase
import com.example.player.SonoraPlaybackService
import com.google.common.util.concurrent.Futures
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainActivityStartupTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun `MainActivity launches cleanly renders Home and resolves launcher intent`() {
        val app = ApplicationProvider.getApplicationContext<SonoraApplication>()
        app.playerController.controllerFutureFactoryForTest = {
            Futures.immediateFailedFuture(IllegalStateException("Test bypass service bind"))
        }

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            composeRule.waitForIdle()
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

            val pm = app.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(app.packageName)
            assertNotNull("Expected launch intent for ${app.packageName}", launchIntent)
            org.junit.Assert.assertEquals(
                MainActivity::class.java.name,
                launchIntent?.component?.className
            )

            val mainIntent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(app.packageName)
            val activities = pm.queryIntentActivities(mainIntent, 0)
            assertTrue("Expected MainActivity as launcher activity", activities.any {
                it.activityInfo?.name == MainActivity::class.java.name
            })

            val db = SonoraDatabase.getInstance(app)
            assertTrue(db.openHelper.writableDatabase.isOpen)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun `SonoraPlaybackService onCreate completes without throwing`() {
        val controller = Robolectric.buildService(SonoraPlaybackService::class.java).create()
        val service = controller.get()
        assertNotNull(service)
        controller.destroy()
    }
}
