package com.bitchat.online

import android.content.Context
import android.util.Base64
import com.bitchat.crypto.CryptoEngine
import com.bitchat.data.DataGraph
import com.bitchat.mesh.MeshManager
import com.bitchat.mesh.hexToBytes
import com.bitchat.security.AccessControl
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

object OnlineService {

    enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    data class UiState(
        val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
        val username: String = "",
        val detail: String = "",
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var context: Context? = null
    private var firestore: FirebaseFirestore? = null
    private var auth: FirebaseAuth? = null
    private var sessionJob: Job? = null
    private var heartbeatJob: Job? = null
    private val listeners = mutableListOf<ListenerRegistration>()
    
    private var myNode: String = ""
    private var myUsername: String = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val watchedGroups = mutableSetOf<String>()

    fun init(appContext: Context) {
        context = appContext.applicationContext
        myNode = MeshManager.nodeId.value
    }

    private fun ensureFirebase(ctx: Context): Boolean {
        if (firestore != null) return true
        val options = OnlineConfig.getFirebaseOptions(ctx) ?: return false
        return try {
            val app = try {
                FirebaseApp.getInstance("bitchat_online")
            } catch (_: Exception) {
                FirebaseApp.initializeApp(ctx, options, "bitchat_online")
            }
            auth = FirebaseAuth.getInstance(app)
            firestore = FirebaseFirestore.getInstance(app).apply {
                val settings = FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                    .build()
                firestoreSettings = settings
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun connect(username: String) {
        val ctx = context ?: return
        if (!OnlineConfig.isConfigured(ctx)) {
            _state.value = UiState(ConnectionStatus.ERROR, username, "Online is not configured.")
            return
        }
        myUsername = if (username.isBlank()) "Node-${myNode.take(4).uppercase()}" else username.trim().take(20)
        disconnectInternal()
        _state.value = UiState(ConnectionStatus.CONNECTING, myUsername, "Connecting...")
        sessionJob = scope.launch { connectLoop() }
    }

    fun disconnect() {
        disconnectInternal()
    }

    private fun disconnectInternal() {
        sessionJob?.cancel()
        sessionJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        listeners.forEach { it.remove() }
        listeners.clear()
        watchedGroups.clear()
        firestore = null
        auth = null
        if (_state.value.status != ConnectionStatus.DISCONNECTED) {
            _state.value = UiState(ConnectionStatus.DISCONNECTED, _state.value.username, "")
        }
    }

    private suspend fun connectLoop() {
        val ctx = context ?: return
        try {
            if (!ensureFirebase(ctx)) {
                _state.value = UiState(ConnectionStatus.ERROR, myUsername, "Firebase init failed.")
                return
            }
            
            val firebaseAuth = auth ?: return
            val firebaseFirestore = firestore ?: return

            // Step 1: Anonymous Auth
            if (firebaseAuth.currentUser == null) {
                firebaseAuth.signInAnonymously().await()
            }
            val myUid = firebaseAuth.currentUser?.uid ?: throw Exception("Auth failed")

            // Step 2: Claim profile
            val profileRef = firebaseFirestore.collection("profiles").document(myUsername)
            val profileDoc = profileRef.get().await()
            if (profileDoc.exists()) {
                val owner = profileDoc.getString("node_id")
                val ownerUid = profileDoc.getString("uid")
                if (owner != myNode && ownerUid != myUid) {
                    _state.value = UiState(ConnectionStatus.ERROR, myUsername, "Username taken.")
                    return
                }
            }

            val xPubB64 = Base64.encodeToString(CryptoEngine.x25519PublicKey(), Base64.NO_WRAP)
            val profileData = mapOf(
                "username" to myUsername,
                "node_id" to myNode,
                "display_name" to MeshManager.displayName.value,
                "x_pub" to xPubB64,
                "uid" to myUid,
                "created_at" to System.currentTimeMillis()
            )
            profileRef.set(profileData).await()
            firebaseFirestore.collection("nodes").document(myNode).set(profileData).await()

            _state.value = UiState(ConnectionStatus.CONNECTED, myUsername, "Connected")

            // Step 3: Listeners
            startListeners(firebaseFirestore)
            loadAccessSettings(firebaseFirestore)
            maybeJoinDefaultGroup()
            
            heartbeatJob?.cancel()
            heartbeatJob = scope.launch { heartbeatLoop() }

        } catch (e: Exception) {
            _state.value = UiState(ConnectionStatus.ERROR, myUsername, e.message ?: "Connection failed")
        }
    }

    private fun startListeners(db: FirebaseFirestore) {
        // Inbox Listener
        listeners.add(db.collection("myinbox").document(myNode).collection("messages")
            .addSnapshotListener { snapshots, e ->
                if (e != null || snapshots == null) return@addSnapshotListener
                for (dc in snapshots.documentChanges) {
                    if (dc.type == DocumentChange.Type.ADDED) {
                        val doc = dc.document
                        val msgId = doc.getString("msg_id") ?: continue
                        val sender = doc.getString("sender") ?: continue
                        val payload = doc.getString("payload") ?: continue
                        val ts = doc.getLong("ts") ?: System.currentTimeMillis()
                        MeshManager.receiveOnlineDm(msgId, sender, payload, ts)
                        doc.reference.delete()
                    }
                }
            })

        // Invites Listener
        listeners.add(db.collection("mygroups").document(myNode).collection("invites")
            .addSnapshotListener { snapshots, e ->
                if (e != null || snapshots == null) return@addSnapshotListener
                for (dc in snapshots.documentChanges) {
                    if (dc.type == DocumentChange.Type.ADDED) {
                        val groupId = dc.document.getString("group_id") ?: continue
                        scope.launch {
                            if (!DataGraph.repository.isGroupMember(groupId, myNode)) {
                                MeshManager.receiveOnlineGroupInvite(groupId)
                            }
                        }
                    }
                }
            })

        // Default open group: the OWNER fills key envelopes for members who
        // self-join (their member doc carries an empty key_env placeholder).
        listeners.add(db.collection("groups").document(AccessControl.DEFAULT_GROUP_ID)
            .collection("members")
            .addSnapshotListener { snapshots, e ->
                if (e != null || snapshots == null) return@addSnapshotListener
                scope.launch {
                    try {
                        val gDoc = db.collection("groups").document(AccessControl.DEFAULT_GROUP_ID).get().await()
                        if (gDoc.getString("uid") != auth?.currentUser?.uid) return@launch
                        val keyB64 = DataGraph.repository.groupSecret(AccessControl.DEFAULT_GROUP_ID) ?: return@launch
                        val key = Base64.decode(keyB64, Base64.NO_WRAP)
                        for (dc in snapshots.documentChanges) {
                            if (dc.type != DocumentChange.Type.ADDED && dc.type != DocumentChange.Type.MODIFIED) continue
                            val member = dc.document.getString("node_id") ?: continue
                            val env = dc.document.getString("key_env") ?: continue
                            if (member == myNode || env.isNotEmpty()) continue
                            val pub = xPubFor(member) ?: continue
                            val wrapped = Base64.encodeToString(
                                CryptoEngine.wrapGroupKey(pub, AccessControl.DEFAULT_GROUP_ID, key),
                                Base64.NO_WRAP
                            )
                            db.collection("groups").document(AccessControl.DEFAULT_GROUP_ID)
                                .collection("members").document(member)
                                .update(mapOf("key_env" to wrapped)).await()
                        }
                    } catch (_: Exception) {
                    }
                }
            })

        // Group Messages Listener
        scope.launch {
            val groups = DataGraph.repository.allGroups()
            for (group in groups) {
                if (!watchedGroups.add(group.groupId)) continue
                listeners.add(groupMessagesListener(db, group.groupId))
            }
        }
    }

    fun watchGroup(groupId: String) {
        val db = firestore ?: return
        if (!watchedGroups.add(groupId)) return
        listeners.add(groupMessagesListener(db, groupId))
    }

    private fun groupMessagesListener(db: FirebaseFirestore, groupId: String): ListenerRegistration {
        return db.collection("groups").document(groupId).collection("messages")
            .addSnapshotListener { snapshots, e ->
                if (e != null || snapshots == null) return@addSnapshotListener
                for (dc in snapshots.documentChanges) {
                    val doc = dc.document
                    val control = doc.getString("control")
                    if (control != null) {
                        if (dc.type == DocumentChange.Type.ADDED) {
                            val node = doc.getString("node")
                            MeshManager.receiveGroupControl(groupId, control, node ?: "")
                            doc.reference.delete().addOnFailureListener { }
                        }
                        continue
                    }
                    val msgId = doc.getString("msg_id") ?: continue
                    val sender = doc.getString("sender") ?: continue
                    val payload = doc.getString("payload") ?: continue
                    val ts = doc.getLong("ts") ?: 0L
                    when (dc.type) {
                        DocumentChange.Type.ADDED ->
                            MeshManager.receiveOnlineGroupMessage(msgId, groupId, sender, payload, ts)
                        DocumentChange.Type.MODIFIED ->
                            MeshManager.receiveOnlineGroupEdit(groupId, msgId, sender, payload, ts)
                        else -> {}
                    }
                }
            }
    }

    private suspend fun heartbeatLoop() {
        while (true) {
            try {
                val db = firestore ?: return
                val myUid = auth?.currentUser?.uid ?: return
                db.collection("presence").document(myNode).set(mapOf(
                    "node_id" to myNode,
                    "online" to true,
                    "last_seen" to System.currentTimeMillis(),
                    "uid" to myUid
                )).await()
            } catch (_: Exception) {
            }
            delay(30_000 + kotlin.random.Random.nextLong(0, 6_000))
        }
    }

    suspend fun xPubFor(nodeId: String): ByteArray? {
        val local = DataGraph.repository.peer(nodeId)?.x25519PubKey
        val db = firestore ?: return local
        return try {
            val doc = db.collection("nodes").document(nodeId).get().await()
            val b64 = doc.getString("x_pub") ?: return local
            val pub = Base64.decode(b64, Base64.NO_WRAP)
            if (local != null && !local.contentEquals(pub)) {
                local
            } else {
                if (local == null) DataGraph.repository.setPeerKey(nodeId, pub)
                pub
            }
        } catch (_: Exception) {
            local
        }
    }

    suspend fun resolveUserToNode(username: String): String? {
        val db = firestore ?: return null
        return try {
            val doc = db.collection("profiles").document(username).get().await()
            doc.getString("node_id")
        } catch (_: Exception) {
            null
        }
    }

    fun sendDm(recipientNodeId: String, msgIdHex: String, text: String, ts: Long) {
        val db = firestore ?: return
        scope.launch {
            try {
                val peerPub = xPubFor(recipientNodeId) ?: return@launch
                val plaintext = JSONObject().put("t", text).put("ts", ts).put("u", _state.value.username).toString()
                    .toByteArray(Charsets.UTF_8)
                val envelope = CryptoEngine.encryptDM(peerPub, msgIdHex.hexToBytes(), plaintext)
                val payloadB64 = Base64.encodeToString(envelope, Base64.NO_WRAP)
                
                db.collection("myinbox").document(recipientNodeId).collection("messages").document(msgIdHex)
                    .set(mapOf(
                        "msg_id" to msgIdHex,
                        "sender" to myNode,
                        "recipient_node" to recipientNodeId,
                        "payload" to payloadB64,
                        "ts" to ts
                    )).await()
            } catch (_: Exception) {
            }
        }
    }

    fun sendGroupMessage(groupId: String, msgIdHex: String, signedB64: String, ts: Long) {
        val db = firestore ?: return
        scope.launch {
            try {
                db.collection("groups").document(groupId).collection("messages").document(msgIdHex)
                    .set(mapOf(
                        "msg_id" to msgIdHex,
                        "sender" to myNode,
                        "payload" to signedB64,
                        "ts" to ts
                    )).await()
            } catch (_: Exception) {
            }
        }
    }

    fun sendGroupEdit(groupId: String, msgIdHex: String, signedB64: String) {
        val db = firestore ?: return
        scope.launch {
            try {
                db.collection("groups").document(groupId).collection("messages").document(msgIdHex)
                    .update(mapOf("payload" to signedB64, "edited" to true)).await()
            } catch (_: Exception) {
            }
        }
    }

    fun deleteGroupRemote(groupId: String, memberNodeIds: List<String>) {
        val db = firestore ?: return
        scope.launch {
            try {
                db.collection("groups").document(groupId).collection("messages")
                    .document("_control_" + System.currentTimeMillis())
                    .set(mapOf(
                        "control" to "delete",
                        "sender" to myNode,
                        "ts" to System.currentTimeMillis()
                    )).await()
            } catch (_: Exception) {
            }
        }
    }

    fun kickGroupMemberRemote(groupId: String, memberNodeId: String) {
        val db = firestore ?: return
        scope.launch {
            try {
                db.collection("groups").document(groupId).collection("messages")
                    .document("_control_" + System.currentTimeMillis())
                    .set(mapOf(
                        "control" to "kick",
                        "node" to memberNodeId,
                        "sender" to myNode,
                        "ts" to System.currentTimeMillis()
                    )).await()
            } catch (_: Exception) {
            }
        }
    }

        /** Owner-only: creates the open default group document and plants the owner's envelope. */
    fun pushOpenGroup(groupId: String, name: String, selfEnvelopeB64: String) {
        val db = firestore ?: return
        val myUid = auth?.currentUser?.uid ?: return
        scope.launch {
            try {
                db.collection("groups").document(groupId).set(mapOf(
                    "name" to name,
                    "created_by" to myNode,
                    "uid" to myUid,
                    "open" to true,
                    "created_at" to System.currentTimeMillis()
                )).await()
                db.collection("groups").document(groupId).collection("members").document(myNode)
                    .set(mapOf("node_id" to myNode, "key_env" to selfEnvelopeB64)).await()
                watchGroup(groupId)
            } catch (_: Exception) {
            }
        }
    }

    fun pushGroup(groupId: String, name: String, memberNodeIds: List<String>, keyEnvelopes: Map<String, String>) {
        val db = firestore ?: return
        val myUid = auth?.currentUser?.uid ?: return
        scope.launch {
            try {
                db.collection("groups").document(groupId).set(mapOf(
                    "name" to name,
                    "created_by" to myNode,
                    "uid" to myUid,
                    "created_at" to System.currentTimeMillis()
                )).await()
                
                for (member in memberNodeIds) {
                    db.collection("mygroups").document(member).collection("invites").document(groupId)
                        .set(mapOf("group_id" to groupId))
                    
                    val envelopeB64 = keyEnvelopes[member]
                    if (envelopeB64 != null) {
                        db.collection("groups").document(groupId).collection("members").document(member)
                            .set(mapOf(
                                "node_id" to member,
                                "key_env" to envelopeB64
                            ))
                    }
                }
                watchGroup(groupId)
            } catch (_: Exception) {
            }
        }
    }

    fun syncGroup(groupId: String, onLoaded: (String, List<String>, String?, String) -> Unit, onError: ((String) -> Unit)? = null) {
        val db = firestore ?: run {
            onError?.invoke("Not connected")
            return
        }
        scope.launch {
            try {
                val gDoc = db.collection("groups").document(groupId).get().await()
                if (!gDoc.exists()) {
                    onError?.invoke("Room code not found")
                    return@launch
                }
                val name = gDoc.getString("name") ?: groupId
                val createdBy = gDoc.getString("created_by") ?: ""
                
                val mRoot = db.collection("groups").document(groupId).collection("members").get().await()
                val members = mutableListOf<String>()
                var myKeyEnv: String? = null
                for (doc in mRoot.documents) {
                    val nodeId = doc.getString("node_id") ?: continue
                    members.add(nodeId)
                    if (nodeId == myNode) myKeyEnv = doc.getString("key_env")
                }
                
                if (members.isEmpty()) {
                    onError?.invoke("Group has no members yet")
                    return@launch
                }
                onLoaded(name, members, myKeyEnv, createdBy)
                watchGroup(groupId)
            } catch (_: Exception) {
                onError?.invoke("Could not fetch group")
            }
        }
    }

    // ---------------------------------------------------------------------
    // Session-style access control: personal-chat master secret + allowlist
    // ---------------------------------------------------------------------

    private suspend fun loadAccessSettings(db: FirebaseFirestore) {
        try {
            val doc = db.collection("settings").document("access").get().await()
            if (!doc.exists()) return
            val allowlist = doc.get("allowlist") as? List<*> ?: emptyList<Any>()
            if (allowlist.contains(myNode)) {
                AccessControl.setDmUnlocked(true)
            }
        } catch (_: Exception) {
        }
    }

    suspend fun verifyPersonalSecret(secret: String): Boolean {
        val db = firestore ?: return false
        return try {
            val doc = db.collection("settings").document("access").get().await()
            val hash = doc.getString("personal_secret") ?: return false
            val ok = AccessControl.constantTimeEquals(AccessControl.sha256Hex(secret), hash)
            if (ok) AccessControl.setDmUnlocked(true)
            ok
        } catch (_: Exception) {
            false
        }
    }

    suspend fun accessSettingsExists(): Boolean {
        val db = firestore ?: return false
        return try {
            db.collection("settings").document("access").get().await().exists()
        } catch (_: Exception) {
            false
        }
    }

    suspend fun isAccessOwner(): Boolean {
        val db = firestore ?: return false
        val myUid = auth?.currentUser?.uid ?: return false
        return try {
            val owner = db.collection("settings").document("access").get().await().getString("owner")
            owner == myUid
        } catch (_: Exception) {
            false
        }
    }

    /** Owner-only: create/update settings/access (owner uid is bound at first create by rules). */
    suspend fun setAccessSettings(secret: String?, allowlist: List<String>?) {
        val db = firestore ?: return
        val myUid = auth?.currentUser?.uid ?: return
        try {
            val data = mutableMapOf<String, Any>("owner" to myUid)
            if (secret != null) data["personal_secret"] = AccessControl.sha256Hex(secret)
            else data["personal_secret"] = FieldValue.delete()
            if (allowlist != null) data["allowlist"] = allowlist
            db.collection("settings").document("access").set(data, SetOptions.merge()).await()
        } catch (_: Exception) {
        }
    }

    /** Owner-only: append a node id to the personal-chat allowlist. */
    suspend fun addAllowlistNode(nodeId: String) {
        val db = firestore ?: return
        val myUid = auth?.currentUser?.uid ?: return
        try {
            val ref = db.collection("settings").document("access")
            val doc = ref.get().await()
            if (doc.getString("owner") != myUid) return
            val current = (doc.get("allowlist") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            if (nodeId in current) return
            val data = mutableMapOf<String, Any>("owner" to myUid, "allowlist" to current + nodeId)
            ref.set(data, SetOptions.merge()).await()
        } catch (_: Exception) {
        }
    }

    /** Owner-only: removes a node id from the personal-chat allowlist. */
    suspend fun removeAllowlistNode(nodeId: String) {
        val db = firestore ?: return
        val myUid = auth?.currentUser?.uid ?: return
        try {
            val ref = db.collection("settings").document("access")
            val doc = ref.get().await()
            if (doc.getString("owner") != myUid) return
            val current = (doc.get("allowlist") as? List<*>?)?.filterIsInstance<String>() ?: emptyList()
            val list = mutableMapOf<String, Any>(
                "owner" to myUid,
                "allowlist" to current.filterNot { it == nodeId }
            )
            ref.set(list, SetOptions.merge()).await()
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------------
    // Groups: group secret codes, self-leave, default open group
    // ---------------------------------------------------------------------

    suspend fun fetchGroupSecret(groupId: String): String? {
        val db = firestore ?: return null
        return try {
            db.collection("groups").document(groupId).get().await().getString("secret")
        } catch (_: Exception) {
            null
        }
    }

    /** Owner-only: set or clear this group's entry secret (stored as SHA-256). */
    fun setGroupSecret(groupId: String, secret: String?) {
        val db = firestore ?: return
        scope.launch {
            try {
                if (secret.isNullOrBlank()) {
                    db.collection("groups").document(groupId)
                        .update(mapOf("secret" to FieldValue.delete())).await()
                } else {
                    db.collection("groups").document(groupId)
                        .update(mapOf("secret" to AccessControl.sha256Hex(secret))).await()
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Deletes this node's own membership (allowed by rules: any member may leave). */
    fun leaveGroupRemote(groupId: String) {
        val db = firestore ?: return
        scope.launch {
            try {
                db.collection("groups").document(groupId).collection("members")
                    .document(myNode).delete().await()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Automatically joins the default open group when the user has no group
     * yet. The owner fills the key envelope afterwards; we re-sync until it
     * lands (or give up after ~30s).
     */
    fun maybeJoinDefaultGroup() {
        val db = firestore ?: return
        val groupId = AccessControl.DEFAULT_GROUP_ID
        scope.launch {
            try {
                if (DataGraph.repository.allGroups().isNotEmpty()) return@launch
                val g = db.collection("groups").document(groupId).get().await()
                if (!g.exists() || g.getBoolean("open") != true) return@launch
                db.collection("groups").document(groupId).collection("members")
                    .document(myNode).set(mapOf("node_id" to myNode, "key_env" to "")).await()
                var joined = false
                for (i in 0 until 8) {
                    MeshManager.joinGroupByCode(groupId) { ok, _ ->
                        if (ok) joined = true
                    }
                    if (joined) return@launch
                    delay(4_000)
                }
            } catch (_: Exception) {
            }
        }
    }
}
