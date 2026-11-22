package fartingaround.capture

import android.app.Application
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.video.VideoFrameDecoder
import fartingaround.capture.consume.ConsumeDb
import fartingaround.capture.consume.ConsumeRepository
import fartingaround.capture.consume.FeedFetcher
import fartingaround.capture.consume.RefreshWorker
import fartingaround.capture.data.BlobStore
import fartingaround.capture.data.StoryStore
import fartingaround.capture.data.Settings
import fartingaround.capture.net.ApiClient
import fartingaround.capture.sync.SyncEngine
import fartingaround.capture.sync.SyncWorker

/**
 * App-wide singletons, wired by hand. A DI framework would be overkill for
 * six objects; anything that needs them reaches for `context.captureApp`.
 */
class CaptureApp : Application(), SingletonImageLoader.Factory {
	val blobStore by lazy { BlobStore(this) }
	val storyStore by lazy { StoryStore(this, blobStore) }
	val settings by lazy { Settings(this) }
	val api by lazy { ApiClient() }
	val syncEngine by lazy { SyncEngine(storyStore, blobStore, settings, api) }
	val consume by lazy {
		ConsumeRepository(ConsumeDb.open(this), FeedFetcher(api.http)) { opml ->
			// Auto-backup to /api/v1/following whenever a site + token are configured.
			val s = settings.flow.first()
			if (s.apiBase.isNotBlank() && s.apiToken.isNotBlank()) api.session(s.base, s.apiToken).putFollowing(opml)
		}
	}

	override fun onCreate() {
		super.onCreate()
		SyncWorker.ensureChannel(this)
		RefreshWorker.schedule(this)
	}

	/**
	 * Coil loader: shares our OkHttp client and can pull poster frames out of videos.
	 *
	 * Images of drafts and private stories are no longer public: the site
	 * redirects /img/… to /private/img/…, which only answers the author. So
	 * image requests to the configured site carry the same bearer token the
	 * API uses. OkHttp keeps the header across that same-host redirect.
	 * Requests to any other host (feeds in Consume) are left alone.
	 */
	override fun newImageLoader(context: PlatformContext): ImageLoader {
		val signed = api.http.newBuilder()
			.addInterceptor { chain ->
				val req = chain.request()
				// Runs on Coil's network thread, never the main thread.
				val s = runBlocking { settings.flow.first() }
				val site = s.base.toHttpUrlOrNull()
				if (site != null && s.apiToken.isNotBlank() && req.url.host == site.host && req.url.port == site.port) {
					chain.proceed(req.newBuilder().header("Authorization", "Bearer ${s.apiToken}").build())
				} else {
					chain.proceed(req)
				}
			}
			.build()
		return ImageLoader.Builder(context)
			.components {
				add(OkHttpNetworkFetcherFactory(callFactory = { signed }))
				add(VideoFrameDecoder.Factory())
			}
			.build()
	}
}

val Context.captureApp: CaptureApp get() = applicationContext as CaptureApp
