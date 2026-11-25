package com.github.jschbe.simplempdplayer.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.annotation.RawRes
import androidx.annotation.StringRes
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
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
    var page by rememberSaveable { mutableStateOf(AboutPage.ABOUT) }
    when (page) {
        AboutPage.ABOUT -> AboutOverview(
            onGpl = { page = AboutPage.GPL_TEXT },
            onThirdParty = { page = AboutPage.THIRD_PARTY },
            onDismiss = onDismiss,
        )
        AboutPage.THIRD_PARTY -> ThirdPartyOverview(
            onFullText = { page = AboutPage.THIRD_PARTY_TEXT },
            onDismiss = { page = AboutPage.ABOUT },
        )
        AboutPage.GPL_TEXT -> LegalTextDialog(R.string.license_title, R.raw.gpl_v3) {
            page = AboutPage.ABOUT
        }
        AboutPage.THIRD_PARTY_TEXT -> LegalTextDialog(R.string.full_license_texts, R.raw.third_party_notices) {
            page = AboutPage.THIRD_PARTY
        }
    }
}

private enum class AboutPage { ABOUT, THIRD_PARTY, GPL_TEXT, THIRD_PARTY_TEXT }

@Composable
private fun AboutOverview(onGpl: () -> Unit, onThirdParty: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.about)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.app_version, BuildConfig.VERSION_NAME))
                    Text(stringResource(R.string.app_license))
                    Text(stringResource(R.string.license_warranty))
                }
                Spacer(Modifier.height(4.dp))
                LicenseAction(R.string.gpl_license_link, onGpl)
                LicenseAction(R.string.third_party_licenses, onThirdParty)
                AboutBrowserLink(R.string.open_source, R.string.source_repository_url)
                AboutBrowserLink(R.string.privacy_policy, R.string.privacy_policy_url)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}

@Composable
private fun AboutBrowserLink(@StringRes label: Int, @StringRes destination: Int) {
    val context = LocalContext.current
    val url = stringResource(destination)
    LicenseAction(label) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.source_open_failed, Toast.LENGTH_SHORT).show()
        }
    }
}

private val thirdPartyComponents = listOf(
    R.string.license_component_androidx to R.string.license_apache,
    R.string.license_component_media3 to R.string.license_apache,
    R.string.license_component_kotlin to R.string.license_kotlin,
    R.string.license_component_guava to R.string.license_apache,
    R.string.license_component_okio to R.string.license_apache,
    R.string.license_component_protobuf to R.string.license_bsd,
)

@Composable
private fun ThirdPartyOverview(onFullText: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.third_party_licenses)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.third_party_intro), Modifier.fillMaxWidth())
                thirdPartyComponents.forEach { (component, license) ->
                    Column {
                        Text(stringResource(component), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(license), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                LicenseAction(R.string.full_license_texts, onFullText)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}

@Composable
private fun LicenseAction(@StringRes label: Int, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        Text(stringResource(label), Modifier.fillMaxWidth())
    }
}

@Composable
private fun LegalTextDialog(@StringRes title: Int, @RawRes resource: Int, onDismiss: () -> Unit) {
    val resources = LocalResources.current
    val paragraphs by produceState(emptyList<String>(), resources, resource) {
        value = withContext(Dispatchers.IO) {
            resources.openRawResource(resource).bufferedReader().use { it.readText() }.split("\n\n")
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            LazyColumn(Modifier.testTag("legal_text")) {
                items(paragraphs) { paragraph ->
                    SelectionContainer { Text(paragraph, style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}
