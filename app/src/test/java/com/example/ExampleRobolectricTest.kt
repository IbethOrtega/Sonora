package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.buildFirebaseOptionsFromResources
import com.example.data.ai.ensureFirebaseAppInitialized
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
  fun `ensureFirebaseAppInitialized initializes when configured or fails safely without google-services`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val options = buildFirebaseOptionsFromResources(context)

    if (options != null) {
      assertTrue(options.applicationId.isNotBlank())
      val app = ensureFirebaseAppInitialized(context)
      assertNotNull("Expected FirebaseApp to initialize from resources", app)
      assertTrue(FirebaseApp.getApps(context).isNotEmpty())
    } else {
      // CI builds without google-services.json use missing.passthrough=true and must not crash
      assertNull(ensureFirebaseAppInitialized(context))

      val syntheticOptions = FirebaseOptions.Builder()
        .setApplicationId("1:123456789012:android:0000000000000000")
        .setApiKey("test-api-key")
        .setProjectId("sonora-ci-test")
        .build()
      val initialized = FirebaseApp.initializeApp(context, syntheticOptions)
      try {
        val resolved = ensureFirebaseAppInitialized(context)
        assertNotNull("Expected ensureFirebaseAppInitialized to return active FirebaseApp", resolved)
        assertEquals(initialized.name, resolved!!.name)
      } finally {
        runCatching { initialized.delete() }
      }
    }
  }
}
