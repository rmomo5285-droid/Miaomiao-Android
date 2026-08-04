package com.v2ray.ang.xboard

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.Utils
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

class XBoardApiException(
    message: String,
    val statusCode: Int? = null,
    val outcomeUnknown: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)

interface XBoardService {
    @Throws(XBoardApiException::class)
    fun login(email: String, password: String): String

    @Throws(XBoardApiException::class)
    fun getSubscribe(token: String): XBoardSubscription

    @Throws(XBoardApiException::class)
    fun fetchPlans(token: String): List<XBoardPlan>

    @Throws(XBoardApiException::class)
    fun fetchNotices(token: String): List<XBoardNotice>

    @Throws(XBoardApiException::class)
    fun fetchInviteInfo(token: String): XBoardInviteInfo

    @Throws(XBoardApiException::class)
    fun generateInviteCode(token: String): XBoardInviteInfo

    @Throws(XBoardApiException::class)
    fun fetchOrders(token: String): List<XBoardOrderRecord>

    @Throws(XBoardApiException::class)
    fun saveOrder(token: String, order: XBoardSaveOrderRequest): XBoardOrder

    @Throws(XBoardApiException::class)
    fun getPaymentMethods(token: String, tradeNo: String): List<XBoardPaymentMethod>

    @Throws(XBoardApiException::class)
    fun checkout(token: String, checkout: XBoardCheckoutRequest): XBoardCheckoutResult

    @Throws(XBoardApiException::class)
    fun getOrderStatus(
        token: String,
        tradeNo: String,
        timeoutMillis: Long,
    ): XBoardOrderStatus
}

class XBoardApiClient(
    private val endpointProvider: () -> List<String>,
    private val client: OkHttpClient = secureClient(),
    private val gson: Gson = Gson(),
    private val proxyClientProvider: () -> OkHttpClient? = { localProxyClient(client) },
) : XBoardService {
    override fun login(email: String, password: String): String {
        require(email.isNotBlank()) { "Email must not be blank" }
        require(password.isNotEmpty()) { "Password must not be empty" }
        val body = FormBody.Builder()
            .add("email", email)
            .add("password", password)
            .build()
        val root = execute(PATH_LOGIN) { url ->
            Request.Builder().url(url).post(body).build()
        }
        val data = dataElement(root)
        val token = firstString(data, "auth_data", "token")
            ?: firstString(root, "auth_data", "token")
            ?: data.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: throw XBoardApiException("Login response did not contain an authentication token")
        return normalizeToken(token)
    }

    override fun getSubscribe(token: String): XBoardSubscription {
        val data = dataElement(executeAuthenticatedGet(PATH_SUBSCRIBE, token))
        requireObject(data, "subscription")
        return gson.fromJson(data, XBoardSubscription::class.java)
    }

    override fun fetchPlans(token: String): List<XBoardPlan> {
        val data = dataElement(executeAuthenticatedGet(PATH_PLANS, token))
        return requireArray(data, "plans").map { gson.fromJson(it, XBoardPlan::class.java) }
    }

    override fun fetchNotices(token: String): List<XBoardNotice> {
        val data = dataElement(executeAuthenticatedGet(PATH_NOTICES, token))
        return requireArray(data, "notices").map { gson.fromJson(it, XBoardNotice::class.java) }
    }

    override fun fetchInviteInfo(token: String): XBoardInviteInfo {
        return parseInviteInfo(dataElement(executeAuthenticatedGet(PATH_INVITE_INFO, token)))
    }

    override fun generateInviteCode(token: String): XBoardInviteInfo {
        val route = selectAuthenticatedRoute(token)
        val url = buildUrl(route.endpoint, PATH_INVITE_GENERATE, emptyMap())
        val request = authenticatedRequest(url, token).get().build()
        val response = try {
            route.client.newBuilder()
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
                .newCall(request)
                .execute()
        } catch (error: IOException) {
            throw XBoardApiException(
                "The invite-code request outcome is unknown. Refresh before retrying.",
                outcomeUnknown = true,
                cause = error,
            )
        }
        response.use {
            if (!it.isSuccessful) {
                throw XBoardApiException(responseErrorMessage(it), statusCode = it.code)
            }
            val data = dataElement(parseResponse(it))
            if (!data.isJsonPrimitive || !data.asJsonPrimitive.isBoolean || !data.asBoolean) {
                throw XBoardApiException(
                    "Invite-code response did not confirm success",
                    statusCode = it.code,
                    outcomeUnknown = true,
                )
            }
        }
        return try {
            fetchInviteInfo(token)
        } catch (error: XBoardApiException) {
            throw XBoardApiException(
                "The invite code may have been generated, but refresh failed. Refresh before retrying.",
                outcomeUnknown = true,
                cause = error,
            )
        }
    }

    override fun fetchOrders(token: String): List<XBoardOrderRecord> {
        val data = dataElement(executeAuthenticatedGet(PATH_ORDERS, token))
        return requireArray(data, "orders").map { gson.fromJson(it, XBoardOrderRecord::class.java) }
    }

    override fun saveOrder(token: String, order: XBoardSaveOrderRequest): XBoardOrder {
        require(order.planId > 0) { "Plan ID must be positive" }
        require(order.period.isNotBlank()) { "Order period must not be blank" }
        val body = FormBody.Builder()
            .add("plan_id", order.planId.toString())
            .add("period", order.period)
            .apply {
                order.couponCode?.takeIf { it.isNotBlank() }?.let { add("coupon_code", it) }
            }
            .build()
        val root = executeAuthenticatedMutation(PATH_SAVE_ORDER, token, body)
        try {
            val data = dataElement(root)
            val tradeNo = when {
                data.isJsonPrimitive -> data.asString
                data.isJsonObject -> firstString(data, "trade_no", "tradeNo")
                else -> null
            }
            if (tradeNo.isNullOrBlank() || tradeNo.length > MAX_TRADE_NO_CHARS) {
                throw XBoardApiException("Order response did not contain a trade number")
            }
            return XBoardOrder(tradeNo)
        } catch (error: XBoardApiException) {
            throw ambiguousMutationResponse(error)
        } catch (error: RuntimeException) {
            throw XBoardApiException(
                "Order response could not be interpreted",
                outcomeUnknown = true,
                cause = error,
            )
        }
    }

    override fun getPaymentMethods(token: String, tradeNo: String): List<XBoardPaymentMethod> {
        require(tradeNo.isNotBlank()) { "Trade number must not be blank" }
        val root = execute(PATH_PAYMENT_METHODS, query = mapOf("trade_no" to tradeNo)) { url ->
            authenticatedRequest(url, token).get().build()
        }
        return requireArray(dataElement(root), "payment methods")
            .map { gson.fromJson(it, XBoardPaymentMethod::class.java) }
    }

    override fun checkout(token: String, checkout: XBoardCheckoutRequest): XBoardCheckoutResult {
        require(checkout.tradeNo.isNotBlank()) { "Trade number must not be blank" }
        require(checkout.methodId > 0) { "Payment method ID must be positive" }
        val body = FormBody.Builder()
            .add("trade_no", checkout.tradeNo)
            .add("method", checkout.methodId.toString())
            .build()
        val root = executeAuthenticatedMutation(PATH_CHECKOUT, token, body)
        try {
            val objectRoot = root.takeIf(JsonElement::isJsonObject)?.asJsonObject
                ?: throw XBoardApiException("Checkout response was not an object")
            val rawData = objectRoot.get("data")
            val type = objectRoot.get("type")?.takeIf { it.isJsonPrimitive }?.asInt
                ?: rawData?.takeIf { it.isJsonObject }?.asJsonObject?.get("type")
                    ?.takeIf { it.isJsonPrimitive }?.asInt
            val paymentData = if (rawData?.isJsonObject == true && rawData.asJsonObject.has("data")) {
                rawData.asJsonObject.get("data")
            } else {
                rawData
            }
            val paymentUrl = paymentData
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
                ?: paymentData?.takeIf { it.isJsonObject }
                    ?.let { firstString(it, "url", "payment_url", "qr_code") }
            val completed = type == CHECKOUT_COMPLETED ||
                paymentData?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                    ?.asBoolean == true
            return XBoardCheckoutResult(
                type = type,
                completed = completed,
                paymentUrl = paymentUrl,
                rawData = paymentData,
            )
        } catch (error: XBoardApiException) {
            throw ambiguousMutationResponse(error)
        } catch (error: RuntimeException) {
            throw XBoardApiException(
                "Checkout response could not be interpreted",
                outcomeUnknown = true,
                cause = error,
            )
        }
    }

    override fun getOrderStatus(
        token: String,
        tradeNo: String,
        timeoutMillis: Long,
    ): XBoardOrderStatus {
        require(tradeNo.isNotBlank()) { "Trade number must not be blank" }
        require(timeoutMillis in 1..MAX_ORDER_STATUS_TIMEOUT_MILLIS)
        val root = execute(
            path = PATH_ORDER_STATUS,
            query = mapOf("trade_no" to tradeNo),
            totalTimeoutMillis = timeoutMillis,
        ) { url -> authenticatedRequest(url, token).get().build() }
        val data = dataElement(root)
        val status = when {
            data.isJsonPrimitive -> data.intValueOrNull()
            data.isJsonObject -> data.asJsonObject.get("status")?.intValueOrNull()
            else -> null
        }
        val paid = when {
            data.isJsonPrimitive && data.asJsonPrimitive.isBoolean -> data.asBoolean
            status != null -> status == ORDER_COMPLETED || status == ORDER_DISCOUNTED
            else -> false
        }
        return XBoardOrderStatus(paid = paid, statusCode = status, rawData = data)
    }

    private fun executeAuthenticatedGet(path: String, token: String): JsonElement {
        return execute(path) { url -> authenticatedRequest(url, token).get().build() }
    }

    private fun parseInviteInfo(data: JsonElement): XBoardInviteInfo {
        val root = requireObject(data, "invite")
        val codes = root.get("codes")
            ?.takeIf(JsonElement::isJsonArray)
            ?.asJsonArray
            ?.mapNotNull { item ->
                val value = item.takeIf(JsonElement::isJsonObject)?.asJsonObject
                    ?: return@mapNotNull null
                val code = value.get("code")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && it.length <= MAX_INVITE_CODE_CHARS }
                    ?: return@mapNotNull null
                val active = value.get("status")?.let { status ->
                    when {
                        status.isJsonPrimitive && status.asJsonPrimitive.isBoolean -> status.asBoolean
                        status.isJsonPrimitive && status.asJsonPrimitive.isNumber -> status.asInt != 0
                        else -> true
                    }
                } ?: true
                XBoardInviteCode(
                    code = code,
                    views = value.get("pv")?.intValueOrNull()?.coerceAtLeast(0) ?: 0,
                    active = active,
                )
            }
            .orEmpty()
        val stats = root.get("stat")
            ?.takeIf(JsonElement::isJsonArray)
            ?.asJsonArray
            ?.take(MAX_INVITE_STATS)
            ?.map { value ->
                runCatching { value.asLong }.getOrDefault(0L).coerceAtLeast(0L)
            }
            .orEmpty()
        return XBoardInviteInfo(codes = codes, stats = stats)
    }

    private fun executeAuthenticatedMutation(
        path: String,
        token: String,
        body: FormBody,
    ): JsonElement {
        val route = selectAuthenticatedRoute(token)
        val url = buildUrl(route.endpoint, path, emptyMap())
        val request = authenticatedRequest(url, token).post(body).build()
        val singleAttemptClient = route.client.newBuilder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val response = try {
            singleAttemptClient.newCall(request).execute()
        } catch (error: IOException) {
            throw XBoardApiException(
                "The request outcome is unknown. Check existing orders before retrying.",
                outcomeUnknown = true,
                cause = error,
            )
        }
        response.use {
            if (!it.isSuccessful) {
                throw XBoardApiException(
                    responseErrorMessage(it),
                    statusCode = it.code,
                    outcomeUnknown = isTransientEndpointStatus(it.code),
                )
            }
            return try {
                parseResponse(it)
            } catch (error: Exception) {
                val apiError = error as? XBoardApiException ?: XBoardApiException(
                    "The response was lost after the request was submitted",
                    cause = error,
                )
                throw ambiguousMutationResponse(apiError)
            }
        }
    }

    private fun selectAuthenticatedRoute(token: String): AuthenticatedRoute {
        val endpoints = endpointProvider().distinct()
        if (endpoints.isEmpty()) throw XBoardApiException("No XBoard API endpoint is available")
        val failures = mutableListOf<Exception>()
        for (routeClient in routeClients()) {
            for (endpoint in endpoints) {
                val url = buildUrl(endpoint, PATH_USER_INFO, emptyMap())
                val response = try {
                    routeClient.newCall(authenticatedRequest(url, token).get().build()).execute()
                } catch (error: IOException) {
                    failures += error
                    continue
                }
                response.use {
                    if (isTransientEndpointStatus(it.code)) {
                        failures += IOException("HTTP ${it.code} from ${url.host}")
                        return@use
                    }
                    if (!it.isSuccessful) {
                        throw XBoardApiException(responseErrorMessage(it), statusCode = it.code)
                    }
                    try {
                        val data = dataElement(parseResponse(it))
                        requireObject(data, "authenticated route probe")
                        return AuthenticatedRoute(routeClient, endpoint)
                    } catch (error: Exception) {
                        failures += error
                        return@use
                    }
                }
            }
        }
        throw XBoardApiException(
            "All XBoard API endpoints failed before the request was submitted",
            cause = failures.lastOrNull(),
        )
    }

    private fun authenticatedRequest(url: HttpUrl, token: String): Request.Builder {
        if (token.isBlank()) throw XBoardApiException("Authentication token is missing")
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
    }

    private fun execute(
        path: String,
        query: Map<String, String> = emptyMap(),
        totalTimeoutMillis: Long? = null,
        requestFactory: (HttpUrl) -> Request,
    ): JsonElement {
        val endpoints = endpointProvider().distinct()
        if (endpoints.isEmpty()) throw XBoardApiException("No XBoard API endpoint is available")
        val failures = mutableListOf<Exception>()
        val deadlineNanos = totalTimeoutMillis?.let {
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(it)
        }

        for (routeClient in routeClients()) {
            for (endpoint in endpoints) {
                val url = buildUrl(endpoint, path, query)
                val requestClient = clientForDeadline(routeClient, deadlineNanos)
                val response = try {
                    requestClient.newCall(requestFactory(url)).execute()
                } catch (error: IOException) {
                    failures += error
                    continue
                }
                response.use {
                    if (isTransientEndpointStatus(it.code)) {
                        failures += IOException("HTTP ${it.code} from ${url.host}")
                        return@use
                    }
                    if (!it.isSuccessful) {
                        throw XBoardApiException(
                            responseErrorMessage(it),
                            statusCode = it.code,
                        )
                    }
                    try {
                        return parseResponse(it)
                    } catch (error: Exception) {
                        failures += error
                        return@use
                    }
                }
            }
        }

        throw XBoardApiException(
            "All XBoard API endpoints failed",
            cause = failures.lastOrNull(),
        )
    }

    private fun buildUrl(endpoint: String, path: String, query: Map<String, String>): HttpUrl {
        val base = endpoint.trimEnd('/').toHttpUrlOrNull()
            ?: throw XBoardApiException("Invalid XBoard endpoint")
        if (!base.isHttps) throw XBoardApiException("XBoard endpoint must use HTTPS")
        val resolved = base.resolve(path)
            ?: throw XBoardApiException("Invalid XBoard API path")
        return resolved.newBuilder().apply {
            query.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
    }

    private fun parseResponse(response: Response): JsonElement {
        val raw = readResponseText(response)
        val parsed = try {
            gson.fromJson(raw, JsonElement::class.java)
        } catch (error: Exception) {
            throw XBoardApiException(
                "XBoard response was not valid JSON",
                statusCode = response.code,
                cause = error,
            )
        } ?: throw XBoardApiException("XBoard response was empty", response.code)
        if (!parsed.isJsonObject) {
            throw XBoardApiException("XBoard response was not a JSON object", response.code)
        }
        return parsed
    }

    private fun responseErrorMessage(response: Response): String {
        val fallback = "XBoard request failed with HTTP ${response.code}"
        val raw = runCatching { readResponseText(response) }.getOrNull() ?: return fallback
        val root = runCatching { gson.fromJson(raw, JsonElement::class.java) }.getOrNull()
        return firstString(root, "message", "msg")?.take(MAX_ERROR_MESSAGE_CHARS) ?: fallback
    }

    private fun readResponseText(response: Response): String {
        val body = response.body
        if (body.contentLength() > MAX_RESPONSE_BYTES) {
            throw XBoardApiException("XBoard response is too large", response.code)
        }
        val output = ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_RESPONSE_BYTES) {
                    throw XBoardApiException("XBoard response is too large", response.code)
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun dataElement(root: JsonElement): JsonElement {
        if (!root.isJsonObject) return root
        val objectRoot = root.asJsonObject
        val data = objectRoot.get("data")
        if (data == null || data.isJsonNull) {
            val message = firstString(root, "message", "msg")
            throw XBoardApiException(message ?: "XBoard response did not contain data")
        }
        return data
    }

    private fun requireArray(element: JsonElement, label: String) =
        element.takeIf(JsonElement::isJsonArray)?.asJsonArray
            ?: throw XBoardApiException("XBoard $label response was not a list")

    private fun requireObject(element: JsonElement, label: String): JsonObject =
        element.takeIf(JsonElement::isJsonObject)?.asJsonObject
            ?: throw XBoardApiException("XBoard $label response was not an object")

    private fun firstString(element: JsonElement?, vararg names: String): String? {
        val objectValue = element?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return null
        return names.firstNotNullOfOrNull { name ->
            objectValue.get(name)
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
                ?.takeIf(String::isNotBlank)
        }
    }

    private fun JsonElement.intValueOrNull(): Int? = runCatching {
        takeIf(JsonElement::isJsonPrimitive)?.asInt
    }.getOrNull()

    private fun routeClients(): Sequence<OkHttpClient> = sequence {
        yield(client)
        val proxyClient = proxyClientProvider()
        if (proxyClient != null) yield(proxyClient)
    }

    private fun clientForDeadline(
        baseClient: OkHttpClient,
        deadlineNanos: Long?,
    ): OkHttpClient {
        if (deadlineNanos == null) return baseClient
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) {
            throw XBoardApiException("XBoard order status request timed out")
        }
        val remainingMillis = maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos))
        return baseClient.newBuilder()
            .callTimeout(remainingMillis, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun ambiguousMutationResponse(error: XBoardApiException): XBoardApiException {
        // A server response with a status code is authoritative. Only transport failures or
        // unparseable successful responses have an unknown mutation outcome.
        if (error.outcomeUnknown || error.statusCode?.let { it !in 200..299 } == true) return error
        return XBoardApiException(
            error.message ?: "The request outcome is unknown",
            statusCode = error.statusCode,
            outcomeUnknown = true,
            cause = error,
        )
    }

    companion object {
        private const val PATH_LOGIN = "/api/v1/passport/auth/login"
        private const val PATH_USER_INFO = "/api/v1/user/info"
        private const val PATH_SUBSCRIBE = "/api/v1/user/getSubscribe"
        private const val PATH_PLANS = "/api/v1/user/plan/fetch"
        private const val PATH_NOTICES = "/api/v1/user/notice/fetch"
        private const val PATH_INVITE_INFO = "/api/v1/user/invite/fetch"
        private const val PATH_INVITE_GENERATE = "/api/v1/user/invite/save"
        private const val PATH_ORDERS = "/api/v1/user/order/fetch"
        private const val PATH_SAVE_ORDER = "/api/v1/user/order/save"
        private const val PATH_PAYMENT_METHODS = "/api/v1/user/order/getPaymentMethod"
        private const val PATH_CHECKOUT = "/api/v1/user/order/checkout"
        private const val PATH_ORDER_STATUS = "/api/v1/user/order/check"
        private const val CHECKOUT_COMPLETED = -1
        private const val ORDER_COMPLETED = 3
        private const val ORDER_DISCOUNTED = 4
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        private const val MAX_ERROR_MESSAGE_CHARS = 512
        private const val MAX_TRADE_NO_CHARS = 128
        private const val MAX_ORDER_STATUS_TIMEOUT_MILLIS = 20_000L
        private const val MAX_INVITE_CODE_CHARS = 256
        private const val MAX_INVITE_STATS = 8

        private fun normalizeToken(rawToken: String): String {
            val token = rawToken.trim()
            val normalized = if (token.startsWith("Bearer ", ignoreCase = true)) {
                token.substringAfter(' ').trim()
            } else {
                token
            }
            if (normalized.isBlank() || normalized.length > 8_192) {
                throw XBoardApiException("Login response contained an invalid authentication token")
            }
            return normalized
        }

        private fun isTransientEndpointStatus(statusCode: Int): Boolean =
            statusCode in 300..399 ||
                statusCode == 403 ||
                statusCode == 404 ||
                statusCode == 408 ||
                statusCode == 429 ||
                statusCode >= 500

        private fun localProxyClient(baseClient: OkHttpClient): OkHttpClient? {
            val proxyType = if (Utils.isXray()) Proxy.Type.SOCKS else Proxy.Type.HTTP
            val port = if (proxyType == Proxy.Type.SOCKS) {
                SettingsManager.getSocksPort()
            } else {
                SettingsManager.getHttpPort()
            }
            return localRouteProxyClient(
                baseClient,
                proxyType,
                port,
                SettingsManager.getSocksUsername(),
                SettingsManager.getSocksPassword(),
                ::isLoopbackPortOpen,
            )
        }

        internal fun localHttpProxyClient(
            baseClient: OkHttpClient,
            port: Int,
            username: String?,
            password: String?,
            portOpen: (Int) -> Boolean,
        ): OkHttpClient? = localRouteProxyClient(
            baseClient,
            Proxy.Type.HTTP,
            port,
            username,
            password,
            portOpen,
        )

        internal fun localSocksProxyClient(
            baseClient: OkHttpClient,
            port: Int,
            portOpen: (Int) -> Boolean,
        ): OkHttpClient? = localRouteProxyClient(
            baseClient,
            Proxy.Type.SOCKS,
            port,
            null,
            null,
            portOpen,
        )

        private fun localRouteProxyClient(
            baseClient: OkHttpClient,
            proxyType: Proxy.Type,
            port: Int,
            username: String?,
            password: String?,
            portOpen: (Int) -> Boolean,
        ): OkHttpClient? {
            if (port !in 1..65_535 || !portOpen(port)) return null
            return baseClient.newBuilder()
                .proxy(Proxy(proxyType, InetSocketAddress(AppConfig.LOOPBACK, port)))
                .apply {
                    if (proxyType == Proxy.Type.HTTP &&
                        !username.isNullOrBlank() && !password.isNullOrBlank()
                    ) {
                        proxyAuthenticator { _, response ->
                            if (response.request.header("Proxy-Authorization") != null) {
                                null
                            } else {
                                response.request.newBuilder()
                                    .header("Proxy-Authorization", Credentials.basic(username, password))
                                    .build()
                            }
                        }
                    }
                }
                .build()
        }

        private fun isLoopbackPortOpen(port: Int): Boolean = try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(AppConfig.LOOPBACK, port), LOCAL_PROXY_PROBE_MILLIS)
            }
            true
        } catch (_: IOException) {
            false
        }

        private fun secureClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

        private const val LOCAL_PROXY_PROBE_MILLIS = 500
    }

    private data class AuthenticatedRoute(
        val client: OkHttpClient,
        val endpoint: String,
    )
}
