package app.kabuk.ssh

import android.content.Context
import android.os.Build
import app.kabuk.data.SecretBox
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import java.security.SecureRandom
import java.util.Base64

/** The phone's own Ed25519 SSH key. The private half is stored encrypted with the Keystore. */
object KeyPairStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("sshkey", Context.MODE_PRIVATE)

    fun publicKey(ctx: Context): String {
        ensure(ctx)
        return prefs(ctx).getString("pub", "")!!
    }

    /** OpenSSH-format private key (PEM text) */
    fun privateKey(ctx: Context): String {
        ensure(ctx)
        return SecretBox.decrypt(prefs(ctx).getString("privEnc", "")!!)
            ?: error("could not decrypt the SSH key (Keystore reset?) - create a new key in Settings")
    }

    fun regenerate(ctx: Context) {
        prefs(ctx).edit().clear().commit()
        ensure(ctx)
    }

    @Synchronized
    private fun ensure(ctx: Context) {
        val p = prefs(ctx)
        if (p.contains("pub")) return
        val (priv, pub) = generate("kabuk@" + Build.MODEL.replace(' ', '-'))
        p.edit().putString("pub", pub).putString("privEnc", SecretBox.encrypt(priv)).commit()
    }

    /** returns (OpenSSH private key PEM, "ssh-ed25519 AAAA... comment") */
    fun generate(comment: String): Pair<String, String> {
        val gen = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(SecureRandom())) }
        val kp = gen.generateKeyPair()
        val priv = kp.private as Ed25519PrivateKeyParameters
        val pub = kp.public as Ed25519PublicKeyParameters
        val body = Base64.getMimeEncoder(70, "\n".toByteArray()).encodeToString(OpenSSHPrivateKeyUtil.encodePrivateKey(priv))
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n$body\n-----END OPENSSH PRIVATE KEY-----\n"
        val pubLine = "ssh-ed25519 " + Base64.getEncoder().encodeToString(OpenSSHPublicKeyUtil.encodePublicKey(pub)) + " " + comment
        return pem to pubLine
    }
}
