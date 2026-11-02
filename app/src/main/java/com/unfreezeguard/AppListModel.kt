package com.unfreezeguard

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppRow(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)

object AppListModel {

    @Suppress("DEPRECATION")
    suspend fun load(ctx: Context): List<AppRow> = withContext(Dispatchers.IO) {
        val pm = ctx.packageManager
        pm.getInstalledPackages(PackageManager.GET_ACTIVITIES)
            .asSequence()
            .filter { it.packageName != ctx.packageName }
            .map { info ->
                val app: ApplicationInfo? = info.applicationInfo
                AppRow(
                    packageName = info.packageName,
                    label = app?.loadLabel(pm)?.toString() ?: info.packageName,
                    isSystem = (app?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0,
                )
            }
            .sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
            .toList()
    }
}
