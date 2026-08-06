package yokai.presentation.source.group

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.CrossfadeTransition
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController

class SourceGroupsController : BaseComposeController() {

    @Composable
    override fun ScreenContent() {
        Navigator(
            screen = SourceGroupsScreen(),
            content = {
                CrossfadeTransition(navigator = it)
            },
        )
    }
}
