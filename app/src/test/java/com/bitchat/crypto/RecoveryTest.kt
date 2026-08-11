package com.bitchat.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryTest {

    private fun seed(vararg b: Int): ByteArray = ByteArray(18) { i -> if (i < b.size) b[i].toByte() else 0 }

    @Test
    fun roundtrip_encode_seedFromKey() {
        val s = seed(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18)
        val key = Recovery.withChecksum(Recovery.encode(s))
        assertTrue(key.length == 30)
        assertArrayEquals(s, Recovery.seedFromKey(key))
        assertNotNull(Recovery.parseOrNull(key))
    }

    @Test
    fun parse_tolerates_formatting() {
        val key = Recovery.withChecksum(Recovery.encode(seed(9, 9, 9)))
        assertEquals(key, Recovery.parseOrNull(Recovery.format(key)))
        assertEquals(key, Recovery.parseOrNull(key.lowercase()))
        assertEquals(key, Recovery.parseOrNull("   " + key.chunked(4).joinToString(" ") + "   "))
    }

    @Test
    fun parse_rejects_checksum_flip() {
        val key = Recovery.withChecksum(Recovery.encode(seed(5, 5, 5)))
        val flipped = key.take(15) + (if (key[15] == 'A') 'B' else 'A') + key.drop(16)
        assertNull(Recovery.parseOrNull(flipped))
    }

    @Test
    fun parse_rejects_wrong_length() {
        val key = Recovery.withChecksum(Recovery.encode(seed(7)))
        assertNull(Recovery.parseOrNull(key.dropLast(1)))
        assertNull(Recovery.parseOrNull(key + "A"))
    }

    @Test
    fun parse_rejects_ambiguous_chars() {
        val key = Recovery.withChecksum(Recovery.encode(seed(1, 2, 3, 4, 5, 6, 7, 8)))
        for (bad in listOf('0', 'O', '1', 'I')) {
            assertNull(Recovery.parseOrNull(key.dropLast(1) + bad))
        }
    }

    @Test
    fun encode_deterministic_and_unique() {
        val a = Recovery.encode(seed(1, 2, 3))
        val b = Recovery.encode(seed(1, 2, 3))
        val c = Recovery.encode(seed(1, 2, 4))
        assertEquals(a, b)
        assertTrue(a != c)
    }

    @Test
    fun nodeId_deterministic_32hex() {
        val s = seed(7, 7, 7)
        val id1 = Recovery.deriveNodeId(s)
        val id2 = Recovery.deriveNodeId(s)
        assertEquals(id1, id2)
        assertEquals(32, id1.length)
        assertTrue(id1.all { it in "0123456789abcdef" })
        assertTrue(id1 != Recovery.deriveNodeId(seed(7, 7, 8)))
    }

    @Test
    fun keys_derive_deterministically_from_seed() {
        val s = seed(4, 4, 4)
        val (x1, e1) = CryptoEngine.deriveKeysFromSeed(s)
        val (x2, e2) = CryptoEngine.deriveKeysFromSeed(s)
        assertArrayEquals(x1, x2)
        assertArrayEquals(e1, e2)
        assertEquals(32, x1.size)
        assertEquals(32, e1.size)

        val (x3, _) = CryptoEngine.deriveKeysFromSeed(seed(4, 4, 5))
        assertTrue(!x1.contentEquals(x3))
    }
}