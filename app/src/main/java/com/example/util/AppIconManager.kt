package com.example.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.example.model.AppIconVariant

object AppIconManager {

    fun applyVariant(context: Context, variant: AppIconVariant): Boolean {
        return false
    }

    fun getActiveVariant(context: Context): AppIconVariant {
        return AppIconVariant.EMERALD
    }
}
