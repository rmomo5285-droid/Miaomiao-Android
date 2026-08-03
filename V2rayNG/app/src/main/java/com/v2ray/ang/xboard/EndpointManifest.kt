package com.v2ray.ang.xboard

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.math.BigDecimal
import java.net.URI
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Locale

data class EndpointManifestEnvelope(
    val algorithm: String,
    val payload: String,
    val signature: String,
)

data class EndpointMigrationNotice(
    val id: String,
    val title: String,
    val message: String,
    val autoApply: Boolean = false,
    val required: Boolean = false,
)

data class EndpointClientUpdate(
    val version: String,
    val build: Long,
    val downloadUrl: String,
    val required: Boolean,
    val title: String,
    val message: String,
) {
    fun isNewerThan(localBuild: Long): Boolean = build > localBuild
}

data class EndpointManifestUpdates(
    val android: EndpointClientUpdate,
    val desktop: EndpointClientUpdate,
)

data class EndpointManifestPayload(
    val schema: Int,
    val version: Long,
    val issuedAt: String,
    val expiresAt: String,
    val apiEndpoints: List<String>,
    val registrationUrl: String,
    val downloadPageUrl: String,
    val bootstrapMirrors: List<String>,
    val migrationNotice: EndpointMigrationNotice? = null,
    val updates: EndpointManifestUpdates? = null,
)

data class VerifiedEndpointManifest(
    val envelope: EndpointManifestEnvelope,
    val payload: EndpointManifestPayload,
    val payloadBytes: ByteArray,
    val issuedAt: Instant,
    val expiresAt: Instant,
)

class EndpointManifestException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

class EndpointManifestVerifier(
    publicKeyPem: String = EndpointBootstrapConfig.PUBLIC_KEY_PEM,
    private val nowProvider: () -> Instant = Instant::now,
) {
    private val gson = Gson()
    private val publicKey = parsePublicKey(publicKeyPem)

    @Throws(EndpointManifestException::class)
    fun verify(rawEnvelope: String, requireFresh: Boolean = true): VerifiedEndpointManifest {
        if (rawEnvelope.length > MAX_ENVELOPE_CHARS) {
            throw EndpointManifestException("Manifest envelope is too large")
        }
        val envelopeJson = parseJsonObject(rawEnvelope, "envelope")
        validateExactFields(
            envelopeJson,
            allowed = ENVELOPE_FIELDS,
            required = ENVELOPE_FIELDS,
            label = "envelope",
        )
        val envelope = try {
            gson.fromJson(envelopeJson, EndpointManifestEnvelope::class.java)
        } catch (error: Exception) {
            throw EndpointManifestException("Manifest envelope is not valid JSON", error)
        } ?: throw EndpointManifestException("Manifest envelope is empty")

        if (envelope.algorithm != ALGORITHM) {
            throw EndpointManifestException("Unsupported manifest algorithm: ${envelope.algorithm}")
        }
        if (envelope.payload.isBlank() || envelope.signature.isBlank()) {
            throw EndpointManifestException("Manifest payload or signature is missing")
        }
        if (envelope.payload.length > MAX_ENCODED_PAYLOAD_CHARS ||
            envelope.signature.length > MAX_ENCODED_SIGNATURE_CHARS
        ) {
            throw EndpointManifestException("Manifest payload or signature is too large")
        }

        val payloadBytes = decodeBase64(envelope.payload, "payload")
        val signatureBytes = decodeBase64(envelope.signature, "signature")
        if (payloadBytes.size > MAX_PAYLOAD_BYTES || signatureBytes.size > MAX_SIGNATURE_BYTES) {
            throw EndpointManifestException("Manifest payload or signature is too large")
        }
        val signatureValid = try {
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initVerify(publicKey)
                update(payloadBytes)
                verify(signatureBytes)
            }
        } catch (error: Exception) {
            throw EndpointManifestException("Manifest signature could not be verified", error)
        }
        if (!signatureValid) {
            throw EndpointManifestException("Manifest signature is invalid")
        }

        val payloadJson = parseJsonObject(payloadBytes.toString(Charsets.UTF_8), "signed payload")
        validatePayloadJson(payloadJson)
        val payload = try {
            gson.fromJson(payloadJson, EndpointManifestPayload::class.java)
        } catch (error: Exception) {
            throw EndpointManifestException("Signed manifest payload is not valid JSON", error)
        } ?: throw EndpointManifestException("Signed manifest payload is empty")

        val issuedAt = parseInstant(payload.issuedAt, "issuedAt")
        val expiresAt = parseInstant(payload.expiresAt, "expiresAt")
        validatePayload(payload, issuedAt, expiresAt, requireFresh)

        return VerifiedEndpointManifest(
            envelope = envelope,
            payload = payload,
            payloadBytes = payloadBytes,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
        )
    }

    private fun validatePayload(
        payload: EndpointManifestPayload,
        issuedAt: Instant,
        expiresAt: Instant,
        requireFresh: Boolean,
    ) {
        if (payload.schema != SUPPORTED_SCHEMA) {
            throw EndpointManifestException("Unsupported manifest schema: ${payload.schema}")
        }
        if (payload.version <= 0L) {
            throw EndpointManifestException("Manifest version must be positive")
        }
        if (!expiresAt.isAfter(issuedAt)) {
            throw EndpointManifestException("Manifest expiresAt must be after issuedAt")
        }
        if (requireFresh) {
            val now = nowProvider()
            if (issuedAt.isAfter(now.plus(MAX_CLOCK_SKEW))) {
                throw EndpointManifestException("Manifest issuedAt is in the future")
            }
            if (!expiresAt.isAfter(now)) {
                throw EndpointManifestException("Manifest has expired")
            }
        }
        if (payload.apiEndpoints.isEmpty() || payload.apiEndpoints.size > MAX_ENDPOINT_COUNT) {
            throw EndpointManifestException("Manifest contains no API endpoints")
        }
        if (payload.bootstrapMirrors.isEmpty() || payload.bootstrapMirrors.size > MAX_MIRROR_COUNT) {
            throw EndpointManifestException("Manifest contains no bootstrap mirrors")
        }

        validateDistinctHttpsUrls(payload.apiEndpoints, "API endpoint", rootOnly = true)
        validateDistinctHttpsUrls(payload.bootstrapMirrors, "bootstrap mirror", allowFragment = false)
        validateHttpsUrl(payload.registrationUrl, "registration URL")
        validateHttpsUrl(payload.downloadPageUrl, "download page URL")
        payload.updates?.let { updates ->
            validateClientUpdate(updates.android, "android update")
            validateClientUpdate(updates.desktop, "desktop update")
        }
        payload.migrationNotice?.let { notice ->
            if (!notice.autoApply ||
                notice.id.isBlank() || notice.id.length > MAX_NOTICE_ID_CHARS ||
                notice.title.isBlank() || notice.title.length > MAX_NOTICE_TITLE_CHARS ||
                notice.message.isBlank() || notice.message.length > MAX_NOTICE_MESSAGE_CHARS
            ) {
                throw EndpointManifestException("Manifest contains an invalid migration notice")
            }
        }
    }

    private fun validateDistinctHttpsUrls(
        urls: List<String>,
        label: String,
        allowFragment: Boolean = true,
        rootOnly: Boolean = false,
    ) {
        if (urls.any { it.isBlank() }) {
            throw EndpointManifestException("Manifest contains a blank $label")
        }
        if (urls.map { it.lowercase(Locale.ROOT) }.distinct().size != urls.size) {
            throw EndpointManifestException("Manifest contains duplicate ${label}s")
        }
        urls.forEach { validateHttpsUrl(it, label, allowFragment, rootOnly) }
    }

    private fun validateHttpsUrl(
        rawUrl: String,
        label: String,
        allowFragment: Boolean = true,
        rootOnly: Boolean = false,
    ) {
        if (rawUrl.length > MAX_URL_CHARS) {
            throw EndpointManifestException("$label is too long")
        }
        val uri = try {
            URI(rawUrl)
        } catch (error: Exception) {
            throw EndpointManifestException("Invalid $label", error)
        }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
            throw EndpointManifestException("$label must use HTTPS and include a host")
        }
        if (uri.userInfo != null) {
            throw EndpointManifestException("$label must not contain user information")
        }
        val host = uri.host
        if (!host.contains('.') || host.endsWith(".local", ignoreCase = true) ||
            IPV4_LITERAL.matches(host) || host.contains(':')
        ) {
            throw EndpointManifestException("$label must use a public DNS host")
        }
        if (!allowFragment && uri.rawFragment != null) {
            throw EndpointManifestException("$label must not contain a fragment")
        }
        if (rootOnly && (uri.rawQuery != null || uri.rawFragment != null || uri.path !in listOf("", "/"))) {
            throw EndpointManifestException("$label must be an HTTPS origin without a path, query, or fragment")
        }
    }

    private fun parseJsonObject(rawJson: String, label: String): JsonObject {
        return try {
            val element = JsonParser.parseString(rawJson)
            if (!element.isJsonObject) {
                throw EndpointManifestException("Manifest $label must be a JSON object")
            }
            element.asJsonObject
        } catch (error: EndpointManifestException) {
            throw error
        } catch (error: Exception) {
            throw EndpointManifestException("Manifest $label is not valid JSON", error)
        }
    }

    private fun validatePayloadJson(payload: JsonObject) {
        validateExactFields(
            payload,
            allowed = PAYLOAD_FIELDS,
            required = REQUIRED_PAYLOAD_FIELDS,
            label = "signed payload",
        )
        requireNumber(payload, "schema")
        requireNumber(payload, "version")
        requireString(payload, "issuedAt")
        requireString(payload, "expiresAt")
        requireStringArray(payload, "apiEndpoints")
        requireString(payload, "registrationUrl")
        requireString(payload, "downloadPageUrl")
        requireStringArray(payload, "bootstrapMirrors")

        val updatesElement = payload.get("updates")
        if (updatesElement != null) {
            if (!updatesElement.isJsonObject) {
                throw EndpointManifestException("Manifest updates must be an object")
            }
            val updates = updatesElement.asJsonObject
            validateExactFields(
                updates,
                allowed = UPDATE_CHANNEL_FIELDS,
                required = UPDATE_CHANNEL_FIELDS,
                label = "updates",
            )
            updates.keySet().forEach { channel ->
                val channelElement = updates.get(channel)
                if (!channelElement.isJsonObject) {
                    throw EndpointManifestException("Manifest $channel update must be an object")
                }
                val update = channelElement.asJsonObject
                validateExactFields(
                    update,
                    allowed = CLIENT_UPDATE_FIELDS,
                    required = CLIENT_UPDATE_FIELDS,
                    label = "$channel update",
                )
                requireString(update, "version")
                requirePositiveInteger(update, "build")
                requireString(update, "downloadUrl")
                requireBoolean(update, "required")
                requireString(update, "title")
                requireString(update, "message")
            }
        }

        val noticeElement = payload.get("migrationNotice") ?: return
        if (noticeElement.isJsonNull) return
        if (!noticeElement.isJsonObject) {
            throw EndpointManifestException("Manifest migrationNotice must be an object or null")
        }
        val notice = noticeElement.asJsonObject
        validateExactFields(
            notice,
            allowed = NOTICE_FIELDS,
            required = NOTICE_FIELDS,
            label = "migration notice",
        )
        requireString(notice, "id")
        requireString(notice, "title")
        requireString(notice, "message")
        requireBoolean(notice, "autoApply")
        requireBoolean(notice, "required")
    }

    private fun validateClientUpdate(update: EndpointClientUpdate, label: String) {
        if (!SEMVER.matches(update.version) || update.version.length > MAX_UPDATE_VERSION_CHARS) {
            throw EndpointManifestException("Manifest $label version is invalid")
        }
        if (update.build <= 0L || update.build > MAX_UPDATE_BUILD) {
            throw EndpointManifestException("Manifest $label build must be positive")
        }
        validateHttpsUrl(update.downloadUrl, "$label download URL")
        if (update.title.isBlank() || update.title.length > MAX_NOTICE_TITLE_CHARS) {
            throw EndpointManifestException("Manifest $label title is invalid")
        }
        if (update.message.isBlank() || update.message.length > MAX_NOTICE_MESSAGE_CHARS) {
            throw EndpointManifestException("Manifest $label message is invalid")
        }
    }

    private fun validateExactFields(
        value: JsonObject,
        allowed: Set<String>,
        required: Set<String>,
        label: String,
    ) {
        val fields = value.keySet()
        val unexpected = fields - allowed
        val missing = required - fields
        if (unexpected.isNotEmpty() || missing.isNotEmpty()) {
            throw EndpointManifestException("Manifest $label contains unexpected or missing fields")
        }
    }

    private fun requireString(value: JsonObject, name: String) {
        val field = value.get(name)
        if (field == null || !field.isJsonPrimitive || !field.asJsonPrimitive.isString) {
            throw EndpointManifestException("Manifest field $name must be a string")
        }
    }

    private fun requireNumber(value: JsonObject, name: String) {
        val field = value.get(name)
        if (field == null || !field.isJsonPrimitive || !field.asJsonPrimitive.isNumber) {
            throw EndpointManifestException("Manifest field $name must be a number")
        }
    }

    private fun requirePositiveInteger(value: JsonObject, name: String) {
        val field = value.get(name)
        val primitive = field?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        if (primitive == null || !primitive.isNumber) {
            throw EndpointManifestException("Manifest field $name must be a positive integer")
        }
        val number = runCatching { primitive.asString.toBigDecimal() }.getOrNull()
        if (number == null || number.signum() <= 0 ||
            number.stripTrailingZeros().scale() > 0 || number > BigDecimal.valueOf(MAX_UPDATE_BUILD)
        ) {
            throw EndpointManifestException("Manifest field $name must be a positive integer")
        }
    }

    private fun requireBoolean(value: JsonObject, name: String) {
        val field = value.get(name)
        if (field == null || !field.isJsonPrimitive || !field.asJsonPrimitive.isBoolean) {
            throw EndpointManifestException("Manifest field $name must be a boolean")
        }
    }

    private fun requireStringArray(value: JsonObject, name: String) {
        val field = value.get(name)
        if (field == null || !field.isJsonArray ||
            field.asJsonArray.any { !it.isJsonPrimitive || !it.asJsonPrimitive.isString }
        ) {
            throw EndpointManifestException("Manifest field $name must be an array of strings")
        }
    }

    private fun parsePublicKey(pem: String): ECPublicKey {
        val encoded = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace(Regex("\\s"), "")
        val key = try {
            val bytes = Base64.getDecoder().decode(encoded)
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid embedded manifest public key", error)
        }
        val ecKey = key as? ECPublicKey
            ?: throw IllegalArgumentException("Manifest public key is not an EC key")
        require(ecKey.params.curve.field.fieldSize == P256_FIELD_SIZE) {
            "Manifest public key must use P-256"
        }
        return ecKey
    }

    private fun decodeBase64(value: String, label: String): ByteArray = try {
        Base64.getDecoder().decode(value)
    } catch (error: IllegalArgumentException) {
        throw EndpointManifestException("Manifest $label is not valid Base64", error)
    }

    private fun parseInstant(value: String, label: String): Instant = try {
        Instant.parse(value)
    } catch (error: Exception) {
        throw EndpointManifestException("Manifest $label is not a valid UTC timestamp", error)
    }

    companion object {
        const val ALGORITHM = "ECDSA_P256_SHA256"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val SUPPORTED_SCHEMA = 1
        private const val P256_FIELD_SIZE = 256
        private const val MAX_ENVELOPE_CHARS = 256 * 1024
        private const val MAX_ENCODED_PAYLOAD_CHARS = 256 * 1024
        private const val MAX_ENCODED_SIGNATURE_CHARS = 1024
        private const val MAX_PAYLOAD_BYTES = 128 * 1024
        private const val MAX_SIGNATURE_BYTES = 512
        private const val MAX_ENDPOINT_COUNT = 8
        private const val MAX_MIRROR_COUNT = 8
        private const val MAX_URL_CHARS = 2_048
        private const val MAX_NOTICE_ID_CHARS = 128
        private const val MAX_NOTICE_TITLE_CHARS = 200
        private const val MAX_NOTICE_MESSAGE_CHARS = 4_000
        private const val MAX_UPDATE_VERSION_CHARS = 64
        private const val MAX_UPDATE_BUILD = 2_147_483_647L
        private val ENVELOPE_FIELDS = setOf("algorithm", "payload", "signature")
        private val PAYLOAD_FIELDS = setOf(
            "schema",
            "version",
            "issuedAt",
            "expiresAt",
            "apiEndpoints",
            "registrationUrl",
            "downloadPageUrl",
            "bootstrapMirrors",
            "migrationNotice",
            "updates",
        )
        private val REQUIRED_PAYLOAD_FIELDS = PAYLOAD_FIELDS - setOf("migrationNotice", "updates")
        private val NOTICE_FIELDS = setOf("id", "title", "message", "autoApply", "required")
        private val UPDATE_CHANNEL_FIELDS = setOf("android", "desktop")
        private val CLIENT_UPDATE_FIELDS = setOf(
            "version",
            "build",
            "downloadUrl",
            "required",
            "title",
            "message",
        )
        private val SEMVER = Regex("^(0|[1-9]\\d{0,8})\\.(0|[1-9]\\d{0,8})\\.(0|[1-9]\\d{0,8})$")
        private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
        private val MAX_CLOCK_SKEW = Duration.ofMinutes(5)
    }
}

object EndpointBootstrapConfig {
    const val PUBLIC_KEY_PEM = """-----BEGIN PUBLIC KEY-----
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEOyQ5cjr5sBj6ljunoleDGSupjtaz
v1LzyUeGQ5NN2R0STXbcIN/fzgGpfG9EPDLIHiXKgkbG61VV+06cOV3Wdg==
-----END PUBLIC KEY-----"""

    val payload = EndpointManifestPayload(
        schema = 1,
        version = 3,
        issuedAt = "2026-08-03T07:11:26Z",
        expiresAt = "2027-08-03T07:11:26Z",
        apiEndpoints = listOf(
            "https://www.miaonetwork.com",
            "https://www.vpnmiao.com",
        ),
        registrationUrl = "https://www.miaonetwork.com/#/register",
        downloadPageUrl = "https://download.vpnmiao.com/download/index.html",
        bootstrapMirrors = listOf(
            "https://cdn.vpnmiao.com/manifest.json",
            "https://rmomo5285-droid.github.io/Miaomiao-Config/manifest.json",
            "https://cdn.jsdelivr.net/gh/rmomo5285-droid/Miaomiao-Config@gh-pages/manifest.json",
            "https://raw.githubusercontent.com/rmomo5285-droid/Miaomiao-Config/gh-pages/manifest.json",
        ),
    )
}
