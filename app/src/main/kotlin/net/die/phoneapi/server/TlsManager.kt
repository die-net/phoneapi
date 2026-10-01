package net.die.phoneapi.server

import android.util.Log
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * Self-signed server certificate, generated on first run and persisted as PKCS#12 in private
 * storage. Clients pin it by SHA-256 fingerprint (shown in the pairing QR code).
 */
class TlsManager(private val dir: File, val password: CharArray) {
    private val file = File(dir, "tls.p12")

    val keyStore: KeyStore by lazy { loadOrCreate() }

    val certificate: X509Certificate
        get() = keyStore.getCertificate(ALIAS) as X509Certificate

    /** Uppercase hex SHA-256 of the DER certificate, colon-separated. */
    val fingerprint: String by lazy {
        MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .toHexString(
                HexFormat {
                    upperCase = true
                    bytes.byteSeparator = ":"
                }
            )
    }

    private fun loadOrCreate(): KeyStore {
        val ks = KeyStore.getInstance("PKCS12")
        if (file.exists()) {
            try {
                file.inputStream().use { ks.load(it, password) }
                return ks
            } catch (e: IOException) {
                Log.w(TAG, "TLS keystore unreadable; generating a new certificate", e)
            }
        }
        val keyPair =
            KeyPairGenerator.getInstance("EC").run {
                initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
                generateKeyPair()
            }
        val now = System.currentTimeMillis()
        val name = X500Name("CN=Android device")
        val holder =
            JcaX509v3CertificateBuilder(
                    name,
                    BigInteger(64, SecureRandom()),
                    Date(now - CLOCK_SKEW_MS),
                    Date(now + VALIDITY_MS),
                    name,
                    keyPair.public,
                )
                .build(JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private))
        val cert = JcaX509CertificateConverter().getCertificate(holder)
        ks.load(null, null)
        ks.setKeyEntry(ALIAS, keyPair.private, password, arrayOf(cert))
        dir.mkdirs()
        val tmp = File(dir, "tls.p12.tmp")
        tmp.outputStream().use { ks.store(it, password) }
        check(tmp.renameTo(file)) { "Failed to persist TLS keystore" }
        return ks
    }

    companion object {
        const val ALIAS = "server"
        private const val TAG = "PhoneApiTls"
        private const val CLOCK_SKEW_MS = 24L * 60 * 60 * 1000
        private const val VALIDITY_MS = 20L * 365 * 24 * 60 * 60 * 1000
    }
}
