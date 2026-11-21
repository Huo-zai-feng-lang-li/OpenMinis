# M1 · Shizuku 特权宿主建屏（shell-host virtual display）

> 分支：`feature/virtual-display-m1`（基线 = P0 的 `feature/virtual-display` @ `55df920`）。
> 目标：当 P0 进程内（普通 app uid）`createVirtualDisplay` 建 PUBLIC 虚拟屏被框架拒绝时
> （大概率——AOSP 限制普通 app 只能建 PRIVATE 屏），自动切到 **Shizuku 特权宿主**
> 以 shell 语义（uid 2000）建 TRUSTED 屏。P0 的 `android-vd-cli` 对这次切换**完全透明**。

---

## 1. 架构

```
OpenMinis app 进程 (user 0, 普通 uid)
 ├─ MinisAgentDisplayManager.create(w,h,dpi)
 │   ├─ P0 路径: DisplayManager.createVirtualDisplay(PUBLIC+TRUSTED → 降级 PUBLIC)
 │   │    └─ 被拒 (CREATION_FAILED) 时 ↓
 │   └─ M1 fallback: MinisShellClient.createDisplay(reader.surface, w, h, dpi)
 │         │  (Surface 走 binder parcel，画面仍进 app 进程 ImageReader)
 │         ▼
 Shizuku 特权宿主进程 (app_process, 进程名 com.openminis.app:shizuku)
 ├─ uid: 2000 (Shizuku 以无线调试/USB 启动时) 或 0 (root 模块启动时)
 ├─ MinisShellHostService (IMinisShell.Stub, @Keep 双构造, Shizuku 反射实例化)
 │   └─ MinisShellDisplay:
 │       • DisplayManager(反射构造, context=ShellContext com.android.shell/2000)
 │       • createVirtualDisplay("minis-shell", w,h,dpi, surface, flags)
 │       │    flags = Mika displayFlags: PUBLIC|OWN_CONTENT_ONLY|SUPPORTS_TOUCH
 │       │            |ROTATES_WITH_CONTENT|DESTROY_CONTENT_ON_REMOVAL
 │       │            +API33: TOUCH_FEEDBACK_DISABLED (+trusted: TRUSTED|OWN_DISPLAY_GROUP
 │       │            |ALWAYS_UNLOCKED) +API34: OWN_FOCUS|STEAL_TOP_FOCUS_DISABLED
 │       │    TRUSTED 被拒 (SecurityException) → 去掉信任位重试一次 (Mika 语义)
 │       • 建屏后: setFullscreenWindowingMode (防 freeform) + setImePolicy LOCAL
 │       • DisplayAwake: OWN_DISPLAY_GROUP 屏每 4s IPowerManager.userActivity(displayId)
 │       • startAppInUser: ILauncherApps 跨用户 → 12 参 startActivityAsUser
 │       │   → `am start --user N --display D -n ...` (用户白名单: alive+unlocked
 │       │   +非 guest/managed)
 │       • injectTap/injectKey/injectText: 反射 injectInputEvent + setDisplayId,
 │       │   API33+ 定向 targetUid (该屏启动过的 App 的 uid), 回退 `input -d <id> ...`
 │       └─ runSystemCommand: sh -c (shell 身份, MINIS_DISPLAY_ID 环境变量)
 └─ 读屏/截图/注入 CLI: android-vd-cli (P0) — 不感知 owner 是谁 (AgentDisplayApi)
```

物理屏不可见原理不变：合成输出只进 app 侧 ImageReader。焦点隔离靠 TRUSTED 屏的
OWN_FOCUS+STEAL_TOP_FOCUS_DISABLED（API34+；降级后无此保护，见 P0 文档风险节）。

## 2. 文件清单（本分支新增/修改）

| 文件 | 说明 |
|---|---|
| `app/src/main/aidl/com/openminis/app/shizuku/IMinisShell.aidl`（新） | Mika `IMikaShell` 的 9 方法子集：createDisplay/releaseDisplay/startAppInUser/injectTap/injectKey/injectText/runSystemCommand/identity/releaseAll。Surface 参数**直接 parcel 传 `in Surface`**（Mika 同款）：app 把 ImageReader Surface 寄给 shell 侧，画面像素回 app 进程 |
| `app/.../shizuku/MinisShellHostService.kt`（新） | 宿主：`@Keep` 双构造（`(Context)` + 无参，Mika `ShizukuShellUserService` 形态），每方法 uid 校验（只放行 OpenMinis app uid）；内部 `MinisShellDisplay`（建屏/降级/全屏窗口/IME/续亮/跨用户启动/输入注入/sh -c）+ `MinisShellDisplayAwake`（Mika `DisplayAwake` 移植） |
| `app/.../shizuku/ShellContext.kt`（新） | 照搬 Mika（保留 scrcpy Apache-2.0 头 + Ported 注释） |
| `app/.../shizuku/MinisShellClient.kt`（新） | app 侧客户端：`Shizuku.bindUserService` 拿宿主 binder（技术调研见 §3），全部 AIDL 调用 + 错误定位 |
| `app/.../display/MinisAgentDisplay.kt`（改） | 新增 `AgentDisplayApi` 接口（owner 无关的显示读法）；`MinisAgentDisplay` 实现它；`create()` 在进程内 CREATION_FAILED 时自动走 `shellFallback()` → `MinisShellAgentDisplay`（同文件新增：帧管道复用 P0，release 委托 shell 宿主） |
| `app/.../sandbox/offload/VdOffloadHandler.kt`（改） | `displayToJson` 改走接口；create 帮助文本更新（自动 fallback） |
| `app/build.gradle.kts`（改） | `buildFeatures.aidl = true` |
| `app/proguard-rules.pro`（改） | `-keep MinisShellHostService / IMinisShell* / ShellContext`（宿主类被 Shizuku 反射按名实例化，R8 不能改名/删） |
| `docs/virtual-display-M1.md`（本文档） | — |
| `THIRD_PARTY_LICENSES.md`（改） | 追加 M1 Mika 移植条目 |

## 3. Shizuku UserService 接入（技术调研结论）

**依赖**：OpenMinis 用的是 `dev.rikka.shizuku:api:13.1.5` + `:provider:13.1.5`
（`rikka.shizuku.Shizuku` / `rikka.shizuku.ShizukuProvider`，**不是**旧的
`com.schizukadev:shizuku` v10 库；仓库里没有 legacy 依赖）。

**UserService 机制（v13，已对 13.1.5 源码/字节码逐项核实）**：

1. Shizuku 服务端（`moe.shizuku.privileged.api` 进程，无线调试/USB 启动时 = shell
   uid 2000）收到 `Shizuku.bindUserService(UserServiceArgs, conn)` 后，执行
   `app_process` 命令（`moe.shizuku.starter.ServiceStarter`，
   `--nice-name=com.openminis.app:shizuku`，CLASSPATH = Shizuku 自己的 APK）；
2. helper 进程（**shell 权限**）从 OpenMinis APK 里按类名反射实例化
   `MinisShellHostService`（`@Keep` 双构造；无参构造兜底），把它的 binder
   经 Shizuku 的 content-provider 通道交回 app 的 `ServiceConnection`；
3. app 侧 `IMinisShell.Stub.asInterface(binder)` 即得宿主接口。

**因此**：
- **不需要**在 AndroidManifest 加 `<service>` 条目——宿主类不是 Android Service，
  它由 Shizuku 的 app_process helper 按类名反射加载（与 Mika 的做法一致：Mika 的
  `ShizukuShellUserService` 在其清单里也没有 service 条目）。官方样例里的
  `<service>` 是 legacy helper 路径才需要的。
- **需要** R8 保留宿主类（proguard-rules.pro 已加）。
- `UserServiceArgs` 参数（Mika 同款）：`daemon(false)`（app 进程死了 helper 随之停，
  不留无人管理的屏）、`processNameSuffix("shizuku")`、`version=1`
  （宿主 AIDL/行为变更时 +1，Shizuku 会重建）。
- 版本门槛：UserService 需 Shizuku ≥ v12（OpenMinis 依赖 13.1.5，客户端
  `Shizuku.getVersion()` 运行时校验）。

**用户侧前置条件（P0 与 M1 完全相同，无新增授权步骤）**：
1. 安装 Shizuku（`moe.shizuku.privileged.api`）或以 root 模块跑 Sui；
2. 在 Shizuku App 里以**无线调试 / USB 调试**方式启动 Shizuku
   → `android-shizuku-cli service status`：`state=READY` 且 **`startup_type=adb`
   （uid=2000）**。`startup_type=root`（uid=0，Sui）时建屏同样成立（uid 0 更宽）；
   `unknown` 说明 Shizuku 没起来。
3. 在 Shizuku App 里给 OpenMinis 授权（P0 已要求的同一权限）。
   首次 `bindUserService` 若权限未授，`Shizuku.requestPermission(requestCode)`
   走的就是 P0 `ShizukuBackend` 已实现的同一个权限流——**没有第二个授权动作**。

> 结论：**这台设备（Shizuku 以无线调试启动 = shell uid 2000）上，UserService
> 给 shell 级建屏是可行的**：helper 进程继承 shell 身份，`DisplayManager`（反射
> 构造、包名报 com.android.shell）即可建 PUBLIC+TRUSTED 屏；TRUSTED 若被 ROM
> 拒绝（缺 ADD_TRUSTED_DISPLAY）自动降级，与 P0 同一套 flags 函数。

## 4. P0 实测失败时的合并步骤

1. 确认 P0 `create` 报 `CREATION_FAILED`（进程内 PUBLIC 被拒）——预期结果。
2. 合 M1：
   ```sh
   git checkout feature/virtual-display
   git merge feature/virtual-display-m1      # 或 rebase，基线是 55df920，无冲突预期
   ```
   之后重新出 APK 安装（沙箱构建管线不变：javac/d8/aapt2/apksigner）。
3. 装完无需任何新设置；`android-vd-cli create` 自动完成 进程内 → shell 宿主
   的切换，`shot`/`launch`/`tap`/`release` 原样可用。
4. 若报 `SHELL_CREATION_FAILED`：按 §3 前置条件逐项查（Shizuku 是否 READY、
   startup_type、授权）；宿主 binder 的最后一行错误在
   `MinisShellClient.lastError()`（CLI envelope 的 error.message 里带出）。
5. 宿主日志：logcat tag `MinisShell`（建屏/降级/注入通道）与
   `MinisShellClient`（binder 连接）；宿主进程 `com.openminis.app:shizuku`
   （ps 里 uid 2000）。

## 5. 已知风险（M1 特有）

| 风险 | 说明 / 对策 |
|---|---|
| 降级 TRUSTED 后焦点隔离失效 | 与 P0 同风险节：无 OWN_FOCUS/STEAL_TOP_FOCUS_DISABLED 时，虚拟屏开新页会成最上层焦点屏、主屏焦点被清（Mika MuMu #55/#280）。shell 宿主下多数 ROM 的 shell 有 ADD_TRUSTED_DISPLAY，降级概率低于 app 内路径 |
| HyperOS back 键进不了虚拟屏（Mika #622） | `injectKey` 的 back 有定向→非定向→`input -d` 三级通道并打日志（`channel=targeted/nontargeted/shell`），失败时 agent 侧用 a11y 节点"返回"控件替代 |
| `injectText` 只保证 ASCII | 走虚拟键盘 key 事件（可定向）；无键位映射的字符退回 shell `input text`（仅 ASCII）。中文整段输入（剪贴板+粘贴）是 M2 路径 |
| 跨用户白名单是简化版 | AppCopies-lite：alive + unlocked + 非 guest/managed；私密空间/工作资料一律拒。Mika 完整版还会读 profileGroup/media 共享等 flags，需要时再补 |
| shell 宿主进程被杀 / Shizuku 重启 | `daemon(false)`：app 进程死 → 宿主停 → 屏随之释放，无孤儿屏；Shizuku 重启后重新 `bindUserService` 即可，`ensureShell` 每次调用自动重连 |
| R8 改名宿主类 | proguard 规则已加；**改宿主 AIDL 时记得 `MinisShellClient.HOST_VERSION` +1** |
| Surface parcel 失败 | binder 传 Surface 依赖 ImageReader 存活；app 侧 release 顺序（先关宿主屏、再关 reader）已在 `MinisShellAgentDisplay.release()` 固化 |

## 6. 许可

所有自 Mika（wangzan101/Mika，Apache-2.0，commit `7101e99`）移植的代码保留
Apache-2.0 头 + "Ported from wangzan101/Mika" 注释；Apache-2.0 与仓库 GPLv3
兼容。明细见 `THIRD_PARTY_LICENSES.md` 的 M1 条目。
