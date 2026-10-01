package net.die.phoneapi.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.model.Scope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@OptIn(ExperimentalCoroutinesApi::class)
class PairingManagerTest {
    private fun TestScope.manager(tokens: MemoryTokens = MemoryTokens(), scope: CoroutineScope) =
        PairingManager(tokens, scope, clock = { testScheduler.currentTime })

    @Test
    fun `closed by default`() = runTest {
        val pairing = manager(scope = backgroundScope)
        assertFalse(pairing.isOpen)
        val error = assertThrows<ApiException> { pairing.submit("laptop", "10.0.0.2") }
        assertEquals(404, error.status)
    }

    @Test
    fun `window expires pending request`() = runTest {
        val pairing = manager(scope = backgroundScope)
        pairing.open()
        val id = pairing.submit("laptop", "10.0.0.2")
        advanceTimeBy(PairingManager.WINDOW_MS + 1)
        runCurrent()
        assertFalse(pairing.isOpen)
        assertEquals(PairingManager.Outcome.Expired, pairing.await(id, 0))
    }

    @Test
    fun `one pending request at a time`() = runTest {
        val pairing = manager(scope = backgroundScope)
        pairing.open()
        pairing.submit("laptop", "10.0.0.2")
        val error = assertThrows<ApiException> { pairing.submit("other", "10.0.0.3") }
        assertEquals(429, error.status)
    }

    @Test
    fun `deny keeps window open`() = runTest {
        val pairing = manager(scope = backgroundScope)
        pairing.open()
        val first = pairing.submit("laptop", "10.0.0.2")
        pairing.deny(first)
        assertEquals(PairingManager.Outcome.Denied, pairing.await(first, 0))
        assertTrue(pairing.isOpen)
        pairing.submit("laptop", "10.0.0.2")
    }

    @Test
    fun `approval hands token out once`() = runTest {
        val tokens = MemoryTokens()
        val pairing = manager(tokens, backgroundScope)
        pairing.open()
        val id = pairing.submit("  laptop\n", "10.0.0.2")
        val waiting = async { pairing.await(id, 10_000) }
        runCurrent()
        pairing.approve(id)
        val outcome = assertInstanceOf(PairingManager.Outcome.Approved::class.java, waiting.await())
        assertEquals("laptop", outcome.name)
        assertEquals(Scope.entries.toSet(), tokens.authenticate(outcome.token)?.scopes)
        assertFalse(pairing.isOpen)
        assertNull(pairing.await(id, 0))
        assertFalse(pairing.knows(id))
    }

    @Test
    fun `uncollected token is revoked`() = runTest {
        val tokens = MemoryTokens()
        val pairing = manager(tokens, backgroundScope)
        pairing.open()
        val id = pairing.submit("laptop", "10.0.0.2")
        pairing.approve(id)
        assertEquals(1, tokens.list().size)
        advanceTimeBy(PairingManager.HANDOFF_MS + 1)
        runCurrent()
        assertTrue(tokens.list().isEmpty())
        assertFalse(pairing.knows(id))
    }

    @Test
    fun `poll times out as waiting`() = runTest {
        val pairing = manager(scope = backgroundScope)
        pairing.open()
        val id = pairing.submit("laptop", "10.0.0.2")
        assertEquals(PairingManager.Outcome.Waiting, pairing.await(id, 1_000))
        assertTrue(pairing.knows(id))
    }

    @Test
    fun `blank names get a default`() = runTest {
        val pairing = manager(scope = backgroundScope)
        pairing.open()
        pairing.submit(" \t", "10.0.0.2")
        val state = pairing.state.value as PairingManager.State.Open
        assertEquals(PairingManager.DEFAULT_NAME, state.pending?.name)
    }
}
