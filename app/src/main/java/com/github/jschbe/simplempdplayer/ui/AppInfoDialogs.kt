package com.github.jschbe.simplempdplayer.ui

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.github.jschbe.simplempdplayer.BuildConfig
import com.github.jschbe.simplempdplayer.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LanguageDialog(selected: AppLanguage, onSelect: (AppLanguage) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                AppLanguage.entries.forEach { language ->
                    Row(
                        Modifier.fillMaxWidth().testTag("language_${language.tag.ifEmpty { "system" }}")
                            .selectable(selected == language, role = Role.RadioButton, onClick = { onSelect(language) })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == language, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(language.label()))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}

private fun AppLanguage.label() = when (this) {
    AppLanguage.SYSTEM -> R.string.language_system
    AppLanguage.ENGLISH -> R.string.language_en
    AppLanguage.GERMAN -> R.string.language_de
    AppLanguage.FRENCH -> R.string.language_fr
    AppLanguage.ITALIAN -> R.string.language_it
    AppLanguage.SPANISH -> R.string.language_es
    AppLanguage.UKRAINIAN -> R.string.language_uk
    AppLanguage.RUSSIAN -> R.string.language_ru
}

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    var document by rememberSaveable { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val source = stringResource(R.string.source_repository_url)
    if (document != null) {
        val thirdParty = document == "third_party"
        val resource = if (thirdParty) R.raw.third_party_notices else R.raw.gpl_v3
        val paragraphs by produceState(emptyList<String>(), resource) {
            value = withContext(Dispatchers.IO) {
                resources.openRawResource(resource).bufferedReader().use { it.readText() }.split("\n\n")
            }
        }
        AlertDialog(
            onDismissRequest = { document = null },
            title = { Text(stringResource(if (thirdParty) R.string.third_party_licenses else R.string.license_title)) },
            text = {
                LazyColumn(Modifier.testTag("legal_text")) {
                    items(paragraphs) { paragraph ->
                        SelectionContainer { Text(paragraph, style = MaterialTheme.typography.bodySmall) }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { document = null }) { Text(stringResource(R.string.done)) } },
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.about)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.app_version, BuildConfig.VERSION_NAME))
                    Text(stringResource(R.string.app_license))
                    Text(stringResource(R.string.license_warranty))
                    TextButton(onClick = { document = "license" }) { Text(stringResource(R.string.read_license)) }
                    TextButton(onClick = { document = "third_party" }) { Text(stringResource(R.string.third_party_licenses)) }
                    TextButton(onClick = {
                        try { uriHandler.openUri(source) } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, R.string.source_open_failed, Toast.LENGTH_SHORT).show()
                        }
                    }) { Text(stringResource(R.string.open_source)) }
                    SelectionContainer { Text(source, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
        )
    }
}
