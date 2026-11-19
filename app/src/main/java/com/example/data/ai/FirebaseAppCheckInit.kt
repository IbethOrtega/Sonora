package com.example.data.ai

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

private const val TAG = "AppCheckInit"
private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

@Volatile
private var appCheckInstalled: Boolean = false

fun ensureFirebaseAppInitialized(context: Context): FirebaseApp? {
    val appContext = context.applicationContext ?: context
    FirebaseApp.getApps(appContext).firstOrNull()?.let { return it }

    val defaultApp = runCatching { FirebaseApp.initializeApp(appContext) }.getOrNull()
    if (defaultApp != null) return defaultApp

    val options = buildFirebaseOptionsFromResources(appContext)
    if (options == null) {
        Log.e(TAG, "No FirebaseOptions found in resources for package ${appContext.packageName}.")
        return null
    }

    return runCatching { FirebaseApp.initializeApp(appContext, options) }
        .onFailure { Log.e(TAG, "Explicit FirebaseApp.initializeApp(context, options) failed", it) }
        .getOrNull()
}

internal fun buildFirebaseOptionsFromResources(context: Context): FirebaseOptions? {
    fun lookupString(name: String): String? {
        val packages = linkedSetOf(context.packageName, "com.example")
        for (pkg in packages) {
            if (pkg.isBlank()) continue
            val resId = context.resources.getIdentifier(name, "string", pkg)
            if (resId != 0) {
                val value = runCatching { context.getString(resId).trim() }.getOrNull()
                if (!value.isNullOrBlank()) return value
            }
        }
        return null
    }

    val appId = lookupString("google_app_id") ?: return null
    val apiKey = lookupString("google_api_key") ?: return null

    val builder = FirebaseOptions.Builder()
        .setApplicationId(appId)
        .setApiKey(apiKey)

    lookupString("project_id")?.let(builder::setProjectId)
    lookupString("gcm_defaultSenderId")?.let(builder::setGcmSenderId)
    lookupString("google_storage_bucket")?.let(builder::setStorageBucket)
    lookupString("firebase_database_url")?.let(builder::setDatabaseUrl)

    return builder.build()
}

fun configureAppCheck(context: Context, intent: Intent?) {
    val appContext = context.applicationContext ?: context
    try {
        val firebaseApp = ensureFirebaseAppInitialized(appContext)
        if (firebaseApp == null) {
            Log.e(TAG, "FirebaseApp could not be initialized; skipping App Check setup.")
            return
        }

        if (BuildConfig.DEBUG) {
            val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)
            if (!debugToken.isNullOrEmpty()) {
                val persistenceKey = firebaseApp.persistenceKey
                val prefs = appContext.getSharedPreferences(
                    "$DEBUG_STORE_PREFIX$persistenceKey",
                    Context.MODE_PRIVATE
                )
                // Must commit synchronously before DebugAppCheckProviderFactory initializes
                prefs.edit()
                    .putString(DEBUG_SECRET_KEY, debugToken)
                    .commit()
                Log.d(TAG, "App Check debug secret configured successfully.")
            } else {
                Log.w(TAG, "No debug token found in Intent extra.")
            }

            FirebaseAppCheck.getInstance(firebaseApp).installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
            appCheckInstalled = true
            Log.d(TAG, "DebugAppCheckProviderFactory installed.")
        } else {
            val resId = appContext.resources.getIdentifier(RECAPTCHA_SITE_KEY_RES, "string", appContext.packageName)
            if (resId != 0) {
                val siteKey = appContext.getString(resId)
                if (siteKey.isNotBlank()) {
                    FirebaseAppCheck.getInstance(firebaseApp).installAppCheckProviderFactory(
                        RecaptchaAppCheckProviderFactory.getInstance(siteKey)
                    )
                    appCheckInstalled = true
                    Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
                } else {
                    Log.e(TAG, "recaptcha_site_key resource string is blank.")
                }
            } else {
                Log.e(TAG, "recaptcha_site_key string resource not found in package.")
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
    }
}

internal fun ensureFirebaseAndAppCheck(context: Context): FirebaseApp? {
    val app = ensureFirebaseAppInitialized(context)
    if (app != null && !appCheckInstalled) {
        configureAppCheck(context, null)
    }
    return app
}

