package com.bitchat.mesh

import android.content.Context
import com.bitchat.crypto.CryptoEngine
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Group control messages (create/info, delete, kick) used to be plain JSON, so a relay could
 * rewrite the packet's src to the creator's node id and have delete/kick accepted, or spin up
 * a group under a spoofed creator. Signing the payload with the sender's ed25519 key - which
 * is bound to the node id - means a command is only acted on when it really came from that
 * identity.
 */
@RunWith(RobolectricTestRunner::class)
class GroupControlTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun reset() {
        context.getSharedPreferences("bitchat_keys", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("bitchat_recovery", Context.MODE_PRIVATE)
            .edit().clear().commit()
        CryptoEngine.init(context)
    }

    @Test
    fun aControlSignedByANodeParsesForThatNode() {
        val json = JSONObject().put("g", "group-1").put("m", "member-1")
        val signed = GroupControl.sign(CryptoEngine.nodeId(), json)

        val parsed = GroupControl.parse(CryptoEngine.nodeId(), signed)
        assertEquals("group-1", parsed!!.getString("g"))
        assertEquals("member-1", parsed.getString("m"))
    }

    @Test
    fun aControlIsRejectedWhenTheClaimedSenderIsAnotherNode() {
        val signed = GroupControl.sign(CryptoEngine.nodeId(), JSONObject().put("g", "group-1"))

        assertNull(GroupControl.parse("aa".repeat(16), signed))
    }

    @Test
    fun aTamperedControlIsRejected() {
        val signed = GroupControl.sign(CryptoEngine.nodeId(), JSONObject().put("g", "group-1"))
        signed[signed.lastIndex] = (signed.last().toInt() xor 0x01).toByte()

        assertNull(GroupControl.parse(CryptoEngine.nodeId(), signed))
    }
}
