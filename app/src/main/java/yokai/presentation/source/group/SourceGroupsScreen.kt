package yokai.presentation.source.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import dev.icerock.moko.resources.compose.pluralStringResource
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.ui.source.group.SourceGroupBrowseController
import eu.kanade.tachiyomi.util.compose.LocalBackPress
import eu.kanade.tachiyomi.util.compose.LocalDialogHostState
import eu.kanade.tachiyomi.util.compose.LocalRouter
import eu.kanade.tachiyomi.util.compose.currentOrThrow
import eu.kanade.tachiyomi.util.isTablet
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import yokai.domain.DialogHostState
import yokai.domain.source.model.SourceGroup
import yokai.i18n.MR
import yokai.presentation.AppBarType
import yokai.presentation.YokaiScaffold
import yokai.presentation.component.EmptyScreen
import yokai.presentation.component.ToolTipButton
import yokai.presentation.component.preference.widget.SwitchPreferenceWidget
import yokai.presentation.core.enterAlwaysAppBarScrollBehavior
import yokai.util.Screen
import yokai.util.lang.getString

class SourceGroupsScreen : Screen() {

    @Composable
    override fun Content() {
        val onBackPress = LocalBackPress.currentOrThrow
        val context = LocalContext.current
        val alertDialog = LocalDialogHostState.currentOrThrow
        val router = LocalRouter.currentOrThrow
        val navigator = LocalNavigator.currentOrThrow

        val scope = rememberCoroutineScope()
        val screenModel = rememberScreenModel { SourceGroupsScreenModel() }
        val state by screenModel.state.collectAsState()
        val listState = rememberLazyListState()

        YokaiScaffold(
            onNavigationIconClicked = onBackPress,
            title = stringResource(MR.strings.source_groups),
            appBarType = AppBarType.SMALL,
            scrollBehavior = enterAlwaysAppBarScrollBehavior(
                canScroll = { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 },
            ),
            actions = {
                ToolTipButton(
                    toolTipLabel = stringResource(MR.strings.create_source_group),
                    icon = Icons.Filled.Add,
                    buttonClicked = {
                        scope.launch {
                            alertDialog.awaitNamePrompt(
                                title = context.getString(MR.strings.create_source_group),
                                initialName = "",
                                onConfirm = { screenModel.createGroup(it) },
                            )
                        }
                    },
                )
            },
        ) { innerPadding ->
            val success = state as? SourceGroupsScreenModel.State.Success

            LazyColumn(
                modifier = Modifier.padding(innerPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                state = listState,
            ) {
                if (success == null) return@LazyColumn

                if (success.isEmpty) {
                    item {
                        EmptyScreen(
                            modifier = Modifier.fillParentMaxSize(),
                            image = Icons.AutoMirrored.Outlined.Label,
                            message = stringResource(MR.strings.information_empty_source_groups),
                            isTablet = isTablet(),
                        )
                    }
                    return@LazyColumn
                }

                items(
                    count = success.groups.size,
                    key = { success.groups[it].group.id },
                ) { index ->
                    val item = success.groups[index]
                    SourceGroupCard(
                        group = item.group,
                        installedCount = item.installedCount,
                        onEditMembers = { navigator.push(SourceGroupMembersScreen(item.group.id)) },
                        onOpenGroup = {
                            router.pushController(
                                SourceGroupBrowseController(item.group.id).withFadeTransaction(),
                            )
                        },
                        onRename = {
                            scope.launch {
                                alertDialog.awaitNamePrompt(
                                    title = context.getString(MR.strings.rename_source_group),
                                    initialName = item.group.name,
                                    onConfirm = { screenModel.renameGroup(item.group.id, it) },
                                )
                            }
                        },
                        onDelete = {
                            scope.launch {
                                alertDialog.awaitDeletePrompt(item.group) {
                                    screenModel.deleteGroup(item.group.id)
                                }
                            }
                        },
                        onShowInBrowseChanged = { screenModel.setShowInBrowse(item.group.id, it) },
                        onIncludeInGlobalSearchChanged = {
                            screenModel.setIncludeInGlobalSearch(item.group.id, it)
                        },
                    )
                }
            }

            alertDialog.value?.invoke()
        }

        LaunchedEffect(Unit) {
            screenModel.event.collectLatest { event ->
                when (event) {
                    is SourceGroupEvent.LocalizedMessage -> context.toast(event.stringRes)
                }
            }
        }
    }

    @Composable
    private fun SourceGroupCard(
        group: SourceGroup,
        installedCount: Int,
        onEditMembers: () -> Unit,
        onOpenGroup: () -> Unit,
        onRename: () -> Unit,
        onDelete: () -> Unit,
        onShowInBrowseChanged: (Boolean) -> Unit,
        onIncludeInGlobalSearchChanged: (Boolean) -> Unit,
    ) {
        var menuExpanded by remember { mutableStateOf(false) }

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onEditMembers)
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = pluralStringResource(
                            MR.plurals.source_group_sources,
                            quantity = installedCount,
                            installedCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = stringResource(MR.strings.more),
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(MR.strings.open_source_group)) },
                            onClick = {
                                menuExpanded = false
                                onOpenGroup()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(MR.strings.edit_group_sources)) },
                            onClick = {
                                menuExpanded = false
                                onEditMembers()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(MR.strings.rename_source_group)) },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(MR.strings.delete)) },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }

            SwitchPreferenceWidget(
                title = stringResource(MR.strings.show_group_in_browse),
                subtitle = stringResource(MR.strings.show_group_in_browse_summary),
                checked = group.showInBrowse,
                onCheckedChanged = onShowInBrowseChanged,
            )
            SwitchPreferenceWidget(
                title = stringResource(MR.strings.include_group_in_global_search),
                subtitle = stringResource(MR.strings.include_group_in_global_search_summary),
                checked = group.includeInGlobalSearch,
                onCheckedChanged = onIncludeInGlobalSearchChanged,
            )
        }
    }

    private suspend fun DialogHostState.awaitNamePrompt(
        title: String,
        initialName: String,
        onConfirm: (String) -> Unit,
    ): Unit = dialog { cont ->
        var name by remember { mutableStateOf(initialName) }

        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
            onDismissRequest = { cont.cancel() },
            title = { Text(text = title) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(text = stringResource(MR.strings.source_group_name)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onConfirm(name)
                        cont.cancel()
                    },
                ) {
                    Text(text = stringResource(MR.strings.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { cont.cancel() }) {
                    Text(text = stringResource(MR.strings.cancel))
                }
            },
        )
    }

    private suspend fun DialogHostState.awaitDeletePrompt(
        group: SourceGroup,
        onDelete: () -> Unit,
    ): Unit = dialog { cont ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
            onDismissRequest = { cont.cancel() },
            title = { Text(text = stringResource(MR.strings.confirm_source_group_deletion)) },
            text = {
                Text(
                    text = stringResource(
                        MR.strings.confirm_source_group_deletion_message,
                        group.name,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        cont.cancel()
                    },
                ) {
                    Text(text = stringResource(MR.strings.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { cont.cancel() }) {
                    Text(text = stringResource(MR.strings.cancel))
                }
            },
        )
    }
}
