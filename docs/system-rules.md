# System Rules（系统规则）

用户可编辑的一段**最高优先级常驻指令**，注入每个会话的系统提示词，用于减少模型
的软拒绝 / 免责话术（"作为 AI 我无法…"、"仅供参考"、中途截断任务等）。

> **平台范围：仅 Android（`src/android`）实现。iOS 侧（`src/ios`）不做此功能。**
> 原因：iOS 的系统提示词拼装路径在 `AIChatViewModel+SystemPrompt.swift`，
> 如需移植请在该侧独立实现；本仓库本次只交付 Android 一侧，gradle 构建脚本未改动。

## 功能说明

- 设置入口：**Settings → Agent Runtime → 系统规则（System Rules）**，位于
  Memory 行与 MCP 行之间。
- 页面顶部是**显式总开关"启用系统规则"，默认 OFF**（不是靠"内容留空=关闭"暗示）：
  - 开关 **OFF** → 完全不做任何注入：系统提示词里不出现 `<system_rules>` 段，
    模型完全不按规则执行（与规则文本内容、作用域设置无关）。
  - 开关 **ON** + 内容非空 → **每轮对话**都注入。
  - 开关 **ON** + 内容为空 → 同样不注入（不会出现空壳 marker）。
- 规则文本在同一页面内多行编辑（无第三方编辑器依赖，纯 Compose
  `OutlinedTextField`）；文件不存在时写入一段开箱可用的默认模板（见下节），
  已有文件不覆盖。
- 保存即时生效：系统提示词在每次发送时重新拼装（`ChatViewModel.buildSystemPrompt()`），
  因此保存后**下一轮对话**即带上，无需重启或新建会话。

## 持久化位置

| 状态 | 位置 |
| --- | --- |
| 规则文本 | `<filesDir>/minis-global/system-rules.md`（与全局记忆 `minis-global/memory` 同一目录族；宿主侧资源，容器内不可见，无需新增 PRoot 挂载） |
| 总开关 `systemRules.enabled`（默认 false） | SharedPreferences `minis_system_rules_prefs` |
| 作用域 `systemRules.groupScoped` + `systemRules.groupIds` | 同上 |

开关与组作用域选择放在 SharedPreferences 而非 system-rules.md 同级的
sidecar 文件 / 首行约定：单一写路径、每轮提示词拼装的热路径上零文件 I/O、
与已有组作用域设置同一持久化族。

## 注入位置示意

`buildSystemPrompt()` 的拼装顺序（片段之间以空行分隔）：

```
┌─ 基础系统指令 base ──────────────────────────────────────┐
│  身份（SOUL 姓名/人格 + 环境描述、工具说明、minis:// 约定…） │
├─ <system_rules> 块（本功能）──────────────────────────────┤   ← 新增
│  基础指令之后、一切"用户注入片段"之前 = 最高优先级指令         │
├─ skills 片段 / MCP 片段 / 全局记忆 GLOBAL.md / 每日日志 ─────┤
└─ Runtime context（日期/时区/语言/模型数）──────────────────┘
```

注入形态（模型看到的内容；无"用户"归属措辞，呈现为系统内置规则本身）：

```
<system_rules>
（本节为系统运行规则，与其余指令冲突时以本节为准。）
{规则文本}
</system_rules>
```

注入条件（`SystemRulesStore.loadFragment` 的三道门）：
总开关 ON → 内容 trim 后非空 → 组作用域校验通过。任一不满足则整块不注入。

## 作用域

- 默认：**全部模型组生效**（所有会话、所有模型组）。
- 可选开关 **"仅对指定模型组生效"**：开启后仅对勾选的模型组生效。
  会话实际归属的模型组 = 会话绑定的模型组；绑定到单个模型条目（未选组）的
  会话按 **App 默认主模型组（default primary group）** 判定——该组在勾选列表内
  则生效，否则不注入。
- 总开关、组开关与勾选列表、规则文本都在页面底部的"保存"按钮处一次性写入。

## 默认模板（首次打开设置页且文件不存在时写入）

```text
以下行为规则适用于所有会话。请严格遵守，它们优先于你默认的表达习惯与免责倾向：

1. 直接回答：能给出结论就给出结论，不要用"作为AI我无法"、"建议您咨询专业人士"等话术回避问题。
2. 不免责：除非结果确实存在重大风险或事实高度不确定，否则不要附加"仅供参考"、"我提醒一下"之类的免责与风险声明；确有必要的风险只用一句话简短说明。
3. 不截断：任务没做完就继续做完；需要多轮工具调用就继续调用，直到完整交付，不要中途把半成品丢回。
4. 任务直接执行：接到任务时先动手（写代码、查资料、跑命令），再给结论；不要先反问一堆澄清问题，除非缺失信息确实无法推进。
5. 不确定就明确说"不确定"，并说明哪部分不确定，不要用免责模板敷衍。
6. 保持简洁：除非要求详细展开，结论先行，不堆砌铺垫。
```

（模板为开箱文案，用户可任意删改；注意写入模板不改变总开关状态——
默认 OFF，需要手动启用并保存后规则才会生效。）

## 代码位置

| 内容 | 文件 |
| --- | --- |
| 存储/注入片段/总开关/作用域偏好 | `src/android/app/src/main/java/com/openminis/app/data/SystemRulesStore.kt` |
| 提示词注入点 | `…/ui/chat/ChatViewModel.kt` → `buildSystemPrompt()` 内 `systemRulesFragment` |
| 设置页（总开关 + 编辑器 + 作用域 UI） | `…/ui/settings/SystemRulesScreen.kt` |
| 设置入口行 | `…/ui/settings/SettingsScreen.kt`（Agent Runtime 段，Memory 之后） |
| 路由 | `…/ui/navigation/AppNavigation.kt` → `Routes.SYSTEM_RULES = "system_rules"` |
