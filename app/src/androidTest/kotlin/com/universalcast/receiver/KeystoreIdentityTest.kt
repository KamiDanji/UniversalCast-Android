package com.universalcast.receiver

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.universalcast.receiver.platform.KeystoreIdentityProvider
import com.universalcast.security.EcKeys
import com.universalcast.security.Fingerprint
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class KeystoreIdentityTest {
    private val alias = "ucp_test_identity"
    private val storeAlias = "ucp_test_store"

    @Test fun createsStableNonExportableIdentityAndSignsVerifiably() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias); deleteEntry(storeAlias) }
        val p = KeystoreIdentityProvider(alias, storeAlias)
        val a = p.loadOrCreate()
        assertTrue(a.regenerated)
        val b = KeystoreIdentityProvider(alias, storeAlias).loadOrCreate()
        assertFalse(b.regenerated, "existing valid key must be reused")
        assertEquals(a.identity.fingerprint, b.identity.fingerprint)
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null)
        assertNull(key.encoded, "private key must not be extractable")
        val msg = "hello".toByteArray()
        assertTrue(EcKeys.verify(a.identity.publicKeySpki, msg, a.identity.sign(msg)))
        assertTrue(Fingerprint.isWellFormed(a.identity.fingerprint))
    }

    @Test fun storeAeadRoundTripsAndRejectsTampering() {
        val aead = KeystoreIdentityProvider(alias, storeAlias).storeAead()
        val sealed = aead.seal("secret".toByteArray(), "aad".toByteArray())
        assertEquals("secret", String(aead.open(sealed, "aad".toByteArray())))
        sealed[sealed.size - 1] = (sealed.last().toInt() xor 1).toByte()
        assertFailsWith<Exception> { aead.open(sealed, "aad".toByteArray()) }
    }
}
