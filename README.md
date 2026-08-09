# Ghostwire — Anonymous Wireless Mesh Messenger

Offline, decentralized peer-to-peer messaging over Bluetooth Low Energy. No internet,
no server, no Google Play Services. Designed for small trusted groups who want
anonymity and full control of their network.

## What works (full MVP)

- **Peer discovery** — devices advertise and scan simultaneously; live list with
  RSSI and online/offline status.
- **Direct messaging** — GATT connection between two devices; plaintext→encrypted
  messages with delivery status (pending → sent → delivered).
- **Mesh relay (flooding)** — messages hop through intermediate devices. Every packet
  carries a 16-byte message ID, a TTL (5 hops), and is de-duplicated per node.
  Packets are fragmented and reassembled, so messages up to ~1.5 KB travel intact.
- **Store-and-forward** — undeliverable messages are cached (locally and at relay
  nodes) and delivered when the target peer is discovered again. A background retry
  loop flushes queues every 8 s.
- **End-to-end encryption (DMs)** — X25519 key exchange on first contact, per-message
  key via HKDF-SHA256, ChaCha20-Poly1305 AEAD. Intermediaries can relay but never
  read. Keys are pinned after first contact (TOFU): a node that already knows a
  peer's key refuses a *different* key from that peer, so later key swaps (MITM)
  are rejected. Residual caveat: an attacker present at the very first contact could
  still impersonate — a Noise-style handshake with identity keys is the next
  hardening step.
- **Signed public channel** — broadcasts are Ed25519-signed (not encrypted) and flood
  the mesh. Verified before display.
- **Persistence** - Room database keeps chat history, peer keys, and delivery status
  across restarts. The database is encrypted at rest (SQLCipher), private key
  material is stored wrapped under an Android Keystore AES key, and `allowBackup`
  is off.
- **Ephemeral online mailbox** - the cloud relay only holds *undelivered* envelopes
  (ciphertext) and purges everything older than 24 hours. Chat history lives purely
  on devices: nothing is ever deleted from a phone, and the server never accumulates
  data, keeping the free tier viable for the lifetime of the app.
- **Optional PIN lock** — an app-open PIN (PBKDF2-hashed with salt) can be set from
  the Home screen; the app re-locks when backgrounded.
- **Foreground service** keeps scanning/advertising/GATT alive with a notification.

## Project structure

```
app/src/main/java/com/bitchat/
├── GhostwireApp.kt          Initializes data, crypto, mesh
├── MainActivity.kt        Permissions + screen navigation
├── mesh/                  BLE transport + protocol
│   ├── MeshAdvertiser.kt  Advertising (peripheral role)
│   ├── MeshScanner.kt     Scanning (central role)
│   ├── MeshGattServer.kt  GATT server (inbox per device)
│   ├── MeshLink.kt        GATT client connection to one peer
│   ├── MeshPacket.kt      Binary packet codec + fragmentation
│   ├── RelayEngine.kt     Dedup set, reassembly, store-and-forward cache
│   ├── MeshManager.kt     Routing, handshake, relay, retry loop
│   └── MeshService.kt     Foreground service
├── crypto/
│   └── CryptoEngine.kt    X25519, ChaCha20-Poly1305, HKDF, Ed25519
├── data/
│   ├── AppDatabase.kt     Room database (peers, messages)
│   └── Repository.kt      Flows for conversations/messages
└── ui/
    ├── home/              Chat list
    ├── chat/              Chat view with delivery status
    └── discovery/         Nearby devices
```

## Protocol sketch

```
Packet: [BM][ver][type][ttl][msgId 16B][src 16B][dst 16B][len][payload]
Types:  DIRECT (encrypted DM) · BROADCAST (signed) · HANDSHAKE (X25519 key) · ACK
```

- Advertisements: vendor UUID 0xFFAA + [ver][nodeId] (29 of 31 bytes).
- GATT: service f5a4c3e2-...-7e with TX (write) and RX (notify) characteristics;
  both roles run simultaneously on every device.
- Every node relays DIRECT/BROADCAST/HANDSHAKE/ACK once (TTL-1) to its online
  neighbors except the sender; relays cache packets for known-but-offline targets.

## How to run

1. Open the project in Android Studio (Gradle 8.14.3; JDK 21 bundled with Studio or
   JDK 24 both work). If you see a Gradle JVM error, set the Gradle JVM to the
   embedded JDK or accept Studio's fix.
2. Install on **two+ physical phones** — BLE doesn't work on emulators.
3. Grant permissions, start the mesh on each phone. Keep screens on during testing
   (Android pauses BLE scanning when the screen is off on most devices).
4. Test: message a peer directly; then try a 3-device line — A → B → C with B out of
   range of... no wait, A must reach B; test relay by putting A and C within B's range
   but out of each other's range. Messages should hop via B (watch delivery status).
5. Try the public channel: broadcasts reach every node in the mesh, signed and relayed.

## Permission matrix

| API level | Manifest (static) | Runtime-requested |
|---|---|---|
| API 26-30 (8-11) | `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` | `ACCESS_FINE_LOCATION` only |
| API 31-32 (12-12L) | `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` | all three |
| API 33+ (13+) | + `POST_NOTIFICATIONS` | the three + notifications |

`BLUETOOTH_SCAN` uses `neverForLocation`, so no location permission is needed on 12+.
Android 14+ additionally requires `FOREGROUND_SERVICE_CONNECTED_DEVICE`.

## Known limitations / next steps

- First-contact key exchange is MITM-able; keys are pinned (TOFU) after first contact,
  so only the initial pairing window is exposed. A Noise-style handshake with
  identity keys, or QR code pairing, would close it fully.
- BLE scanning pauses with screen off; a wake-lock option would improve background
  delivery at a battery cost.
- Long messages (> ~1.5 KB) are truncated at the UI; MTU negotiation is per-link.
- Relay loops are bounded by TTL, not routing tables; fine for small groups, not for
  large networks.
- No read receipts; delivery = ACK from the final recipient.
