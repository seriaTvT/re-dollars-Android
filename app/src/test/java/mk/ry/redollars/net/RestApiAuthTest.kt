package mk.ry.redollars.net

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RestApiAuthTest {

    @Test
    fun tokenLoginParsesPersistentTokenAndUser() = runBlocking {
        var request: Request? = null
        val api = RestApi(testClient {
            request = it
            response(it, 200, """
                {"status":true,"token":"local-token","user":{"id":42,"nickname":"Alice","avatar":"a"}}
            """.trimIndent())
        })

        val result = api.tokenLogin("header.payload.signature")

        assertEquals(
            TokenLoginResult.Valid(
                token = "local-token",
                user = AuthUserDto(42, "Alice", "a"),
            ),
            result,
        )
        assertEquals("POST", request?.method)
        assertEquals("${Config.BACKEND_API_URL}/auth/token-login", request?.url.toString())
        val body = Buffer().also { request?.body?.writeTo(it) }.readUtf8()
        assertEquals("{\"token\":\"header.payload.signature\"}", body)
    }

    @Test
    fun tokenLoginTreatsExplicitRejectionAsInvalid() = runBlocking {
        val api = RestApi(testClient { response(it, 200, "{\"status\":false,\"message\":\"Invalid or expired token\"}") })

        assertEquals(TokenLoginResult.Invalid, api.tokenLogin("bad-token"))
    }

    @Test
    fun tokenLoginTreatsServerFailureAndTransportFailureAsRetryableError() = runBlocking {
        val serverFailure = RestApi(testClient { response(it, 503, "unavailable") })
        assertEquals(TokenLoginResult.Error, serverFailure.tokenLogin("token"))

        val transportFailure = RestApi(
            OkHttpClient.Builder()
                .addInterceptor { throw IOException("offline") }
                .build(),
        )
        assertEquals(TokenLoginResult.Error, transportFailure.tokenLogin("token"))
    }

    @Test
    fun authMeAcceptsSupportedUserShapesButRejectsExplicitFalse() = runBlocking {
        val responses = ArrayDeque(
            listOf(
                "{\"status\":true,\"user\":{\"id\":42,\"nickname\":\"Alice\"}}",
                "{\"user\":{\"id\":43,\"nickname\":\"Bob\"}}",
                "{\"status\":true,\"uid\":44}",
                "{\"status\":false,\"user\":{\"id\":45}}",
            ),
        )
        val api = RestApi(testClient { response(it, 200, responses.removeFirst()) })

        assertTrue(api.authMe("token") is AuthMeResult.Valid)
        assertEquals(AuthUserDto(43, "Bob", ""), (api.authMe("token") as AuthMeResult.Valid).user)
        assertEquals(AuthUserDto(44, "", ""), (api.authMe("token") as AuthMeResult.Valid).user)
        assertEquals(AuthMeResult.Invalid, api.authMe("token"))
    }

    @Test
    fun authMeDistinguishesUnauthorizedFromServerFailureAndMalformedBody() = runBlocking {
        val responses = ArrayDeque(
            listOf(
                responsePlaceholder(401, "{\"status\":false}"),
                responsePlaceholder(500, "error"),
                responsePlaceholder(200, "not-json"),
            ),
        )
        val api = RestApi(testClient { responses.removeFirst().withRequest(it) })

        assertEquals(AuthMeResult.Invalid, api.authMe("token"))
        assertEquals(AuthMeResult.Error, api.authMe("token"))
        assertEquals(AuthMeResult.Error, api.authMe("token"))
    }

    private fun testClient(handler: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain -> handler(chain.request()) }
            .build()

    private fun response(request: Request, code: Int, body: String): Response =
        responsePlaceholder(code, body).withRequest(request)

    private fun responsePlaceholder(code: Int, body: String): TestResponse =
        TestResponse(code, body)

    private data class TestResponse(val code: Int, val body: String) {
        fun withRequest(request: Request): Response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }
}
