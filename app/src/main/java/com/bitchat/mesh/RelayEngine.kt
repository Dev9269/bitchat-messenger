package com.bitchat.mesh

class RelayEngine {

    private val seen = HashMap<String, Long>()
    private val storeForward = HashMap<String, MutableMap<String, Pair<Long, ByteArray>>>()
    private val reassembly = HashMap<String, Buffer>()

    private class Buffer(val total: Int, val firstSeen: Long) {
        val parts = arrayOfNulls<ByteArray>(total)
        var received = 0

        fun add(idx: Int, data: ByteArray): Boolean {
            if (idx < 0 || idx >= total) return false
            if (parts[idx] != null) return false
            parts[idx] = data
            received++
            return received == total
        }

        fun join(): ByteArray {
            val size = parts.sumOf { it?.size ?: 0 }
            val out = ByteArray(size)
            var offset = 0
            for (part in parts) {
                part?.let {
                    it.copyInto(out, offset)
                    offset += it.size
                }
            }
            return out
        }
    }

    fun markSeen(msgIdHex: String) {
        seen[msgIdHex] = System.currentTimeMillis()
    }

    fun isNew(msgIdHex: String): Boolean {
        if (seen.containsKey(msgIdHex)) return false
        seen[msgIdHex] = System.currentTimeMillis()
        return true
    }

    fun addFragment(packet: MeshPacket.Packet): ByteArray? {
        if (packet.payload.size < 2) return null
        val total = packet.payload[0].toInt() and 0xFF
        val idx = packet.payload[1].toInt() and 0xFF
        val data = packet.payload.copyOfRange(2, packet.payload.size)
        if (total <= 1) return data
        val key = packet.msgId.hex()
        val buffer = reassembly.getOrPut(key) { Buffer(total, System.currentTimeMillis()) }
        if (buffer.total != total) return null
        return if (buffer.add(idx, data)) buffer.join() else null
    }

    fun cache(dst: String, bytes: ByteArray) {
        val msgIdHex = MeshPacket.decode(bytes)?.msgId?.hex() ?: return
        storeForward.getOrPut(dst) { HashMap() }[msgIdHex] = System.currentTimeMillis() to bytes
    }

    fun takeFor(dst: String): List<ByteArray> =
        storeForward.remove(dst)?.values?.map { it.second } ?: emptyList()

    fun prune() {
        val seenCutoff = System.currentTimeMillis() - 5 * 60_000
        seen.entries.removeAll { it.value < seenCutoff }
        val storeCutoff = System.currentTimeMillis() - 10 * 60_000
        storeForward.keys.forEach { dst ->
            storeForward[dst]?.entries?.removeAll { it.value.first < storeCutoff }
            if (storeForward[dst].isNullOrEmpty()) storeForward.remove(dst)
        }
        reassembly.entries.removeAll { it.value.firstSeen < seenCutoff }
    }

    fun clear() {
        seen.clear()
        storeForward.clear()
        reassembly.clear()
    }
}
