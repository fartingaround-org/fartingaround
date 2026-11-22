package fartingaround.capture.consume

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fartingaround.capture.captureApp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Everything the Consume screens do: subscribe, refresh, mark read, OPML.
 * Sits between the Room database and the network. Refresh is also what the
 * hourly background worker calls.
 */
class ConsumeRepository(
	private val db: ConsumeDb,
	private val fetcher: FeedFetcher,
	/** Called after the subscription list changes; wired to the site backup when one is configured. */
	private val onSubscriptionsChanged: suspend (opml: String) -> Unit = {}
) {

	val feeds: Flow<List<Feed>> get() = db.feeds().all()
	val feedsWithCounts: Flow<List<FeedWithCounts>> get() = db.feeds().withCounts()
	val unreadCount: Flow<Int> get() = db.items().unreadCount()
	fun timeline(unreadOnly: Boolean): Flow<List<Item>> = if (unreadOnly) db.items().unread() else db.items().timeline()
	fun itemsFor(feedUrl: String): Flow<List<Item>> = db.items().forFeed(feedUrl)
	fun starred(): Flow<List<Item>> = db.items().starred()
	fun item(id: String): Flow<Item?> = db.items().observe(id)
	suspend fun feed(url: String): Feed? = db.feeds().get(url)

	private val _refreshing = MutableStateFlow(false)
	val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

	/** Add a feed from a URL or site address. Returns the stored feed. */
	suspend fun subscribe(input: String): Feed {
		val (url, parsed) = fetcher.discover(input)
		val existing = db.feeds().get(url)
		if (existing != null) return existing
		val feed = feedFrom(url, parsed, etag = null, lastModified = null)
		db.feeds().upsert(feed)
		storeItems(feed, parsed)
		backup()
		return feed
	}

	/** Add from OPML: no network — the first refresh fills the titles/items in. */
	suspend fun subscribeFromOutline(o: Opml.Outline) {
		if (db.feeds().get(o.xmlUrl) != null) return
		db.feeds().upsert(Feed(url = o.xmlUrl, title = o.title, siteUrl = o.htmlUrl))
	}

	suspend fun unsubscribe(url: String) {
		db.items().deleteForFeed(url)
		db.feeds().delete(url)
		backup()
	}

	/** Push the current list to the site. Failures are swallowed: the phone is the source of truth. */
	suspend fun backup() {
		// Personal feed links are keys and stay on the phone; the rest is backed up (and published as the blogroll).
		runCatching { onSubscriptionsChanged(Opml.export(db.feeds().allOnce().filterNot { Opml.isPersonal(it.url) })) }
	}

	/** Refresh one feed. Errors are stored on the feed, not thrown. */
	suspend fun refresh(url: String) {
		val feed = db.feeds().get(url) ?: return
		try {
			when (val r = fetcher.fetch(feed.url, feed.etag, feed.lastModified)) {
				is FeedFetcher.Result.NotModified ->
					db.feeds().update(feed.copy(lastFetchedAt = System.currentTimeMillis(), lastError = null))
				is FeedFetcher.Result.Fetched -> {
					val updated = feedFrom(feed.url, r.parsed, r.etag, r.lastModified).copy(addedAt = feed.addedAt)
					db.feeds().update(updated)
					storeItems(updated, r.parsed)
					dropWithdrawn(updated, r.parsed)
				}
			}
		} catch (e: Exception) {
			db.feeds().update(feed.copy(lastFetchedAt = System.currentTimeMillis(), lastError = e.message ?: e.toString()))
		}
	}

	suspend fun refreshAll() {
		if (_refreshing.value) return
		_refreshing.value = true
		try {
			for (f in db.feeds().allOnce()) refresh(f.url)
			// Keep the database bounded: read, unstarred items older than 90 days go.
			db.items().prune(System.currentTimeMillis() - 90L * 24 * 3600 * 1000)
		} finally {
			_refreshing.value = false
		}
	}

	suspend fun setRead(id: String, read: Boolean) = db.items().setRead(id, read)
	suspend fun setStarred(id: String, starred: Boolean) = db.items().setStarred(id, starred)
	suspend fun markFeedRead(url: String) = db.items().markFeedRead(url)
	suspend fun markAllRead() = db.items().markAllRead()

	suspend fun exportOpml(): String = Opml.export(db.feeds().allOnce())

	/** Returns how many new feeds were added. */
	suspend fun importOpml(xml: String): Int {
		var added = 0
		for (o in Opml.import(xml)) {
			if (db.feeds().get(o.xmlUrl) == null) { subscribeFromOutline(o); added++ }
		}
		if (added > 0) backup()
		return added
	}

	// ── internals ───────────────────────────────────────────────────────────

	private fun feedFrom(url: String, p: FeedParser.ParsedFeed, etag: String?, lastModified: String?): Feed {
		val ext = p.extension
		val base = ext?.get("base")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
		val version = ext?.get("version")?.let { runCatching { it.jsonPrimitive.content.toInt() }.getOrNull() }
		return Feed(
			url = url,
			title = p.title,
			siteUrl = p.siteUrl,
			description = p.description,
			iconUrl = p.iconUrl,
			format = p.format,
			// Only trust the extension when its base is the feed's own origin.
			enhanced = ext != null && version == 1 && base != null && sameOrigin(base, url),
			enhancedBase = base,
			lastFetchedAt = System.currentTimeMillis(),
			lastError = null,
			etag = etag,
			lastModified = lastModified
		)
	}

	private suspend fun storeItems(feed: Feed, p: FeedParser.ParsedFeed) {
		val now = System.currentTimeMillis()
		val items = p.items.map { it ->
			Item(
				id = sha1("${feed.url}\n${it.guid}"),
				feedUrl = feed.url,
				guid = it.guid,
				url = it.url,
				title = it.title,
				author = it.author,
				summary = it.summary,
				contentHtml = it.contentHtml,
				imageUrl = it.imageUrl,
				publishedAt = it.publishedAt ?: now,
				fetchedAt = now,
				// A story's blocks only from a feed whose extension checked out; a plate
				// reference only when it points back at the feed's own origin.
				extensionJson = if (feed.enhanced) it.extensionJson else it.extensionJson?.takeIf { e -> AtlasRef.parse(e, feed.url) != null }
			)
		}
		val inserted = db.items().insertIgnore(items)
		// Items we already had: refresh their content (edits, late-added photos)
		// without touching read/starred.
		items.forEachIndexed { i, item ->
			if (inserted[i] == -1L) {
				db.items().refreshContent(item.id, item.title, item.url, item.author, item.summary, item.contentHtml, item.imageUrl, item.publishedAt, item.extensionJson)
			}
		}
	}

	/**
	 * A story deleted on its site should leave the timeline too. Only for a
	 * fartingaround feed, which lists what the site has: an item we hold that
	 * the feed no longer lists, and that is not simply older than everything the
	 * feed lists (scrolled off the end), was withdrawn. Starred items are kept:
	 * they were kept on purpose. Other feeds are left alone, because plenty of
	 * them drop and re-add items for reasons of their own.
	 */
	private suspend fun dropWithdrawn(feed: Feed, p: FeedParser.ParsedFeed) {
		if (!feed.enhanced) return
		val listed = p.items.map { sha1("${feed.url}\n${it.guid}") }.toSet()
		val oldestListed = p.items.mapNotNull { it.publishedAt }.minOrNull() ?: if (p.items.isEmpty()) Long.MIN_VALUE else return
		val gone = db.items().forFeedOnce(feed.url)
			.filter { it.id !in listed && !it.starred && it.publishedAt >= oldestListed }
			.map { it.id }
		gone.chunked(500).forEach { db.items().deleteByIds(it) }
	}

	private fun sameOrigin(a: String, b: String): Boolean = runCatching {
		val ua = java.net.URI(a); val ub = java.net.URI(b)
		ua.scheme == ub.scheme && ua.host.equals(ub.host, ignoreCase = true) && ua.port == ub.port
	}.getOrDefault(false)

	private fun sha1(s: String): String =
		MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Hourly background refresh, on any network, deferred when battery is low. */
class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
	override suspend fun doWork(): Result {
		applicationContext.captureApp.consume.refreshAll()
		return Result.success()
	}

	companion object {
		fun schedule(context: Context) {
			val req = PeriodicWorkRequestBuilder<RefreshWorker>(1, TimeUnit.HOURS)
				.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
				.build()
			WorkManager.getInstance(context).enqueueUniquePeriodicWork("consume-refresh", ExistingPeriodicWorkPolicy.KEEP, req)
		}
	}
}
