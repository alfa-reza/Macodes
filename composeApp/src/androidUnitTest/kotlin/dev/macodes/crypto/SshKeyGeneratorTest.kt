package dev.macodes.crypto

import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import dev.macodes.ssh.isEncryptedPem
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SshKeyGeneratorTest {
    @Test
    fun generatesValidUnencryptedOpenSshEd25519KeyPairAcceptedBySshj() {
        val pair = generateOpenSshEd25519KeyPair(comment = "deploy@macodes")
        assertTrue(pair.privateKeyPem.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----\n"))
        assertTrue(pair.privateKeyPem.trimEnd().endsWith("-----END OPENSSH PRIVATE KEY-----"))
        assertFalse(isEncryptedPem(pair.privateKeyPem))
        assertTrue(pair.publicKeyLine.startsWith("ssh-ed25519 "))
        assertTrue(pair.publicKeyLine.endsWith(" deploy@macodes"))

        val provider = OpenSSHKeyV1KeyFile()
        provider.init(StringReader(pair.privateKeyPem), null, null)
        assertNotNull(provider.public)
        assertNotNull(provider.private)

        val second = generateOpenSshEd25519KeyPair(comment = "")
        assertNotEquals(pair.privateKeyPem, second.privateKeyPem)
        assertNotEquals(pair.publicKeyLine, second.publicKeyLine)
        assertEquals("macodes", second.publicKeyLine.substringAfterLast(' '))
    }
}
