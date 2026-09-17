package ai.nidhoggfgg.phoneuse.ui

import ai.nidhoggfgg.phoneuse.R
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat

@Composable
internal fun SettingsScreen() {
    val selectedLanguage = AppCompatDelegate.getApplicationLocales()[0]?.language.orEmpty()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionCard {
            Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.language_help), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.selectableGroup()) {
                listOf("" to R.string.language_system, "zh" to R.string.language_chinese, "en" to R.string.language_english).forEach { (tag, label) ->
                    val selected = selectedLanguage == tag
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = {
                            if (!selected) AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                        }).padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
