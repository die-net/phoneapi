package net.die.phoneapi.server

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import net.die.phoneapi.core.ApiJson
import net.die.phoneapi.model.PairState
import net.die.phoneapi.model.PairStatus
import net.die.phoneapi.model.PairTicket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class PairRoutesTest {
    @Test
    fun `closed pairing is a bare 404`() = testApplication {
        val api = FakeApi()
        application { phoneApiModule(api.services) }
        apiClient().use { client ->
            val page = client.get("/pair")
            assertEquals(HttpStatusCode.NotFound, page.status)
            assertEquals("", page.bodyAsText())
            val submit =
                client.post("/v1/pair") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"laptop"}""")
                }
            assertEquals(HttpStatusCode.NotFound, submit.status)
            assertEquals("", submit.bodyAsText())
            assertEquals(HttpStatusCode.NotFound, client.get("/v1/pair/whatever").status)
        }
    }

    @Test
    fun `approved token is served once`() = testApplication {
        val api = FakeApi()
        application { phoneApiModule(api.services) }
        api.pairing.open()
        apiClient().use { client ->
            assertEquals("<html>pair</html>", client.get("/pair").bodyAsText())
            val submit =
                client.post("/v1/pair") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"laptop"}""")
                }
            assertEquals(HttpStatusCode.Created, submit.status)
            val id = ApiJson.decodeFromString(PairTicket.serializer(), submit.bodyAsText()).id

            api.pairing.approve(id)
            val approved = client.get("/v1/pair/$id")
            assertEquals(HttpStatusCode.OK, approved.status)
            val status = ApiJson.decodeFromString(PairStatus.serializer(), approved.bodyAsText())
            assertEquals(PairState.APPROVED, status.state)
            val pairing = checkNotNull(status.pairing)
            assertEquals("laptop", pairing.name)
            assertEquals("AA:BB", pairing.certSha256)
            assertNotNull(api.tokens.authenticate(pairing.token))

            assertEquals(HttpStatusCode.NotFound, client.get("/v1/pair/$id").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/pair").status)
        }
    }
}
