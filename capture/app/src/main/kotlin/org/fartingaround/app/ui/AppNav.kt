@file:OptIn(ExperimentalMaterial3Api::class)

package org.fartingaround.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.fartingaround.app.captureApp
import org.fartingaround.app.data.AppMode
import org.fartingaround.app.data.CaptureSettings
import org.fartingaround.app.ui.consume.ConsumeRoutes
import org.fartingaround.app.ui.consume.consumeGraph
import org.fartingaround.app.ui.consume.consumeSelectedTab
import org.fartingaround.app.ui.consume.consumeTabs
import org.fartingaround.app.ui.drafts.StoriesListScreen
import org.fartingaround.app.ui.drafts.StoryPreviewScreen
import org.fartingaround.app.ui.editor.StoryEditorScreen
import org.fartingaround.app.ui.live.LiveScreen
import org.fartingaround.app.ui.settings.ConnectScreen
import org.fartingaround.app.ui.settings.SettingsScreen
import org.fartingaround.app.ui.theme.LocalCaptureColors
import kotlinx.coroutines.launch

/** Route names. The editor takes the draft id as a path argument. */
object Routes {
	const val DRAFTS = "drafts"
	const val LIVE = "live"
	const val SETTINGS = "settings"
	const val EDITOR = "draft/{id}"
	fun editor(id: String) = "draft/$id"
	const val PREVIEW = "preview/{id}"
	fun preview(id: String) = "preview/$id"
}

/** One destination in the bottom bar. */
data class NavTab(val route: String, val label: String, val icon: ImageVector)

private val createTabs = listOf(
	NavTab(Routes.DRAFTS, "Stories", Icons.Filled.Edit),
	NavTab(Routes.LIVE, "Live", Icons.Filled.PlayArrow)
)

/** Index into [createTabs] for a route, or -1 when none of them is showing (Settings). */
private fun createSelectedTab(route: String?): Int = when {
	route == null -> 0
	route.startsWith("draft") -> 0 // list + editor both belong to Stories
	else -> createTabs.indexOfFirst { it.route == route }
}

@Composable
fun AppNav(openDraftId: String?, onOpenedDraft: () -> Unit) {
	val nav = rememberNavController()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	// Null until the saved settings have been read. Building the NavHost before
	// then would start it on Create's home; when the real mode arrived the start
	// destination would change, and a changed graph throws away the back stack
	// that was just restored (which is what a rotation in Consume used to do).
	val loaded by context.captureApp.settings.flow.collectAsStateWithLifecycle<CaptureSettings?>(initialValue = null)
	val settings = loaded ?: return

	// A draft shared in from another app: jump straight to its editor.
	LaunchedEffect(openDraftId) {
		if (openDraftId != null) {
			nav.navigate(Routes.editor(openDraftId)) { launchSingleTop = true }
			onOpenedDraft()
		}
	}

	val backStack by nav.currentBackStackEntryAsState()
	val route = backStack?.destination?.route
	val consume = settings.mode == AppMode.CONSUME

	// Switching modes swaps the whole stack: each half has its own home.
	LaunchedEffect(settings.mode) {
		val home = if (consume) ConsumeRoutes.TIMELINE else Routes.DRAFTS
		if (route != null && ConsumeRoutes.isConsume(route) != consume) {
			nav.navigate(home) { popUpTo(0) { inclusive = true }; launchSingleTop = true }
		}
	}

	// The editor is a writing surface: it gets the whole screen and brings its
	// own "← Stories" row, so the app's bars step aside while it is open.
	val fullScreen = route == Routes.EDITOR || route == Routes.PREVIEW

	Scaffold(
		topBar = {
			if (!fullScreen) {
				Column {
					TopAppBar(
						title = {
							BoxWithConstraints(Modifier.fillMaxWidth()) {
								// Read here: the Row's own scope hides the constraints.
								val showTitle = maxWidth >= 280.dp
								Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
									// On a narrow phone the switch matters more than the name.
									if (showTitle) {
										Text("fart around", style = MaterialTheme.typography.headlineSmall, maxLines = 1, softWrap = false)
									}
									ModeSwitch(consume = consume, onSelect = { mode -> scope.launch { context.captureApp.settings.setMode(mode) } })
								}
							}
						},
						actions = {
							IconButton(onClick = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } }) {
								Icon(
									Icons.Filled.Settings,
									contentDescription = "Settings",
									tint = if (route == Routes.SETTINGS) LocalCaptureColors.current.wax else LocalCaptureColors.current.pencil
								)
							}
						},
						colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
					)
					HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
				}
			}
		},
		bottomBar = {
			if (!fullScreen) {
				if (consume) {
					BottomTabs(consumeTabs, consumeSelectedTab(route)) { r ->
						nav.navigate(r) { popUpTo(ConsumeRoutes.TIMELINE) { inclusive = false }; launchSingleTop = true }
					}
				} else {
					BottomTabs(createTabs, createSelectedTab(route)) { r ->
						nav.navigate(r) { popUpTo(Routes.DRAFTS) { inclusive = false }; launchSingleTop = true }
					}
				}
			}
		},
		containerColor = MaterialTheme.colorScheme.background
	) { padding ->
		NavHost(
			nav,
			startDestination = if (consume) ConsumeRoutes.TIMELINE else Routes.DRAFTS,
			// The bars' space is already in `padding`; consuming it keeps imePadding
			// from adding the bottom bar's height a second time under the keyboard.
			modifier = Modifier.padding(padding).consumeWindowInsets(padding).imePadding()
		) {
			consumeGraph(nav)
			// Create's two homes need a site. Until one is connected they show the
			// connect step instead. The editor stays reachable, so something shared
			// in from another app is still kept as a draft on the phone.
			composable(Routes.DRAFTS) {
				NeedsSite { StoriesListScreen(onOpen = { nav.navigate(Routes.editor(it)) }) }
			}
			composable(Routes.EDITOR) { entry ->
				val id = entry.arguments?.getString("id") ?: return@composable
				StoryEditorScreen(
					localId = id,
					onClose = { nav.popBackStack(Routes.DRAFTS, inclusive = false) },
					onPreview = { nav.navigate(Routes.preview(id)) { launchSingleTop = true } }
				)
			}
			composable(Routes.PREVIEW) { entry ->
				val id = entry.arguments?.getString("id") ?: return@composable
				StoryPreviewScreen(localId = id, onBack = { nav.popBackStack() })
			}
			composable(Routes.LIVE) { NeedsSite { LiveScreen() } }
			composable(Routes.SETTINGS) { SettingsScreen() }
		}
	}
}

/**
 * Shows [content] once a site is connected, and the connect step until then.
 * It reads the settings itself rather than taking them from AppNav: a value
 * captured by the NavHost's graph would rebuild the graph, and with it throw
 * away the back stack, every time any setting changed.
 */
@Composable
private fun NeedsSite(content: @Composable () -> Unit) {
	val app = LocalContext.current.captureApp
	val loaded by app.settings.flow.collectAsStateWithLifecycle<CaptureSettings?>(initialValue = null)
	val settings = loaded ?: return
	if (settings.apiBase.isBlank()) ConnectScreen() else content()
}

/** Create | Consume, always visible: the two halves of the app. */
@Composable
private fun ModeSwitch(consume: Boolean, onSelect: (AppMode) -> Unit) {
	Row(
		Modifier
			.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
			.padding(2.dp)
	) {
		ModeSegment("Create", selected = !consume) { onSelect(AppMode.CAPTURE) }
		ModeSegment("Consume", selected = consume) { onSelect(AppMode.CONSUME) }
	}
}

@Composable
private fun ModeSegment(label: String, selected: Boolean, onClick: () -> Unit) {
	val scheme = MaterialTheme.colorScheme
	Box(
		contentAlignment = Alignment.Center,
		modifier = Modifier
			.clip(RoundedCornerShape(50))
			.background(if (selected) scheme.onBackground else Color.Transparent)
			.selectable(selected = selected, role = Role.Tab, onClick = onClick)
			.heightIn(min = 40.dp)
			.padding(horizontal = 10.dp)
	) {
		Text(
			label.uppercase(),
			style = MaterialTheme.typography.labelMedium,
			color = if (selected) scheme.background else scheme.onSurfaceVariant
		)
	}
}

/** Bottom bar: within thumb reach, unlike the tab row it replaces. [selected] may be -1. */
@Composable
private fun BottomTabs(tabs: List<NavTab>, selected: Int, onSelect: (String) -> Unit) {
	val c = LocalCaptureColors.current
	val scheme = MaterialTheme.colorScheme
	NavigationBar(containerColor = scheme.surfaceVariant, contentColor = scheme.onBackground) {
		tabs.forEachIndexed { i, tab ->
			NavigationBarItem(
				selected = i == selected,
				onClick = { onSelect(tab.route) },
				icon = { Icon(tab.icon, contentDescription = null) },
				label = { Text(tab.label.uppercase(), style = MaterialTheme.typography.labelMedium) },
				colors = NavigationBarItemDefaults.colors(
					selectedIconColor = c.wax,
					selectedTextColor = scheme.onBackground,
					indicatorColor = scheme.background,
					unselectedIconColor = c.pencil,
					unselectedTextColor = c.pencil
				)
			)
		}
	}
}
