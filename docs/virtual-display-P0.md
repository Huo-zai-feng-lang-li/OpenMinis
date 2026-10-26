# Virtual Display P0 — 虚拟显示最小闭环

> 分支：`feature/virtual-display` · 范围：**P0 最小闭环**（M1/M2 未做）
> 目标：让 Agent 全程在物理屏不可见的虚拟显示上操作任意应用（含双开 user 999）。

## 1. 架构（P0 版 = 进程内自建）

```
Minis app 进程 (user 0)
 ├─ MinisAgentDisplayManager（app/display/MinisAgentDisplay.kt，新）
 │   ├─ DisplayManager.createVirtualDisplay("minis-vd-*", w, h, dpi, ImageReader.surface, flags)
 │   │   flags（纯函数 displayFlags，搬自 Mika ShellService.kt:1841-1870）：
 │   │   基础 PUBLIC|OWN_CONTENT_ONLY|SUPPORTS_TOUCH|ROTATES_WITH_CONTENT|DESTROY_CONTENT_ON_REMOVAL
 │   │   API33+ 追加 TOUCH_FEEDBACK_DISABLED；trusted 时再追加 TRUSTED|OWN_DISPLAY_GROUP|ALWAYS_UNLOCKED
 │   │   API34+ trusted 追加 OWN_FOCUS|STEAL_TOP_FOCUS_DISABLED
 │   │   先按 trusted=true 建；SecurityException → 按 Mika retryWithoutTrust 语义去掉信任位重试一次；
 │   │   再失败 → CREATION_FAILED（见 §5 已知坑）
 │   ├─ 建屏后配套（反射，尽力而为，失败只打 log）：
 │   │   IWindowManager.setWindowingMode(display,1)  防 freeform 小窗
 │   │   IWindowManager.setDisplayImePolicy(display,0)  IME 弹在虚拟屏不串主屏
 │   │   OWN_DISPLAY_GROUP 的屏：每 4s IPowerManager.userActivity(displayId,...) 续亮（DisplayAwake 语义）
 │   ├─ 帧管道（ImageReader ≤10fps、stride 处理、旋转转正、黑帧判定，整体移植 Mika AgentDisplay.kt）
 │   └─ 生命周期：create→displayId / release(id) 销毁 + 清线程；MinisAgentDisplayManager 进程内单例持有
 ├─ MinisAccessibilityService（最小改动）
 │   ├─ windowInfos() 带 displayId；visibleDisplayIds()；windowsForDisplay(id)；rootsForDisplay(id)
 │   └─ dispatchGestureOn(displayId, ...)：GestureDescription.Builder.setDisplayId（API30+，公开 API）
 ├─ 沙箱 CLI（offload）
 │   ├─ android-vd-cli（新）      create / list / shot / launch / tap / release
 │   ├─ android-a11y-cli（改）    ui displays；ui dump|find|info --display N
 │   └─ android-shizuku-cli（改）  input tap|swipe|key|text 支持 -d/--display；activity start --display
 └─ 画面/输入链路：
     帧 → app 内 ImageReader → PNG 落会话 /var/minis/attachments/screenshots/vd_<ts>.png
     UI 树 → a11y 服务按 displayId 读
     输入 → Shizuku `input -d <id>` / `am start --user <u> --display <id> -n <component>`
```

物理屏不可见：虚拟屏的合成输出只进 app 侧 ImageReader，系统不映射到任何物理显示器；
trusted 屏在 API34+ 靠 OWN_FOCUS + STEAL_TOP_FOCUS_DISABLED 不抢主屏焦点。

## 2. 用法（沙箱内）

```sh
# 建屏（默认 = 主屏物理尺寸/dpi）
android-vd-cli create                       # → {"displayId":7,...}
android-vd-cli create --w 1080 --h 2520 --dpi 420

# 上屏（需 Shizuku READY；双开 user 999）
android-vd-cli launch 7 --user 999 com.tencent.mm/.ui.LauncherUI
# 或原生面
android-shizuku-cli activity start -n com.tencent.mm/.ui.LauncherUI --user 999 --display 7

# 截图（app 侧 ImageReader 帧，非 a11y 截图）
android-vd-cli shot 7                       # → /var/minis/attachments/screenshots/vd_<ts>.png

# 读 UI 树（按屏）
android-a11y-cli ui displays                # 各屏 a11y 窗口数
android-a11y-cli ui dump --display 7        # 屏 7 的节点树
android-a11y-cli ui find --display 7 --text-contains 登录

# 输入（落虚拟屏）
android-vd-cli tap 7 540 1200               # = input -d 7 tap 540 1200
android-shizuku-cli input swipe 540 1500 540 400 --display 7
android-shizuku-cli input key 4 --display 7 # back（HyperOS 有坑，见 §5）
android-shizuku-cli input text 你好 --display 7

android-vd-cli list                         # 活跃屏 + a11y 可见屏
android-vd-cli release 7
```

## 3. P0 验证清单（照单执行）

按顺序做，任一失败即记录并调整路线（来自实施计划 §3）：

1. **建屏**：1080×2520 PUBLIC 屏建成（TRUSTED 被拒时降级路径实测；
   降级后检查"主屏焦点是否被清"——OWN_FOCUS/STEAL_TOP 不可用的后果）。
2. **上屏**：双开微信（user 999）`am start --user 999 --display N` 启动到虚拟屏，主屏无感。
3. **读屏**：user 0 的 a11y `ui displays` / `ui dump --display N` 能否读到 u999 的窗口节点
   —— **全案最大不确定点**（物理屏上已实测读不到 u999；虚拟屏路径未复现该限制）。
   若失败：兜底 A = 放弃 UI 树，走 ImageReader 截图 + 坐标注入；兜底 B = Shizuku 侧在 u999 挂 a11y。
4. **截图**：`android-vd-cli shot N` 帧非黑（`blackFrame=false`）且能定位 UI 元素。
5. **输入**：tap/type 落在虚拟屏；**back 键**（`input -d N key 4`）单独验证
   （HyperOS #622：可能到不了虚拟屏，失败则用节点"返回"控件 performAction 替代）。
6. **长任务**：虚拟屏 30 分钟不睡（OWN_DISPLAY_GROUP 屏靠 4s userActivity 续亮；
   非 trusted 降级屏无显示组，不睡）；主屏焦点隔离（trusted+API34 时 OWN_FOCUS 生效）。

## 4. 改动文件

| 文件 | 改动 |
|---|---|
| `src/android/.../app/display/MinisAgentDisplay.kt`（新） | 虚拟屏 create/release + 帧管道 + PNG/黑帧 + 续亮 + displayFlags（Mika 移植，保留 Apache-2.0 注释） |
| `src/android/.../app/accessibility/MinisAccessibilityService.kt` | windowInfos 加 displayId；visibleDisplayIds / windowsForDisplay / rootsForDisplay；dispatchGestureOn（setDisplayId） |
| `src/android/.../app/sandbox/offload/AccessibilityOffloadHandler.kt` | `ui displays` 子命令；`ui dump/find/info` 加 `--display`；UI_HELP/TOP_HELP/statusForSubAction 同步 |
| `src/android/.../app/sandbox/offload/ShizukuOffloadHandler.kt` | input tap/swipe/key 加 `-d/--display`；input text 加 `--display`（长形式，防值泄漏进文本）；`activity start` 加 `--display`；HELP 同步 |
| `src/android/.../app/sandbox/offload/VdOffloadHandler.kt`（新） | `android-vd-cli`：create/list/shot/launch/tap/release + OffloadGate `display_cli` 门 |
| `src/android/.../app/MinisApp.kt` | 注册 `android-vd-cli`（stub 由 PRootKernel.installHandlerStubs 自动写入 guest） |
| `docs/virtual-display-P0.md` | 本文档 |

许可证：所有自 Mika（wangzan101/Mika，Apache-2.0）移植的代码在文件头保留
Apache-2.0 署名 + "Ported from wangzan101/Mika" 注释；Apache-2.0 与 GPLv3 兼容，
未新增依赖（ImageReader/DisplayManager 均为系统 API）。

## 5. 已知坑（P0 阶段）

| 坑 | 说明 / 对策 |
|---|---|
| **进程内建 PUBLIC 屏被框架拒绝** | 普通 app uid 建 PUBLIC/非私有虚拟屏大概率 SecurityException（框架 `MAX_UNTRUSTED_VIRTUAL_DISPLAYS` 限制 + PUBLIC 屏 owner 校验）。trusted 降级重试也只到"非 trusted PUBLIC"。`create` 报 **CREATION_FAILED** 时 → **M1 Shizuku 宿主路线**：Shizuku ShellUserService（uid 2000）建屏 + `ShizukuContext`（com.android.shell 包名），app 侧帧管道/CLI/a11y 面不变 |
| **HyperOS back 键到不了虚拟屏（Mika #622，未决）** | `input -d N key 4` / 注入的 KEYCODE_BACK 在小米 ROM 上可能无效；tap/type/scroll 走 a11y 节点 performAction 正常；back 用目标 App 内"返回"节点替代 |
| **OWN_DISPLAY_GROUP 自睡** | trusted 屏自成显示组、有独立息屏计时，注入输入不算用户活动；4s `IPowerManager.userActivity(displayId)` 续亮（本实现已带）；userActivity 反射不可用时彻底不续，长任务会黑 |
| **freeform 小窗** | 部分 ROM 把新建虚拟屏默认设 freeform；建屏后立刻 `setWindowingMode(display,1)`（反射，尽力而为——app uid 调 IWindowManager 隐藏接口可能被 hidden-API 拦截，降级后靠 `android-shizuku-cli exec wm set-display-windowing-mode -d N 1` 兜底） |
| **u999 跨用户 a11y 可见性** | 待实测（验证项 3）；失败走 §3 的兜底 A/B |
| **降级非 trusted 屏的焦点行为（API33/34-）** | 无 OWN_FOCUS/STEAL_TOP_FOCUS_DISABLED：虚拟屏上开新页面会成为最上层焦点屏、主屏焦点窗口被清空（Mika MuMu #55/#280 同类） |
| **任务 spill 到主屏** | App 弹系统选择框时任务落主屏——M2 的 `moveRootTaskToDisplay` 搬回未做（M1+） |
| **`input text` 只收 ASCII** | shell `input text` 不支持中文/Unicode；用 a11y 节点 `input text`（performAction SET_TEXT）或 M1 的 shell 级 InputManager 注入 |

## 6. 后续（M1/M2，不在本分支）

- Shizuku ShellUserService 建屏（ShellContext + trusted 全套 + ILauncherApps 跨用户 + 主屏占用守卫）
- injectInputEvent(setDisplayId, targetUid) 定向注入 + 节点 handler 手势
- ScreenSpill 主屏监视 + AppMuter + 12 项自检（装机即判 ROM 可用性）
- 常驻通知 / 胶囊 live view / PiP 预览
