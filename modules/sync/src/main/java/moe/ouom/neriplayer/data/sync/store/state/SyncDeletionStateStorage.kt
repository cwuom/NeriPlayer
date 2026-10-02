package moe.ouom.neriplayer.data.sync.store.state

import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Reader
import java.io.StringReader
import java.lang.reflect.Type
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException

internal class SyncDeletionStateStorage(
    private val preferences: SharedPreferences,
    private val directory: File? = null,
    private val syncDirectory: (File) -> Unit = { path ->
        FileChannel.open(path.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
) {
    private val gson = Gson()
    private var preparingGenerations: MutableList<File>? = null

    fun marker(key: String): String? {
        val raw = preferences.getString(key, null) ?: return null
        return if (raw.startsWith(FILE_PREFIX)) raw
        else MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).hex()
    }

    fun <T> read(key: String, type: Type): T? {
        val raw = preferences.getString(key, null) ?: return null
        return try {
            if (raw.startsWith(FILE_PREFIX)) readGeneration(raw) { reader ->
                gson.fromJson<T>(reader, type) ?: error("Deletion generation has no valid document")
            }
            else gson.fromJson<T>(StringReader(raw), type)
                ?: error("Deletion state has no valid document")
        } catch (error: Exception) {
            throw IllegalStateException("Failed to read durable sync deletion state", error)
        }
    }

    fun <T : Any> visitObjectArray(key: String, itemType: Class<T>, checkActive: () -> Unit, visit: (T) -> Unit) {
        try {
            val adapter = gson.getAdapter(itemType)
            val captured = synchronized(syncMutationLock) { captureDocument(key) } ?: return checkActive()
            consumeObjectArray(captured.reader, adapter, checkActive, visit)
            captured.verifyChecksum()
            checkActive()
            synchronized(syncMutationLock) {
                if (preferences.getString(key, null) != captured.raw) throw SyncDeletionGenerationChangedException()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: SyncDeletionGenerationChangedException) {
            throw error
        } catch (error: Exception) {
            throw IllegalStateException("Failed to scan durable sync state", error)
        }
    }

    private fun <T : Any> consumeObjectArray(
        reader: Reader, adapter: TypeAdapter<T>, checkActive: () -> Unit, visit: (T) -> Unit
    ) {
        JsonReader(reader).use { json ->
            json.strictness = Strictness.STRICT
            json.beginArray()
            while (json.hasNext()) {
                checkActive()
                visit(readObjectRecord(json, adapter))
            }
            json.endArray()
            check(json.peek() == JsonToken.END_DOCUMENT) { "Trailing sync state document" }
        }
    }

    private fun <T : Any> readObjectRecord(json: JsonReader, adapter: TypeAdapter<T>): T {
        check(json.peek() == JsonToken.BEGIN_OBJECT) { "Invalid sync state record" }
        return checkNotNull(adapter.read(json)) { "Missing sync state record" }
    }

    private fun captureDocument(key: String): CapturedDocument? {
        val raw = preferences.getString(key, null) ?: return null
        if (!raw.startsWith(FILE_PREFIX)) return CapturedDocument(raw, StringReader(raw))
        val (name, expectedHash) = parseMarker(raw)
        val digest = MessageDigest.getInstance("SHA-256")
        // 锁内打开文件，后续 generation 清理不会使已打开的完整文件失效
        val stream = FileInputStream(File(checkNotNull(directory), name))
        return CapturedDocument(raw, InputStreamReader(DigestInputStream(stream, digest), Charsets.UTF_8), digest, expectedHash)
    }

    private class CapturedDocument(
        val raw: String,
        val reader: Reader,
        val digest: MessageDigest? = null,
        val expectedHash: String? = null
    ) {
        fun verifyChecksum() {
            if (digest == null) return
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            check(actual == expectedHash) { "Sync deletion generation checksum mismatch" }
        }
    }

    fun readPlaylistIds(): Set<Long> {
        val raw = preferences.getString(KEY_DELETED_PLAYLIST_IDS, null) ?: return emptySet()
        return try {
            if (raw.startsWith(FILE_PREFIX)) readGeneration(raw, ::readPlaylistIdsJson)
            else readInlinePlaylistIds(raw)
        } catch (error: Exception) {
            throw IllegalStateException("Failed to read durable playlist deletion IDs", error)
        }
    }

    private fun readInlinePlaylistIds(raw: String): Set<Long> {
        if (raw.trimStart().startsWith("[")) return readPlaylistIdsJson(StringReader(raw))
        if (raw.isEmpty()) return emptySet()
        val ids = linkedSetOf<Long>()
        var start = 0
        while (true) {
            val separator = raw.indexOf(',', start)
            val end = if (separator < 0) raw.length else separator
            ids += checkNotNull(raw.substring(start, end).toLongOrNull()) { "Invalid legacy playlist deletion ID" }
            if (separator < 0) return ids
            start = separator + 1
        }
    }

    private fun readPlaylistIdsJson(reader: Reader): Set<Long> {
        return JsonReader(reader).use { json ->
            val ids = linkedSetOf<Long>()
            json.beginArray()
            while (json.hasNext()) ids += readPlaylistId(json)
            json.endArray()
            check(json.peek() == JsonToken.END_DOCUMENT) { "Trailing playlist deletion state" }
            ids
        }
    }

    private fun readPlaylistId(json: JsonReader): Long {
        check(json.peek() == JsonToken.NUMBER) { "Invalid playlist deletion ID" }
        return checkNotNull(json.nextString().toLongOrNull()) { "Invalid playlist deletion ID" }
    }

    fun write(editor: SharedPreferences.Editor, key: String, value: Any?) = synchronized(syncMutationLock) {
        val path = directory
        if (path == null) {
            if (value == null) editor.remove(key) else editor.putString(key, gson.toJson(value))
            return@synchronized
        }
        val batch = checkNotNull(preparingGenerations) { "File-backed sync state requires a preparation batch" }
        writeFileState(editor, path, key, value, batch)
    }

    private fun writeFileState(editor: SharedPreferences.Editor, path: File, key: String, value: Any?, batch: MutableList<File>) {
        check(path.isDirectory || path.mkdirs()) { "Cannot create sync deletion directory" }
        val owned = mutableListOf<File>()
        try {
            val previous = preferences.getString(key, null)?.let { previousGeneration(key, it, owned) }
            val current = value?.let { writeGeneration(owned) { writer -> gson.toJson(it, writer) } }
            if (current == null) editor.remove(key) else editor.putString(key, current)
            if (previous == null) editor.remove(backupKey(key)) else editor.putString(backupKey(key), previous)
            batch.addAll(owned)
        } catch (failure: Exception) {
            deleteUnpublished(owned, failure)
            throw failure
        }
    }

    // 读取方只确认自身 prefs 的耐久性，不清理其它持有方仍在重试的 generation
    fun confirm(): Boolean = preferences.commitEdit {}

    fun commitEdit(action: SharedPreferences.Editor.() -> Unit): Boolean = synchronized(syncMutationLock) {
        val editor = prepareEditor(action)
        // commit 可能已经更新内存或磁盘，进入此边界后不能再回收生成文件
        val committed = editor.commit()
        if (committed) cleanUnreferencedGenerations()
        committed
    }

    private fun prepareEditor(action: SharedPreferences.Editor.() -> Unit): SharedPreferences.Editor {
        val outer = preparingGenerations
        val owned = mutableListOf<File>()
        preparingGenerations = owned
        try {
            return preferences.edit().also { it.action() }
        } catch (failure: Exception) {
            deleteUnpublished(owned, failure)
            throw failure
        } finally {
            preparingGenerations = outer
        }
    }

    private fun previousGeneration(key: String, raw: String, owned: MutableList<File>): String {
        if (raw.startsWith(FILE_PREFIX)) {
            validateGeneration(raw)
            return raw
        }
        if (key == KEY_DELETED_PLAYLIST_IDS) {
            val ids = readInlinePlaylistIds(raw)
            return writeGeneration(owned) { writer -> gson.toJson(ids, writer) }
        }
        check(!JsonParser.parseString(raw).isJsonNull) { "Deletion state has no valid document" }
        return writeGeneration(owned) { it.write(raw) }
    }

    private fun validateGeneration(marker: String) {
        val (name, expectedHash) = parseMarker(marker)
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(FileInputStream(File(checkNotNull(directory), name)), digest).use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (stream.read(buffer) != -1) {
                // 完整读取到结尾后再验证流式校验和
            }
        }
        check(digest.digest().hex() == expectedHash) { "Sync deletion generation checksum mismatch" }
    }

    private fun <T> readGeneration(marker: String, decode: (Reader) -> T): T {
        val (name, expectedHash) = parseMarker(marker)
        val path = checkNotNull(directory) { "Deletion state requires its file directory" }
        val digest = MessageDigest.getInstance("SHA-256")
        val decoded = FileInputStream(File(path, name)).use { stream ->
            InputStreamReader(DigestInputStream(stream, digest), Charsets.UTF_8).use { reader ->
                decode(reader)
            }
        }
        check(digest.digest().hex() == expectedHash) { "Sync deletion generation checksum mismatch" }
        return decoded
    }

    private fun writeGeneration(owned: MutableList<File>, write: (OutputStreamWriter) -> Unit): String {
        val path = checkNotNull(directory)
        val name = "${UUID.randomUUID()}.json"
        val temporary = File(path, "$name.tmp")
        val destination = File(path, name)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            FileOutputStream(temporary).use { stream ->
                OutputStreamWriter(DigestOutputStream(stream, digest), Charsets.UTF_8).use { writer ->
                    write(writer)
                    writer.flush()
                    stream.fd.sync()
                }
            }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(path)
            owned += destination
            return "$FILE_PREFIX$name:${digest.digest().hex()}"
        } catch (error: Exception) {
            deleteUnpublished(listOf(temporary, destination), error)
            throw IllegalStateException("Failed to persist sync deletion generation", error)
        }
    }

    private fun deleteUnpublished(files: List<File>, failure: Exception) {
        for (file in files) {
            try {
                Files.deleteIfExists(file.toPath())
            } catch (cleanup: Exception) {
                failure.addSuppressed(cleanup)
            }
        }
    }

    private fun cleanUnreferencedGenerations() {
        val path = directory ?: return
        val retained = STATE_KEYS.asSequence()
            .flatMap { sequenceOf(it, backupKey(it)) }
            .mapNotNull { preferences.getString(it, null) }
            .filter { it.startsWith(FILE_PREFIX) }
            .map { parseMarker(it).first }
            .toHashSet()
        path.listFiles().orEmpty().forEach { file ->
            if (file.name.matches(GENERATION_NAME) && file.name !in retained) file.delete()
        }
    }

    private fun parseMarker(marker: String): Pair<String, String> {
        val parsed = GENERATION_MARKER.matchEntire(marker)
            ?: error("Invalid sync deletion generation marker")
        return parsed.groupValues[1] to parsed.groupValues[2]
    }

    private fun backupKey(key: String): String = "${key}_previous_generation"

    private fun ByteArray.hex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    companion object {
        private const val FILE_PREFIX = "@file-v1:"
        private val GENERATION_NAME = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.json")
        private val GENERATION_MARKER = Regex("$FILE_PREFIX(${GENERATION_NAME.pattern}):([a-f0-9]{64})")
        private val STATE_KEYS = listOf(
            KEY_RECENT_PLAY_DELETIONS, KEY_PLAYLIST_USAGE_DELETIONS, KEY_PLAYLIST_USAGE_DELETION_BARRIERS,
            KEY_PLAYLIST_SONG_DELETIONS, KEY_DELETED_PLAYLIST_IDS,
            KEY_DELETED_PLAYLIST_TIMESTAMPS, KEY_LYRIC_OVERRIDES, KEY_LEGACY_LYRIC_RECOVERY
        )
    }
}

internal class SyncDeletionGenerationChangedException : IllegalStateException("Sync state generation changed during lookup")
