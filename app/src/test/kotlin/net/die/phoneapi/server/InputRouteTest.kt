package net.die.phoneapi.server

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import net.die.phoneapi.model.Scope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InputRouteTest {
    @Test
    fun `show targets a node`() = testApplication {
        val api = FakeApi()
        val control = api.tokens.issue("control", setOf(Scope.CONTROL))
        application { phoneApiModule(api.services) }
        apiClient().use { client ->
            val focused =
                client.post("/v1/ime/show") {
                    bearer(control)
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            assertEquals(HttpStatusCode.OK, focused.status)
            assertEquals(null, api.imeShow?.selector)
            assertEquals(true, api.imeShow?.autoWake)

            val targeted =
                client.post("/v1/ime/show") {
                    bearer(control)
                    contentType(ContentType.Application.Json)
                    setBody("""{"selector":{"ref":"e12"},"autoWake":false}""")
                }
            assertEquals(HttpStatusCode.OK, targeted.status)
            assertEquals("e12", api.imeShow?.selector?.ref)
            assertEquals(false, api.imeShow?.autoWake)
        }
    }
}
