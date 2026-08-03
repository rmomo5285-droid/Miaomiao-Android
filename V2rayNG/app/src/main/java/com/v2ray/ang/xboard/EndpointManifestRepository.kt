package com.v2ray.ang.xboard

import com.tencent.mmkv.MMKV
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

interface EndpointManifestCache {
    fun read(): String?
    fun write(rawEnvelope: String): Boolean
    fun clear()
}

class MmkvEndpointManifestCache(
    private val storage: MMKV = MMKV.mmkvWithID(STORAGE_ID, MMKV.MULTI_PROCESS_MODE),
) : EndpointManifestCache {
    override fun read(): String? = storage.decodeString(KEY_ENVELOPE)

    override fun write(rawEnvelope: String): Boolean = storage.encode(KEY_ENVELOPE, rawEnvelope)

    override fun clear() {
        storage.remove(KEY_ENVELOPE)
    }

    private companion object {
        const val STORAGE_ID = "MIAOMIAO_ENDPOINTS"
        const val KEY_ENVELOPE = "LAST_KNOWN_GOOD_ENVELOPE"
    }
}

interface EndpointManifestTransport {
    @Throws(IOException::class)
    fun fetch(url: String, throughSocksProxy: Boolean): String
}

interface EndpointManifestUpdateLock {
    fun <T> withLock(block: () -> T): T
}

/**
 * Serializes the cache compare-and-write section both inside this process and across app processes.
 */
internal class FileEndpointManifestUpdateLock(
    private val lockFileProvider: () -> File,
) : EndpointManifestUpdateLock {
    private val processLock = ReentrantLock()

    override fun <T> withLock(block: () -> T): T {
        return processLock.withLock {
            val lockFile = lockFileProvider()
            val parent = lockFile.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
                throw IOException("Manifest lock directory could not be created")
            }

            RandomAccessFile(lockFile, "rw").use { file ->
                file.channel.use { channel ->
                    val fileLock = channel.lock()
                    try {
                        block()
                    } finally {
                        fileLock.release()
                    }
                }
            }
        }
    }
}

private object InProcessEndpointManifestUpdateLock : EndpointManifestUpdateLock {
    private val lock = ReentrantLock()

    override fun <T> withLock(block: () -> T): T = lock.withLock(block)
}

private object CrossProcessEndpointManifestUpdateLock : EndpointManifestUpdateLock {
    private val delegate = FileEndpointManifestUpdateLock {
        File(AngApplication.application.noBackupFilesDir, LOCK_FILE_NAME)
    }

    override fun <T> withLock(block: () -> T): T = delegate.withLock(block)

    private const val LOCK_FILE_NAME = "miaomiao_endpoint_manifest.lock"
}

class OkHttpEndpointManifestTransport(
    private val directClient: OkHttpClient = secureClient(),
    private val socksPortProvider: () -> Int = SettingsManager::getSocksPort,
) : EndpointManifestTransport {
    override fun fetch(url: String, throughSocksProxy: Boolean): String {
        requireHttpsUrl(url)
        val client = if (throughSocksProxy) {
            directClient.newBuilder()
                .proxy(
                    Proxy(
                        Proxy.Type.SOCKS,
                        InetSocketAddress.createUnresolved(AppConfig.LOOPBACK, socksPortProvider()),
                    ),
                )
                .build()
        } else {
            directClient
        }

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Manifest request failed with HTTP ${response.code}")
            }
            val body = response.body
            if (body.contentLength() > MAX_MANIFEST_BYTES) {
                throw IOException("Manifest response is too large")
            }
            val output = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_MANIFEST_BYTES) {
                        throw IOException("Manifest response is too large")
                    }
                    output.write(buffer, 0, read)
                }
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }

    private fun requireHttpsUrl(rawUrl: String) {
        val uri = runCatching { URI(rawUrl) }.getOrNull()
        require(uri?.scheme.equals("https", ignoreCase = true) && !uri?.host.isNullOrBlank()) {
            "Manifest URL must use HTTPS"
        }
    }

    companion object {
        private const val MAX_MANIFEST_BYTES = 1024 * 1024
        private const val USER_AGENT = "miaomiao-android/manifest-v1"

        private fun secureClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}

sealed interface EndpointManifestRefreshResult {
    val active: EndpointManifestPayload

    data class Updated(
        override val active: EndpointManifestPayload,
        val sourceUrl: String,
        val throughSocksProxy: Boolean,
    ) : EndpointManifestRefreshResult

    data class Unchanged(
        override val active: EndpointManifestPayload,
        val sourceUrl: String,
        val throughSocksProxy: Boolean,
    ) : EndpointManifestRefreshResult

    data class Failed(
        override val active: EndpointManifestPayload,
        val failures: List<String>,
    ) : EndpointManifestRefreshResult
}

class EndpointManifestRepository(
    private val cache: EndpointManifestCache = MmkvEndpointManifestCache(),
    private val transport: EndpointManifestTransport = OkHttpEndpointManifestTransport(),
    private val verifier: EndpointManifestVerifier = EndpointManifestVerifier(),
    private val builtIn: EndpointManifestPayload = EndpointBootstrapConfig.payload,
    private val updateLock: EndpointManifestUpdateLock =
        if (cache is MmkvEndpointManifestCache) {
            CrossProcessEndpointManifestUpdateLock
        } else {
            InProcessEndpointManifestUpdateLock
        },
) {
    /**
     * Returns only local state. This method never performs network I/O and is safe to use at startup.
     */
    fun current(): EndpointManifestPayload {
        return readCached(requireFresh = false)?.payload ?: builtIn
    }

    /**
     * Refreshes endpoints only when explicitly called by a feature or background task.
     */
    suspend fun refresh(): EndpointManifestRefreshResult = withContext(Dispatchers.IO) {
        refreshBlocking()
    }

    internal fun refreshBlocking(): EndpointManifestRefreshResult {
        val cachedForVersion = readCached(requireFresh = false)
        val cachedFresh = readCached(requireFresh = true)
        val active = cachedFresh?.payload ?: cachedForVersion?.payload ?: builtIn
        val versionFloor = maxOf(builtIn.version, cachedForVersion?.payload?.version ?: Long.MIN_VALUE)
        val mirrors = buildList {
            cachedForVersion?.payload?.bootstrapMirrors?.let(::addAll)
            addAll(builtIn.bootstrapMirrors)
        }.distinct()
        val failures = mutableListOf<String>()
        var bestCandidate: ManifestCandidate? = null

        for (throughProxy in listOf(false, true)) {
            for (mirror in mirrors) {
                val rawEnvelope = try {
                    transport.fetch(mirror, throughProxy)
                } catch (error: Exception) {
                    failures += failureDescription(mirror, throughProxy, error)
                    continue
                }
                val candidate = try {
                    verifier.verify(rawEnvelope, requireFresh = true)
                } catch (error: EndpointManifestException) {
                    failures += failureDescription(mirror, throughProxy, error)
                    continue
                }
                if (candidate.payload.version < versionFloor) {
                    failures += failureDescription(
                        mirror,
                        throughProxy,
                        EndpointManifestException(
                            "Manifest rollback rejected: ${candidate.payload.version} < $versionFloor",
                        ),
                    )
                    continue
                }

                val currentBest = bestCandidate
                if (currentBest == null || candidate.payload.version > currentBest.payload.version) {
                    bestCandidate = ManifestCandidate(
                        rawEnvelope = rawEnvelope,
                        payload = candidate.payload,
                        sourceUrl = mirror,
                        throughSocksProxy = throughProxy,
                    )
                }
            }
        }

        val selected = bestCandidate
        if (selected != null) {
            val committed = try {
                updateLock.withLock {
                    // Another process may have committed a newer manifest while candidates were
                    // downloading. Re-read under the file lock before comparing and writing.
                    val latestCached = readCached(requireFresh = false)
                    val latestVersionFloor = maxOf(
                        builtIn.version,
                        latestCached?.payload?.version ?: Long.MIN_VALUE,
                    )
                    if (selected.payload.version < latestVersionFloor) {
                        throw EndpointManifestException(
                            "Manifest rollback rejected: ${selected.payload.version} < " +
                                latestVersionFloor,
                        )
                    }

                    if (selected.payload.version == latestCached?.payload?.version) {
                        EndpointManifestRefreshResult.Unchanged(
                            active = latestCached.payload,
                            sourceUrl = selected.sourceUrl,
                            throughSocksProxy = selected.throughSocksProxy,
                        )
                    } else {
                        if (!cache.write(selected.rawEnvelope)) {
                            throw IOException("Verified manifest could not be cached")
                        }
                        EndpointManifestRefreshResult.Updated(
                            active = selected.payload,
                            sourceUrl = selected.sourceUrl,
                            throughSocksProxy = selected.throughSocksProxy,
                        )
                    }
                }
            } catch (error: Exception) {
                failures += failureDescription(
                    selected.sourceUrl,
                    selected.throughSocksProxy,
                    error,
                )
                null
            }
            if (committed != null) {
                return committed
            }
        }

        val latestActive = readCached(requireFresh = false)?.payload ?: active
        return EndpointManifestRefreshResult.Failed(active = latestActive, failures = failures)
    }

    private fun readCached(requireFresh: Boolean): VerifiedEndpointManifest? {
        val rawEnvelope = cache.read() ?: return null
        return try {
            verifier.verify(rawEnvelope, requireFresh)
        } catch (_: EndpointManifestException) {
            null
        }
    }

    private fun failureDescription(url: String, throughProxy: Boolean, error: Exception): String {
        val route = if (throughProxy) "SOCKS" else "direct"
        return "$route $url: ${error.message ?: error.javaClass.simpleName}"
    }
}

private data class ManifestCandidate(
    val rawEnvelope: String,
    val payload: EndpointManifestPayload,
    val sourceUrl: String,
    val throughSocksProxy: Boolean,
)
