package com.unfreezeguard

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/** 受保护包名列表的持久化（应用私有目录 JSON）。cached 供守护服务直接读。 */
object PkgStore {

    private const val FILE = "protected_pkgs.json"

    @Volatile
    var cached: Set<String> = emptySet()

    private fun file(ctx: Context): File = File(ctx.filesDir, FILE)

    suspend fun load(ctx: Context): Set<String> = withContext(Dispatchers.IO) {
        val f = file(ctx)
        val loaded = if (!f.exists()) emptySet()
        else runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        }.getOrDefault(emptySet())
        cached = loaded
        loaded
    }

    suspend fun save(ctx: Context, pkgs: Set<String>) = withContext(Dispatchers.IO) {
        file(ctx).writeText(JSONArray(pkgs.toList().sorted()).toString())
        cached = pkgs
        Unit
    }
}
