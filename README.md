# 防冻结守护（UnfreezeGuard）

root 级前台守护服务，让指定 App 在后台也不会被国行 ROM（ColorOS / EMUI 等）的「墓碑冻结」打断，持续保持运行。

## 解决什么问题

Android 12 起，AOSP 引入了「缓存应用冻结器」（cached apps freezer）：内核 cgroup v2 freezer 会把所有缓存态（后台、无可见组件、无前台服务）进程整体暂停；部分 ROM 的「应用速冻」还会用 SIGSTOP 直接暂停进程。典型症状：

- 后台挂着一个带本地服务的应用（例如跑 MCP 服务器），放一会儿就无法再连接
- 「电源管理不限制 / 电池不优化」只防**杀**，防不住**冻结**
- 只能靠小窗（可见态）挂着才正常

把系统冻结器整体关闭的方案（开发者选项 / `cached_apps_freezer`）在国行 ROM 上会被系统改回去，且影响全局。UnfreezeGuard 换一条思路：保留系统冻结行为，用 root 前台服务持续「解冻」你勾选的应用——进程恢复运行后系统会认为它「已冻结」而不反复重冻，你的应用就全程在线。

## 工作原理

守护服务（`specialUse` 前台服务）每 3 秒巡检一次受保护应用：

| 冻结方式 | 检测 | 恢复 |
|---|---|---|
| cgroup v2 freezer（系统缓存冻结器） | 进程 cgroup 目录的 `cgroup.freeze` 读数为 1 | root 写回 0 |
| SIGSTOP（部分「速冻」实现） | `/proc/<pid>/stat` 状态位 `T` | `kill -CONT` |

同时每约 10 分钟补刷一次系统级豁免：

- `cmd appops set <pkg> RUN_ANY_IN_BACKGROUND allow`
- `am set-standby-bucket <pkg> active`
- `dumpsys deviceidle whitelist +<pkg>`

## 功能

- 图形化勾选保护对象：搜索 / 过滤 / 勾选，即时生效、即时保存（`protected_pkgs.json`）
- 通知栏常驻显示实时状态；出现过「已解冻 xxx」说明真的拦下过一次冻结
- 「检查冻结状态」按钮：列出当前系统所有 `frozen` 相关行，验证应用是否 `frozen=false`
- 开机自启（`BOOT_COMPLETED`，仅当已配置保护应用时启动）
- **零网络权限**：不申请 INTERNET，全部逻辑在本机完成

## 系统要求

- Android 11+（minSdk 30；开发验证环境为 ColorOS / Android 16）
- root（Magisk / KernelSU 等 libsu 可接管的主流方案）
- 授予通知权限（Android 13+）

## 安装

从 [Releases](../../releases) 下载预编译 APK，或本地构建：

```bash
# 需要 JDK 17/21，Gradle 走 wrapper 自动下载
.\gradlew.bat assembleDebug
# 产物：app\build\outputs\apk\debug\app-debug.apk
```

工具链：Gradle 8.7 + AGP 8.2.0 + Kotlin 1.9.20 + Compose。

## 使用

1. 打开应用，首次会请求 superuser（建议勾选「永久授予」）；
2. 授予通知权限；
3. 搜索并勾选要保活的应用；
4. 点「启动守护」，通知栏出现常驻通知即完成；
5. 想验证时点「检查冻结状态」，确认你的应用 `frozen=false`。

> ⚠️ 如果你同时使用 Thanox、LBE 等冻结 / 速冻工具，**不要把保护对象加进它们的冻结列表**，两者会互相打架。

## 免责声明

本工具使用 root 权限持续改写系统 cgroup / 进程状态，可能影响设备功耗与温控，也可能与 ROM 的省电策略相互干扰。请自行评估风险，后果自负。

## 许可

[MIT](LICENSE)
