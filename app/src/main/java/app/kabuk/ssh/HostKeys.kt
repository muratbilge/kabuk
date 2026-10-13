package app.kabuk.ssh

import android.content.Context
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

/**
 * Trust on first use, like OpenSSH's known_hosts: the first key a server shows is confirmed by the user and
 * remembered; a different key later is a loud warning that the user must explicitly accept.
 */
class HostKeys(ctx: Context, private val ask: (title: String, message: String, danger: Boolean) -> Boolean) : HostKeyVerifier {
    private val prefs = ctx.getSharedPreferences("known_hosts", Context.MODE_PRIVATE)

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val id = "$hostname:$port"
        val type = KeyType.fromKey(key).toString()
        val blob = Base64.getEncoder().encodeToString(Buffer.PlainBuffer().putPublicKey(key).compactData)
        val stored = prefs.getString(id, null)
        val now = "$type $blob"
        if (stored == now) return true
        val fp = fingerprint(key)
        val ok = if (stored == null) {
            ask("New server", "First connection to $id.\n\n$type\n$fp\n\nTrust this server?", false)
        } else {
            ask(
                "SERVER KEY CHANGED",
                "The key of $id is different from last time. This happens after reinstalling the server, " +
                    "but it can also mean someone is intercepting the connection.\n\nNew key:\n$type\n$fp\n\nOnly accept if you know why it changed.",
                true,
            )
        }
        if (ok) prefs.edit().putString(id, now).apply()
        return ok
    }

    /** prefer the key type we already know, so the server doesn't show a different (unknown) key */
    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
        when (val type = prefs.getString("$hostname:$port", null)?.substringBefore(' ')) {
            null -> emptyList()
            // an RSA host key is negotiated as rsa-sha2-*; plain ssh-rsa (SHA-1) is refused by modern servers
            "ssh-rsa" -> listOf("rsa-sha2-512", "rsa-sha2-256", "ssh-rsa")
            else -> listOf(type)
        }

    companion object {
        fun fingerprint(key: PublicKey): String {
            val d = MessageDigest.getInstance("SHA-256").digest(Buffer.PlainBuffer().putPublicKey(key).compactData)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(d)
        }
    }
}
