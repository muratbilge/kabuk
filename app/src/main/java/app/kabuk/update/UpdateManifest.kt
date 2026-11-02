package app.kabuk.update

import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** What a verified update.json says about the offered build. */
data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val apk: String,
    val sha256: String,
    val size: Long,
    val notes: String,
    val packageName: String,
    val minSdk: Int,
    val certificateSha256: String,
)

class UpdateException(message: String) : Exception(message)

/**
 * The public key is compiled into the app, never downloaded: whoever controls the update server can offer a build
 * but cannot make the app trust it. Format identical to Paperscanner's:
 * {"payload": base64(json), "signature": base64(RSA-SHA256 over the payload bytes)}.
 */
class ManifestVerifier(publicKeyDer: ByteArray, private val packageName: String, private val certificateSha256: String) {
    private val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKeyDer)) as RSAPublicKey

    init {
        require(key.modulus.bitLength() >= 3072) { "update key too weak" }
    }

    /** @throws UpdateException when the envelope is malformed, badly signed or for another app / signer */
    fun verify(bytes: ByteArray): UpdateManifest {
        try {
            require(bytes.size <= MAX_MANIFEST_BYTES)
            val env = JSONObject(bytes.decodeToString())
            require(env.keys().asSequence().toSet() == setOf("payload", "signature"))
            val payload = Base64.getDecoder().decode(env.getString("payload"))
            val signature = Base64.getDecoder().decode(env.getString("signature"))
            val sig = Signature.getInstance("SHA256withRSA")
            sig.initVerify(key)
            sig.update(payload)
            require(sig.verify(signature))
            return parse(JSONObject(payload.decodeToString()))
        } catch (e: UpdateException) {
            throw e
        } catch (e: Exception) {
            throw UpdateException("update.json is not trusted")
        }
    }

    private fun parse(j: JSONObject): UpdateManifest {
        val m = UpdateManifest(
            versionCode = j.getLong("versionCode"),
            versionName = j.getString("versionName"),
            apk = j.getString("apk"),
            sha256 = j.getString("sha256"),
            size = j.getLong("size"),
            notes = j.getString("notes"),
            packageName = j.getString("packageName"),
            minSdk = j.getInt("minSdk"),
            certificateSha256 = j.getString("certificateSha256"),
        )
        require(m.versionCode > 0 && m.size in 1..MAX_APK_BYTES && m.minSdk >= 26)
        require(m.apk == "kabuk-${m.versionCode}.apk")           // no path, no other file
        require(HASH.matches(m.sha256) && HASH.matches(m.certificateSha256))
        require(m.versionName.length in 1..100 && m.notes.length <= 4000)
        if (m.packageName != packageName || m.certificateSha256 != certificateSha256) {
            throw UpdateException("update is for another app or signer")
        }
        return m
    }

    companion object {
        const val MAX_MANIFEST_BYTES = 64 * 1024
        const val MAX_APK_BYTES = 128L * 1024 * 1024
        private val HASH = Regex("[a-f0-9]{64}")
    }
}
