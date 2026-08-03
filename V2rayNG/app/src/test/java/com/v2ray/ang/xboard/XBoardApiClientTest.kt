package com.v2ray.ang.xboard

import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Proxy

class XBoardApiClientTest {
    @Test
    fun businessFallbackUsesConfiguredHttpProxySemantics() {
        val client = XBoardApiClient.localHttpProxyClient(
            baseClient = OkHttpClient(),
            port = 10809,
            username = null,
            password = null,
            portOpen = { true },
        )

        assertEquals(Proxy.Type.HTTP, client?.proxy?.type())
        assertEquals(10809, client?.proxy?.address()?.let { it as java.net.InetSocketAddress }?.port)
        assertNull(
            XBoardApiClient.localHttpProxyClient(
                baseClient = OkHttpClient(),
                port = 10808,
                username = null,
                password = null,
                portOpen = { false },
            ),
        )

        val socksClient = XBoardApiClient.localSocksProxyClient(
            baseClient = OkHttpClient(),
            port = 10808,
            portOpen = { true },
        )
        assertEquals(Proxy.Type.SOCKS, socksClient?.proxy?.type())
        assertEquals(
            10808,
            socksClient?.proxy?.address()?.let { it as java.net.InetSocketAddress }?.port,
        )
    }

    @Test
    fun inviteGenerationSubmitsOnceThenRefreshesInviteInfo() {
        val requests = mutableListOf<Request>()
        val api = XBoardApiClient(
            endpointProvider = { listOf("https://api.example.com") },
            client = clientResponding { request ->
                requests += request
                when (request.url.encodedPath) {
                    "/api/v1/user/info" -> """{"data":{"id":1}}"""
                    "/api/v1/user/invite/save" -> """{"data":true}"""
                    "/api/v1/user/invite/fetch" ->
                        """{"data":{"codes":[{"code":"MIAO","pv":7,"status":1}],"stat":[4,0,0,12]}}"""
                    else -> error("Unexpected path ${request.url.encodedPath}")
                }
            },
            proxyClientProvider = { null },
        )

        val invite = api.generateInviteCode("token")

        assertEquals("MIAO", invite.codes.single().code)
        assertEquals(7, invite.codes.single().views)
        assertEquals(4L, invite.totalInvites)
        assertEquals(12L, invite.commissionRate)
        assertEquals(1, requests.count { it.url.encodedPath == "/api/v1/user/invite/save" })
        assertEquals("GET", requests.single {
            it.url.encodedPath == "/api/v1/user/invite/save"
        }.method)
    }

    @Test
    fun loginUsesExpectedFormWithoutBearerAndAuthenticatedCallsUseBearer() {
        val requests = mutableListOf<Request>()
        val client = clientResponding { request ->
            requests += request
            when (request.url.encodedPath) {
                "/api/v1/passport/auth/login" -> """{"data":{"auth_data":"Bearer secret-token"}}"""
                "/api/v1/user/getSubscribe" ->
                    """{"data":{"plan_id":7,"u":2,"d":3,"transfer_enable":10,"device_limit":3}}"""
                else -> error("Unexpected path ${request.url.encodedPath}")
            }
        }
        val api = XBoardApiClient(
            endpointProvider = { listOf("https://api.example.com") },
            client = client,
        )

        val token = api.login("person@example.com", "not-persisted")
        val subscription = api.getSubscribe(token)

        assertEquals("secret-token", token)
        val loginRequest = requests[0]
        assertNull(loginRequest.header("Authorization"))
        val form = loginRequest.body as FormBody
        assertEquals("person@example.com", formValue(form, "email"))
        assertEquals("not-persisted", formValue(form, "password"))
        assertEquals("Bearer secret-token", requests[1].header("Authorization"))
        assertEquals(5L, subscription.usedTraffic)
        assertEquals(5L, subscription.remainingTraffic)
        assertEquals(3, subscription.deviceLimit)
    }

    @Test
    fun orderEndpointsUseXBoardContract() {
        val requests = mutableListOf<Request>()
        val client = clientResponding { request ->
            requests += request
            when (request.url.encodedPath) {
                "/api/v1/user/info" -> """{"data":{"id":1}}"""
                "/api/v1/user/order/save" -> """{"data":"trade-123"}"""
                "/api/v1/user/order/getPaymentMethod" ->
                    """{"data":[{"id":9,"name":"MiaoPay","payment":"Custom"}]}"""
                "/api/v1/user/order/checkout" -> """{"type":1,"data":"https://pay.example.com/123"}"""
                "/api/v1/user/order/check" -> """{"data":3}"""
                else -> error("Unexpected path ${request.url.encodedPath}")
            }
        }
        val api = XBoardApiClient(
            endpointProvider = { listOf("https://api.example.com") },
            client = client,
        )

        val order = api.saveOrder("token", XBoardSaveOrderRequest(7, "month_price", "SAVE10"))
        val methods = api.getPaymentMethods("token", order.tradeNo)
        val checkout = api.checkout("token", XBoardCheckoutRequest(order.tradeNo, methods.single().id))
        val status = api.getOrderStatus("token", order.tradeNo, 20_000L)

        val orderRequest = requests.single { it.url.encodedPath == "/api/v1/user/order/save" }
        val orderForm = orderRequest.body as FormBody
        assertEquals("7", formValue(orderForm, "plan_id"))
        assertEquals("month_price", formValue(orderForm, "period"))
        assertEquals("SAVE10", formValue(orderForm, "coupon_code"))
        val methodsRequest = requests.single {
            it.url.encodedPath == "/api/v1/user/order/getPaymentMethod"
        }
        assertEquals("trade-123", methodsRequest.url.queryParameter("trade_no"))
        assertEquals(1, requests.count { it.url.encodedPath == "/api/v1/user/order/save" })
        assertEquals(1, requests.count { it.url.encodedPath == "/api/v1/user/order/checkout" })
        assertEquals("https://pay.example.com/123", checkout.paymentUrl)
        assertEquals(3, status.statusCode)
        assertTrue(status.paid)
    }

    @Test
    fun recognizesFreeCheckoutAndDoesNotTreatProcessingAsPaid() {
        val orderResponses = ArrayDeque(listOf("""{"data":1}""", """{"data":4}"""))
        val api = XBoardApiClient(
            endpointProvider = { listOf("https://api.example.com") },
            client = clientResponding { request ->
                when (request.url.encodedPath) {
                    "/api/v1/user/info" -> """{"data":{"id":1}}"""
                    "/api/v1/user/order/checkout" -> """{"type":-1,"data":true}"""
                    "/api/v1/user/order/check" -> orderResponses.removeFirst()
                    else -> error("Unexpected path ${request.url.encodedPath}")
                }
            },
        )

        val checkout = api.checkout("token", XBoardCheckoutRequest("free-order", 1))
        val processing = api.getOrderStatus("token", "free-order", 20_000L)
        val discounted = api.getOrderStatus("token", "free-order", 20_000L)

        assertTrue(checkout.completed)
        assertTrue(!processing.paid)
        assertTrue(discounted.paid)
    }

    @Test
    fun fetchesOfficialOrderListAndAcceptsNumericStrings() {
        val api = XBoardApiClient(
            endpointProvider = { listOf("https://api.example.com") },
            client = clientResponding { request ->
                when (request.url.encodedPath) {
                    "/api/v1/user/order/fetch" ->
                        """{"data":[{"id":"7","trade_no":"trade-7","payment_id":"9","status":"1","created_at":"99"}]}"""
                    else -> error("Unexpected path ${request.url.encodedPath}")
                }
            },
        )

        val order = api.fetchOrders("token").single()

        assertEquals(7, order.id)
        assertEquals("trade-7", order.tradeNo)
        assertEquals(9, order.paymentId)
        assertEquals(1, order.status)
        assertEquals(99L, order.createdAt)
    }

    @Test
    fun failsOverOnBlockedEndpointButRejectsCleartextEndpoint() {
        val hosts = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            hosts += chain.request().url.host
            val successful = chain.request().url.host == "second.example.com"
            response(
                request = chain.request(),
                code = if (successful) 200 else 403,
                body = if (successful) """{"data":{"token":"ok"}}""" else "{}",
            )
        }.build()
        val api = XBoardApiClient(
            endpointProvider = {
                listOf("https://first.example.com", "https://second.example.com")
            },
            client = client,
        )

        assertEquals("ok", api.login("person@example.com", "password"))
        assertEquals(listOf("first.example.com", "second.example.com"), hosts)

        val cleartextApi = XBoardApiClient(
            endpointProvider = { listOf("http://api.example.com") },
            client = client,
        )
        assertThrows(XBoardApiException::class.java) {
            cleartextApi.login("person@example.com", "password")
        }
    }

    @Test
    fun mutationProbesBlockedPrimaryThenSubmitsOnceToSecondary() {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            when {
                request.url.encodedPath == "/api/v1/user/info" &&
                    request.url.host == "first.example.com" -> response(request, 403, "{}")
                request.url.encodedPath == "/api/v1/user/info" ->
                    response(request, 200, """{"data":{"id":1}}""")
                request.url.encodedPath == "/api/v1/user/order/save" ->
                    response(request, 200, """{"data":"trade-secondary"}""")
                else -> error("Unexpected request ${request.url}")
            }
        }.build()
        val api = XBoardApiClient(
            endpointProvider = {
                listOf("https://first.example.com", "https://second.example.com")
            },
            client = client,
            proxyClientProvider = { null },
        )

        val order = api.saveOrder("token", XBoardSaveOrderRequest(7, "month_price"))

        assertEquals("trade-secondary", order.tradeNo)
        assertEquals(
            listOf("first.example.com", "second.example.com", "second.example.com"),
            requests.map { it.url.host },
        )
        assertEquals(1, requests.count { it.url.encodedPath == "/api/v1/user/order/save" })
    }

    @Test
    fun ambiguousMutationFailureIsNeverReplayed() {
        var mutationCount = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath == "/api/v1/user/info") {
                response(request, 200, """{"data":{"id":1}}""")
            } else {
                mutationCount++
                throw java.io.IOException("connection closed after upload")
            }
        }.build()
        val api = XBoardApiClient(
            endpointProvider = {
                listOf("https://first.example.com", "https://second.example.com")
            },
            client = client,
            proxyClientProvider = { null },
        )

        val error = assertThrows(XBoardApiException::class.java) {
            api.saveOrder("token", XBoardSaveOrderRequest(7, "month_price"))
        }

        assertTrue(error.outcomeUnknown)
        assertEquals(1, mutationCount)
    }

    @Test
    fun ambiguousCheckoutFailureIsNeverReplayed() {
        var checkoutCount = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath == "/api/v1/user/info") {
                response(request, 200, """{"data":{"id":1}}""")
            } else {
                checkoutCount++
                throw java.io.IOException("response lost")
            }
        }.build()
        val api = XBoardApiClient(
            endpointProvider = {
                listOf("https://first.example.com", "https://second.example.com")
            },
            client = client,
            proxyClientProvider = { null },
        )

        val error = assertThrows(XBoardApiException::class.java) {
            api.checkout("token", XBoardCheckoutRequest("trade", 1))
        }

        assertTrue(error.outcomeUnknown)
        assertEquals(1, checkoutCount)
    }

    @Test
    fun rejectedCheckoutHasKnownOutcomeAndIsNeverReplayed() {
        var checkoutCount = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            when (request.url.encodedPath) {
                "/api/v1/user/info" -> response(request, 200, """{"data":{"id":1}}""")
                "/api/v1/user/order/checkout" -> {
                    checkoutCount++
                    response(request, 422, """{"message":"payment method rejected"}""")
                }
                else -> error("Unexpected request ${request.url}")
            }
        }.build()
        val api = XBoardApiClient(
            endpointProvider = {
                listOf("https://first.example.com", "https://second.example.com")
            },
            client = client,
            proxyClientProvider = { null },
        )

        val error = assertThrows(XBoardApiException::class.java) {
            api.checkout("token", XBoardCheckoutRequest("trade", 1))
        }

        assertEquals(422, error.statusCode)
        assertTrue(!error.outcomeUnknown)
        assertEquals(1, checkoutCount)
    }

    @Test
    fun malformedMutationResponseIsUnknownAndNeverReplayed() {
        var mutationCount = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath == "/api/v1/user/info") {
                response(request, 200, """{"data":{"id":1}}""")
            } else {
                mutationCount++
                response(request, 200, "not-json")
            }
        }.build()
        val api = XBoardApiClient(
            endpointProvider = {
                listOf("https://first.example.com", "https://second.example.com")
            },
            client = client,
            proxyClientProvider = { null },
        )

        val error = assertThrows(XBoardApiException::class.java) {
            api.saveOrder("token", XBoardSaveOrderRequest(7, "month_price"))
        }

        assertTrue(error.outcomeUnknown)
        assertEquals(1, mutationCount)
    }

    @Test
    fun replaySafeGetSkipsMalformedPrimaryResponse() {
        val hosts = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            hosts += request.url.host
            val body = if (request.url.host == "first.example.com") {
                "not-json"
            } else {
                """{"data":[{"id":7,"name":"Plan"}]}"""
            }
            response(request, 200, body)
        }.build()
        val api = XBoardApiClient(
            endpointProvider = {
                listOf("https://first.example.com", "https://second.example.com")
            },
            client = client,
            proxyClientProvider = { null },
        )

        val plan = api.fetchPlans("token").single()

        assertEquals(7, plan.id)
        assertEquals(listOf("first.example.com", "second.example.com"), hosts)
    }

    private fun clientResponding(bodyFor: (Request) -> String): OkHttpClient {
        return OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            response(chain.request(), 200, bodyFor(chain.request()))
        }).build()
    }

    private fun formValue(body: FormBody, name: String): String? {
        return (0 until body.size)
            .firstOrNull { body.name(it) == name }
            ?.let(body::value)
    }

    private companion object {
        val JSON = "application/json".toMediaType()

        fun response(request: Request, code: Int, body: String): Response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(body.toResponseBody(JSON))
            .build()
    }
}
