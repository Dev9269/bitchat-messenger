# Ghostwire online mode — Firebase setup

Ghostwire's online mode uses **Cloud Firestore** on the free (Spark) plan.
No server code, no Firebase SDK needed by the APK: the app talks to the
Firestore REST API + Firebase Auth REST API directly with your Web API key.

## Free tier

- Cloud Firestore: **1 GiB** stored data for free, 50k reads/day, 20k writes/day.
  E2E-encrypted messages are ~150 B each, so 1 GiB holds millions of messages.
- Cloud Storage: **5 GB** free (use later for image/file attachments).

## 1. Create the Firebase project

1. Go to <https://console.firebase.google.com> and click **Add project**.
2. Name it (e.g. `ghostwire-mesh`). Disable Google Analytics if asked.
3. In **Build → Firestore Database**, click **Create database**.
   Use production mode (rules below open it up).

## 2. Enable anonymous authentication

The app logs into Firebase with **anonymous auth** (no email/password). Each
installation gets a stable UID that owns its data — this is what stops
impersonation: usernames and node IDs get bound to the UID that claimed them.

1. **Build → Authentication → Get started**.
2. Wait for the default sign-in methods screen, then **Sign-in method**.
3. Find **Anonymous** → enable it → **Save**.

## 3. Publish the security rules

These live at the repo root: `firestore.rules` (they mirror the anonymous-auth
contract). Open the file, copy everything, then:

**Build → Firestore Database → Rules** → replace the default rules → **Publish**.

The contract: an anonymous session gets a UID. The first document a UID writes
in `profiles/{username}` / `nodes/{nodeId}` binds that username/node to the
UID forever (create-only, updates only by owner, deletes forbidden).
Group membership key envelopes can only be planted by the group owner.
Messages/inboxes are UID-scoped. Firestore NEVER enforces the "anonymous
account" UI — the REST layer + these rules do.

> IMPORTANT if you already had data under the OLD open rules: those documents
> have no `uid` field, so after publishing these rules they are frozen (bound
> to nobody, editable by nobody, deleted-by-nobody). Delete test data or wipe
> the collections before publishing, or start fresh — rules first, then app.

## 4. Get your two config values

1. **Project ID**: *Project settings → General → Project ID* (e.g. `ghostwire-mesh`).
2. **Web API key**: *Project settings → Your apps → Web app → Create app*,
   copy the **API key** from the generated `<script>` snippet. (It's a public
   client key; it's safe to ship, which is why we treat it like the anon key.)

## 5. In the app

Open **Ghostwire → Home (cloud icon) → Online mode**, paste the two values,
and **Save & connect**. The app creates its anonymous account at
`identitytoolkit` on first connect, then signs every Firestore request with an
ID token. Messages travel E2E-encrypted through Firestore at the same time as
they go over Bluetooth.

## Self-test (two devices)

1. **Clean install → connect** on device 1. In *Authentication → Users* there
   should be exactly ONE anonymous user.
2. **Force-stop → reopen → connect** on that device: still exactly ONE user —
   the refresh token is reused, no duplicate UID.
3. On device 2, set the **same username** and connect → the app shows the
   username-taken error; the profile doc stays owned by device 1's UID.
4. (Advanced) Tamper with the API key in the app → requests get `403` instead
   of `200` — the anonymous account can't claim others.

## Data layout (map)

```
profiles/{username}      -> { uid, username, node_id, display_name, x_pub }
nodes/{nodeId}           -> { uid, username, node_id, display_name, x_pub }   (reverse index)
myinbox/{nodeId}/messages/{msgId}
                          -> { msg_id, sender, recipient_node, payload(enc), ts }
groups/{groupId}         -> { uid, name, created_by, created_at }
groups/{groupId}/members/{nodeId}
                          -> { node_id, key_env }  (group key envelope, encrypted to member)
groups/{groupId}/messages/{msgId}
                          -> { msg_id, sender, payload(signed enc), ts }
mygroups/{nodeId}/invites/{groupId} -> { group_id }
presence/{nodeId}        -> { uid, node_id, online, last_seen }
```

## Privacy model

| Thing             | Stored as                                  |
|-------------------|--------------------------------------------|
| DM body           | ChaCha20-Poly1305 + X25519 (per-message keyed) base64 |
| Group body        | ChaCha20-Poly1305 encrypted with group key, Ed25519-signed |
| Group group key   | distributed as per-member envelope (each member's X25519 pub) |
| Usernames/keys    | plain (public identity + pubkey, fine to share) |