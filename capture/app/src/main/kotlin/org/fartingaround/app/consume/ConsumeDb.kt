package org.fartingaround.app.consume

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/*
 * Consume mode's storage: the feeds you follow and the items they've
 * produced. Room rather than JSON files because a reader accumulates
 * thousands of items and the screens are queries ("unread, newest first,
 * for this feed") that a database answers for free.
 */

@Entity(tableName = "feeds")
data class Feed(
	/** The feed URL itself; stable, so it's the key. */
	@PrimaryKey val url: String,
	val title: String,
	val siteUrl: String? = null,
	val description: String? = null,
	val iconUrl: String? = null,
	/** "rss" | "atom" | "json" — what the last fetch parsed as. */
	val format: String = "rss",
	/** The feed advertises the _fartingaround extension (a fartingaround-style site). */
	val enhanced: Boolean = false,
	/** Extension base origin, when enhanced. */
	val enhancedBase: String? = null,
	val addedAt: Long = System.currentTimeMillis(),
	val lastFetchedAt: Long? = null,
	val lastError: String? = null,
	// Conditional-request state so an unchanged feed costs one 304.
	val etag: String? = null,
	val lastModified: String? = null
)

@Entity(
	tableName = "items",
	indices = [Index("feedUrl"), Index("publishedAt"), Index(value = ["feedUrl", "guid"], unique = true)]
)
data class Item(
	/** sha-1 of feedUrl + guid; stable across refreshes. */
	@PrimaryKey val id: String,
	val feedUrl: String,
	val guid: String,
	val url: String? = null,
	val title: String,
	val author: String? = null,
	val summary: String? = null,
	/** Full HTML content when the feed provides it. */
	val contentHtml: String? = null,
	val imageUrl: String? = null,
	/** Epoch millis; falls back to fetch time when the feed gives no date. */
	val publishedAt: Long,
	val fetchedAt: Long,
	val read: Boolean = false,
	val starred: Boolean = false,
	/** The item's `_fartingaround` object, verbatim JSON, when present. */
	val extensionJson: String? = null
)

/** A feed row joined with its unread count, for the Feeds screen. */
data class FeedWithCounts(
	val url: String,
	val title: String,
	val siteUrl: String?,
	val iconUrl: String?,
	val enhanced: Boolean,
	val lastFetchedAt: Long?,
	val lastError: String?,
	val unread: Int,
	val total: Int
)

@Dao
interface FeedDao {
	@Query("SELECT * FROM feeds ORDER BY title COLLATE NOCASE")
	fun all(): Flow<List<Feed>>

	@Query("SELECT * FROM feeds ORDER BY title COLLATE NOCASE")
	suspend fun allOnce(): List<Feed>

	@Query("SELECT * FROM feeds WHERE url = :url")
	suspend fun get(url: String): Feed?

	@Query(
		"""SELECT f.url, f.title, f.siteUrl, f.iconUrl, f.enhanced, f.lastFetchedAt, f.lastError,
		   (SELECT COUNT(*) FROM items i WHERE i.feedUrl = f.url AND i.read = 0) AS unread,
		   (SELECT COUNT(*) FROM items i WHERE i.feedUrl = f.url) AS total
		   FROM feeds f ORDER BY f.title COLLATE NOCASE"""
	)
	fun withCounts(): Flow<List<FeedWithCounts>>

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun upsert(feed: Feed)

	@Update
	suspend fun update(feed: Feed)

	@Query("DELETE FROM feeds WHERE url = :url")
	suspend fun delete(url: String)
}

@Dao
interface ItemDao {
	@Query("SELECT * FROM items ORDER BY publishedAt DESC LIMIT :limit")
	fun timeline(limit: Int = 500): Flow<List<Item>>

	@Query("SELECT * FROM items WHERE read = 0 ORDER BY publishedAt DESC LIMIT :limit")
	fun unread(limit: Int = 500): Flow<List<Item>>

	@Query("SELECT * FROM items WHERE feedUrl = :feedUrl ORDER BY publishedAt DESC")
	fun forFeed(feedUrl: String): Flow<List<Item>>

	@Query("SELECT * FROM items WHERE starred = 1 ORDER BY publishedAt DESC")
	fun starred(): Flow<List<Item>>

	@Query("SELECT * FROM items WHERE id = :id")
	fun observe(id: String): Flow<Item?>

	@Query("SELECT * FROM items WHERE id = :id")
	suspend fun get(id: String): Item?

	@Query("SELECT COUNT(*) FROM items WHERE read = 0")
	fun unreadCount(): Flow<Int>

	/** Keep read/starred state when an item is seen again on refresh. */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertIgnore(items: List<Item>): List<Long>

	@Query(
		"""UPDATE items SET title = :title, url = :url, author = :author, summary = :summary,
		   contentHtml = :contentHtml, imageUrl = :imageUrl, publishedAt = :publishedAt, extensionJson = :extensionJson
		   WHERE id = :id"""
	)
	suspend fun refreshContent(
		id: String, title: String, url: String?, author: String?, summary: String?,
		contentHtml: String?, imageUrl: String?, publishedAt: Long, extensionJson: String?
	)

	@Query("UPDATE items SET read = :read WHERE id = :id")
	suspend fun setRead(id: String, read: Boolean)

	@Query("UPDATE items SET read = 1 WHERE feedUrl = :feedUrl")
	suspend fun markFeedRead(feedUrl: String)

	@Query("UPDATE items SET read = 1")
	suspend fun markAllRead()

	@Query("UPDATE items SET starred = :starred WHERE id = :id")
	suspend fun setStarred(id: String, starred: Boolean)

	@Query("DELETE FROM items WHERE feedUrl = :feedUrl")
	suspend fun deleteForFeed(feedUrl: String)

	@Query("SELECT * FROM items WHERE feedUrl = :feedUrl")
	suspend fun forFeedOnce(feedUrl: String): List<Item>

	@Query("DELETE FROM items WHERE id IN (:ids)")
	suspend fun deleteByIds(ids: List<String>)

	/** Trim read, unstarred items older than the cutoff so the database stays small. */
	@Query("DELETE FROM items WHERE read = 1 AND starred = 0 AND publishedAt < :cutoff")
	suspend fun prune(cutoff: Long)
}

@Database(entities = [Feed::class, Item::class], version = 1, exportSchema = true)
abstract class ConsumeDb : RoomDatabase() {
	abstract fun feeds(): FeedDao
	abstract fun items(): ItemDao

	companion object {
		fun open(context: Context): ConsumeDb =
			Room.databaseBuilder(context, ConsumeDb::class.java, "consume.db").build()
	}
}
