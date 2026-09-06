package com.carlom.klardrop.common.qrshare

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlin.time.Clock

internal data class QrTlsCertResult(
    val certDer: ByteArray,
    val privateKeyPkcs8Der: ByteArray,
    val pkcs12Der: ByteArray,
    val publicKeyRaw: ByteArray,
)

internal object QrTlsCertGenerator {

    const val PKCS12_PASSWORD = "qrshare"
    private const val PKCS12_MAC_ITERATIONS = 2048

    private val provider = CryptographyProvider.Default
    private val ecdsa = provider.get(ECDSA)

    suspend fun generate(
        ipv4: String,
        validityHours: Int = 24,
    ): QrTlsCertResult {
        val ipBytes = parseIpv4Bytes(ipv4)
        val keyPair = ecdsa.keyPairGenerator(EC.Curve.P256).generateKey()

        val spkiDer = keyPair.publicKey.encodeToByteArray(EC.PublicKey.Format.DER)
        val rawPub = keyPair.publicKey.encodeToByteArray(EC.PublicKey.Format.RAW)
        val pkcs8Priv = keyPair.privateKey.encodeToByteArray(EC.PrivateKey.Format.DER)

        val nowEpochSeconds = Clock.System.now().epochSeconds
        val notBeforeStr = epochSecondsToUtc(nowEpochSeconds - 300) // 5 minutes in the past
        val notAfterStr = epochSecondsToUtc(nowEpochSeconds + validityHours * 3600L)

        // 1. Version: v3 [0] EXPLICIT INTEGER 2
        val version = byteArrayOf(0xA0.toByte(), 0x03, 0x02, 0x01, 0x02)

        // 2. Serial number: positive 8 bytes
        val serialBytes = CryptographyRandom.nextBytes(8)
        val serial = derInteger(serialBytes)

        // 3. Signature algorithm identifier: ecdsa-with-SHA256 (1.2.840.10045.4.3.2)
        val sigAlg = derSequence(derOid("1.2.840.10045.4.3.2"))

        // 4. Issuer: CN = [ipv4]
        val cnRdn = derSet(derSequence(derOid("2.5.4.3"), derUtf8String(ipv4)))
        val issuer = derSequence(cnRdn)

        // 5. Validity: notBefore, notAfter
        val validity = derSequence(derUtcTime(notBeforeStr), derUtcTime(notAfterStr))

        // 6. Subject: CN = [ipv4]
        val subject = issuer

        // 7. SubjectPublicKeyInfo (spkiDer is already DER encoded)
        val spki = spkiDer

        // 8. Extensions [3] EXPLICIT
        // a) KeyUsage: digitalSignature (bit 0), critical = true
        val kuOid = derOid("2.5.29.15")
        val kuVal = derOctetString(derBitString(byteArrayOf(0x80.toByte()), unusedBits = 7))
        val kuExt = derSequence(kuOid, derBoolean(true), kuVal)

        // b) ExtendedKeyUsage: serverAuth (1.3.6.1.5.5.7.3.1)
        val ekuOid = derOid("2.5.29.37")
        val ekuVal = derOctetString(derSequence(derOid("1.3.6.1.5.5.7.3.1")))
        val ekuExt = derSequence(ekuOid, ekuVal)

        // c) SubjectAlternativeName: iPAddress = [ipv4] (GeneralName [7] primitive = 0x87)
        val sanOid = derOid("2.5.29.17")
        val gnIp = derTag(0x87.toByte(), ipBytes)
        val sanVal = derOctetString(derSequence(gnIp))
        val sanExt = derSequence(sanOid, sanVal)

        val extensions = derExplicit(3, derSequence(kuExt, ekuExt, sanExt))

        // TBSCertificate
        val tbsCertificate = derSequence(
            version,
            serial,
            sigAlg,
            issuer,
            validity,
            subject,
            spki,
            extensions
        )

        // Sign TBSCertificate with ECDSA P-256 + SHA-256 (format = DER)
        val sigGen = keyPair.privateKey.signatureGenerator(digest = SHA256, format = ECDSA.SignatureFormat.DER)
        val sigDer = sigGen.generateSignature(tbsCertificate)

        // Certificate ::= SEQUENCE { tbsCertificate, signatureAlgorithm, signatureValue BIT STRING }
        val certDer = derSequence(
            tbsCertificate,
            sigAlg,
            derBitString(sigDer)
        )

        // Build PKCS#12 (.p12) bundle
        val pkcs12Der = buildPkcs12(pkcs8Priv, certDer)

        return QrTlsCertResult(
            certDer = certDer,
            privateKeyPkcs8Der = pkcs8Priv,
            pkcs12Der = pkcs12Der,
            publicKeyRaw = rawPub,
        )
    }

    private fun buildPkcs12(pkcs8PrivateKey: ByteArray, certDer: ByteArray): ByteArray {
        val localKeyId = CryptographyRandom.nextBytes(4)
        val localKeyIdAttr = derSequence(
            derOid("1.2.840.113549.1.9.21"),
            derSet(derOctetString(localKeyId))
        )
        val friendlyNameAttr = derSequence(
            derOid("1.2.840.113549.1.9.20"),
            derSet(derBmpString("lan-share"))
        )
        val trustedUsageAttr = derSequence(
            derOid("2.16.840.1.113894.746875.1.1"),
            derSet(derOid("2.5.29.37.0"))
        )
        val keyBagAttrs = derSet(localKeyIdAttr, friendlyNameAttr)
        val certBagAttrs = derSet(localKeyIdAttr, friendlyNameAttr, trustedUsageAttr)

        // keyBag: OID 1.2.840.113549.1.12.10.1.1
        val keyBagOid = derOid("1.2.840.113549.1.12.10.1.1")
        val keyBagValue = derExplicit(0, pkcs8PrivateKey)
        val keyBag = derSequence(keyBagOid, keyBagValue, keyBagAttrs)

        // certBag: OID 1.2.840.113549.1.12.10.1.3
        val certBagOid = derOid("1.2.840.113549.1.12.10.1.3")
        val x509CertOid = derOid("1.2.840.113549.1.9.22.1")
        val certBagContent = derSequence(x509CertOid, derExplicit(0, derOctetString(certDer)))
        val certBagValue = derExplicit(0, certBagContent)
        val certBag = derSequence(certBagOid, certBagValue, certBagAttrs)

        val safeContents1 = derSequence(keyBag)
        val safeContents2 = derSequence(certBag)

        val pkcs7DataOid = derOid("1.2.840.113549.1.7.1")
        val cinfo1 = derSequence(pkcs7DataOid, derExplicit(0, derOctetString(safeContents1)))
        val cinfo2 = derSequence(pkcs7DataOid, derExplicit(0, derOctetString(safeContents2)))

        val authSafe = derSequence(cinfo1, cinfo2)
        val pfxAuthSafe = derSequence(pkcs7DataOid, derExplicit(0, derOctetString(authSafe)))

        // PKCS#12 MAC: derived key via RFC 7292 Appendix B (SHA-1) and HMAC-SHA1 over authSafe
        val salt = CryptographyRandom.nextBytes(8)
        val macKey = pkcs12DeriveMacKey(PKCS12_PASSWORD, salt, PKCS12_MAC_ITERATIONS)
        val macDigest = HmacSha1.mac(macKey, authSafe)

        val sha1Oid = derOid("1.3.14.3.2.26")
        val digestAlg = derSequence(sha1Oid, derNull())
        val digestInfo = derSequence(digestAlg, derOctetString(macDigest))
        val macData = derSequence(
            digestInfo,
            derOctetString(salt),
            derInteger(PKCS12_MAC_ITERATIONS)
        )

        // PFX ::= SEQUENCE { version INTEGER 3, authSafe ContentInfo, macData MacData }
        return derSequence(derInteger(3), pfxAuthSafe, macData)
    }

    private fun parseIpv4Bytes(ip: String): ByteArray {
        val parts = ip.trim().split('.')
        require(parts.size == 4) { "Invalid IPv4 address: $ip" }
        return ByteArray(4) { i ->
            val num = parts[i].toIntOrNull()
            require(num != null && num in 0..255) { "Invalid IPv4 segment: ${parts[i]}" }
            num.toByte()
        }
    }

    private fun epochSecondsToUtc(epochSeconds: Long): String {
        val days = (epochSeconds / 86400) + 719468
        val remSec = (epochSeconds % 86400).let { if (it < 0) it + 86400 else it }
        val hour = remSec / 3600
        val minute = (remSec % 3600) / 60
        val second = remSec % 60

        val era = (if (days >= 0) days else days - 146096) / 146097
        val doe = days - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        var year = (yoe + era * 400).toInt()
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
        if (month <= 2) year += 1
        val yy = (year % 100)

        fun pad2(n: Int): String = if (n < 10) "0$n" else "$n"
        return "${pad2(yy)}${pad2(month)}${pad2(day)}${pad2(hour.toInt())}${pad2(minute.toInt())}${pad2(second.toInt())}Z"
    }

    private fun derLength(len: Int): ByteArray {
        if (len < 0x80) return byteArrayOf(len.toByte())
        val out = mutableListOf<Byte>()
        var n = len
        while (n > 0) {
            out.add(0, (n and 0xFF).toByte())
            n = n ushr 8
        }
        return byteArrayOf((0x80 or out.size).toByte()) + out.toByteArray()
    }

    private fun derTag(tag: Byte, content: ByteArray): ByteArray =
        byteArrayOf(tag) + derLength(content.size) + content

    private fun derSequence(vararg items: ByteArray): ByteArray {
        val totalSize = items.sumOf { it.size }
        val out = ByteArray(totalSize)
        var offset = 0
        for (item in items) {
            item.copyInto(out, offset)
            offset += item.size
        }
        return derTag(0x30.toByte(), out)
    }

    private fun derSet(vararg items: ByteArray): ByteArray {
        val totalSize = items.sumOf { it.size }
        val out = ByteArray(totalSize)
        var offset = 0
        for (item in items) {
            item.copyInto(out, offset)
            offset += item.size
        }
        return derTag(0x31.toByte(), out)
    }

    private fun derInteger(value: Int): ByteArray {
        if (value == 0) return byteArrayOf(0x02, 0x01, 0x00)
        var v = value
        val bytes = mutableListOf<Byte>()
        while (v > 0) {
            bytes.add(0, (v and 0xFF).toByte())
            v = v ushr 8
        }
        return derInteger(bytes.toByteArray())
    }

    private fun derInteger(bytes: ByteArray): ByteArray {
        var start = 0
        while (start < bytes.size - 1 && bytes[start] == 0.toByte() && (bytes[start + 1].toInt() and 0x80) == 0) {
            start++
        }
        val trimmed = bytes.copyOfRange(start, bytes.size)
        val needsLeadingZero = (trimmed[0].toInt() and 0x80) != 0
        val body = if (needsLeadingZero) byteArrayOf(0) + trimmed else trimmed
        return derTag(0x02.toByte(), body)
    }

    private fun derBoolean(value: Boolean): ByteArray =
        byteArrayOf(0x01, 0x01, if (value) 0xFF.toByte() else 0x00)

    private fun derNull(): ByteArray =
        byteArrayOf(0x05, 0x00)

    private fun derBitString(bytes: ByteArray, unusedBits: Int = 0): ByteArray =
        byteArrayOf(0x03) + derLength(bytes.size + 1) + byteArrayOf(unusedBits.toByte()) + bytes

    private fun derOctetString(bytes: ByteArray): ByteArray =
        derTag(0x04.toByte(), bytes)

    private fun derUtf8String(str: String): ByteArray =
        derTag(0x0C.toByte(), str.encodeToByteArray())

    private fun derBmpString(str: String): ByteArray {
        val bytes = ByteArray(str.length * 2)
        for (i in str.indices) {
            val c = str[i].code
            bytes[i * 2] = (c ushr 8).toByte()
            bytes[i * 2 + 1] = (c and 0xFF).toByte()
        }
        return derTag(0x1E.toByte(), bytes)
    }

    private fun derUtcTime(str: String): ByteArray =
        derTag(0x17.toByte(), str.encodeToByteArray())

    private fun derExplicit(tagNumber: Int, content: ByteArray): ByteArray =
        byteArrayOf((0xA0 or tagNumber).toByte()) + derLength(content.size) + content

    private fun derOid(oidStr: String): ByteArray {
        val parts = oidStr.split('.').map { it.toLong() }
        require(parts.size >= 2) { "Invalid OID: $oidStr" }
        val firstByte = (parts[0] * 40 + parts[1]).toByte()
        val body = mutableListOf<Byte>()
        body.add(firstByte)
        for (i in 2 until parts.size) {
            var v = parts[i]
            if (v == 0L) {
                body.add(0.toByte())
            } else {
                val chunks = mutableListOf<Byte>()
                chunks.add((v and 0x7F).toByte())
                v = v ushr 7
                while (v > 0L) {
                    chunks.add(0, ((v and 0x7F) or 0x80).toByte())
                    v = v ushr 7
                }
                body.addAll(chunks)
            }
        }
        val bytes = body.toByteArray()
        return byteArrayOf(0x06) + derLength(bytes.size) + bytes
    }

    private fun pkcs12DeriveMacKey(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val v = 64 // SHA-1 block size
        val d = ByteArray(v) { 3.toByte() } // Diversion byte 0x03 for MAC key

        // Password as big-endian UTF-16 with trailing 0x00, 0x00
        val p = ByteArray((password.length + 1) * 2)
        for (i in password.indices) {
            val c = password[i].code
            p[i * 2] = (c ushr 8).toByte()
            p[i * 2 + 1] = (c and 0xFF).toByte()
        }

        val sLen = ((salt.size + v - 1) / v) * v
        val pLen = ((p.size + v - 1) / v) * v
        val iBytes = ByteArray(sLen + pLen)
        for (i in 0 until sLen) {
            iBytes[i] = salt[i % salt.size]
        }
        for (i in 0 until pLen) {
            iBytes[sLen + i] = p[i % p.size]
        }

        var a = Sha1.digest(d + iBytes)
        for (iter in 1 until iterations) {
            a = Sha1.digest(a)
        }
        return a
    }

    private object Sha1 {
        fun digest(data: ByteArray): ByteArray {
            var h0 = 0x67452301
            var h1 = 0xEFCDAB89.toInt()
            var h2 = 0x98BADCFE.toInt()
            var h3 = 0x10325476
            var h4 = 0xC3D2E1F0.toInt()

            val bitLength = data.size.toLong() * 8L
            val padLen = (56 - ((data.size + 1) % 64) + 64) % 64
            val padded = ByteArray(data.size + 1 + padLen + 8)
            data.copyInto(padded, 0)
            padded[data.size] = 0x80.toByte()
            for (i in 0 until 8) {
                padded[padded.size - 1 - i] = ((bitLength ushr (i * 8)) and 0xFF).toByte()
            }

            val w = IntArray(80)
            var offset = 0
            while (offset < padded.size) {
                for (i in 0 until 16) {
                    val j = offset + i * 4
                    w[i] = ((padded[j].toInt() and 0xFF) shl 24) or
                            ((padded[j + 1].toInt() and 0xFF) shl 16) or
                            ((padded[j + 2].toInt() and 0xFF) shl 8) or
                            (padded[j + 3].toInt() and 0xFF)
                }
                for (i in 16 until 80) {
                    val t = w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]
                    w[i] = (t shl 1) or (t ushr 31)
                }

                var a = h0
                var b = h1
                var c = h2
                var d = h3
                var e = h4

                for (i in 0 until 80) {
                    val f: Int
                    val k: Int
                    when {
                        i < 20 -> {
                            f = (b and c) or (b.inv() and d)
                            k = 0x5A827999
                        }
                        i < 40 -> {
                            f = b xor c xor d
                            k = 0x6ED9EBA1
                        }
                        i < 60 -> {
                            f = (b and c) or (b and d) or (c and d)
                            k = 0x8F1BBCDC.toInt()
                        }
                        else -> {
                            f = b xor c xor d
                            k = 0xCA62C1D6.toInt()
                        }
                    }
                    val temp = ((a shl 5) or (a ushr 27)) + f + e + k + w[i]
                    e = d
                    d = c
                    c = (b shl 30) or (b ushr 2)
                    b = a
                    a = temp
                }

                h0 += a
                h1 += b
                h2 += c
                h3 += d
                h4 += e

                offset += 64
            }

            val out = ByteArray(20)
            out[0] = (h0 ushr 24).toByte(); out[1] = (h0 ushr 16).toByte(); out[2] = (h0 ushr 8).toByte(); out[3] = h0.toByte()
            out[4] = (h1 ushr 24).toByte(); out[5] = (h1 ushr 16).toByte(); out[6] = (h1 ushr 8).toByte(); out[7] = h1.toByte()
            out[8] = (h2 ushr 24).toByte(); out[9] = (h2 ushr 16).toByte(); out[10] = (h2 ushr 8).toByte(); out[11] = h2.toByte()
            out[12] = (h3 ushr 24).toByte(); out[13] = (h3 ushr 16).toByte(); out[14] = (h3 ushr 8).toByte(); out[15] = h3.toByte()
            out[16] = (h4 ushr 24).toByte(); out[17] = (h4 ushr 16).toByte(); out[18] = (h4 ushr 8).toByte(); out[19] = h4.toByte()
            return out
        }
    }

    private object HmacSha1 {
        fun mac(key: ByteArray, data: ByteArray): ByteArray {
            val blockSize = 64
            val actualKey = ByteArray(blockSize)
            if (key.size > blockSize) {
                val h = Sha1.digest(key)
                h.copyInto(actualKey, 0)
            } else {
                key.copyInto(actualKey, 0)
            }
            val ipad = ByteArray(blockSize) { (actualKey[it].toInt() xor 0x36).toByte() }
            val opad = ByteArray(blockSize) { (actualKey[it].toInt() xor 0x5C).toByte() }

            val inner = Sha1.digest(ipad + data)
            return Sha1.digest(opad + inner)
        }
    }
}
