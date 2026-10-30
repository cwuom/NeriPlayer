package moe.ouom.neriplayer.ui.screen.server

import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRoomStore
import moe.ouom.neriplayer.data.model.server.ServerSongRef
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicClient
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicAccounts
import moe.ouom.neriplayer.platform.subsonic.repository.ServerBrowseKey
import moe.ouom.neriplayer.platform.subsonic.repository.ServerBrowsePage
import moe.ouom.neriplayer.platform.subsonic.repository.ServerLibraryCategory
import moe.ouom.neriplayer.platform.subsonic.repository.SubsonicBrowseCache
import moe.ouom.neriplayer.platform.subsonic.repository.SubsonicBrowseRoomStore
import moe.ouom.neriplayer.platform.subsonic.repository.SubsonicRepository
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MusicServerCatalogIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val report get() = File(context.cacheDir, "navidrome-catalog-result.json")
    private var phase = "guard"
    private val stages = JSONObject()

    @Test
    fun realCatalogsAndArtistAlbumsSurviveRoomReopenWithoutMetadataRequests() = runBlocking {
        requireDedicatedEmulator()
        val databaseName = "pr511-catalog-${UUID.randomUUID()}.db"
        val clients = mutableListOf<OkHttpClient>()
        val scopes = mutableListOf<CoroutineScope>()
        val caches = mutableListOf<SubsonicBrowseCache>()
        var database: NeriUserDataDatabase? = null
        var accounts: SubsonicAccounts? = null
        var activeMetrics: MetadataMetrics? = null
        var ownedProfileId: String? = null
        var errorType: String? = null
        var completed = false
        try {
            withTimeout(120_000L) {
                phase = "private-fixture"
                val fixture = readFixture()
                val coldAccounts = SubsonicAccounts(context)
                coldAccounts.load()
                accounts = coldAccounts
                assertTrue("catalog fixture requires no saved server profiles", coldAccounts.profiles.value.isEmpty())
                val coldMetrics = MetadataMetrics()
                activeMetrics = coldMetrics
                val coldClient = measuredClient(coldMetrics).also(clients::add)
                var activeDatabase = openDatabase(databaseName).also { database = it }
                val coldScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also(scopes::add)
                val coldRoom = SubsonicBrowseRoomStore(PlatformPlaylistCacheRoomStore(activeDatabase))
                val coldCache = SubsonicBrowseCache(coldRoom, coldScope).also(caches::add)
                val cold = SubsonicRepository(coldAccounts, SubsonicClient(coldClient), coldCache)
                phase = "local-profile"
                cold.addAccount("PR511 isolated catalog fixture", fixture.baseUrl, fixture.username, fixture.password)
                val profile = coldAccounts.profiles.value.single()
                ownedProfileId = profile.id
                coldMetrics.reset()

                phase = "cold-catalogs"
                val coldPages = ExpectedCounts.keys.associateWith { category ->
                    cold.catalog(profile.id, category).also { page ->
                        assertCompleteCatalog(page, category, profile.id)
                        val stored = coldRoom.read(cold.catalogKey(profile.id, category))
                        assertNotNull("complete catalog must be committed to Room", stored)
                        assertEquals(catalogIdentity(page, category), catalogIdentity(checkNotNull(stored), category))
                        assertFalse("metadata cache must not retain authentication material", stored.toString().contains(fixture.password))
                    }
                }
                stages.put("coldCatalogs", coldMetrics.snapshot())
                assertEquals("each small fixture category needs one metadata page", 3, coldMetrics.requestCount())
                assertTrue("cold metadata must include actual response bytes", coldMetrics.responseBytes() > 0L)
                assertEquals(1, coldMetrics.requestCount("getAlbumList2"))
                assertEquals(1, coldMetrics.requestCount("search3"))
                assertEquals(1, coldMetrics.requestCount("getArtists"))
                val artwork = checkNotNull(coldPages.getValue(ServerLibraryCategory.SONGS).artistArtwork)
                val cachedArtists = cold.cachedArtists(profile.id, coldPages.getValue(ServerLibraryCategory.ARTISTS).artists)
                assertEquals("song artwork must cover every fixture artist", 7, artwork.size)
                assertTrue("artists without server images must use cached song covers", cachedArtists.all {
                    it.coverUrl == null && it.fallbackCoverUrl == artwork[it.id] && it.imageUrl != null
                })
                assertEquals("resolving cached artist artwork must not request metadata", 3, coldMetrics.requestCount())
                coldMetrics.reset()

                phase = "cold-artist-albums"
                val artists = coldPages.getValue(ServerLibraryCategory.ARTISTS).artists
                val artistAlbums = artists.associate { artist ->
                    val page = cold.artistAlbums(profile.id, artist.id)
                    assertFalse("artist album collection must be complete", page.hasMore)
                    assertEquals("each fixture artist owns five albums", 5, page.albums.size)
                    assertEquals(5, page.albums.map { it.id }.distinct().size)
                    assertTrue("artist albums must retain the local profile identity", page.albums.all { it.profileId == profile.id })
                    val key = ServerBrowseKey(profile.id, profile.revision, "artist", artist.id, size = 500)
                    assertNotNull("artist album collection must be committed to Room", coldRoom.read(key))
                    artist.id to page.albums
                }
                stages.put("coldArtistAlbums", coldMetrics.snapshot())
                assertEquals("artist details must not fetch every album's tracks", 7, coldMetrics.requestCount())
                assertEquals(7, coldMetrics.requestCount("getArtist"))

                phase = "close-room"
                coldCache.clear(clearStore = false)
                coldScope.coroutineContext[Job]?.cancelAndJoin()
                activeDatabase.close()
                database = null
                assertTrue("the persisted catalog database must exist", context.getDatabasePath(databaseName).isFile)

                phase = "warm-room-reopen"
                activeDatabase = openDatabase(databaseName).also { database = it }
                val warmAccounts = SubsonicAccounts(context)
                warmAccounts.load()
                assertNotNull("the owned local profile must survive a fresh account-store instance", warmAccounts.profile(profile.id))
                val warmMetrics = MetadataMetrics(forbidNetwork = true)
                activeMetrics = warmMetrics
                val warmClient = measuredClient(warmMetrics).also(clients::add)
                val warmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also(scopes::add)
                val warmTime = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(2)
                val warmCache = SubsonicBrowseCache(
                    SubsonicBrowseRoomStore(PlatformPlaylistCacheRoomStore(activeDatabase)), warmScope, now = { warmTime }
                ).also(caches::add)
                val warm = SubsonicRepository(warmAccounts, SubsonicClient(warmClient), warmCache)
                ExpectedCounts.keys.forEach { category ->
                    val page = warm.catalog(profile.id, category)
                    assertCompleteCatalog(page, category, profile.id)
                    assertEquals("reopened catalog must preserve source order and identity",
                        catalogIdentity(coldPages.getValue(category), category), catalogIdentity(page, category))
                    assertTrue("reopened catalog must preserve all mapped display metadata", coldPages.getValue(category) == page)
                }
                artists.forEach { artist ->
                    val page = warm.artistAlbums(profile.id, artist.id)
                    assertFalse(page.hasMore)
                    assertTrue("reopened artist albums must preserve ordered display metadata", artistAlbums.getValue(artist.id) == page.albums)
                }
                assertEquals("song artwork survives Room reopen", artwork,
                    warm.catalog(profile.id, ServerLibraryCategory.SONGS).artistArtwork)
                assertTrue("offline artist avatars reuse persisted song artwork", warm.cachedArtists(profile.id, artists).all {
                    it.imageUrl == artwork[it.id]
                })
                stages.put("warmAfterTwoHours", warmMetrics.snapshot())
                assertEquals("Room hydration must not attempt a metadata request", 0, warmMetrics.requestCount())
                assertEquals(0L, warmMetrics.responseBytes())

                phase = "explicit-refresh"
                warmMetrics.forbidNetwork = false
                val refreshed = warm.catalog(profile.id, ServerLibraryCategory.ALBUMS, force = true)
                assertCompleteCatalog(refreshed, ServerLibraryCategory.ALBUMS, profile.id)
                stages.put("explicitAlbumRefresh", warmMetrics.snapshot())
                assertEquals("manual refresh must fetch exactly its requested small catalog", 1, warmMetrics.requestCount())
                assertEquals(1, warmMetrics.requestCount("getAlbumList2"))
                assertTrue(warmMetrics.responseBytes() > 0L)
                phase = "complete"
                completed = true
            }
        } catch (error: Throwable) {
            errorType = error.javaClass.simpleName
            stages.put("failureMetrics", activeMetrics?.snapshot())
            // 只暴露阶段和类型，底层 HTTP 异常可能包含认证地址
            throw AssertionError("Navidrome catalog fixture failed at $phase ($errorType); " +
                "see cache/navidrome-catalog-result.json").apply { stackTrace = error.stackTrace }
        } finally {
            val cleanupErrors = mutableListOf<String>()
            suspend fun cleanup(name: String, action: suspend () -> Unit) {
                try { action() } catch (_: Throwable) { cleanupErrors += name }
            }
            caches.forEach { cache -> cleanup("cache") { cache.clear(clearStore = false) } }
            scopes.forEach { scope -> cleanup("scope") { scope.coroutineContext[Job]?.cancelAndJoin() } }
            cleanup("database") { database?.close() }
            ownedProfileId?.let { id -> cleanup("local-profile") { accounts?.remove(id) } }
            clients.forEach { client -> cleanup("http") {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            } }
            if (completed && cleanupErrors.isEmpty()) cleanup("database-file") {
                check(context.deleteDatabase(databaseName)) { "Cannot delete the fixture's own catalog database" }
            }
            val status = if (completed && cleanupErrors.isEmpty()) "pass" else "fail"
            report.writeText(JSONObject().put("status", status).put("phase", phase)
                .put("executed", true).put("albums", if (completed) 35 else 0)
                .put("songs", if (completed) 65 else 0).put("artists", if (completed) 7 else 0)
                .put("artistAlbumChecks", if (completed) 7 else 0).put("warmAgeHours", 2)
                .put("stages", stages).put("errorType", errorType)
                .put("cleanupErrors", cleanupErrors.joinToString(","))
                .put("diagnosticDatabase", if (status == "fail") databaseName else JSONObject.NULL).toString(2))
            if (completed && cleanupErrors.isNotEmpty()) throw AssertionError("Catalog fixture cleanup failed: ${cleanupErrors.joinToString()}")
        }
    }

    private fun assertCompleteCatalog(page: ServerBrowsePage, category: ServerLibraryCategory, profileId: String) {
        assertFalse("catalog must finish automatic pagination", page.hasMore)
        val identity = catalogIdentity(page, category)
        assertEquals("fixture catalog size must match all available metadata", ExpectedCounts.getValue(category), identity.size)
        assertEquals("catalog must not repeat source identities", identity.size, identity.distinct().size)
        assertTrue("catalog must have a successful persistence timestamp", page.savedAtMs > 0L)
        when (category) {
            ServerLibraryCategory.ALBUMS -> assertTrue(page.albums.all { it.profileId == profileId })
            ServerLibraryCategory.SONGS -> assertTrue(page.songs.all { ServerSongRef.from(it)?.profileId == profileId })
            ServerLibraryCategory.ARTISTS -> assertTrue(page.artists.all { it.profileId == profileId })
        }
    }

    private fun catalogIdentity(page: ServerBrowsePage, category: ServerLibraryCategory): List<String> = when (category) {
        ServerLibraryCategory.ALBUMS -> page.albums.map { it.id }
        ServerLibraryCategory.SONGS -> page.songs.map { it.stableKey() }
        ServerLibraryCategory.ARTISTS -> page.artists.map { it.id }
    }

    private fun openDatabase(name: String) = Room.databaseBuilder(context, NeriUserDataDatabase::class.java, name).build()

    private fun measuredClient(metrics: MetadataMetrics): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val url = chain.request().url
            check(url.host == "10.0.2.2" && url.port == 45331 && url.encodedPath.startsWith("/music/rest/")) {
                "catalog fixture attempted a request outside the isolated local service"
            }
            val endpoint = url.encodedPath.substringAfterLast('/').removeSuffix(".view")
            check(endpoint in MetadataEndpoints) { "catalog browsing must only fetch metadata" }
            metrics.requestStarted(endpoint)
            if (metrics.forbidNetwork) throw IOException("Warm catalog unexpectedly attempted network access")
            chain.proceed(chain.request())
        }
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val body = response.body
            val bytes = body.bytes()
            val endpoint = chain.request().url.encodedPath.substringAfterLast('/').removeSuffix(".view")
            metrics.responseReceived(endpoint, bytes.size.toLong())
            response.newBuilder().body(bytes.toResponseBody(body.contentType())).build()
        }.build()

    private fun requireDedicatedEmulator() {
        assumeTrue("local catalog integration is opt-in", InstrumentationRegistry.getArguments().getString("navidromeFixture") == "true")
        assumeTrue("catalog integration requires an emulator", Build.VERSION.SDK_INT >= 30 && Build.HARDWARE in setOf("ranchu", "goldfish"))
        val avd = instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name").use {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText().trim() }
        }
        assumeTrue("catalog integration requires the dedicated PR511 AVD", avd == "NeriPlayer_PR511_Test")
        check(!report.exists() || report.delete()) { "Cannot replace the previous catalog fixture report" }
    }

    private fun readFixture(): Fixture {
        val file = File(context.cacheDir, "navidrome-test.json")
        check(file.isFile) { "Private catalog fixture configuration is missing" }
        val data = try { JSONObject(file.readText()) } catch (_: Exception) { throw AssertionError("Invalid private catalog fixture JSON") }
        val baseUrl = data.optString("baseUrl")
        val uri = Uri.parse(baseUrl)
        check(uri.scheme == "http" && uri.host == "10.0.2.2" && uri.port == 45331 && uri.path == "/music/" &&
            uri.query == null && uri.userInfo == null && uri.fragment == null) { "Catalog fixture must use the isolated local service" }
        val username = data.optString("username")
        val password = data.optString("password")
        check(username.isNotBlank() && password.isNotEmpty()) { "Private catalog fixture credentials are incomplete" }
        return Fixture(baseUrl, username, password)
    }

    private class Fixture(val baseUrl: String, val username: String, val password: String)

    private class MetadataMetrics(@Volatile var forbidNetwork: Boolean = false) {
        private val attempts = linkedMapOf<String, Int>()
        private val bytes = linkedMapOf<String, Long>()
        @Synchronized fun requestStarted(endpoint: String) { attempts[endpoint] = (attempts[endpoint] ?: 0) + 1 }
        @Synchronized fun responseReceived(endpoint: String, count: Long) { bytes[endpoint] = (bytes[endpoint] ?: 0L) + count }
        @Synchronized fun requestCount(endpoint: String? = null): Int = if (endpoint == null) attempts.values.sum() else attempts[endpoint] ?: 0
        @Synchronized fun responseBytes(): Long = bytes.values.sum()
        @Synchronized fun reset() { attempts.clear(); bytes.clear() }
        @Synchronized fun snapshot(): JSONObject = JSONObject().put("metadataRequestAttempts", requestCount())
            .put("encodedResponseBodyBytes", responseBytes()).put("endpoints", JSONObject().apply {
                attempts.forEach { (endpoint, count) -> put(endpoint, JSONObject().put("requests", count)
                    .put("encodedResponseBodyBytes", bytes[endpoint] ?: 0L)) }
            })
    }

    private companion object {
        val ExpectedCounts = linkedMapOf(ServerLibraryCategory.ALBUMS to 35, ServerLibraryCategory.SONGS to 65,
            ServerLibraryCategory.ARTISTS to 7)
        val MetadataEndpoints = setOf("ping", "getAlbumList2", "search3", "getArtists", "getArtist")
    }
}
