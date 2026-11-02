package app.kabuk

import app.kabuk.update.ManifestVerifier
import app.kabuk.update.UpdateException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.BeforeClass
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class UpdateManifestTest {
    companion object {
        lateinit var key: KeyPair
        lateinit var other: KeyPair
        val cert = "a".repeat(64)

        @BeforeClass @JvmStatic fun keys() {
            val g = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }
            key = g.generateKeyPair(); other = g.generateKeyPair()
        }
    }

    private fun payload(code: Int = 22, apk: String = "kabuk-$code.apk", pkg: String = "app.kabuk", certHash: String = cert) =
        JSONObject().put("packageName", pkg).put("versionCode", code).put("versionName", "0.5.0").put("minSdk", 26)
            .put("certificateSha256", certHash).put("apk", apk).put("size", 1234).put("sha256", "b".repeat(64))
            .put("notes", "hello").toString().toByteArray()

    private fun envelope(payload: ByteArray, signer: KeyPair = key): ByteArray {
        val s = Signature.getInstance("SHA256withRSA").apply { initSign(signer.private); update(payload) }.sign()
        val e = Base64.getEncoder()
        return JSONObject().put("payload", e.encodeToString(payload)).put("signature", e.encodeToString(s)).toString().toByteArray()
    }

    private fun verifier(k: KeyPair = key) = ManifestVerifier(k.public.encoded, "app.kabuk", cert)

    @Test fun acceptsSignedManifest() {
        val m = verifier().verify(envelope(payload()))
        assertEquals(22L, m.versionCode); assertEquals("0.5.0", m.versionName); assertEquals("hello", m.notes)
        assertEquals("kabuk-22.apk", m.apk)
    }

    @Test fun rejectsWrongKey() {
        assertThrows(UpdateException::class.java) { verifier(other).verify(envelope(payload())) }
        assertThrows(UpdateException::class.java) { verifier().verify(envelope(payload(), signer = other)) }
    }

    @Test fun rejectsTamperedPayload() {
        val good = JSONObject(String(envelope(payload())))
        good.put("payload", Base64.getEncoder().encodeToString(payload(code = 99)))
        assertThrows(UpdateException::class.java) { verifier().verify(good.toString().toByteArray()) }
    }

    @Test fun rejectsOtherAppSignerOrFile() {
        assertThrows(UpdateException::class.java) { verifier().verify(envelope(payload(pkg = "evil.app"))) }
        assertThrows(UpdateException::class.java) { verifier().verify(envelope(payload(certHash = "c".repeat(64)))) }
        assertThrows(UpdateException::class.java) { verifier().verify(envelope(payload(apk = "../x.apk"))) }
        assertThrows(UpdateException::class.java) { verifier().verify("not json".toByteArray()) }
    }
}
