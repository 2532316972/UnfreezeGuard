package com.unfreezeguard

import android.content.Context
import org.json.JSONArray
import java.io.File

/** 守护服务（无协程环境）同步读取包名列表。 */
fun PkgStore.loadSync(ctx: Context): Set<String> {
    val f = File(ctx.filesDir, "protected_pkgs.json")
    if (!f.exists()) return emptySet()
    return runCatching {
        val arr = JSONArray(f.readText())
        (0 until arr.length()).map { arr.getString(it) }.toSet()
    }.getOrDefault(emptySet())
}
