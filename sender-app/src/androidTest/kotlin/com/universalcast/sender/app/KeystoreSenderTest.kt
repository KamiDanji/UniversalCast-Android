package com.universalcast.sender.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.universalcast.security.EcKeys
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class KeystoreSenderTest {
    private val alias = "ucp_test_sender_identity"
    private val storeAlias = "ucp_test_sender_store"

    @Test fun identityIsStableNonExportableAndSignsVerifiably() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias); deleteEntry(storeAlias) }
        val a = KeystoreSenderIdentity.loadOrCreate(alias)
        assertTrue(a.regenerated)
        val b = KeystoreSenderIdentity.loadOrCreate(alias)
        assertFalse(b.regenerated)
        assertEquals(a.senderId, b.senderId)
        assertNull(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null).encoded)
        val msg = "challenge".toByteArray()
        assertTrue(EcKeys.verify(a.publicKeySpki, msg, a.sign(msg)))
        assertFalse(EcKeys.verify(a.publicKeySpki, "other".toByteArray(), a.sign(msg)))
    }

    @Test fun storeKeyRoundTripsAndDetectsTampering() {
        val aead = KeystoreStoreKey.aead(storeAlias)
        val sealed = aead.seal("tv list".toByteArray(), "aad".toByteArray())
        assertEquals("tv list", String(aead.open(sealed, "aad".toByteArray())))
        sealed[sealed.size - 1] = (sealed.last().toInt() xor 1).toByte()
        assertFailsWith<Exception> { aead.open(sealed, "aad".toByteArray()) }
    }
}
