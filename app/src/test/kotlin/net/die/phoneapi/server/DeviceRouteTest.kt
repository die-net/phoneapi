package net.die.phoneapi.server

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.ScreenOrientation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DeviceRouteTest {
    @Test
    fun `orientation reaches power`() = testApplication {
        val api = FakeApi()
        val control = api.tokens.issue("control", setOf(Scope.CONTROL))
        application { phoneApiModule(api.services) }
        apiClient().use { client ->
            val response =
                client.post("/v1/device/orientation") {
                    bearer(control)
                    contentType(ContentType.Application.Json)
                    setBody("""{"orientation":"90"}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(ScreenOrientation.R90, api.orientation?.orientation)
        }
    }
}
