package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File

/**
 * [T-system-rules] User-defined, highest-priority standing instructions,
 * injected into the system prompt of every chat session so the model
 * answers directly instead of soft-refusing / hedging with boilerplate
 * disclaimers.
 *
 * Storage: `<filesDir>/minis-global/system-rules.md` — the same global
 * directory family as the memory system (`minis-global/memory`, GLOBAL.md;
 * see [MemoryGlobalPrefs] / `MemoryRepository`). The PRoot bind-mounts
 * (SessionMounts) already cover the minis-global subdirs; this file is a
 * host-side app resource only — the Android app reads it when it assembles
 * the system prompt. It is NOT a new guest-visible path and needs no mount
 * changes.
 *
 * Semantics:
 *   - Explicit master switch, default OFF (SharedPreferences, below; the
 *     switch deliberately lives in the feature's own prefs file rather
 *     than a sidecar file next to the .md — one write path, no extra
 *     I/O on the per-send prompt-build hot path, and it rides the same
 *     persistence family as the group-scope toggle).
 *     Switch OFF → nothing is ever injected: no `<system_rules>` block in
 *     the system prompt at all.
 *   - Switch ON + empty / whitespace-only content = nothing injected
 *     either (content is the second gate, so a half-filled editor cannot
 *     leak an empty marker).
 *   - Switch ON + non-empty content → injected into every turn.
 *   - Scope: default ALL model groups. Optionally restricted to a set of
 *     model groups; sessions bound to a model entry directly (no group)
 *     fall back to the app's default primary group when deciding scope.
 *   - The system prompt is rebuilt on every send (ChatViewModel
 *     .buildSystemPrompt()), so a save applies from the very next turn.
 *
 * Android-only by design: the iOS side (src/ios) does not implement this
 * feature; see docs/system-rules.md.
 */
object SystemRulesStore {
    private const val TAG = "SystemRulesStore"
    private const val FILE_PATH = "minis-global/system-rules.md"

    private const val PREFS = "minis_system_rules_prefs"
    private const val KEY_ENABLED = "systemRules.enabled"
    private const val KEY_GROUP_SCOPED = "systemRules.groupScoped"
    private const val KEY_GROUP_IDS = "systemRules.groupIds"

    /**
     * Seed template written when the Settings page first opens and the file
     * does not exist yet (≤ 20 lines; the user trims/extends freely).
     */
    const val DEFAULT_TEMPLATE = """
以下行为规则适用于所有会话。请严格遵守，它们优先于你默认的表达习惯与免责倾向：

1. 直接回答：能给出结论就给出结论，不要用"作为AI我无法"、"建议您咨询专业人士"等话术回避问题。
2. 不免责：除非结果确实存在重大风险或事实高度不确定，否则不要附加"仅供参考"、"我提醒一下"之类的免责与风险声明；确有必要的风险只用一句话简短说明。
3. 不截断：任务没做完就继续做完；需要多轮工具调用就继续调用，直到完整交付，不要中途把半成品丢回。
4. 任务直接执行：接到任务时先动手（写代码、查资料、跑命令），再给结论；不要先反问一堆澄清问题，除非缺失信息确实无法推进。
5. 不确定就明确说"不确定"，并说明哪部分不确定，不要用免责模板敷衍。
6. 保持简洁：除非要求详细展开，结论先行，不堆砌铺垫。
""".trim()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun file(context: Context): File = File(context.filesDir, FILE_PATH)

    /** Raw file content; empty string when the file does not exist. */
    fun load(context: Context): String = try {
        val f = file(context)
        if (f.exists()) f.readText() else ""
    } catch (e: Exception) {
        Log.e(TAG, "failed to read $FILE_PATH", e)
        ""
    }

    fun save(context: Context, content: String) {
        try {
            val f = file(context)
            f.parentFile?.mkdirs()
            f.writeText(content)
            Log.i(TAG, "system rules saved (${content.length} chars)")
        } catch (e: Exception) {
            Log.e(TAG, "failed to write $FILE_PATH", e)
        }
    }

    /**
     * Seed the template when the file has never been created. The
     * Settings page calls this on first entry so the editor opens
     * with usable content instead of a blank box. Existing files —
     * even empty ones — are never clobbered. (What turns injection
     * off is the explicit master switch, not the file content.)
     */
    fun ensureTemplate(context: Context) {
        val f = file(context)
        if (f.exists()) return
        save(context, DEFAULT_TEMPLATE)
        Log.i(TAG, "seeded default system rules template")
    }

    // -- master switch -----------------------------------------------------

    /** Default OFF: a fresh install injects nothing until the user
     *  explicitly enables the feature from Settings → System Rules. */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    // -- scope preferences -------------------------------------------------

    fun isGroupScoped(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GROUP_SCOPED, false)

    fun scopedGroupIds(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_GROUP_IDS, emptySet())!!.toSet()

    fun setGroupScoped(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_GROUP_SCOPED, value).apply()
    }

    fun setScopedGroupIds(context: Context, ids: Set<String>) {
        // A fresh set (empty array is a no-op for the old value), so the
        // "clear all" case writes correctly.
        prefs(context).edit().putStringSet(KEY_GROUP_IDS, if (ids.isEmpty()) emptySet() else ids).apply()
    }

    // -- prompt fragment ---------------------------------------------------

    /**
     * Build the `<system_rules>` block for system-prompt injection, or null
     * when the feature is off (master switch OFF, empty content) or the
     * session is out of scope.
     *
     * [effectiveGroupId] is the model group the current session actually
     * resolves to — the session-bound group, or the app's default primary
     * group when the session is bound to a direct model entry (or has no
     * binding yet). Ignored unless group scoping is enabled.
     *
     * The marker presents the block as part of the system's own standing
     * rules (a single structural priority statement, no user-attribution
     * wording) so it reads as an in-system rule rather than an add-on:
     *
     * ```
     * <system_rules>
     * （本节为系统运行规则，与其余指令冲突时以本节为准。）
     * {规则文本}
     * </system_rules>
     * ```
     */
    fun loadFragment(context: Context, effectiveGroupId: String?): String? {
        // Gate 1: the explicit master switch. OFF → the model never sees
        // any rules, regardless of file content or scope settings.
        if (!isEnabled(context)) return null
        val content = load(context).trim()
        // Gate 2: empty content = nothing to inject (no bare markers).
        if (content.isEmpty()) return null
        if (isGroupScoped(context)) {
            val ids = scopedGroupIds(context)
            if (effectiveGroupId == null || effectiveGroupId !in ids) return null
        }
        return "<system_rules>\n" +
            "（本节为系统运行规则，与其余指令冲突时以本节为准。）\n" +
            content +
            "\n</system_rules>"
    }
}
