package com.bitchat.mesh

import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.ParcelUuid
import com.bitchat.crypto.CryptoEngine
import com.bitchat.data.DataGraph
import com.bitchat.data.MessageEntity
import com.bitchat.data.STATUS_DELIVERED
import com.bitchat.data.STATUS_PENDING
import com.bitchat.data.STATUS_SENT
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.security.MessageDigest

object MeshManager {

    private interface PacketChannel {
        fun send(bytes: ByteArray)
    }

    private class LinkChannel(private val link: MeshLink) : PacketChannel {
        override fun send(bytes: ByteArray) {
            link.write(bytes) {}
        }
    }

    private class ServerChannel(private val mac: String) : PacketChannel {
        override fun send(bytes: ByteArray) {
            gattServer?.sendTo(mac, bytes)
        }
    }

    private var appContext: Context? = null
    private var advertiser: MeshAdvertiser? = null
    private var scanner: MeshScanner? = null
    private var gattServer: MeshGattServer? = null
    private var job: Job? = null
    private val links = HashMap<String, MeshLink>()
    private val relay = RelayEngine()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val lastHandshakeSent = HashMap<String, Long>()

    val bluetoothEnabled = MutableStateFlow(false)
    val permissionsGranted = MutableStateFlow(false)
    val isScanning = MutableStateFlow(false)
    val isAdvertising = MutableStateFlow(false)
    val statusError = MutableStateFlow<String?>(null)
    val nodeId = MutableStateFlow("")
    val displayName = MutableStateFlow("")

    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    val peers: StateFlow<Map<String, Peer>> = _peers.asStateFlow()

    private val _links = MutableStateFlow<Map<String, MeshLink.State>>(emptyMap())
    val linkStates: StateFlow<Map<String, MeshLink.State>> = _links.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        nodeId.value = NodeIdentity.getNodeId(context)
        displayName.value = NodeIdentity.getDisplayName(context, nodeId.value)
        refreshRuntimeState(context)
    }

    fun setDisplayName(name: String) {
        val clean = name.trim().take(AdvertisePayload.MAX_NAME_BYTES)
        val context = appContext ?: return
        if (clean.isEmpty() || clean == displayName.value) return
        NodeIdentity.setDisplayName(context, clean)
        displayName.value = clean
        val adv = advertiser
        if (adv != null && isAdvertising.value) {
            adv.stop()
            advertiser = MeshAdvertiser(context).also { a ->
                a.start(nodeId.value, clean) { result ->
                    when (result) {
                        is MeshAdvertiser.Result.Started -> {
                            isAdvertising.value = true
                            statusError.value = null
                        }
                        is MeshAdvertiser.Result.Failed -> {
                            isAdvertising.value = false
                            statusError.value = result.reason
                        }
                    }
                }
            }
        }
    }

    fun refreshRuntimeState(context: Context) {
        bluetoothEnabled.value = BluetoothSupport.isEnabled(context)
        permissionsGranted.value = PermissionRequirements.allGranted(context)
    }

    fun start() {
        val context = appContext ?: return
        stop()
        statusError.value = null

        advertiser = MeshAdvertiser(context).also { a ->
            a.start(nodeId.value, displayName.value) { result ->
                when (result) {
                    is MeshAdvertiser.Result.Started -> {
                        isAdvertising.value = true
                        statusError.value = null
                    }
                    is MeshAdvertiser.Result.Failed -> {
                        isAdvertising.value = false
                        statusError.value = result.reason
                    }
                }
            }
        }

        scanner = MeshScanner(context).also { s ->
            s.start(::onScanResult) { error ->
                isScanning.value = false
                statusError.value = error
            }
            isScanning.value = s.isScanning
        }

        val server = MeshGattServer(context) { bytes, mac ->
            handlePacket(bytes, ServerChannel(mac))
        }
        if (server.open()) {
            gattServer = server
        } else {
            statusError.value = "Failed to start BLE server"
        }

        _peers.value = mapOf(
            nodeId.value to Peer(
                address = "",
                nodeId = nodeId.value,
                displayName = displayName.value,
                rssi = 0,
                lastSeen = System.currentTimeMillis(),
                isSelf = true,
            )
        )

        job = scope.launch { retryLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        links.values.forEach { it.close() }
        links.clear()
        _links.value = emptyMap()
        gattServer?.close()
        gattServer = null
        relay.clear()
        scanner?.stop()
        scanner = null
        advertiser?.stop()
        advertiser = null
        isScanning.value = false
        isAdvertising.value = false
        statusError.value = null
        _peers.value = emptyMap()
    }

    fun sendText(toNodeId: String, text: String) {
        if (toNodeId == MeshConstants.PUBLIC_CHANNEL_ID) {
            sendBroadcast(text)
            return
        }
        scope.launch {
            val msgId = MeshPacket.newMsgId()
            DataGraph.repository.insertMessage(
                MessageEntity(
                    msgId = msgId.hex(),
                    conversationId = toNodeId,
                    srcNodeId = nodeId.value,
                    dstNodeId = toNodeId,
                    text = text,
                    timestamp = System.currentTimeMillis(),
                    outbound = true,
                    deliveryStatus = STATUS_PENDING,
                    broadcast = false,
                )
            )
            val peer = DataGraph.repository.peer(toNodeId)
            if (peer?.x25519PubKey == null) {
                sendHandshake(toNodeId)
            } else {
                deliverDirect(toNodeId, msgId, text)
            }
        }
    }

    fun sendBroadcast(text: String) {
        scope.launch {
            val msgId = MeshPacket.newMsgId()
            DataGraph.repository.insertMessage(
                MessageEntity(
                    msgId = msgId.hex(),
                    conversationId = MeshConstants.PUBLIC_CHANNEL_ID,
                    srcNodeId = nodeId.value,
                    dstNodeId = MeshPacket.BROADCAST_NODE_HEX,
                    text = text,
                    timestamp = System.currentTimeMillis(),
                    outbound = true,
                    deliveryStatus = STATUS_PENDING,
                    broadcast = true,
                )
            )
            val packets = buildBroadcastPackets(msgId, text)
            if (deliverToNetwork(packets)) {
                DataGraph.repository.setStatus(msgId.hex(), STATUS_SENT)
            }
        }
    }

    private fun buildBroadcastPackets(msgId: ByteArray, text: String): List<MeshPacket.Packet> {
        val signed = CryptoEngine.signBroadcast(text.toByteArray(Charsets.UTF_8))
        return Fragmentation.split(signed, MeshPacket.FRAGMENT_PAYLOAD_SIZE).map {
            MeshPacket.Packet(
                type = MeshPacket.TYPE_BROADCAST,
                msgId = msgId,
                src = nodeId.value,
                dst = MeshPacket.BROADCAST_NODE_HEX,
                ttl = MeshPacket.DEFAULT_TTL,
                payload = it,
            )
        }
    }

    private suspend fun deliverDirect(toNodeId: String, msgId: ByteArray, text: String): Boolean {
        val peer = DataGraph.repository.peer(toNodeId) ?: return false
        val key = peer.x25519PubKey ?: return false
        val plaintext = JSONObject()
            .put("t", text)
            .put("ts", System.currentTimeMillis())
            .toString()
            .toByteArray(Charsets.UTF_8)
        val envelope = CryptoEngine.encryptDM(key, msgId, plaintext)
        val packets = Fragmentation.split(envelope, MeshPacket.FRAGMENT_PAYLOAD_SIZE).map {
            MeshPacket.Packet(
                type = MeshPacket.TYPE_DIRECT,
                msgId = msgId,
                src = nodeId.value,
                dst = toNodeId,
                ttl = MeshPacket.DEFAULT_TTL,
                payload = it,
            )
        }
        val sent = deliverToNetwork(packets)
        if (sent) {
            DataGraph.repository.setStatus(msgId.hex(), STATUS_SENT)
        }
        return sent
    }

    private suspend fun sendHandshake(toNodeId: String) {
        val now = System.currentTimeMillis()
        val last = lastHandshakeSent[toNodeId] ?: 0L
        if (now - last < 30_000) return
        lastHandshakeSent[toNodeId] = now
        val packet = MeshPacket.Packet(
            type = MeshPacket.TYPE_HANDSHAKE,
            msgId = handshakeIdFor(toNodeId),
            src = nodeId.value,
            dst = toNodeId,
            ttl = MeshPacket.DEFAULT_TTL,
            payload = CryptoEngine.x25519PublicKey(),
        )
        deliverToNetwork(listOf(packet))
    }

    private fun handshakeIdFor(toNodeId: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = CryptoEngine.x25519PublicKey() + toNodeId.hexToBytes()
        return digest.digest(input).copyOfRange(0, 16)
    }

    private suspend fun deliverToNetwork(packets: List<MeshPacket.Packet>): Boolean {
        if (packets.isEmpty()) return false
        for (packet in packets) {
            relay.markSeen(packet.msgId.hex())
        }
        val online = peers.value.values.filter { it.isOnline && !it.isSelf }
        if (online.isEmpty()) return false
        var any = false
        for (peer in online) {
            val existing = links[peer.nodeId]
            if (links.size >= MAX_CONCURRENT_LINKS && existing?.state != MeshLink.State.READY) continue
            if (writeToNode(peer.nodeId, peer.address, packets)) any = true
        }
        return any
    }

    private suspend fun writeToNode(nodeId: String, mac: String, packets: List<MeshPacket.Packet>): Boolean {
        val link = linkFor(nodeId, mac)
        try {
            withTimeout(LINK_TIMEOUT_MS) { link.ready.await() }
        } catch (_: Exception) {
            return false
        }
        var any = false
        for (packet in packets) {
            val ok = try {
                withTimeoutOrNull(LINK_TIMEOUT_MS) { link.writeAwait(MeshPacket.encode(packet)) } ?: false
            } catch (_: Exception) {
                false
            }
            if (ok) any = true
        }
        return any
    }

    private fun linkFor(nodeId: String, mac: String): MeshLink {
        val existing = links[nodeId]
        if (existing != null && existing.state != MeshLink.State.CLOSED) return existing
        val link = MeshLink(
            context = appContext!!,
            nodeId = nodeId,
            deviceMac = mac,
            onPacket = { l, bytes -> handlePacket(bytes, LinkChannel(l)) },
            onClosed = { l ->
                links.remove(l.nodeId)
                _links.update { it - l.nodeId }
            },
        )
        links[nodeId] = link
        _links.update { it + (nodeId to MeshLink.State.CONNECTING) }
        link.connect()
        return link
    }

    private fun onScanResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val payload = record.getServiceData(ParcelUuid(MeshConstants.DISCOVERY_UUID)) ?: return
        val decoded = AdvertisePayload.decode(payload) ?: return
        val peerNodeId = decoded.nodeId
        val peerName = decoded.name ?: NodeIdentity.displayName(peerNodeId)
        val now = System.currentTimeMillis()
        if (peerNodeId == nodeId.value) {
            _peers.update { map ->
                map + (peerNodeId to map.getValue(peerNodeId).copy(lastSeen = now))
            }
            return
        }
        _peers.update { map ->
            map + (peerNodeId to Peer(
                address = result.device.address,
                nodeId = peerNodeId,
                displayName = peerName,
                rssi = result.rssi,
                lastSeen = now,
            ))
        }
        scope.launch {
            DataGraph.repository.upsertPeerFromScan(peerNodeId, peerName)
        }
    }

    private fun handlePacket(bytes: ByteArray, channel: PacketChannel) {
        val packet = MeshPacket.decode(bytes) ?: return
        when (packet.type) {
            MeshPacket.TYPE_DIRECT -> handleDirect(packet, channel)
            MeshPacket.TYPE_BROADCAST -> handleBroadcast(packet)
            MeshPacket.TYPE_HANDSHAKE -> {
                if (packet.dst == nodeId.value) handleHandshake(packet, channel)
                else relayIt(packet)
            }
            MeshPacket.TYPE_ACK -> {
                if (relay.isNew(packet.msgId.hex())) {
                    handleAck(packet)
                    relayIt(packet)
                }
            }
        }
    }

    private fun handleDirect(packet: MeshPacket.Packet, channel: PacketChannel) {
        val msgIdHex = packet.msgId.hex()
        if (packet.dst == nodeId.value) {
            val isNew = relay.isNew(msgIdHex)
            if (isNew) {
                val assembled = relay.addFragment(packet)
                if (assembled != null) {
                    scope.launch {
                        val peerPub = DataGraph.repository.peer(packet.src)?.x25519PubKey
                        if (peerPub == null) {
                            sendHandshake(packet.src)
                        } else {
                            val plaintext = CryptoEngine.decryptDM(peerPub, packet.msgId, assembled)
                            if (plaintext == null) {
                                sendHandshake(packet.src)
                            } else {
                                try {
                                    val json = JSONObject(String(plaintext, Charsets.UTF_8))
                                    DataGraph.repository.insertMessage(
                                        MessageEntity(
                                            msgId = msgIdHex,
                                            conversationId = packet.src,
                                            srcNodeId = packet.src,
                                            dstNodeId = nodeId.value,
                                            text = json.getString("t"),
                                            timestamp = json.optLong("ts", System.currentTimeMillis()),
                                            outbound = false,
                                            deliveryStatus = STATUS_DELIVERED,
                                            broadcast = false,
                                        )
                                    )
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                }
            }
            val ack = MeshPacket.Packet(
                type = MeshPacket.TYPE_ACK,
                msgId = packet.msgId,
                src = nodeId.value,
                dst = packet.src,
                ttl = MeshPacket.DEFAULT_TTL,
                payload = ByteArray(0),
            )
            channel.send(MeshPacket.encode(ack))
        } else {
            if (relay.isNew(msgIdHex)) relayIt(packet)
        }
    }

    private fun handleBroadcast(packet: MeshPacket.Packet) {
        val msgIdHex = packet.msgId.hex()
        if (!relay.isNew(msgIdHex)) return
        val assembled = relay.addFragment(packet) ?: return
        relayIt(packet)
        scope.launch {
            val text = CryptoEngine.verifyBroadcast(assembled) ?: return@launch
            DataGraph.repository.insertMessage(
                MessageEntity(
                    msgId = msgIdHex,
                    conversationId = MeshConstants.PUBLIC_CHANNEL_ID,
                    srcNodeId = packet.src,
                    dstNodeId = nodeId.value,
                    text = String(text, Charsets.UTF_8),
                    timestamp = System.currentTimeMillis(),
                    outbound = false,
                    deliveryStatus = STATUS_DELIVERED,
                    broadcast = true,
                )
            )
        }
    }

    private fun handleHandshake(packet: MeshPacket.Packet, channel: PacketChannel) {
        scope.launch {
            if (packet.payload.size == 32) {
                DataGraph.repository.setPeerKey(packet.src, packet.payload)
            }
            val reply = MeshPacket.Packet(
                type = MeshPacket.TYPE_HANDSHAKE,
                msgId = handshakeIdFor(packet.src),
                src = nodeId.value,
                dst = packet.src,
                ttl = MeshPacket.DEFAULT_TTL,
                payload = CryptoEngine.x25519PublicKey(),
            )
            channel.send(MeshPacket.encode(reply))
            flushPendingFor(packet.src)
        }
    }

    private fun handleAck(packet: MeshPacket.Packet) {
        scope.launch {
            DataGraph.repository.setStatus(packet.msgId.hex(), STATUS_DELIVERED)
        }
    }

    private fun relayIt(packet: MeshPacket.Packet) {
        if (packet.ttl <= 1) return
        val next = packet.copy(ttl = packet.ttl - 1)
        val targets = peers.value.values.filter { it.isOnline && !it.isSelf && it.nodeId != packet.src }
        for (target in targets) {
            scope.launch {
                writeToNode(target.nodeId, target.address, listOf(next))
            }
        }
        if (packet.type == MeshPacket.TYPE_DIRECT ||
            packet.type == MeshPacket.TYPE_HANDSHAKE ||
            packet.type == MeshPacket.TYPE_ACK
        ) {
            scope.launch {
                val dstOnline = peers.value.values.any { it.nodeId == packet.dst && it.isOnline }
                if (!dstOnline && DataGraph.repository.hasPeer(packet.dst)) {
                    relay.cache(packet.dst, MeshPacket.encode(next))
                }
            }
        }
    }

    private suspend fun flushPendingFor(peerNodeId: String) {
        val peer = DataGraph.repository.peer(peerNodeId) ?: return
        if (peer.x25519PubKey == null) return
        val pending = DataGraph.repository.allPending()
        for (message in pending) {
            if (message.conversationId == peerNodeId) {
                deliverDirect(peerNodeId, message.msgId.hexToBytes(), message.text)
            }
        }
    }

    private suspend fun retryLoop() {
        while (true) {
            delay(8_000)
            val cutoff = System.currentTimeMillis() - MeshConstants.PEER_TIMEOUT_MS
            _peers.update { map ->
                map
                    .filterValues { it.lastSeen >= cutoff }
                    .mapValues { (id, peer) ->
                        if (id == nodeId.value && isAdvertising.value) peer.copy(lastSeen = System.currentTimeMillis()) else peer
                    }
            }
            relay.prune()
            flushStoreForward()
            flushPending()
        }
    }

    private suspend fun flushStoreForward() {
        val online = peers.value.values.filter { it.isOnline && !it.isSelf }
        for (peer in online) {
            val batch = relay.takeFor(peer.nodeId)
            if (batch.isEmpty()) continue
            val packets = batch.mapNotNull { MeshPacket.decode(it) }
            if (packets.isNotEmpty()) {
                if (!writeToNode(peer.nodeId, peer.address, packets)) {
                    for (bytes in batch) relay.cache(peer.nodeId, bytes)
                }
            }
        }
    }

    private suspend fun flushPending() {
        val pending = DataGraph.repository.allPending()
        for (message in pending) {
            if (message.conversationId == MeshConstants.PUBLIC_CHANNEL_ID) {
                val packets = buildBroadcastPackets(message.msgId.hexToBytes(), message.text)
                if (deliverToNetwork(packets)) {
                    DataGraph.repository.setStatus(message.msgId, STATUS_SENT)
                }
            } else {
                val peer = DataGraph.repository.peer(message.conversationId) ?: continue
                if (peer.x25519PubKey == null) {
                    sendHandshake(message.conversationId)
                } else {
                    deliverDirect(message.conversationId, message.msgId.hexToBytes(), message.text)
                }
            }
        }
    }

    private const val MAX_CONCURRENT_LINKS = 3
    private const val LINK_TIMEOUT_MS = 8_000L
}
