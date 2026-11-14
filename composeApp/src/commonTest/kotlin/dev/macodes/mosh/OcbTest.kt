package dev.macodes.mosh

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

/**
 * The expected values are derived from the independent AES-128-OCB standard test vectors: 96-bit nonce / 128-bit tag / no AD, consistent with the production environment.
 */
class OcbTest {
    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val key = hex("000102030405060708090A0B0C0D0E0F")

    @Test
    fun emptyPlaintext() {
        val out = Ocb(key).encrypt(hex("BBAA99887766554433221100"), ByteArray(0))
        assertContentEquals(hex("785407BFFFC8AD9EDCC5520AC9111EE6"), out)
    }

    @Test
    fun eightBytes() {
        val out = Ocb(key).encrypt(hex("BBAA99887766554433221101"), hex("0001020304050607"))
        assertContentEquals(hex("6820B3657B6F615AD6E734CE5F69DEA19FC35214F5795FAA"), out)
    }

    @Test
    fun sixteenBytes() {
        val out =
            Ocb(key).encrypt(
                hex("BBAA99887766554433221102"),
                hex("000102030405060708090A0B0C0D0E0F"),
            )
        assertContentEquals(
            hex("C050A7E919AA5643BFF595B66ACC106C92537991AB4B8C84A250F74868833FB8"),
            out,
        )
    }

    @Test
    fun twentyFourBytes() {
        // A single block + 8-byte trailer
        val out =
            Ocb(key).encrypt(
                hex("BBAA99887766554433221103"),
                hex("000102030405060708090A0B0C0D0E0F1011121314151617"),
            )
        assertContentEquals(
            hex("1591E0EC9E6FC5A83475F939906EB53E5E93E9CFEEEC495FA4E32618DD97FAD9A80F61C1A055EBA7"),
            out,
        )
    }

    @Test
    fun thirtyThreeBytes() {
        // Two full blocks + a 1-byte trailer
        val out =
            Ocb(key).encrypt(
                hex("BBAA99887766554433221104"),
                hex("000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F20"),
            )
        assertContentEquals(
            hex(
                "571D535B60B277188BE5147170A9A22CDB9EF96F538354AF0E6E5D7F6F640AF83" +
                    "F30EB393BC18541A8BEB2883475DF7A62",
            ),
            out,
        )
    }

    @Test
    fun decryptRoundTrip() {
        val ocb = Ocb(key)
        val plain = "mosh over kotlin multiplatform 多平台".encodeToByteArray()
        val nonce = hex("BBAA9988776655443322110F")
        val ct = ocb.encrypt(nonce, plain)
        assertContentEquals(plain, ocb.decrypt(nonce, ct))
    }

    /**
     * Independent decryption vector: Use the standard `encrypt` vector to verify the decryption.
     * Round-trip verification only proves that encryption and decryption are consistent; here, using standard vector output also verifies that the direction is correct.
     */
    @Test
    fun decryptAgainstReferenceVectors() {
        val ocb = Ocb(key)
        assertContentEquals(
            ByteArray(0),
            ocb.decrypt(hex("BBAA99887766554433221100"), hex("785407BFFFC8AD9EDCC5520AC9111EE6")),
        )
        assertContentEquals(
            hex("0001020304050607"),
            ocb.decrypt(hex("BBAA99887766554433221101"), hex("6820B3657B6F615AD6E734CE5F69DEA19FC35214F5795FAA")),
        )
        assertContentEquals(
            hex("000102030405060708090A0B0C0D0E0F"),
            ocb.decrypt(
                hex("BBAA99887766554433221102"),
                hex("C050A7E919AA5643BFF595B66ACC106C92537991AB4B8C84A250F74868833FB8"),
            ),
        )
        assertContentEquals(
            hex("000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F20"),
            ocb.decrypt(
                hex("BBAA99887766554433221104"),
                hex(
                    "571D535B60B277188BE5147170A9A22CDB9EF96F538354AF0E6E5D7F6F640AF83" +
                        "F30EB393BC18541A8BEB2883475DF7A62",
                ),
            ),
        )
    }

    @Test
    fun decryptRejectsCorruptedTag() {
        val ocb = Ocb(key)
        val nonce = hex("BBAA9988776655443322110E")
        val ct = ocb.encrypt(nonce, "hello".encodeToByteArray())
        ct[ct.size - 1] = (ct[ct.size - 1].toInt() xor 1).toByte()
        assertNull(ocb.decrypt(nonce, ct))
    }
}
