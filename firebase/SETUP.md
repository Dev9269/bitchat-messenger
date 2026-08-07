# Bitchat online mode — Firebase setup

Bitchat's online mode uses **Cloud Firestore** on the free (Spark) plan.
No server code, no Firebase SDK needed by the APK: the app talks to the
Firestore REST API directly with your Web API key.

## Free tier

- Cloud Firestore: **1 GiB** stored data for free, 50k reads/day, 20k writes/day.
  E2E-encrypted messages are ~150 B each, so 1 GiB holds millions of messages.
- Cloud Storage: **5 GB** free (use later for image/file attachments).

## 1. Create the Firebase project

1. Go to <https://console.firebase.google.com> and click **Add project**.
2. Name it (e.g. `bitchat-messenger`). Disable Google Analytics if asked.
3. In **Build → Firestore Database**, click **Create database**.
   Use default settings (production mode is fine; rules below open it up).

## 2. Open security rules

Firestore uses a NoSQL structure of *documents*. The app reads/writes these
paths with no account, so the rules must allow anonymous read/write over the
specific collections the app uses.

Go to **Build → Firestore Database → Rules** and paste:

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    // Usernames -> nodeId + public X25519 keys (used for E2EE exchange).
    match /profiles/{username} {
      allow read, write: if true;
    }
    match /nodes/{nodeId} {
      allow read, write: if true;
    }
    // Private inboxes, one subcollection per recipient nodeId.
    match /myinbox/{recipientId}/messages/{msgId} {
      allow read, create, delete: if true;
      allow update: if false;
    }
    // Group metadata + membership (key envelopes) + messages.
    match /groups/{groupId} {
      allow read, write: if true;
    }
    match /groups/{groupId}/members/{nodeId} {
      allow read, write: if true;
    }
    match /groups/{groupId}/messages/{msgId} {
      allow read, write: if true;
    }
    // Invite pointer: written into each member's mailbox so they learn about new groups.
    match /mygroups/{nodeId}/invites/{groupId} {
      allow read, write: if true;
    }
    // Presence heartbeats.
    match /presence/{nodeId} {
      allow read, write: if true;
    }
  }
}
```

This is intentionally open — it is the login-free MVP contract. Messages
themselves are **end-to-end encrypted on-device before upload**, so the
server (and Google) only ever sees ciphertext. The plain x_pub keys are just
X25519 public keys (safe to be public). To go stricter later, swap these
rules for per-node authenticated checks.

## 3. Get your two config values

1. **Project ID**: *Project settings → General → Project ID* (e.g. `bitchat-messenger`).
2. **Web API key**: *Project settings → Your apps → Web app → Create app*,
   copy the **API key** from the generated `<script>` snippet. (It's a public
   client key; it's safe to ship, which is why we treat it like the anon key.)

## 4. In the app

Open **Bitchat → Home (cloud icon) → Online mode**, paste the two values,
and **Save & connect**. Messages then travel E2E-encrypted through
Firestore at the same time as they go over Bluetooth.

## Data layout (map)

```
profiles/{username}      -> { username, node_id, display_name, x_pub }
nodes/{nodeId}           -> { username, node_id, display_name, x_pub }   (reverse index)
myinbox/{nodeId}/messages/{msgId}
                          -> { msg_id, sender, recipient_node, payload(enc), ts }
groups/{groupId}         -> { name, created_by, created_at }
groups/{groupId}/members/{nodeId}
                          -> { node_id, key_env }  (group key envelope, encrypted to member)
groups/{groupId}/messages/{msgId}
                          -> { msg_id, sender, payload(signed enc), ts }
mygroups/{nodeId}/invites/{groupId} -> { group_id }
presence/{nodeId}        -> { node_id, online, last_seen, username }
```

## Privacy model

| Thing             | Stored as                                  |
|-------------------|--------------------------------------------|
| DM body           | ChaCha20-Poly1305 + X25519 (per-message keyed) base64 |
| Group body        | ChaCha20-Poly1305 encrypted with group key, Ed25519-signed |
| Group group key   | distributed as per-member envelope (each member's X25519 pub) |
| Usernames/keys    | plain (public identity + pubkey, fine to share) |