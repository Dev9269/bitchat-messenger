package com.bitchat.mesh

import android.annotation.SuppressLint
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.ParcelUuid
import android.util.Base64
import com.bitchat.crypto.CryptoEngine
import com.bitchat.data.DataGraph
import com.bitchat.data.MessageEntity
import com.bitchat.data.STATUS_DELIVERED
import com.bitchat.data.STATUS_FAILED
import com.bitchat.data.STATUS_PENDING
import com.bitchat.data.STATUS_SENT
import com.bitchat.online.OnlineService
import com.bitchat.security.AccessControl
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
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

@SuppressLint("StaticFieldLeak")
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
        refreshIdentity(context)
        refreshRuntimeState(context)
    }

    /** Re-load node id + display name (after an account restore). */
    fun refreshIdentity(context: Context) {
        nodeId.value = NodeIdentity.getNodeId(context)
        displayName.value = NodeIdentity.getDisplayName(context, nodeId.value)
    }

    suspend fun setDisplayName(name: String): Boolean {
        val clean = name.trim().take(AdvertisePayload.MAX_NAME_BYTES)
        val context = appContext ?: return false
        if (clean.isEmpty() || clean == displayName.value) return false
        val owner = DataGraph.repository.findPeerByNameExact(clean)
        if (owner != null && owner.nodeId != nodeId.value) return false
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
        return true
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
            val now = System.currentTimeMillis()
            DataGraph.repository.insertMessage(
                MessageEntity(
                    msgId = msgId.hex(),
                    conversationId = toNodeId,
                    srcNodeId = nodeId.value,
                    dstNodeId = toNodeId,
                    text = text,
                    timestamp = now,
                    outbound = true,
                    deliveryStatus = STATUS_PENDING,
                    broadcast = false,
                )
            )
            OnlineService.sendDm(toNodeId, msgId.hex(), text, now)
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

    fun createGroup(name: String, memberNodeIds: List<String>): String {
        val clean = name.trim().take(40)
        val groupId = MeshPacket.newMsgId().hex()
        scope.launch {
            // One group at a time: creating a new group leaves the previous one.
            for (g in DataGraph.repository.allGroups()) {
                OnlineService.leaveGroupRemote(g.groupId)
                DataGraph.repository.deleteGroup(g.groupId)
            }
            val secret = Base64.encodeToString(CryptoEngine.newGroupKey(), Base64.NO_WRAP)
            DataGraph.repository.createGroup(groupId, clean, nodeId.value)
            DataGraph.repository.setGroupSecret(groupId, secret)
            val members = LinkedHashSet<String>().apply {
                add(nodeId.value)
                addAll(memberNodeIds.filter { it.isNotBlank() && it != nodeId.value })
            }
            DataGraph.repository.addGroupMembers(
                groupId,
                members.map { n -> n to (if (n == nodeId.value) displayName.value else NodeIdentity.displayName(n)) }
            )
            val envelopes = HashMap<String, String>()
            for (member in members) {
                if (member == nodeId.value) continue
                val pub = DataGraph.repository.peer(member)?.x25519PubKey ?: OnlineService.xPubFor(member)
                if (pub != null) {
                    val env = CryptoEngine.wrapGroupKey(pub, groupId, Base64.decode(secret, Base64.NO_WRAP))
                    val envB64 = Base64.encodeToString(env, Base64.NO_WRAP)
                    DataGraph.repository.setGroupMemberKey(groupId, member, envB64)
                    envelopes[member] = envB64
                }
            }
            OnlineService.pushGroup(groupId, clean, members.toList(), envelopes)
            val info = JSONObject()
                .put("g", groupId)
                .put("n", clean)
                .put("m", JSONArray().also { arr -> members.forEach { arr.put(it) } })
                .put("k", JSONObject().also { obj -> envelopes.forEach { (n, env) -> obj.put(n, env) } })
            val packet = MeshPacket.Packet(
                type = MeshPacket.TYPE_GROUP_INFO,
                msgId = MeshPacket.newMsgId(),
                src = nodeId.value,
                dst = MeshPacket.BROADCAST_NODE_HEX,
                ttl = MeshPacket.DEFAULT_TTL,
                payload = info.toString().toByteArray(Charsets.UTF_8),
            )
            deliverToNetwork(listOf(packet))
        }
        return groupId
    }

    /** Owner-only: create the public default group every user auto-joins. */
    fun createDefaultGroup(onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        val groupId = AccessControl.DEFAULT_GROUP_ID
        scope.launch {
            if (DataGraph.repository.isGroupMember(groupId, nodeId.value)) {
                onDone(false, "You are already in the default group")
                return@launch
            }
            for (g in DataGraph.repository.allGroups()) {
                OnlineService.leaveGroupRemote(g.groupId)
                DataGraph.repository.deleteGroup(g.groupId)
            }
            val secret = Base64.encodeToString(CryptoEngine.newGroupKey(), Base64.NO_WRAP)
            DataGraph.repository.createGroup(groupId, "Ghostwire", nodeId.value)
            DataGraph.repository.setGroupSecret(groupId, secret)
            DataGraph.repository.addGroupMembers(groupId, listOf(nodeId.value to displayName.value))
            val selfEnv = Base64.encodeToString(
                CryptoEngine.wrapGroupKey(
                    DataGraph.repository.peer(nodeId.value)?.x25519PubKey
                        ?: CryptoEngine.x25519PublicKey(),
                    groupId,
                    Base64.decode(secret, Base64.NO_WRAP)
                ),
                Base64.NO_WRAP
            )
            OnlineService.pushOpenGroup(groupId, "Ghostwire", selfEnv)
            onDone(true, "Default group created")
        }
    }

    fun sendGroupText(groupId: String, text: String) {
        scope.launch {
            val msgId = MeshPacket.newMsgId()
            val now = System.currentTimeMillis()
            DataGraph.repository.insertMessage(
                MessageEntity(
                    msgId = msgId.hex(),
                    conversationId = groupId,
                    srcNodeId = nodeId.value,
                    dstNodeId = groupId,
                    text = text,
                    timestamp = now,
                    outbound = true,
                    deliveryStatus = STATUS_PENDING,
                    broadcast = false,
                    isGroup = true,
                )
            )
            val secret = DataGraph.repository.groupSecret(groupId)
            if (secret == null) {
                DataGraph.repository.setStatus(msgId.hex(), STATUS_FAILED)
                return@launch
            }
            val key = Base64.decode(secret, Base64.NO_WRAP)
            val ciphertext = CryptoEngine.encryptGroupMessage(key, msgId, text.toByteArray(Charsets.UTF_8))
            val signed = CryptoEngine.signBroadcast(ciphertext)
            val signedB64 = Base64.encodeToString(signed, Base64.NO_WRAP)
            OnlineService.sendGroupMessage(groupId, msgId.hex(), signedB64, now)
            val packets = buildGroupPackets(signed, msgId, groupId)
            if (deliverToNetwork(packets)) {
                DataGraph.repository.setStatus(msgId.hex(), STATUS_SENT)
            }
        }
    }

    private fun buildGroupPackets(signed: ByteArray, msgId: ByteArray, groupId: String): List<MeshPacket.Packet> {
        return Fragmentation.split(signed, MeshPacket.FRAGMENT_PAYLOAD_SIZE).map {
            MeshPacket.Packet(
                type = MeshPacket.TYPE_GROUP,
                msgId = msgId,
                src = nodeId.value,
                dst = groupId,
                ttl = MeshPacket.DEFAULT_TTL,
                payload = it,
            )
        }
    }

    fun deleteGroup(groupId: String) {
        scope.launch {
            val group = DataGraph.repository.group(groupId) ?: return@launch
            if (group.createdByNodeId != nodeId.value) return@launch
            val memberIds = DataGraph.repository.allGroupMemberIds(groupId)
            DataGraph.repository.deleteGroup(groupId)
            OnlineService.deleteGroupRemote(groupId, memberIds)
            val info = JSONObject().put("g", groupId)
            val packet = MeshPacket.Packet(
                type = MeshPacket.TYPE_GROUP_DELETE,
                msgId = MeshPacket.newMsgId(),
                src = nodeId.value,
                dst = MeshPacket.BROADCAST_NODE_HEX,
                ttl = MeshPacket.DEFAULT_TTL,
                payload = info.toString().toByteArray(Charsets.UTF_8),
            )
            deliverToNetwork(listOf(packet))
        }
    }

    fun removeGroupMember(groupId: String, memberNodeId: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        scope.launch {
            val group = DataGraph.repository.group(groupId) ?: run {
                onResult(false, "Group not found")
                return@launch
            }
            if (group.createdByNodeId != nodeId.value) {
                onResult(false, "Only the group creator can remove members")
                return@launch
            }
            if (memberNodeId == nodeId.value) {
                onResult(false, "You are the creator")
                return@launch
            }
            if (!DataGraph.repository.isGroupMember(groupId, memberNodeId)) {
                onResult(false, "Not a member")
                return@launch
            }
            DataGraph.repository.removeGroupMember(groupId, memberNodeId)
            OnlineService.kickGroupMemberRemote(groupId, memberNodeId)
            val info = JSONObject().put("g", groupId).put("m", memberNodeId)
            val packet = MeshPacket.Packet(
                type = MeshPacket.TYPE_GROUP_KICK,
                msgId = MeshPacket.newMsgId(),
                src = nodeId.value,
                dst = MeshPacket.BROADCAST_NODE_HEX,
                ttl = MeshPacket.DEFAULT_TTL,
                payload = info.toString().toByteArray(Charsets.UTF_8),
            )
            deliverToNetwork(listOf(packet))
            onResult(true, "Member removed")
        }
    }

    fun editGroupMessage(groupId: String, msgIdHex: String, newText: String) {
        scope.launch {
            if (newText.isBlank()) return@launch
            DataGraph.repository.updateMessageText(msgIdHex, newText)
            val secret = DataGraph.repository.groupSecret(groupId) ?: return@launch
            val key = Base64.decode(secret, Base64.NO_WRAP)
            val ciphertext = CryptoEngine.encryptGroupMessage(key, msgIdHex.hexToBytes(), newText.toByteArray(Charsets.UTF_8))
            val signed = CryptoEngine.signBroadcast(ciphertext)
            val signedB64 = Base64.encodeToString(signed, Base64.NO_WRAP)
            OnlineService.sendGroupEdit(groupId, msgIdHex, signedB64)
            val info = JSONObject().put("m", msgIdHex).put("p", signedB64)
            val packet = MeshPacket.Packet(
                type = MeshPacket.TYPE_GROUP_EDIT,
                msgId = MeshPacket.newMsgId(),
                src = nodeId.value,
                dst = groupId,
                ttl = MeshPacket.DEFAULT_TTL,
                payload = info.toString().toByteArray(Charsets.UTF_8),
            )
            deliverToNetwork(listOf(packet))
        }
    }

    fun receiveGroupControl(groupId: String, action: String, memberNodeId: String) {
        scope.launch {
            val isMember = DataGraph.repository.isGroupMember(groupId, nodeId.value)
            when (action) {
                "delete" -> if (isMember) DataGraph.repository.deleteGroup(groupId)
                "kick" -> {
                    if (!isMember) return@launch
                    if (memberNodeId == nodeId.value) DataGraph.repository.deleteGroup(groupId)
                    else DataGraph.repository.removeGroupMember(groupId, memberNodeId)
                }
            }
        }
    }

    fun receiveOnlineGroupEdit(groupId: String, msgIdHex: String, senderNode: String, signedB64: String, ts: Long) {
        scope.launch {
            try {
                if (!DataGraph.repository.isGroupMember(groupId, nodeId.value)) return@launch
                val decoded = Base64.decode(signedB64, Base64.NO_WRAP)
                val ciphertext = CryptoEngine.verifyBroadcast(decoded) ?: return@launch
                val secretRaw = DataGraph.repository.groupSecret(groupId) ?: return@launch
                val key = Base64.decode(secretRaw, Base64.NO_WRAP)
                val plaintext = CryptoEngine.decryptGroupMessage(key, msgIdHex.hexToBytes(), ciphertext) ?: return@launch
                DataGraph.repository.updateMessageText(msgIdHex, String(plaintext, Charsets.UTF_8))
            } catch (_: Exception) {
            }
        }
    }

    fun receiveOnlineDm(msgIdHex: String, senderNode: String, payload: String, ts: Long) {
        scope.launch {
            try {
                val peerPub = OnlineService.xPubFor(senderNode) ?: return@launch
                val envelope = try {
                    Base64.decode(payload, Base64.NO_WRAP)
                } catch (_: Exception) {
                    return@launch
                }
                val plaintext = CryptoEngine.decryptDM(peerPub, msgIdHex.hexToBytes(), envelope) ?: return@launch
                val json = JSONObject(String(plaintext, Charsets.UTF_8))
                val text = json.optString("t")
                if (text.isEmpty()) return@launch
                val name = json.optString("u").ifEmpty { NodeIdentity.displayName(senderNode) }
                DataGraph.repository.upsertPeerFromScan(senderNode, name)
                DataGraph.repository.insertMessage(
                    MessageEntity(
                        msgId = msgIdHex,
                        conversationId = senderNode,
                        srcNodeId = senderNode,
                        dstNodeId = nodeId.value,
                        text = text,
                        timestamp = json.optLong("ts", ts),
                        outbound = false,
                        deliveryStatus = STATUS_DELIVERED,
                        broadcast = false,
                    )
                )
            } catch (_: Exception) {
            }
        }
    }

    fun receiveOnlineGroupMessage(msgIdHex: String, groupId: String, senderNode: String, signedB64: String, ts: Long) {
        scope.launch {
            try {
                if (!DataGraph.repository.isGroupMember(groupId, nodeId.value)) return@launch
                val decoded = Base64.decode(signedB64, Base64.NO_WRAP)
                val ciphertext = CryptoEngine.verifyBroadcast(decoded) ?: return@launch
                val secretRaw = DataGraph.repository.groupSecret(groupId) ?: return@launch
                val key = Base64.decode(secretRaw, Base64.NO_WRAP)
                val plaintext = CryptoEngine.decryptGroupMessage(key, msgIdHex.hexToBytes(), ciphertext) ?: return@launch
                DataGraph.repository.insertMessage(
                    MessageEntity(
                        msgId = msgIdHex,
                        conversationId = groupId,
                        srcNodeId = senderNode,
                        dstNodeId = nodeId.value,
                        text = String(plaintext, Charsets.UTF_8),
                        timestamp = ts,
                        outbound = false,
                        deliveryStatus = STATUS_DELIVERED,
                        broadcast = false,
                        isGroup = true,
                    )
                )
            } catch (_: Exception) {
            }
        }
    }

fun joinGroupByCode(code: String, secret: String? = null, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        val groupId = code.trim()
        if (groupId.isEmpty()) {
            onResult(false, "Room code empty")
            return
        }
        scope.launch {
            try {
                if (DataGraph.repository.isGroupMember(groupId, nodeId.value)) {
                    onResult(true, "Already a member")
                    return@launch
                }
                // Session-style: one group at a time. Joining a non-default
                // group always requires that group's secret code.
                val currentGroups = DataGraph.repository.allGroups()
                val switching = currentGroups.any { it.groupId != groupId }
                if (groupId != AccessControl.DEFAULT_GROUP_ID) {
                    val serverSecret = OnlineService.fetchGroupSecret(groupId)
                    if (serverSecret != null) {
                        val supplied = AccessControl.sha256Hex(secret ?: "")
                        if (!AccessControl.constantTimeEquals(supplied, serverSecret)) {
                            onResult(false, "This group is protected — enter the group secret code")
                            return@launch
                        }
                    }
                }
                if (switching) {
                    for (g in currentGroups) {
                        if (g.groupId == groupId) continue
                        OnlineService.leaveGroupRemote(g.groupId)
                        DataGraph.repository.deleteGroup(g.groupId)
                    }
                }
                OnlineService.syncGroup(groupId, onLoaded = { name, memberIds, myKeyEnv, createdBy ->
                    scope.launch {
                        try {
                            if (!memberIds.contains(nodeId.value)) {
                                onResult(false, "Group found, but you are not on the member list")
                                return@launch
                            }
                            DataGraph.repository.createGroup(groupId, name, groupId)
                            DataGraph.repository.addGroupMembers(
                                groupId,
                                memberIds.map { n -> n to (if (n == nodeId.value) displayName.value else NodeIdentity.displayName(n)) }
                            )
                            val creatorPub = OnlineService.xPubFor(createdBy)
                            var secretOk = false
                            if (creatorPub != null && !myKeyEnv.isNullOrEmpty()) {
                                val env = Base64.decode(myKeyEnv, Base64.NO_WRAP)
                                val secret = CryptoEngine.unwrapGroupKey(creatorPub, groupId, env)
                                if (secret != null) {
                                    DataGraph.repository.setGroupSecret(groupId, Base64.encodeToString(secret, Base64.NO_WRAP))
                                    secretOk = true
                                }
                            }
                            onResult(secretOk, if (secretOk) "Joined $name" else "Joined, waiting for group key")
                        } catch (_: Exception) {
                            onResult(false, "Join failed")
                        }
                    }
                }, onError = { msg -> onResult(false, msg) })
            } catch (_: Exception) {
                onResult(false, "Room code not found")
            }
        }
    }

    fun receiveOnlineGroupInvite(groupId: String) {
        scope.launch {
            try {
                if (DataGraph.repository.isGroupMember(groupId, nodeId.value)) return@launch
                // One group at a time: an invite to a new group leaves the old one.
                for (g in DataGraph.repository.allGroups()) {
                    if (g.groupId == groupId) continue
                    OnlineService.leaveGroupRemote(g.groupId)
                    DataGraph.repository.deleteGroup(g.groupId)
                }
                OnlineService.syncGroup(groupId, onLoaded = { name, memberIds, myKeyEnv, createdBy ->
                    scope.launch {
                        if (memberIds.contains(nodeId.value)) {
                            DataGraph.repository.createGroup(groupId, name, groupId)
                            DataGraph.repository.addGroupMembers(
                                groupId,
                                memberIds.map { n -> n to (if (n == nodeId.value) displayName.value else NodeIdentity.displayName(n)) }
                            )
                            val creatorPub = OnlineService.xPubFor(createdBy)
                            if (creatorPub != null && !myKeyEnv.isNullOrEmpty()) {
                                val env = Base64.decode(myKeyEnv, Base64.NO_WRAP)
                                val secret = CryptoEngine.unwrapGroupKey(creatorPub, groupId, env)
                                if (secret != null) {
                                    DataGraph.repository.setGroupSecret(groupId, Base64.encodeToString(secret, Base64.NO_WRAP))
                                }
                            }
                        }
                    }
                })
            } catch (_: Exception) {
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
        if (decoded.name != null && decoded.name.equals(displayName.value, ignoreCase = true)) {
            statusError.value = "Someone else nearby is using your username \"${decoded.name}\""
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
            MeshPacket.TYPE_GROUP -> handleGroup(packet)
            MeshPacket.TYPE_GROUP_INFO -> handleGroupInfo(packet)
            MeshPacket.TYPE_GROUP_DELETE -> {
                relayIt(packet)
                handleGroupDelete(packet)
            }
            MeshPacket.TYPE_GROUP_KICK -> {
                relayIt(packet)
                handleGroupKick(packet)
            }
            MeshPacket.TYPE_GROUP_EDIT -> {
                relayIt(packet)
                handleGroupEdit(packet)
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
                val existing = DataGraph.repository.peer(packet.src)?.x25519PubKey
                if (existing == null || existing.contentEquals(packet.payload)) {
                    DataGraph.repository.setPeerKey(packet.src, packet.payload)
                }
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

    private fun handleGroup(packet: MeshPacket.Packet) {
        val msgIdHex = packet.msgId.hex()
        if (!relay.isNew(msgIdHex)) return
        relayIt(packet)
        scope.launch {
            if (!DataGraph.repository.isGroupMember(packet.dst, nodeId.value)) return@launch
            val assembled = relay.addFragment(packet) ?: return@launch
            val ciphertext = CryptoEngine.verifyBroadcast(assembled) ?: return@launch
            val secretRaw = DataGraph.repository.groupSecret(packet.dst) ?: return@launch
            val key = Base64.decode(secretRaw, Base64.NO_WRAP)
            val plaintext = CryptoEngine.decryptGroupMessage(key, packet.msgId, ciphertext) ?: return@launch
            DataGraph.repository.insertMessage(
                MessageEntity(
                    msgId = msgIdHex,
                    conversationId = packet.dst,
                    srcNodeId = packet.src,
                    dstNodeId = nodeId.value,
                    text = String(plaintext, Charsets.UTF_8),
                    timestamp = System.currentTimeMillis(),
                    outbound = false,
                    deliveryStatus = STATUS_DELIVERED,
                    broadcast = false,
                    isGroup = true,
                )
            )
        }
    }

    private fun handleGroupInfo(packet: MeshPacket.Packet) {
        val msgIdHex = packet.msgId.hex()
        if (!relay.isNew(msgIdHex)) return
        relayIt(packet)
        scope.launch {
            try {
                val json = JSONObject(String(packet.payload, Charsets.UTF_8))
                val groupId = json.getString("g")
                val name = json.getString("n")
                val members = json.optJSONArray("m") ?: return@launch
                val memberList = (0 until members.length()).map { members.getString(it) }
                if (!memberList.contains(nodeId.value)) return@launch
                if (DataGraph.repository.isGroupMember(groupId, nodeId.value)) return@launch
                DataGraph.repository.createGroup(groupId, name, packet.src)
                DataGraph.repository.addGroupMembers(
                    groupId,
                    memberList.map { n -> n to (if (n == nodeId.value) displayName.value else NodeIdentity.displayName(n)) }
                )
                val keys = json.optJSONObject("k")
                if (keys != null && keys.has(nodeId.value)) {
                    val envB64 = keys.optString(nodeId.value)
                    if (envB64.isNotEmpty()) {
                        val creatorPub = OnlineService.xPubFor(packet.src)
                        if (creatorPub != null) {
                            val env = Base64.decode(envB64, Base64.NO_WRAP)
                            val secret = CryptoEngine.unwrapGroupKey(creatorPub, groupId, env)
                            if (secret != null) {
                                DataGraph.repository.setGroupSecret(groupId, Base64.encodeToString(secret, Base64.NO_WRAP))
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun handleGroupDelete(packet: MeshPacket.Packet) {
        scope.launch {
            try {
                val json = JSONObject(String(packet.payload, Charsets.UTF_8))
                val groupId = json.getString("g")
                val group = DataGraph.repository.group(groupId) ?: return@launch
                if (group.createdByNodeId != packet.src) return@launch
                DataGraph.repository.deleteGroup(groupId)
            } catch (_: Exception) {
            }
        }
    }

    private fun handleGroupKick(packet: MeshPacket.Packet) {
        scope.launch {
            try {
                val json = JSONObject(String(packet.payload, Charsets.UTF_8))
                val groupId = json.getString("g")
                val member = json.getString("m")
                val group = DataGraph.repository.group(groupId) ?: return@launch
                if (group.createdByNodeId != packet.src) return@launch
                if (member == nodeId.value) {
                    DataGraph.repository.deleteGroup(groupId)
                } else {
                    DataGraph.repository.removeGroupMember(groupId, member)
                    if (DataGraph.repository.allGroupMemberIds(groupId).isEmpty()) {
                        DataGraph.repository.deleteGroup(groupId)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun handleGroupEdit(packet: MeshPacket.Packet) {
        if (packet.dst != nodeId.value && packet.dst != MeshPacket.BROADCAST_NODE_HEX) return
        scope.launch {
            try {
                val json = JSONObject(String(packet.payload, Charsets.UTF_8))
                val msgIdHex = json.getString("m")
                val signedB64 = json.getString("p")
                receiveOnlineGroupEdit(packet.dst, msgIdHex, packet.src, signedB64, System.currentTimeMillis())
            } catch (_: Exception) {
            }
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
            if (message.broadcast) {
                val packets = buildBroadcastPackets(message.msgId.hexToBytes(), message.text)
                if (deliverToNetwork(packets)) {
                    DataGraph.repository.setStatus(message.msgId, STATUS_SENT)
                }
            } else if (message.isGroup) {
                val secretRaw = DataGraph.repository.groupSecret(message.conversationId)
                if (secretRaw != null) {
                    val key = Base64.decode(secretRaw, Base64.NO_WRAP)
                    val ciphertext = CryptoEngine.encryptGroupMessage(key, message.msgId.hexToBytes(), message.text.toByteArray(Charsets.UTF_8))
                    val signed = CryptoEngine.signBroadcast(ciphertext)
                    val packets = buildGroupPackets(signed, message.msgId.hexToBytes(), message.conversationId)
                    if (deliverToNetwork(packets)) {
                        DataGraph.repository.setStatus(message.msgId, STATUS_SENT)
                    }
                } else {
                    DataGraph.repository.setStatus(message.msgId, STATUS_FAILED)
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
