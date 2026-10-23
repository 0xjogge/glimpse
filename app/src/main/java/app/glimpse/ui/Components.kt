package app.glimpse.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.glimpse.core.Language

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp, start = 8.dp),
    )
}

@Composable
fun SettingRow(
    title: String,
    detail: String,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        leadingContent = icon?.let { { Icon(it, null) } },
        trailingContent = trailing ?: onClick?.let { { Icon(Icons.Outlined.ChevronRight, null) } },
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        colors =
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
}

data class Choice(val id: String, val title: String, val detail: String = "")

@Composable
fun ChoiceDialog(
    title: String,
    choices: List<Choice>,
    selected: String,
    onDismiss: () -> Unit,
    onChoose: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(choices, key = { it.id }) { choice ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                onChoose(choice.id)
                                onDismiss()
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected == choice.id, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(choice.title, fontWeight = FontWeight.Medium)
                            if (choice.detail.isNotBlank())
                                Text(choice.detail, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
fun LanguageDialog(
    title: String,
    languages: List<Language>,
    selected: String,
    allowAuto: Boolean,
    onDismiss: () -> Unit,
    onChoose: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val ordered =
        remember(languages, allowAuto) {
            (if (allowAuto) listOf(Language("auto", "Detect language")) else emptyList()) +
                languages.sortedWith(
                    compareBy<Language> {
                            when (it.tag) {
                                "en" -> 0
                                "ja" -> 1
                                else -> 2
                            }
                        }
                        .thenBy { it.name },
                )
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    query,
                    { query = it },
                    singleLine = true,
                    label = { Text("Find a language") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                )
                LazyColumn(Modifier.heightIn(max = 350.dp).padding(top = 8.dp)) {
                    items(
                        ordered.filter {
                            it.name.contains(query, true) || it.tag.contains(query, true)
                        },
                        key = { it.tag },
                    ) { language ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    onChoose(language.tag)
                                    onDismiss()
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(language.tag == selected, onClick = null)
                            Text(language.name, Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
