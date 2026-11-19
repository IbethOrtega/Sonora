package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.buildFirebaseOptionsFromResources
import com.example.data.ai.ensureFirebaseAppInitialized
import com.google.firebase.FirebaseApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Sonora", appName)
  }

  @Test
  fun `ensureFirebaseAppInitialized resolves google services options and initializes default app`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val options = buildFirebaseOptionsFromResources(context)
    assertNotNull("Expected google-services options in resources", options)
    assertTrue(options!!.applicationId.isNotBlank())

    val app = ensureFirebaseAppInitialized(context)
    assertNotNull("Expected FirebaseApp to initialize", app)
    assertTrue(FirebaseApp.getApps(context).isNotEmpty())
  }
}
