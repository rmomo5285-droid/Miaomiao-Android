package com.v2ray.ang.xboard

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class EndpointManifestTest {
    @Test
    fun verifiesPublishedManifestWithEmbeddedPublicKey() {
        val raw = javaClass.getResourceAsStream("/miaomiao-manifest.json")
            ?.bufferedReader()
            ?.use { it.readText() }
        assertNotNull(raw)

        val verified = EndpointManifestVerifier(
            nowProvider = { Instant.parse("2026-08-03T08:00:00Z") },
        ).verify(raw!!)

        assertEquals(3L, verified.payload.version)
        assertEquals(
            listOf("https://www.miaonetwork.com", "https://www.vpnmiao.com"),
            verified.payload.apiEndpoints,
        )
        assertEquals(
            "https://download.vpnmiao.com/download/index.html",
            verified.payload.downloadPageUrl,
        )
        assertEquals("https://cdn.vpnmiao.com/manifest.json", verified.payload.bootstrapMirrors.first())
    }

    @Test
    fun rejectsTamperedPayload() {
        val raw = javaClass.getResourceAsStream("/miaomiao-manifest.json")!!
            .bufferedReader().use { it.readText() }
        val gson = Gson()
        val envelope = gson.fromJson(raw, EndpointManifestEnvelope::class.java)
        val tampered = envelope.copy(
            payload = Base64.getEncoder().encodeToString("{}".toByteArray()),
        )

        assertThrows(EndpointManifestException::class.java) {
            EndpointManifestVerifier(
                nowProvider = { Instant.parse("2026-08-03T00:00:00Z") },
            ).verify(gson.toJson(tampered))
        }
    }

    @Test
    fun rejectsExpiredManifest() {
        val raw = javaClass.getResourceAsStream("/miaomiao-manifest.json")!!
            .bufferedReader().use { it.readText() }

        val error = assertThrows(EndpointManifestException::class.java) {
            EndpointManifestVerifier(
                nowProvider = { Instant.parse("2027-08-04T00:00:00Z") },
            ).verify(raw)
        }
        assertTrue(error.message.orEmpty().contains("expired"))
    }

    @Test
    fun verifiesStructuredMigrationNotice() {
        val fixture = SigningFixture()
        val notice = EndpointMigrationNotice(
            id = "domain-2",
            title = "入口已更新",
            message = "客户端已自动切换到新的服务入口。",
            autoApply = true,
            required = true,
        )
        val payload = fixture.payload(version = 2).copy(migrationNotice = notice)

        val verified = fixture.verifier.verify(fixture.envelope(payload))

        assertEquals(notice, verified.payload.migrationNotice)
    }

    @Test
    fun verifiesOptionalClientUpdatesAndBuildComparison() {
        val fixture = SigningFixture()
        val androidUpdate = EndpointClientUpdate(
            version = "2.3.2",
            build = 742,
            downloadUrl = "https://download.example.com/download/android",
            required = false,
            title = "发现新版本",
            message = "喵喵客户端有新版本可用。",
        )
        val desktopUpdate = EndpointClientUpdate(
            version = "7.24.4",
            build = 72404,
            downloadUrl = "https://download.example.com/download/desktop",
            required = true,
            title = "Desktop update",
            message = "A newer desktop client is available.",
        )
        val payload = fixture.payload(version = 2).copy(
            updates = EndpointManifestUpdates(
                android = androidUpdate,
                desktop = desktopUpdate,
            ),
        )

        val verified = fixture.verifier.verify(fixture.envelope(payload))

        assertEquals(androidUpdate, verified.payload.updates?.android)
        assertEquals(desktopUpdate, verified.payload.updates?.desktop)
        assertTrue(androidUpdate.isNewerThan(741))
        assertFalse(androidUpdate.isNewerThan(742))
    }

    @Test
    fun rejectsInvalidClientUpdateValues() {
        val fixture = SigningFixture()
        val validUpdate = EndpointClientUpdate(
            version = "2.3.2",
            build = 742,
            downloadUrl = "https://download.example.com/download/android",
            required = false,
            title = "发现新版本",
            message = "喵喵客户端有新版本可用。",
        )
        val invalidUpdates = listOf(
            validUpdate.copy(version = "2.3"),
            validUpdate.copy(build = 0),
            validUpdate.copy(downloadUrl = "http://download.example.com/client.apk"),
            validUpdate.copy(downloadUrl = "https://127.0.0.1/client.apk"),
            validUpdate.copy(title = ""),
            validUpdate.copy(message = ""),
        )

        invalidUpdates.forEach { update ->
            val payload = fixture.payload(version = 2).copy(
                updates = EndpointManifestUpdates(
                    android = update,
                    desktop = validUpdate,
                ),
            )
            assertThrows(EndpointManifestException::class.java) {
                fixture.verifier.verify(fixture.envelope(payload))
            }
        }
    }

    @Test
    fun rejectsUnexpectedIncompleteOrFractionalClientUpdateFields() {
        val fixture = SigningFixture()
        val gson = Gson()
        val validUpdate = EndpointClientUpdate(
            version = "2.3.2",
            build = 742,
            downloadUrl = "https://download.example.com/download/android",
            required = false,
            title = "发现新版本",
            message = "喵喵客户端有新版本可用。",
        )

        fun manifestJson() = gson.toJsonTree(
            fixture.payload(version = 2).copy(
                updates = EndpointManifestUpdates(
                    android = validUpdate,
                    desktop = validUpdate,
                ),
            ),
        ).asJsonObject

        val unexpectedField = manifestJson().apply {
            getAsJsonObject("updates").getAsJsonObject("android")
                .addProperty("command", "run-anything")
        }
        val unexpectedChannel = manifestJson().apply {
            getAsJsonObject("updates").add("ios", gson.toJsonTree(validUpdate))
        }
        val missingField = manifestJson().apply {
            getAsJsonObject("updates").getAsJsonObject("android").remove("message")
        }
        val missingChannel = manifestJson().apply {
            getAsJsonObject("updates").remove("desktop")
        }
        val fractionalBuild = manifestJson().apply {
            getAsJsonObject("updates").getAsJsonObject("android").addProperty("build", 742.5)
        }

        listOf(
            unexpectedField,
            unexpectedChannel,
            missingField,
            missingChannel,
            fractionalBuild,
        ).forEach { payload ->
            assertThrows(EndpointManifestException::class.java) {
                fixture.verifier.verify(fixture.envelope(payload.toString()))
            }
        }
    }

    @Test
    fun rejectsSignedLegacyStringNoticeAndRemoteCommandField() {
        val fixture = SigningFixture()
        val gson = Gson()

        val legacyNotice = gson.toJsonTree(fixture.payload(version = 2)).asJsonObject.apply {
            addProperty("migrationNotice", "legacy string notice")
        }
        assertThrows(EndpointManifestException::class.java) {
            fixture.verifier.verify(fixture.envelope(legacyNotice.toString()))
        }

        val remoteCommand = gson.toJsonTree(fixture.payload(version = 2)).asJsonObject.apply {
            addProperty("command", "run-anything")
        }
        assertThrows(EndpointManifestException::class.java) {
            fixture.verifier.verify(fixture.envelope(remoteCommand.toString()))
        }
    }

    @Test
    fun rejectsMissingDownloadPageUrl() {
        val fixture = SigningFixture()
        val payload = Gson().toJsonTree(fixture.payload(version = 2)).asJsonObject.apply {
            remove("downloadPageUrl")
        }

        assertThrows(EndpointManifestException::class.java) {
            fixture.verifier.verify(fixture.envelope(payload.toString()))
        }
    }

    @Test
    fun rejectsNonPublicHttpsDownloadPage() {
        val fixture = SigningFixture()
        listOf(
            "http://download.example.com/download/index.html",
            "https://127.0.0.1/download/index.html",
            "https://downloads.local/download/index.html",
        ).forEach { invalidUrl ->
            val payload = fixture.payload(version = 2).copy(downloadPageUrl = invalidUrl)
            assertThrows(EndpointManifestException::class.java) {
                fixture.verifier.verify(fixture.envelope(payload))
            }
        }
    }

    @Test
    fun builtInBootstrapStartsWithStableCdnAndUsesOfficialDownloadPage() {
        assertEquals("https://cdn.vpnmiao.com/manifest.json", EndpointBootstrapConfig.payload.bootstrapMirrors.first())
        assertEquals(
            "https://download.vpnmiao.com/download/index.html",
            EndpointBootstrapConfig.payload.downloadPageUrl,
        )
    }

    @Test
    fun retriesAllDirectMirrorsBeforeSocksAndCachesNewerVersion() {
        val fixture = SigningFixture()
        val candidate = fixture.envelope(version = 2)
        val cache = MemoryManifestCache()
        val calls = mutableListOf<Boolean>()
        val transport = object : EndpointManifestTransport {
            override fun fetch(url: String, throughSocksProxy: Boolean): String {
                calls += throughSocksProxy
                if (!throughSocksProxy) throw IOException("direct unavailable")
                return candidate
            }
        }
        val repository = EndpointManifestRepository(
            cache = cache,
            transport = transport,
            verifier = fixture.verifier,
            builtIn = fixture.payload(version = 1),
        )

        val result = repository.refreshBlocking()

        assertTrue(result is EndpointManifestRefreshResult.Updated)
        assertEquals(listOf(false, true), calls)
        assertEquals(candidate, cache.value)
        assertEquals(2L, repository.current().version)
    }

    @Test
    fun selectsNewerManifestFromLaterMirrorInsteadOfReturningFirstUnchangedVersion() {
        val fixture = SigningFixture()
        val cdnUrl = "https://cdn.example.com/manifest.json"
        val fallbackUrl = "https://fallback.example.com/manifest.json"
        val mirrors = listOf(cdnUrl, fallbackUrl)
        val cache = MemoryManifestCache(
            fixture.envelope(
                fixture.payload(version = 3).copy(bootstrapMirrors = mirrors),
            ),
        )
        val calls = mutableListOf<Pair<String, Boolean>>()
        val repository = EndpointManifestRepository(
            cache = cache,
            transport = object : EndpointManifestTransport {
                override fun fetch(url: String, throughSocksProxy: Boolean): String {
                    calls += url to throughSocksProxy
                    return if (url == cdnUrl) {
                        fixture.envelope(version = 3)
                    } else {
                        fixture.envelope(version = 4)
                    }
                }
            },
            verifier = fixture.verifier,
            builtIn = fixture.payload(version = 3).copy(
                bootstrapMirrors = mirrors,
            ),
        )

        val result = repository.refreshBlocking()

        assertTrue(result is EndpointManifestRefreshResult.Updated)
        result as EndpointManifestRefreshResult.Updated
        assertEquals(4L, result.active.version)
        assertEquals(fallbackUrl, result.sourceUrl)
        assertFalse(result.throughSocksProxy)
        assertEquals(4L, repository.current().version)
        assertEquals(
            listOf(
                cdnUrl to false,
                fallbackUrl to false,
                cdnUrl to true,
                fallbackUrl to true,
            ),
            calls,
        )
    }

    @Test
    fun lowerManifestFromLaterMirrorDoesNotReplaceSelectedHigherVersion() {
        val fixture = SigningFixture()
        val highUrl = "https://high.example.com/manifest.json"
        val lowUrl = "https://low.example.com/manifest.json"
        val cache = MemoryManifestCache()
        val repository = EndpointManifestRepository(
            cache = cache,
            transport = object : EndpointManifestTransport {
                override fun fetch(url: String, throughSocksProxy: Boolean): String {
                    return if (url == highUrl) {
                        fixture.envelope(version = 5)
                    } else {
                        fixture.envelope(version = 4)
                    }
                }
            },
            verifier = fixture.verifier,
            builtIn = fixture.payload(version = 3).copy(
                bootstrapMirrors = listOf(highUrl, lowUrl),
            ),
        )

        val result = repository.refreshBlocking()

        assertTrue(result is EndpointManifestRefreshResult.Updated)
        result as EndpointManifestRefreshResult.Updated
        assertEquals(5L, result.active.version)
        assertEquals(highUrl, result.sourceUrl)
        assertFalse(result.throughSocksProxy)
        assertEquals(5L, repository.current().version)
        assertEquals(5L, fixture.verifier.verify(cache.value!!).payload.version)
    }

    @Test
    fun rejectsRollbackBelowLastKnownVersion() {
        val fixture = SigningFixture()
        val cache = MemoryManifestCache(fixture.envelope(version = 2))
        val transport = object : EndpointManifestTransport {
            override fun fetch(url: String, throughSocksProxy: Boolean) = fixture.envelope(version = 1)
        }
        val repository = EndpointManifestRepository(
            cache = cache,
            transport = transport,
            verifier = fixture.verifier,
            builtIn = fixture.payload(version = 1),
        )

        val result = repository.refreshBlocking()

        assertTrue(result is EndpointManifestRefreshResult.Failed)
        assertEquals(2L, repository.current().version)
    }

    @Test
    fun retainsExpiredSignedCacheAsLastKnownGoodWhenRefreshFails() {
        val fixture = SigningFixture()
        val expired = fixture.payload(version = 2).copy(
            issuedAt = "2026-06-01T00:00:00Z",
            expiresAt = "2026-07-01T00:00:00Z",
            apiEndpoints = listOf("https://replacement.example.com"),
            bootstrapMirrors = listOf("https://replacement-config.example.com/manifest.json"),
        )
        val cache = MemoryManifestCache(fixture.envelope(expired))
        val attemptedMirrors = mutableListOf<String>()
        val transport = object : EndpointManifestTransport {
            override fun fetch(url: String, throughSocksProxy: Boolean): String {
                attemptedMirrors += url
                throw IOException("offline")
            }
        }
        val repository = EndpointManifestRepository(
            cache = cache,
            transport = transport,
            verifier = fixture.verifier,
            builtIn = fixture.payload(version = 1),
        )

        val result = repository.refreshBlocking()

        assertTrue(result is EndpointManifestRefreshResult.Failed)
        assertEquals(2L, result.active.version)
        assertEquals(listOf("https://replacement.example.com"), result.active.apiEndpoints)
        assertEquals(2L, repository.current().version)
        assertEquals("https://replacement-config.example.com/manifest.json", attemptedMirrors.first())
    }

    @Test
    fun lowerConcurrentRefreshCannotOverwriteNewerCachedVersion() {
        val fixture = SigningFixture()
        val cache = MemoryManifestCache(fixture.envelope(version = 1))
        val lowFetchStarted = CountDownLatch(1)
        val releaseLowFetch = CountDownLatch(1)
        val sharedLockFile = java.io.File.createTempFile("miaomiao-manifest", ".lock").apply {
            deleteOnExit()
        }
        val sharedLock = FileEndpointManifestUpdateLock { sharedLockFile }
        val lowRepository = EndpointManifestRepository(
            cache = cache,
            transport = object : EndpointManifestTransport {
                override fun fetch(url: String, throughSocksProxy: Boolean): String {
                    lowFetchStarted.countDown()
                    assertTrue(releaseLowFetch.await(5, TimeUnit.SECONDS))
                    return fixture.envelope(version = 2)
                }
            },
            verifier = fixture.verifier,
            builtIn = fixture.payload(version = 1),
            updateLock = sharedLock,
        )
        val highRepository = EndpointManifestRepository(
            cache = cache,
            transport = object : EndpointManifestTransport {
                override fun fetch(url: String, throughSocksProxy: Boolean) =
                    fixture.envelope(version = 3)
            },
            verifier = fixture.verifier,
            builtIn = fixture.payload(version = 1),
            updateLock = sharedLock,
        )
        val lowResult = AtomicReference<EndpointManifestRefreshResult>()
        val lowThread = thread(name = "low-manifest-refresh") {
            lowResult.set(lowRepository.refreshBlocking())
        }

        assertTrue(lowFetchStarted.await(5, TimeUnit.SECONDS))
        val highResult = try {
            highRepository.refreshBlocking()
        } finally {
            releaseLowFetch.countDown()
        }
        assertTrue(highResult is EndpointManifestRefreshResult.Updated)
        lowThread.join(5_000)

        assertTrue(!lowThread.isAlive)
        assertNotNull(lowResult.get())
        assertTrue(lowResult.get() is EndpointManifestRefreshResult.Failed)
        assertEquals(3L, highRepository.current().version)
        assertEquals(3L, lowResult.get().active.version)
    }

    @Test
    fun fileUpdateLockIsReleasedWhenCriticalSectionThrows() {
        val lockFile = java.io.File.createTempFile("miaomiao-manifest", ".lock").apply {
            deleteOnExit()
        }
        val lock = FileEndpointManifestUpdateLock { lockFile }

        assertThrows(IllegalStateException::class.java) {
            lock.withLock<Unit> { throw IllegalStateException("expected") }
        }

        var enteredAgain = false
        lock.withLock { enteredAgain = true }
        assertTrue(enteredAgain)
    }
}

private class MemoryManifestCache(@Volatile var value: String? = null) : EndpointManifestCache {
    override fun read() = value
    override fun write(rawEnvelope: String): Boolean {
        value = rawEnvelope
        return true
    }
    override fun clear() {
        value = null
    }
}

private class SigningFixture {
    private val gson = Gson()
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }
    private val publicKeyPem = """-----BEGIN PUBLIC KEY-----
${Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.public.encoded)}
-----END PUBLIC KEY-----"""
    val verifier = EndpointManifestVerifier(
        publicKeyPem = publicKeyPem,
        nowProvider = { Instant.parse("2026-08-02T00:00:00Z") },
    )

    fun payload(version: Long) = EndpointManifestPayload(
        schema = 1,
        version = version,
        issuedAt = "2026-08-01T00:00:00Z",
        expiresAt = "2027-08-01T00:00:00Z",
        apiEndpoints = listOf("https://api.example.com"),
        registrationUrl = "https://api.example.com/register",
        downloadPageUrl = "https://download.example.com/download/index.html",
        bootstrapMirrors = listOf("https://config.example.com/manifest.json"),
    )

    fun envelope(version: Long): String = envelope(payload(version))

    fun envelope(payload: EndpointManifestPayload): String = envelope(gson.toJson(payload))

    fun envelope(payloadJson: String): String {
        val payloadBytes = payloadJson.toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(payloadBytes)
            sign()
        }
        return gson.toJson(
            EndpointManifestEnvelope(
                algorithm = EndpointManifestVerifier.ALGORITHM,
                payload = Base64.getEncoder().encodeToString(payloadBytes),
                signature = Base64.getEncoder().encodeToString(signature),
            ),
        )
    }
}
