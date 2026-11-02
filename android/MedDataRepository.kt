package com.example.medconsults.data // TODO: change to your app's package

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** Mirrors data/manifest.json written by scripts/extract.mjs. */
@Serializable
data class MedManifest(
    val schema: Int = 1,
    val dataVersion: String,
    val generatedAt: String? = null,
    val source: Source? = null,
    val files: List<Entry> = emptyList(),
) {
    @Serializable
    data class Source(
        val url: String? = null,
        val contentCurrentAsOf: String? = null, // e.g. "August 2026" - show this in your app
        val contentVersion: String? = null,     // e.g. "v69"
    )

    @Serializable
    data class Entry(
        val name: String,
        val file: String,
        val origin: String? = null,
        val kind: String? = null,
        val count: Int = 0,
        val bytes: Long = 0,
        val sha256: String,
        val aliases: List<String> = emptyList(),
    )
}

sealed interface SyncResult {
    /** Nothing new on the server. */
    object UpToDate : SyncResult

    /** New data was downloaded, verified and swapped in. */
    data class Updated(val dataVersion: String, val changedFiles: List<String>) : SyncResult

    /** Offline, server error, bad checksum ... the data already on the device is untouched. */
    data class Failed(val error: Throwable) : SyncResult
}

/**
 * Offline-first access to the Medication Matrix data.
 *
 *  - read():  newest downloaded copy, else the copy bundled in assets/med-data/
 *  - sync():  downloads only what changed, verifies SHA-256, then swaps it in atomically-ish
 *             (manifest is written last, so an interrupted sync just repeats itself next time)
 */
class MedDataRepository(
    context: Context,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val client: OkHttpClient = defaultClient(),
) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val dataDir = File(appContext.filesDir, "med-data")
    private val stagingDir = File(appContext.filesDir, "med-data-staging")
    private val syncLock = Mutex()

    /** Manifest of the data currently in use (downloaded copy, else the bundled one). */
    fun manifest(): MedManifest? = runCatching {
        val text = File(dataDir, MANIFEST).takeIf { it.exists() }?.readText()
            ?: appContext.assets.open("$ASSET_DIR/$MANIFEST").bufferedReader().use { it.readText() }
        json.decodeFromString(MedManifest.serializer(), text)
    }.getOrNull()

    /** Reads one data file into your own @Serializable classes. */
    suspend fun <T> read(file: String, deserializer: DeserializationStrategy<T>): T =
        withContext(Dispatchers.IO) {
            open(file).bufferedReader().use { json.decodeFromString(deserializer, it.readText()) }
        }

    /** Reads one data file as a generic JSON tree - handy before you have written data classes. */
    suspend fun readTree(file: String): JsonElement = read(file, JsonElement.serializer())

    /** Checks the server and downloads whatever changed. Never throws (except cancellation). */
    suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        syncLock.withLock {
            try {
                doSync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SyncResult.Failed(e)
            } finally {
                stagingDir.deleteRecursively()
            }
        }
    }

    private fun doSync(): SyncResult {
        val remoteText = fetchText("$baseUrl/$MANIFEST")
        val remote = json.decodeFromString(MedManifest.serializer(), remoteText)
        val local = manifest()
        if (local?.dataVersion == remote.dataVersion) return SyncResult.UpToDate

        remote.files.forEach {
            require(SAFE_FILE_NAME.matches(it.file)) { "Unexpected file name in manifest: ${it.file}" }
        }
        val localHashes = local?.files?.associate { it.file to it.sha256 }.orEmpty()
        val changed = remote.files.filter { localHashes[it.file] != it.sha256 }

        // 1) download and verify everything into a staging folder first
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()
        for (entry in changed) {
            val target = File(stagingDir, entry.file)
            download("$baseUrl/${entry.file}", target)
            if (!sha256(target).equals(entry.sha256, ignoreCase = true)) {
                throw IOException("Checksum mismatch for ${entry.file}")
            }
        }

        // 2) only now touch the live data
        dataDir.mkdirs()
        for (entry in changed) {
            val from = File(stagingDir, entry.file)
            val to = File(dataDir, entry.file)
            if (!from.renameTo(to)) from.copyTo(to, overwrite = true)
        }
        val keep = remote.files.map { it.file }.toSet()
        local?.files?.filter { it.file !in keep }?.forEach { File(dataDir, it.file).delete() }

        // 3) manifest last
        File(dataDir, MANIFEST).writeText(remoteText)
        return SyncResult.Updated(remote.dataVersion, changed.map { it.file })
    }

    private fun open(file: String): InputStream {
        val downloaded = File(dataDir, file)
        return if (downloaded.exists()) downloaded.inputStream() else appContext.assets.open("$ASSET_DIR/$file")
    }

    private fun request(url: String) =
        Request.Builder().url(url).header("Cache-Control", "no-cache").build()

    private fun fetchText(url: String): String =
        client.newCall(request(url)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            response.body?.string() ?: throw IOException("Empty response for $url")
        }

    private fun download(url: String, target: File) {
        client.newCall(request(url)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            val body = response.body ?: throw IOException("Empty response for $url")
            target.parentFile?.mkdirs()
            body.byteStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /**
         * Folder that contains manifest.json. Replace YOUR_USER / YOUR_REPO, or point this at
         * your own website once you host the data/ folder there.
         */
        const val DEFAULT_BASE_URL = "https://raw.githubusercontent.com/YOUR_USER/YOUR_REPO/main/data"

        private const val ASSET_DIR = "med-data"
        private const val MANIFEST = "manifest.json"
        private val SAFE_FILE_NAME = Regex("[A-Za-z0-9._-]+")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

/** `val drugs: List<Drug> = repo.load("drugs.json")` once you have @Serializable data classes. */
suspend inline fun <reified T> MedDataRepository.load(file: String): T = read(file, serializer<T>())
