package app.kabuk

import android.app.Application
import app.kabuk.data.Settings
import app.kabuk.term.TabManager
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class KabukApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Android ships a cut-down "BC" provider; sshj needs the full one (Ed25519, curve25519, ...).
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        Settings.init(this)
        TabManager.init(this)
    }
}
