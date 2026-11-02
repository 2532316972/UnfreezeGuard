package com.unfreezeguard

import com.topjohnwu.superuser.Shell

/**
 * root shell 单例入口。libsu 在首次 getShell() 时弹出授权，
 * ok=false 表示未获 root（用户拒绝或设备无 root）。
 */
object RootShell {

    @Volatile
    var ok: Boolean = false
        private set

    fun init() {
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10)
        )
        ok = runCatching { Shell.getShell().isRoot }.getOrDefault(false)
    }

    /** 在 root shell 里执行一组命令，包一次异常转 null。 */
    fun <T> cmd(block: () -> T): T? = runCatching(block).getOrNull()
}
