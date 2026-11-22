package fartingaround.capture.ui.consume

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.RssFeed
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import fartingaround.capture.ui.NavTab

/** Routes for the reader half of the app. */
object ConsumeRoutes {
	const val TIMELINE = "consume/timeline"
	const val FEEDS = "consume/feeds"
	const val STARRED = "consume/starred"
	const val FEED = "consume/feed/{url}"
	const val ITEM = "consume/item/{id}"
	fun feed(url: String) = "consume/feed/${java.net.URLEncoder.encode(url, "utf-8")}"
	fun item(id: String) = "consume/item/${java.net.URLEncoder.encode(id, "utf-8")}"
	fun isConsume(route: String?) = route?.startsWith("consume/") == true
}

fun NavGraphBuilder.consumeGraph(nav: NavHostController) {
	composable(ConsumeRoutes.TIMELINE) { TimelineScreen(onOpen = { nav.navigate(ConsumeRoutes.item(it)) }) }
	composable(ConsumeRoutes.STARRED) { TimelineScreen(starredOnly = true, onOpen = { nav.navigate(ConsumeRoutes.item(it)) }) }
	composable(ConsumeRoutes.FEEDS) { FeedsScreen(onOpenFeed = { nav.navigate(ConsumeRoutes.feed(it)) }) }
	composable(ConsumeRoutes.FEED) { entry ->
		val url = entry.arguments?.getString("url")?.let { java.net.URLDecoder.decode(it, "utf-8") } ?: return@composable
		TimelineScreen(feedUrl = url, onOpen = { nav.navigate(ConsumeRoutes.item(it)) })
	}
	composable(ConsumeRoutes.ITEM) { entry ->
		val id = entry.arguments?.getString("id")?.let { java.net.URLDecoder.decode(it, "utf-8") } ?: return@composable
		ItemScreen(itemId = id, onBack = { nav.popBackStack() })
	}
}

/** The reader's bottom-bar destinations. */
val consumeTabs = listOf(
	NavTab(ConsumeRoutes.TIMELINE, "Timeline", Icons.Filled.Home),
	NavTab(ConsumeRoutes.FEEDS, "Feeds", Icons.Filled.RssFeed),
	NavTab(ConsumeRoutes.STARRED, "Saved", Icons.Filled.Bookmark)
)

/** Index into [consumeTabs] for a route, or -1 when none of them is showing (Settings). */
fun consumeSelectedTab(route: String?): Int = when {
	route == null -> 0
	route.startsWith("consume/feed/") -> 1
	route.startsWith("consume/item/") -> 0
	else -> consumeTabs.indexOfFirst { it.route == route }
}
