package yokai.presentation.source.group

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.util.isTablet
import yokai.i18n.MR
import yokai.presentation.AppBarType
import yokai.presentation.YokaiScaffold
import yokai.presentation.component.EmptyScreen
import yokai.presentation.source.group.component.rememberSourcePainter
import yokai.util.Screen

/**
 * Picker for a group's members. Sources are listed under their owning extension, with a tri-state
 * checkbox on the header so a whole extension can be added in one tap.
 */
class SourceGroupMembersScreen(private val groupId: String) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { SourceGroupMembersScreenModel(groupId) }
        val state by screenModel.state.collectAsState()
        val listState = rememberLazyListState()

        val success = state as? SourceGroupMembersScreenModel.State.Success

        YokaiScaffold(
            onNavigationIconClicked = { navigator.pop() },
            title = success?.groupName ?: stringResource(MR.strings.edit_group_sources),
            appBarType = AppBarType.SMALL,
        ) { innerPadding ->
            if (success == null) return@YokaiScaffold

            if (success.extensions.isEmpty()) {
                EmptyScreen(
                    modifier = Modifier.padding(innerPadding),
                    image = Icons.AutoMirrored.Outlined.Label,
                    message = stringResource(MR.strings.information_empty_source_group),
                    isTablet = isTablet(),
                )
                return@YokaiScaffold
            }

            LazyColumn(
                modifier = Modifier.padding(innerPadding),
                state = listState,
            ) {
                success.extensions.forEach { extension ->
                    item(key = "header-${extension.label ?: "other"}") {
                        ExtensionHeader(
                            label = extension.label ?: stringResource(MR.strings.other),
                            state = when {
                                extension.allSelected -> ToggleableState.On
                                extension.noneSelected -> ToggleableState.Off
                                else -> ToggleableState.Indeterminate
                            },
                            onClick = {
                                screenModel.toggleExtension(
                                    extension.sources.map { it.id },
                                    selected = !extension.allSelected,
                                )
                            },
                        )
                    }

                    items(
                        count = extension.sources.size,
                        key = { "source-${extension.sources[it].id}" },
                    ) { index ->
                        val source = extension.sources[index]
                        SourceRow(
                            entry = source,
                            onClick = { screenModel.toggleSource(source.id, !source.isSelected) },
                        )
                    }

                    item(key = "divider-${extension.label ?: "other"}") {
                        HorizontalDivider()
                    }
                }
            }
        }

        // The group was deleted from another screen; nothing left to edit.
        LaunchedEffect(state) {
            if (state is SourceGroupMembersScreenModel.State.Missing) navigator.pop()
        }
    }

    @Composable
    private fun ExtensionHeader(label: String, state: ToggleableState, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            TriStateCheckbox(state = state, onClick = onClick)
        }
    }

    @Composable
    private fun SourceRow(
        entry: SourceGroupMembersScreenModel.SourceEntry,
        onClick: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 32.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val painter = rememberSourcePainter(entry.source)
            if (painter != null) {
                Image(
                    modifier = Modifier.size(32.dp),
                    painter = painter,
                    contentDescription = null,
                )
            } else {
                Spacer(modifier = Modifier.size(32.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val subtitle = if (entry.otherGroups.isEmpty()) {
                    entry.lang
                } else {
                    stringResource(MR.strings.also_in_group_, entry.otherGroups.joinToString(", "))
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Checkbox(checked = entry.isSelected, onCheckedChange = null)
        }
    }
}
