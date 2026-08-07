package com.bitchat.online

import android.content.Context
import android.util.Base64
import com.bitchat.crypto.CryptoEngine
import com.bitchat.data.DataGraph
import com.bitchat.data.GroupEntity
import com.bitchat.mesh.MeshManager
import com.bitchat.mesh.hexToBytes
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.ConnectionSpec
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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
    private var client: HttpClient? = null
    private var sessionJob: Job? = null
    private var heartbeatJob: Job? = null
    private var pollJob: Job? = null
    private var myNode: String = ""
    private var myUsername: String = ""
    private var projectId: String = ""
    private var apiKey: String = ""
    private val myState: UiState get() = _state.value
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun firestoreBase(): String =
        "https://firestore.googleapis.com/v1/projects/$projectId/databases/%28default%29/documents"

    fun init(appContext: Context) {
        context = appContext.applicationContext
        myNode = MeshManager.nodeId.value
    }

    fun connect(username: String) {
        val ctx = context ?: return
        if (!OnlineConfig.isConfigured(ctx)) {
            _state.value = UiState(ConnectionStatus.ERROR, username, "Online is not configured. Add your Firebase project ID and Web API key first.")
            return
        }
        myUsername = if (username.isBlank()) "Node-${myNode.take(4).uppercase()}" else username.trim().take(20)
        myNode = MeshManager.nodeId.value
        projectId = OnlineConfig.getProjectId(ctx)
        apiKey = OnlineConfig.getApiKey(ctx)
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
        pollJob?.cancel()
        pollJob = null
        try {
            client?.close()
        } catch (_: Exception) {
        }
        client = null
        if (_state.value.status != ConnectionStatus.DISCONNECTED) {
            _state.value = UiState(ConnectionStatus.DISCONNECTED, _state.value.username, "")
        }
    }

    private suspend fun connectLoop() {
        var attempt = 0
        while (true) {
            try {
                claimAndListen()
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                val backoff = minOf(15_000L, 2_000L * attempt)
                _state.value = UiState(ConnectionStatus.ERROR, _state.value.username, "Offline: ${e.message ?: "connection failed"}")
                delay(backoff)
            }
        }
    }

    private suspend fun claimAndListen() {
        val ctx = context ?: return
        projectId = OnlineConfig.getProjectId(ctx)
        apiKey = OnlineConfig.getApiKey(ctx)
        val http = HttpClient(OkHttp) {
            expectSuccess = false
            engine {
                config {
                    connectTimeout(10, TimeUnit.SECONDS)
                    retryOnConnectionFailure(true)
                    connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))
                }
            }
        }
        client = http

        val takenBy = profileNodeForUsername(http, myUsername)
        if (takenBy != null && takenBy != myNode) {
            _state.value = UiState(ConnectionStatus.ERROR, myUsername, "Username '$myUsername' is already used by someone else. Pick another.")
            http.close()
            client = null
            return
        }
        upsertProfile(http)

        _state.value = UiState(ConnectionStatus.CONNECTED, myUsername, "Connected as $myUsername")

        heartbeatJob?.cancel()
        heartbeatJob = scope.launch { heartbeatLoop() }
        pollJob?.cancel()
        pollJob = scope.launch { pollLoop() }
    }

    // ---- Firestore low-level helpers -------------------------------------------------

    private fun s(value: String): JSONObject = JSONObject().put("stringValue", value)
    private fun i(value: Long): JSONObject = JSONObject().put("integerValue", value.toString())
    private fun b(value: Boolean): JSONObject = JSONObject().put("booleanValue", value)

    private fun docBody(f: JSONObject): JSONObject = JSONObject().put("fields", f)

    private fun baseUrl(segments: List<String>): String =
        "${firestoreBase()}/${segments.joinToString("/")}?key=${apiKey.encodeURLParameter()}"

    private suspend fun getDoc(http: HttpClient, segments: List<String>): JSONObject? {
        return try {
            val res = http.get(baseUrl(segments)) { header("Accept", "application/json") }
            JSONObject(res.bodyAsText())
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun listDocs(http: HttpClient, segments: List<String>, pageSize: Int = 1024, pageToken: String? = null): JSONObject? {
        return try {
            var url = "${firestoreBase()}/${segments.joinToString("/")}?key=${apiKey.encodeURLParameter()}&pageSize=$pageSize"
            if (pageToken != null) url += "&pageToken=${pageToken.encodeURLParameter()}"
            val res = http.get(url) { header("Accept", "application/json") }
            JSONObject(res.bodyAsText())
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun upsertDoc(http: HttpClient, segments: List<String>, payload: JSONObject) {
        try {
            val existing = getDoc(http, segments)
            if (existing == null) {
                val id = segments.last()
                val parent = segments.dropLast(1).joinToString("/")
                val url = "${firestoreBase()}/$parent?key=${apiKey.encodeURLParameter()}&documentId=${id.encodeURLParameter()}"
                http.post(url) {
                    contentType(ContentType.Application.Json)
                    header("Accept", "application/json")
                    setBody(payload.toString())
                }
            } else {
                http.patch(baseUrl(segments)) {
                    contentType(ContentType.Application.Json)
                    header("Accept", "application/json")
                    setBody(payload.toString())
                }
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun deleteDoc(http: HttpClient, segments: List<String>) {
        try {
            http.delete(baseUrl(segments))
        } catch (_: Exception) {
        }
    }

    // ---- Profiles -------------------------------------------------------------------

    private suspend fun profileNodeForUsername(http: HttpClient, username: String): String? {
        val doc = getDoc(http, listOf("profiles", username)) ?: return null
        val f = doc.optJSONObject("fields") ?: return null
        return f.optJSONObject("node_id")?.optString("stringValue")?.ifEmpty { null }
    }

    private suspend fun upsertProfile(http: HttpClient) {
        val xPubB64 = Base64.encodeToString(CryptoEngine.x25519PublicKey(), Base64.NO_WRAP)
        upsertDoc(http, listOf("profiles", myUsername), docBody(JSONObject()
            .put("username", s(myUsername))
            .put("node_id", s(myNode))
            .put("display_name", s(MeshManager.displayName.value))
            .put("x_pub", s(xPubB64))
            .put("created_at", i(System.currentTimeMillis()))))
        upsertDoc(http, listOf("nodes", myNode), docBody(JSONObject()
            .put("username", s(myUsername))
            .put("node_id", s(myNode))
            .put("display_name", s(MeshManager.displayName.value))
            .put("x_pub", s(xPubB64))))
    }

    suspend fun xPubFor(nodeId: String): ByteArray? {
        val local = DataGraph.repository.peer(nodeId)?.x25519PubKey
        if (local != null) return local
        val http = client ?: return null
        val doc = getDoc(http, listOf("nodes", nodeId)) ?: return null
        val b64 = doc.optJSONObject("fields")?.optJSONObject("x_pub")?.optString("stringValue") ?: return null
        val pub = try {
            Base64.decode(b64, Base64.NO_WRAP)
        } catch (_: Exception) {
            return null
        }
        DataGraph.repository.setPeerKey(nodeId, pub)
        return pub
    }

    suspend fun resolveUserToNode(username: String): String? {
        val http = client ?: return null
        val doc = getDoc(http, listOf("profiles", username)) ?: return null
        return doc.optJSONObject("fields")?.optJSONObject("node_id")?.optString("stringValue")
    }

    // ---- Heartbeat + polling --------------------------------------------------------

    private suspend fun heartbeatLoop() {
        while (true) {
            try {
                val http = client ?: return
                upsertDoc(http, listOf("presence", myNode), docBody(JSONObject()
                    .put("node_id", s(myNode))
                    .put("online", b(true))
                    .put("last_seen", i(System.currentTimeMillis()))
                    .put("username", s(myUsername))))
            } catch (_: Exception) {
            }
            delay(30_000)
        }
    }

    private suspend fun pollLoop() {
        while (true) {
            try {
                pollInbox()
                pollGroupInvites()
                pollGroupMessages()
            } catch (_: Exception) {
            }
            delay(4_000)
        }
    }

    private suspend fun pollInbox() {
        val http = client ?: return
        var pageToken: String? = null
        while (true) {
            val list = listDocs(http, listOf("myinbox", myNode, "messages"), pageToken = pageToken) ?: return
            val docs = list.optJSONArray("documents") ?: return
            for (i in 0 until docs.length()) {
                val doc = docs.getJSONObject(i)
                val f = doc.optJSONObject("fields") ?: continue
                val msgId = f.optJSONObject("msg_id")?.optString("stringValue") ?: continue
                val sender = f.optJSONObject("sender")?.optString("stringValue") ?: continue
                val payload = f.optJSONObject("payload")?.optString("stringValue") ?: continue
                val ts = f.optJSONObject("ts")?.optLong("integerValue") ?: System.currentTimeMillis()
                MeshManager.receiveOnlineDm(msgId, sender, payload, ts)
                val name = doc.optString("name", "")
                if (name.isNotEmpty()) {
                    val seg = name.substringAfterLast('/')
                    deleteDoc(http, listOf("myinbox", myNode, "messages", seg))
                }
            }
            val next = list.optString("nextPageToken").ifEmpty { null }
            if (next == null) break
            pageToken = next
        }
    }

    private suspend fun pollGroupInvites() {
        val http = client ?: return
        val list = listDocs(http, listOf("mygroups", myNode, "invites"), pageSize = 512) ?: return
        val docs = list.optJSONArray("documents") ?: return
        for (i in 0 until docs.length()) {
            val doc = docs.getJSONObject(i)
            val f = doc.optJSONObject("fields") ?: continue
            val groupId = f.optJSONObject("group_id")?.optString("stringValue") ?: continue
            if (!DataGraph.repository.isGroupMember(groupId, myNode)) {
                MeshManager.receiveOnlineGroupInvite(groupId)
            }
        }
    }

    private suspend fun pollGroupMessages() {
        val http = client ?: return
        for (group in DataGraph.repository.allGroups()) {
            if (!DataGraph.repository.isGroupMember(group.groupId, myNode)) continue
            val list = listDocs(http, listOf("groups", group.groupId, "messages"), pageSize = 512) ?: continue
            val docs = list.optJSONArray("documents") ?: continue
            for (i in 0 until docs.length()) {
                val doc = docs.getJSONObject(i)
                val f = doc.optJSONObject("fields") ?: continue
                val msgId = f.optJSONObject("msg_id")?.optString("stringValue") ?: continue
                val sender = f.optJSONObject("sender")?.optString("stringValue") ?: continue
                val payload = f.optJSONObject("payload")?.optString("stringValue") ?: continue
                val ts = f.optJSONObject("ts")?.optLong("integerValue") ?: 0L
                MeshManager.receiveOnlineGroupMessage(msgId, group.groupId, sender, payload, ts)
            }
        }
    }

    // ---- Outbound --------------------------------------------------------------

    fun sendDm(recipientNodeId: String, msgIdHex: String, text: String, ts: Long) {
        val http = client ?: return
        scope.launch {
            try {
                val peerPub = xPubFor(recipientNodeId) ?: return@launch
                val plaintext = JSONObject().put("t", text).put("ts", ts).put("u", myState.username).toString()
                    .toByteArray(Charsets.UTF_8)
                val envelope = CryptoEngine.encryptDM(peerPub, msgIdHex.hexToBytes(), plaintext)
                val payloadB64 = Base64.encodeToString(envelope, Base64.NO_WRAP)
                upsertDoc(http, listOf("myinbox", recipientNodeId, "messages", msgIdHex), docBody(JSONObject()
                    .put("msg_id", s(msgIdHex))
                    .put("sender", s(myNode))
                    .put("recipient_node", s(recipientNodeId))
                    .put("payload", s(payloadB64))
                    .put("ts", i(ts))))
            } catch (_: Exception) {
            }
        }
    }

    fun sendGroupMessage(groupId: String, msgIdHex: String, signedB64: String, ts: Long) {
        val http = client ?: return
        scope.launch {
            try {
                upsertDoc(http, listOf("groups", groupId, "messages", msgIdHex), docBody(JSONObject()
                    .put("msg_id", s(msgIdHex))
                    .put("sender", s(myNode))
                    .put("payload", s(signedB64))
                    .put("ts", i(ts))))
            } catch (_: Exception) {
            }
        }
    }

    fun pushGroup(groupId: String, name: String, memberNodeIds: List<String>, keyEnvelopes: Map<String, String>) {
        val http = client ?: return
        scope.launch {
            try {
                upsertDoc(http, listOf("groups", groupId), docBody(JSONObject()
                    .put("name", s(name))
                    .put("created_by", s(myNode))
                    .put("created_at", i(System.currentTimeMillis()))))
                for (member in memberNodeIds) {
                    upsertDoc(http, listOf("mygroups", member, "invites", groupId), docBody(JSONObject()
                        .put("group_id", s(groupId))))
                    val envelopeB64 = keyEnvelopes[member]
                    if (envelopeB64 != null) {
                        upsertDoc(http, listOf("groups", groupId, "members", member), docBody(JSONObject()
                            .put("node_id", s(member))
                            .put("key_env", s(envelopeB64))))
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun syncGroup(groupId: String, onLoaded: (String, List<String>, String?, String) -> Unit) {
        val http = client ?: return
        scope.launch {
            try {
                val gDoc = getDoc(http, listOf("groups", groupId)) ?: return@launch
                val f = gDoc.optJSONObject("fields") ?: return@launch
                val name = f.optJSONObject("name")?.optString("stringValue") ?: groupId
                val createdBy = f.optJSONObject("created_by")?.optString("stringValue") ?: ""
                val mRoot = listDocs(http, listOf("groups", groupId, "members"), pageSize = 1024) ?: return@launch
                val docs = mRoot.optJSONArray("documents") ?: return@launch
                val members = mutableListOf<String>()
                var myKeyEnv: String? = null
                for (i in 0 until docs.length()) {
                    val mf = docs.getJSONObject(i).optJSONObject("fields") ?: continue
                    val nodeId = mf.optJSONObject("node_id")?.optString("stringValue") ?: continue
                    members.add(nodeId)
                    if (nodeId == myNode) myKeyEnv = mf.optJSONObject("key_env")?.optString("stringValue")
                }
                if (members.isEmpty()) return@launch
                onLoaded(name, members, myKeyEnv, createdBy)
            } catch (_: Exception) {
            }
        }
    }
}