@file:OptIn(coil.annotation.ExperimentalCoilApi::class)

package moe.ouom.neriplayer.ui.screen.server

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.ImageLoader
import coil.decode.DataSource
import coil.disk.DiskCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.core.player.cache.RetainingImageDiskCache
import moe.ouom.neriplayer.core.startup.app.serverCoverCacheAliases
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.server.ServerSongRef
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.codec.SyncDataSerializer
import moe.ouom.neriplayer.data.sync.mapping.fromSongItem
import moe.ouom.neriplayer.data.sync.mapping.toSongItem
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicClient
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicCoverReference
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicResourceInterceptor
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicAccounts
import moe.ouom.neriplayer.platform.subsonic.repository.ServerLibraryCategory
import moe.ouom.neriplayer.platform.subsonic.repository.SubsonicBrowseCache
import moe.ouom.neriplayer.platform.subsonic.repository.SubsonicRepository
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavidromePortableCoverIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val report get() = File(context.cacheDir, "navidrome-cover-result.json")
    private var phase = "guard"
    private val checks = JSONObject()

    @Test
    fun realCoverSurvivesSyncRoundTripsAndADifferentLocalProfileUuid() = runBlocking {
        requireDedicatedEmulator()
        val namespace = "pr511-cover-${UUID.randomUUID()}-"
        val isolatedContext = AccountContext(context, namespace)
        val clients = mutableListOf<OkHttpClient>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val cache = SubsonicBrowseCache(scope = scope)
        var accounts: SubsonicAccounts? = null
        var errorType: String? = null
        var completed = false
        try {
            withTimeout(120_000L) {
                phase = "private-fixture"
                val fixture = readFixture()
                val sourceAccounts = SubsonicAccounts(isolatedContext).also { accounts = it }
                sourceAccounts.load()
                assertTrue("the namespaced account store must start empty", sourceAccounts.profiles.value.isEmpty())
                val sourceProfile = SubsonicAccounts.draft("Portable cover source", fixture.baseUrl, fixture.username)
                sourceAccounts.save(sourceProfile, fixture.password)
                val sourceClient = resourceClient(sourceAccounts).also(clients::add)
                val repository = SubsonicRepository(sourceAccounts, SubsonicClient(sourceClient), cache)

                phase = "real-catalog-cover"
                val catalog = repository.catalog(sourceProfile.id, ServerLibraryCategory.SONGS)
                assertFalse("the fixture must finish loading its catalog", catalog.hasMore)
                val song = checkNotNull(catalog.songs.firstOrNull { !it.coverUrl.isNullOrBlank() }) {
                    "The local catalog must contain a song with artwork"
                }
                val url = checkNotNull(song.coverUrl)
                val reference = checkNotNull(SubsonicCoverReference.parse(url.toHttpUrl())) {
                    "Catalog artwork must use a portable cover reference"
                }
                assertTrue("the cover must match its logical source account", reference.matches(sourceProfile))
                assertCredentialFreeCover(url)
                val originalImage = readImage(sourceClient, url)
                checks.put("imageBytes", originalImage.bytes.size).put("imageSha256", originalImage.sha256)
                    .put("decodedImage", true).put("urlHasNoAuthentication", true)

                phase = "legacy-projection"
                val serverSong = checkNotNull(ServerSongRef.from(song))
                val legacy = serverSong.resourceUrl("cover", reference.coverArtId)
                val projected = sourceAccounts.portableCoverUrl(legacy)
                assertTrue("legacy artwork must project to the same portable cover", projected == url)
                checks.put("legacyCoverProjected", true)
                verifyLegacyCoilCache(sourceAccounts, sourceClient, legacy, url)

                phase = "sync-round-trips"
                val syncSong = SyncSong.fromSongItem(song.copy(coverUrl = projected,
                    customCoverUrl = projected, originalCoverUrl = projected))
                val data = SyncData(deviceId = "portable-cover-source", deviceName = "Isolated cover fixture",
                    playlists = listOf(SyncPlaylist(id = 511L, name = "Portable cover fixture", songs = listOf(syncSong))))
                val restored = listOf(false, true).map { dataSaver ->
                    val decoded = SyncDataSerializer.deserialize(SyncDataSerializer.serialize(data, dataSaver))
                        .playlists.single().songs.single()
                    assertTrue("sync must retain the original song identity", decoded.toSongItem().stableKey() == song.stableKey())
                    assertTrue("sync must retain every portable artwork field", decoded.coverUrl == url &&
                        decoded.customCoverUrl == url && decoded.originalCoverUrl == url)
                    assertCredentialFreeCover(checkNotNull(decoded.coverUrl))
                    decoded
                }
                checks.put("jsonAndBinaryPreserved", true).put("syncIdentityPreserved", true)

                phase = "replace-local-profile"
                sourceAccounts.remove(sourceProfile.id)
                assertTrue("the source UUID must no longer resolve", sourceAccounts.profile(sourceProfile.id) == null)
                val destinationAccounts = SubsonicAccounts(isolatedContext).also { accounts = it }
                destinationAccounts.load()
                assertTrue("the replacement account store must not retain the source profile", destinationAccounts.profiles.value.isEmpty())
                val destinationProfile = SubsonicAccounts.draft("Portable cover destination", fixture.baseUrl, fixture.username)
                assertTrue("each device context needs a different local UUID", destinationProfile.id != sourceProfile.id)
                destinationAccounts.save(destinationProfile, fixture.password)
                assertTrue("the old UUID must remain absent in the destination store",
                    destinationAccounts.profile(sourceProfile.id) == null)
                assertTrue("the existing reference must match the replacement logical account", reference.matches(destinationProfile))
                assertTrue("equivalent accounts must generate the same portable URL",
                    SubsonicCoverReference.create(destinationProfile, reference.coverArtId) == url)
                val destinationClient = resourceClient(destinationAccounts).also(clients::add)

                phase = "destination-real-image"
                restored.forEach { decoded ->
                    val destinationImage = readImage(destinationClient, checkNotNull(decoded.coverUrl))
                    assertTrue("synced artwork must return the identical real image bytes",
                        destinationImage.bytes.contentEquals(originalImage.bytes))
                    assertTrue("synced artwork must retain its image hash", destinationImage.sha256 == originalImage.sha256)
                }
                checks.put("localUuidsDiffer", true).put("oldUuidUnavailable", true)
                    .put("replacementAccountImageMatches", true)
                phase = "complete"
                completed = true
            }
        } catch (error: Throwable) {
            errorType = error.javaClass.simpleName
            // 底层异常可能带认证地址，报告只保留阶段、类型和非敏感校验结果
            throw AssertionError("Navidrome portable cover fixture failed at $phase ($errorType); " +
                "see cache/navidrome-cover-result.json").apply { stackTrace = error.stackTrace }
        } finally {
            val cleanupErrors = mutableListOf<String>()
            suspend fun cleanup(name: String, action: suspend () -> Unit) {
                try { action() } catch (_: Throwable) { cleanupErrors += name }
            }
            cleanup("cache") { cache.clear(clearStore = false) }
            cleanup("scope") { scope.coroutineContext[Job]?.cancelAndJoin() }
            cleanup("profiles") { accounts?.let { store -> store.profiles.value.forEach { store.remove(it.id) } } }
            clients.forEach { client -> cleanup("http") {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            } }
            cleanup("preferences") {
                check(context.deleteSharedPreferences(namespace + "subsonic_accounts_secure")) {
                    "Cannot remove this fixture's namespaced account store"
                }
            }
            report.writeText(JSONObject().put("status", if (completed && cleanupErrors.isEmpty()) "pass" else "fail")
                .put("phase", phase).put("executed", true).put("checks", checks)
                .put("errorType", errorType ?: JSONObject.NULL).put("cleanupErrors", cleanupErrors.joinToString(","))
                .toString(2))
            if (completed && cleanupErrors.isNotEmpty()) throw AssertionError("Cover fixture cleanup failed: ${cleanupErrors.joinToString()}")
        }
    }

    private suspend fun verifyLegacyCoilCache(accounts: SubsonicAccounts, sourceClient: OkHttpClient,
                                             legacy: String, current: String) {
        val directory = File(context.cacheDir, "navidrome-cover-cache-${UUID.randomUUID()}")
        check(!directory.exists() && directory.mkdirs()) { "Cannot create the isolated cover cache" }
        var disk: DiskCache? = null
        val loaders = mutableListOf<ImageLoader>()
        val offlineAttempts = AtomicInteger()
        val onlineAttempts = AtomicInteger()
        val offlineClient = OkHttpClient.Builder().addInterceptor {
            offlineAttempts.incrementAndGet()
            throw IOException("The isolated cover cache test forbids network access")
        }.build()
        var failed = false
        try {
            val isolatedDisk = RetainingImageDiskCache(
                delegate = DiskCache.Builder().directory(File(directory, "disk"))
                    .maxSizeBytes(8L * 1024L * 1024L).build(),
                registryFile = File(directory, "keys"),
                fallbackKeys = { key -> serverCoverCacheAliases(key, accounts.profiles.value) }
            ).also { disk = it }
            val onlineClient = sourceClient.newBuilder().addNetworkInterceptor { chain ->
                onlineAttempts.incrementAndGet()
                chain.proceed(chain.request())
            }.build()
            val onlineLoader = isolatedImageLoader(onlineClient, isolatedDisk).also(loaders::add)
            val keys = listOf(
                null to null,
                "nowplaying-cover|$legacy|3|7|11|data=$legacy" to
                    "nowplaying-cover|$current|3|7|11|data=$current",
                "fixture-cover|version=4|size=128|data=$legacy" to
                    "fixture-cover|version=4|size=128|data=$current"
            )
            phase = "coil-legacy-cache-warm"
            val imageHashes = keys.map { (oldKey, _) ->
                val result = loadCoilImage(onlineLoader, legacy, oldKey)
                assertTrue("each legacy key must be filled from the real local server", result.dataSource == DataSource.NETWORK)
                checkNotNull(isolatedDisk.openSnapshot(oldKey ?: legacy)) {
                    "The real legacy image must be committed to the isolated disk cache"
                }.close()
                decodedImageHash(result)
            }
            assertTrue("each old cache representation must fetch its real image", onlineAttempts.get() == keys.size)

            phase = "coil-portable-cache-offline"
            val offlineLoader = isolatedImageLoader(offlineClient, isolatedDisk).also(loaders::add)
            keys.forEachIndexed { index, (_, newKey) ->
                val result = loadCoilImage(offlineLoader, current, newKey)
                assertTrue("portable artwork must reuse its legacy disk entry offline", result.dataSource == DataSource.DISK)
                assertTrue("offline disk artwork must decode identically to its real network image",
                    decodedImageHash(result) == imageHashes[index])
            }
            assertTrue("legacy cache reuse must make no network request", offlineAttempts.get() == 0)
            checks.put("coilBareUrlDiskHit", true).put("coilRepeatedUrlKeyDiskHit", true)
                .put("coilVersionAndSizeKeyDiskHit", true).put("coilOfflineDecodedImageMatches", true)
                .put("coilOfflineNetworkAttempts", offlineAttempts.get())
        } catch (error: Throwable) {
            failed = true
            throw error
        } finally {
            val cleanupErrors = mutableListOf<String>()
            fun cleanup(name: String, action: () -> Unit) {
                if (runCatching(action).isFailure) cleanupErrors += name
            }
            loaders.forEach { loader -> cleanup("image-loader") { loader.shutdown() } }
            cleanup("image-disk") { disk?.clear() }
            cleanup("offline-http") {
                offlineClient.dispatcher.executorService.shutdown()
                offlineClient.connectionPool.evictAll()
            }
            cleanup("image-directory") { check(directory.deleteRecursively()) { "Cannot remove the isolated cover cache" } }
            checks.put("coilOwnedCacheCleaned", cleanupErrors.isEmpty())
            if (!failed && cleanupErrors.isNotEmpty()) throw AssertionError("Coil fixture cleanup failed: ${cleanupErrors.joinToString()}")
        }
    }

    private fun isolatedImageLoader(client: OkHttpClient, disk: DiskCache): ImageLoader =
        ImageLoader.Builder(context).okHttpClient(client).respectCacheHeaders(false)
            .memoryCache(null).memoryCachePolicy(CachePolicy.DISABLED)
            .diskCache(disk).diskCachePolicy(CachePolicy.ENABLED).build()

    private suspend fun loadCoilImage(loader: ImageLoader, url: String, key: String?): SuccessResult {
        val request = ImageRequest.Builder(context).data(url).size(128).allowHardware(false)
            .bitmapConfig(Bitmap.Config.ARGB_8888).memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.ENABLED).networkCachePolicy(CachePolicy.ENABLED)
            .apply { if (key != null) diskCacheKey(key) }.build()
        val result = loader.execute(request)
        assertTrue("Coil must decode the fixture artwork successfully", result is SuccessResult)
        return result as SuccessResult
    }

    private fun decodedImageHash(result: SuccessResult): String {
        val bitmap = checkNotNull((result.drawable as? BitmapDrawable)?.bitmap) {
            "Coil must return a decoded image bitmap"
        }
        assertTrue("Coil's decoded artwork must have positive dimensions", bitmap.width > 0 && bitmap.height > 0)
        val bytes = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Cannot inspect decoded fixture artwork" }
            output.toByteArray()
        }
        return sha256(bytes)
    }

    private fun assertCredentialFreeCover(raw: String) {
        val url = raw.toHttpUrl()
        assertTrue("portable artwork must target the fixture's real cover endpoint",
            url.scheme == "http" && url.host == "10.0.2.2" && url.port == 45331 &&
                url.encodedPath == "/music/rest/getCoverArt.view")
        assertTrue("persistent artwork must not contain authentication query parameters or user info",
            url.username.isEmpty() && url.password.isEmpty() && url.querySize == 2 &&
                url.queryParameterNames == setOf("id", "size") && url.queryParameter("size") == "600")
    }

    private fun resourceClient(accounts: SubsonicAccounts): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false)
        .addInterceptor(SubsonicResourceInterceptor { accounts })
        .addNetworkInterceptor { chain ->
            val url = chain.request().url
            check(url.scheme == "http" && url.host == "10.0.2.2" && url.port == 45331 &&
                url.encodedPath in setOf("/music/rest/search3.view", "/music/rest/getCoverArt.view")) {
                "Cover fixture attempted a request outside its local catalog and artwork endpoints"
            }
            chain.proceed(chain.request())
        }.build()

    private fun readImage(client: OkHttpClient, url: String): Image =
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            assertTrue("the real cover request must succeed", response.isSuccessful)
            assertTrue("the caller-visible response must retain its credential-free URL", response.request.url.toString() == url)
            val body = response.body
            assertTrue("the response must contain an image media type", body.contentType()?.type == "image")
            check(!body.source().request(MAX_IMAGE_BYTES + 1L)) { "Fixture artwork exceeds the diagnostic image limit" }
            val bytes = body.bytes()
            assertTrue("the real image must contain bytes", bytes.isNotEmpty())
            val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) {
                "The server response must decode as a real image"
            }
            try { assertTrue("decoded artwork must have positive dimensions", bitmap.width > 0 && bitmap.height > 0) }
            finally { bitmap.recycle() }
            Image(bytes, sha256(bytes))
        }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun requireDedicatedEmulator() {
        assumeTrue("local portable artwork integration is opt-in",
            InstrumentationRegistry.getArguments().getString("navidromeFixture") == "true")
        assumeTrue("portable artwork integration requires an emulator",
            Build.VERSION.SDK_INT >= 30 && Build.HARDWARE in setOf("ranchu", "goldfish"))
        val avd = instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name").use {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText().trim() }
        }
        assumeTrue("portable artwork integration requires the dedicated PR511 AVD", avd == "NeriPlayer_PR511_Test")
        check(!report.exists() || report.delete()) { "Cannot replace the previous portable artwork report" }
    }

    private fun readFixture(): Fixture {
        val file = File(context.cacheDir, "navidrome-test.json")
        check(file.isFile) { "Private portable artwork fixture configuration is missing" }
        val data = try { JSONObject(file.readText()) }
        catch (_: Exception) { throw AssertionError("Invalid private portable artwork fixture JSON") }
        val baseUrl = data.optString("baseUrl")
        val uri = Uri.parse(baseUrl)
        check(uri.scheme == "http" && uri.host == "10.0.2.2" && uri.port == 45331 && uri.path == "/music/" &&
            uri.query == null && uri.userInfo == null && uri.fragment == null) {
            "Portable artwork fixture must use the isolated local service"
        }
        val username = data.optString("username")
        val password = data.optString("password")
        check(username.isNotBlank() && password.isNotEmpty()) { "Private portable artwork fixture credentials are incomplete" }
        return Fixture(baseUrl, username, password)
    }

    // 加密存储内部也会获取 applicationContext，包装必须在这条路径上保留独立命名空间
    private class AccountContext(base: Context, private val namespace: String) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences(namespace + name, mode)
    }

    private class Fixture(val baseUrl: String, val username: String, val password: String)
    private class Image(val bytes: ByteArray, val sha256: String)

    private companion object {
        const val MAX_IMAGE_BYTES = 4L * 1024L * 1024L
    }
}
