package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.SystemRulesStore
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.MinisButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [T-system-rules] Settings → System Rules: the master switch ("启用系统
 * 规则", default OFF) plus a minimal multi-line editor for
 * `filesDir/minis-global/system-rules.md` — the highest-priority standing
 * instructions that are injected into every session's system prompt (after
 * the base system instructions, before the user-injected fragments) to
 * keep the model from soft-refusing or hedging with boilerplate
 * disclaimers — and a "apply only to specified model groups" scope toggle
 * with a per-group switch list.
 *
 * Injection gate (SystemRulesStore.loadFragment): master switch ON AND
 * content non-empty AND (scope OFF OR session group in the scoped set).
 * Master switch OFF → the `<system_rules>` block never reaches the model.
 *
 * Deliberately dependency-free: a plain [OutlinedTextField] + the shared
 * [SettingsSwitchRow] rows; no editor library. First entry seeds
 * [SystemRulesStore.DEFAULT_TEMPLATE] into the file when it has never been
 * created (an existing file is never clobbered). The switch, scope and
 * content all commit on Save; the system prompt is rebuilt on every send,
 * so the saved state applies from the next chat turn.
 *
 * iOS: not implemented on the iOS side (src/ios) — see docs/system-rules.md.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemRulesScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    var rulesText by remember { mutableStateOf("") }
    // Scope prefs are read once on entry; the Save button is the single
    // write path (mirrors the ThinkingRulesSection commit style rather than
    // writing on every keystroke).
    var enabled by remember { mutableStateOf(SystemRulesStore.isEnabled(appContext)) }
    var groupScoped by remember { mutableStateOf(SystemRulesStore.isGroupScoped(appContext)) }
    // Immutable set + whole-replacement on toggle: an in-place MutableSet
    // mutation (`scopedIds += id`) is NOT a snapshot write, so it never
    // invalidates the switch's composition — the toggle rendered stuck.
    var scopedIds by remember {
        mutableStateOf<Set<String>>(SystemRulesStore.scopedGroupIds(appContext))
    }
    var saved by remember { mutableStateOf(false) }

    val config by providerRepository.config.collectAsState()
    val groups = config.modelGroups

    // Seed the template on first entry, then load whatever the file holds.
    LaunchedEffect(Unit) {
        rulesText = withContext(Dispatchers.IO) {
            SystemRulesStore.ensureTemplate(appContext)
            SystemRulesStore.load(appContext)
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.settings_system_rules),
        onBack = onBack,
    ) {
        // Master switch — OFF (the default) means the model never sees any
        // rules, no matter what the editor below contains.
        SettingsSection(header = stringResource(R.string.system_rules_enable_header)) {
            SettingsSwitchRow(
                title = stringResource(R.string.system_rules_enable_title),
                subtitle = stringResource(R.string.system_rules_enable_subtitle),
                checked = enabled,
                onCheckedChange = { enabled = it; saved = false },
                showDivider = false,
            )
        }

        SettingsSection(
            header = stringResource(R.string.system_rules_rules_header),
            footer = stringResource(R.string.system_rules_empty_footer),
        ) {
            OutlinedTextField(
                value = rulesText,
                onValueChange = { rulesText = it; saved = false },
                label = { Text(stringResource(R.string.system_rules_editor_label)) },
                placeholder = { Text(stringResource(R.string.system_rules_editor_placeholder)) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .heightIn(min = 220.dp),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSection(header = stringResource(R.string.system_rules_scope_header)) {
            SettingsSwitchRow(
                title = stringResource(R.string.system_rules_group_scoped_title),
                subtitle = stringResource(R.string.system_rules_group_scoped_subtitle),
                checked = groupScoped,
                onCheckedChange = { groupScoped = it; saved = false },
            )
            if (groupScoped) {
                if (groups.isEmpty()) {
                    Text(
                        stringResource(R.string.system_rules_no_groups),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                } else {
                    groups.forEachIndexed { index, group ->
                        SettingsSwitchRow(
                            title = group.name,
                            checked = group.id in scopedIds,
                            onCheckedChange = { on ->
                                // Replace the whole set (a snapshot write); do NOT
                                // mutate an underlying collection in place.
                                scopedIds = if (on) scopedIds + group.id else scopedIds - group.id
                                saved = false
                            },
                            showDivider = index < groups.lastIndex,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        MinisButton(
            onClick = {
                // Empty text on save = empty file = nothing to inject even
                // with the switch on; the injection path trims, so trailing
                // whitespace is cosmetic.
                SystemRulesStore.save(appContext, rulesText)
                SystemRulesStore.setEnabled(appContext, enabled)
                SystemRulesStore.setGroupScoped(appContext, groupScoped)
                SystemRulesStore.setScopedGroupIds(appContext, scopedIds)
                saved = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(stringResource(R.string.common_save))
        }
        if (saved) {
            Text(
                stringResource(R.string.system_rules_saved_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}
