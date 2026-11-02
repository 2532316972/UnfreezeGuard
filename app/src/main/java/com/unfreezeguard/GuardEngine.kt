package com.unfreezeguard

import com.topjohnwu.superuser.Shell

/**
 * 防冻结核心逻辑。
 *
 * 原理：
 * - Android 12+ 的「缓存应用冻结器」用内核 cgroup v2 freezer 把后台缓存态进程整体暂停，
 *   冻结状态记录在进程 cgroup 目录的 cgroup.freeze（1=冻结 0=解冻）。
 * - 系统冻结后会认为进程"已冻结"，不会反复重新冻结；我们在背后把该值写回 0，
 *   进程即恢复运行，而系统侧状态标记不变。
 * - 部分「应用速冻」实现用 SIGSTOP 暂停进程（/proc/<pid>/stat 状态位 T），用 kill -CONT 恢复。
 */
object GuardEngine {

    const val LOOP_INTERVAL_MS = 3000L

    /** 每 N 次巡检补刷一次系统豁免（200 × 3s ≈ 10 分钟）。 */
    const val EXEMPT_EVERY_LOOPS = 200

    /** 给目标包补上系统级豁免：后台运行许可 + 待机桶活跃 + Doze 白名单。 */
    fun refreshExemptions(pkgs: Set<String>) {
        for (p in pkgs) {
            RootShell.cmd {
                ShellCompat.cmd(
                    "cmd appops set $p RUN_ANY_IN_BACKGROUND allow",
                    "am set-standby-bucket $p active",
                    "dumpsys deviceidle whitelist +$p",
                )
            }
        }
    }

    /** 巡检一轮，返回采取的恢复动作（人话描述，空列表=无需处理）。 */
    fun sweep(pkgs: Set<String>): List<String> {
        val actions = mutableListOf<String>()
        for (pkg in pkgs) {
            for (pid in pidsOf(pkg)) {
                val freezeFile = freezeFileOf(pid) ?: continue
                val cur = RootShell.cmd {
                    ShellCompat.cmd("cat $freezeFile")
                }?.out?.firstOrNull()?.trim()
                if (cur == "1") {
                    val w = RootShell.cmd { ShellCompat.cmd("echo 0 > $freezeFile") }
                    if (w?.isSuccess == true) actions.add("解冻 $pkg（pid $pid）")
                    else actions.add("解冻 $pkg 失败（写 $freezeFile 被拒）")
                }
                val state = RootShell.cmd {
                    ShellCompat.cmd("sed -n 's/^[0-9]*) \\(.[A-Za-z]\\) .*/\\1/p' /proc/$pid/stat")
                }?.out?.firstOrNull()?.trim()
                if (state == "T") {
                    val k = RootShell.cmd { ShellCompat.cmd("kill -CONT $pid") }
                    if (k?.isSuccess == true) actions.add("恢复 $pkg（pid $pid，SIGSTOP）")
                }
            }
        }
        return actions
    }

    fun pidsOf(pkg: String): List<String> {
        val direct = RootShell.cmd { ShellCompat.cmd("pidof $pkg") }
            ?.out?.firstOrNull()?.trim().orEmpty()
        if (direct.isNotEmpty()) {
            return direct.split(Regex("\\s+")).filter { it.isNotBlank() }
        }
        // 主进程不在（只剩 :sub 进程）时用 ps 兜底
        return RootShell.cmd { ShellCompat.cmd("ps -A -o PID,NAME | grep -F $pkg") }
            ?.out.orEmpty()
            .mapNotNull { line ->
                line.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.toLongOrNull() != null }
            }
    }

    /**
     * 定位进程的 cgroup.freeze 节点：
     * 先从 /proc/<pid>/cgroup 读 "0::/uid_xxx/pid_xxx" 相对路径；
     * 读不到（如 sed 不支持）就按 Android 约定拼 /sys/fs/cgroup/uid_<uid>/pid_<pid>。
     */
    private fun freezeFileOf(pid: String): String? {
        val rel = RootShell.cmd {
            ShellCompat.cmd("sed -n 's/^0::\\(.*\\)$/\\1/p' /proc/$pid/cgroup")
        }?.out?.firstOrNull()?.trim()
        if (!rel.isNullOrEmpty()) {
            val f = "/sys/fs/cgroup$rel/cgroup.freeze"
            if (nodeExists(f)) return f
        }
        val uid = RootShell.cmd { ShellCompat.cmd("stat -c %u /proc/$pid") }
            ?.out?.firstOrNull()?.trim()
        if (!uid.isNullOrEmpty()) {
            val f = "/sys/fs/cgroup/uid_$uid/pid_$pid/cgroup.freeze"
            if (nodeExists(f)) return f
        }
        return null
    }

    private fun nodeExists(path: String): Boolean =
        RootShell.cmd { ShellCompat.cmd("test -f $path") }?.isSuccess == true

    /** 供 UI「验证冻结状态」按钮用：抓当前系统里所有 frozen 相关行。 */
    fun frozenStateSnapshot(): String =
        RootShell.cmd {
            ShellCompat.cmd("dumpsys activity processes | grep -i frozen | head -40")
        }?.out?.joinToString("\n").orEmpty()
}

/** 把多条命令合成一段交给 root shell 顺序执行。 */
private object ShellCompat {
    fun cmd(vararg commands: String) = Shell.cmd(*commands).exec()
}
