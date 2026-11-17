package com.example.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.example.model.AppIconVariant

object AppIconManager {

    fun applyVariant(context: Context, variant: AppIconVariant): Boolean {
        return runCatching {
            val pm = context.packageManager
            val packageName = context.packageName
            // Enable the selected alias first so there is always at least one active launcher entry
            val targetComponent = ComponentName(packageName, variant.aliasClassName)
            pm.setComponentEnabledSetting(
                targetComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            AppIconVariant.entries.forEach { other ->
                if (other != variant) {
                    val comp = ComponentName(packageName, other.aliasClassName)
                    pm.setComponentEnabledSetting(
                        comp,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
            }
            true
        }.getOrDefault(false)
    }

    fun getActiveVariant(context: Context): AppIconVariant {
        val pm = context.packageManager
        val packageName = context.packageName
        return AppIconVariant.entries.firstOrNull { variant ->
            val state = pm.getComponentEnabledSetting(ComponentName(packageName, variant.aliasClassName))
            state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                (variant == AppIconVariant.EMERALD && state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        } ?: AppIconVariant.EMERALD
    }
}
