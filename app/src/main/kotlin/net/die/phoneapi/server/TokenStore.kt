package net.die.phoneapi.server

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import net.die.phoneapi.core.ApiJson
import net.die.phoneapi.core.AtomicFiles
import net.die.phoneapi.core.randomToken
import net.die.phoneapi.core.toHex
import net.die.phoneapi.model.CreatedToken
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.TokenInfo

/** Bearer tokens, stored only as SHA-256 hashes. */
class TokenStore(dir: File) {
    @Serializable
    private data class Stored(
        val id: String,
        val name: String,
        val scopes: Set<Scope>,
        val hash: String,
        val createdAtMs: Long,
        val lastUsedAtMs: Long? = null,
    )

    private val file = File(dir, "tokens.json")
    private val random = SecureRandom()
    // Reified serializer<T>() rather than the plugin-generated companion, so detekt's type
    // resolution (which runs without the serialization compiler plugin) can analyse this file.
    private val serializer = serializer<List<Stored>>()
    private var tokens: List<Stored> =
        if (file.exists()) ApiJson.decodeFromString(serializer, file.readText()) else emptyList()
    private var lastPersistMs = 0L

    @Synchronized fun list(): List<TokenInfo> = tokens.map { it.info() }

    @Synchronized fun isEmpty(): Boolean = tokens.isEmpty()

    @Synchronized
    fun create(name: String, scopes: Set<Scope>): CreatedToken {
        val secret = "pa_" + randomToken(random, 32)
        val stored =
            Stored(
                id = randomToken(random, 6),
                name = name,
                scopes = scopes,
                hash = hash(secret),
                createdAtMs = System.currentTimeMillis(),
            )
        tokens = tokens + stored
        persist()
        return CreatedToken(stored.info(), secret)
    }

    @Synchronized
    fun revoke(id: String): Boolean {
        val before = tokens.size
        tokens = tokens.filterNot { it.id == id }
        if (tokens.size != before) persist()
        return tokens.size != before
    }

    /** Returns the token's info if [secret] is valid, updating its last-used time. */
    @Synchronized
    fun authenticate(secret: String): TokenInfo? {
        val candidate = hash(secret).toByteArray()
        val match =
            tokens.firstOrNull { MessageDigest.isEqual(it.hash.toByteArray(), candidate) }
                ?: return null
        val now = System.currentTimeMillis()
        tokens = tokens.map { if (it.id == match.id) it.copy(lastUsedAtMs = now) else it }
        if (now - lastPersistMs > PERSIST_INTERVAL_MS) persist()
        return match.info()
    }

    private fun persist() {
        lastPersistMs = System.currentTimeMillis()
        AtomicFiles.write(file, ApiJson.encodeToString(serializer, tokens))
    }

    private fun Stored.info() = TokenInfo(id, name, scopes, createdAtMs, lastUsedAtMs)

    private fun hash(secret: String): String =
        MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()).toHex()

    private companion object {
        const val PERSIST_INTERVAL_MS = 60_000L
    }
}
