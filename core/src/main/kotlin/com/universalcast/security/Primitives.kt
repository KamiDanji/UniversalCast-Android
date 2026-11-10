package com.universalcast.security

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

interface TimeSource {
    fun nowMs(): Long
}

object SystemTimeSource : TimeSource {
    override fun nowMs(): Long = System.currentTimeMillis()
}

object Bytes {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private const val HEX = "0123456789abcdef"

    fun b64(data: ByteArray): String = encoder.encodeToString(data)

    /** @throws IllegalArgumentException when [text] is not base64url. */
    fun unb64(text: String): ByteArray = decoder.decode(text)

    fun hex(data: ByteArray): String {
        val sb = StringBuilder(data.size * 2)
        for (b in data) {
            sb.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        }
        return sb.toString()
    }

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }
}

object SecureRandoms {
    private val rng = SecureRandom()
    fun bytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }
}

object Hashing {
    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}

object Hmac {
    fun sha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}

/** HKDF-SHA256 as specified in RFC 5869. */
object Hkdf {
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray =
        Hmac.sha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * 32)) { "Invalid HKDF length" }
        val out = ByteArrayOutputStream()
        var previous = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            previous = Hmac.sha256(prk, Bytes.concat(previous, info, byteArrayOf(counter.toByte())))
            out.write(previous)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        expand(extract(salt, ikm), info, length)
}

/**
 * Unambiguous message builder for signatures and MACs: every field is length-prefixed, and every
 * transcript starts with a distinct ASCII label (domain separation), so one signed structure can never be
 * reinterpreted as another.
 */
class Transcript(label: String) {
    private val out = ByteArrayOutputStream()

    init {
        add(label)
    }

    fun add(data: ByteArray): Transcript {
        out.write(ByteBuffer.allocate(4).putInt(data.size).array())
        out.write(data)
        return this
    }

    fun add(text: String): Transcript = add(text.toByteArray(Charsets.UTF_8))
    fun add(value: Long): Transcript = add(ByteBuffer.allocate(8).putLong(value).array())
    fun toBytes(): ByteArray = out.toByteArray()
}

object Fingerprint {
    /** The stable identity of a key: lowercase hex SHA-256 of its DER SubjectPublicKeyInfo. */
    fun ofSpki(spki: ByteArray): String = Bytes.hex(Hashing.sha256(spki))

    fun isWellFormed(fp: String): Boolean = fp.length == 64 && fp.all { it in '0'..'9' || it in 'a'..'f' }

    /** Human-readable form, e.g. "AB:CD:EF:...". */
    fun display(fp: String): String = fp.uppercase().chunked(2).joinToString(":")
}

/** EC P-256 helpers. Only secp256r1 keys are accepted anywhere in the protocol. */
object EcKeys {
    private val P = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
    private val B = BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16)
    private val ORDER = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)

    fun generateKeyPair(): KeyPair {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        return g.generateKeyPair()
    }

    /** Decodes and fully validates an SPKI-encoded P-256 public key (curve parameters and point-on-curve). */
    fun decodePublicKey(spki: ByteArray): PublicKey {
        val key = try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
        } catch (e: GeneralSecurityException) {
            throw IllegalArgumentException("Invalid public key encoding")
        }
        val ec = key as? ECPublicKey ?: throw IllegalArgumentException("Not an EC public key")
        val params = ec.params
        val field = params.curve.field as? ECFieldFp ?: throw IllegalArgumentException("Unsupported curve")
        if (field.p != P || params.order != ORDER || params.cofactor != 1 ||
            params.curve.b != B || params.curve.a != P.subtract(BigInteger.valueOf(3))
        ) throw IllegalArgumentException("Unsupported curve (only secp256r1 is allowed)")
        val x = ec.w.affineX
        val y = ec.w.affineY
        if (x.signum() < 0 || x >= P || y.signum() < 0 || y >= P) throw IllegalArgumentException("Point out of range")
        val lhs = y.multiply(y).mod(P)
        val rhs = x.pow(3).subtract(x.multiply(BigInteger.valueOf(3))).add(B).mod(P)
        if (lhs != rhs) throw IllegalArgumentException("Point is not on the curve")
        return key
    }

    fun sign(privateKey: PrivateKey, data: ByteArray): ByteArray {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(privateKey)
        s.update(data)
        return s.sign()
    }

    /** Returns false for any malformed key, malformed signature or mismatch; never throws. */
    fun verify(spki: ByteArray, data: ByteArray, signature: ByteArray): Boolean = try {
        if (signature.isEmpty() || signature.size > 80) {
            false
        } else {
            val s = Signature.getInstance("SHA256withECDSA")
            s.initVerify(decodePublicKey(spki))
            s.update(data)
            s.verify(signature)
        }
    } catch (e: Exception) {
        false
    }

    /** ECDH shared secret (raw x-coordinate, 32 bytes) with a validated peer key. */
    fun agree(privateKey: PrivateKey, peerSpki: ByteArray): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(decodePublicKey(peerSpki), true)
        return ka.generateSecret()
    }
}
