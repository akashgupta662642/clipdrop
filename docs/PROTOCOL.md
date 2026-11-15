# ClipDrop Protocol

**Protocol version:** 1  
**Status:** Draft. The wire format freezes when milestone 2 ships. After that, any incompatible change needs a new protocol version (§15).

This document is the source of truth for how ClipDrop devices talk to each other. The Android app (`/android`) and the Windows app (`/windows`) implement it independently and share no code. If an implementation and this document disagree, the implementation is wrong. Fix the code, or change this document first.

The key words MUST, MUST NOT, SHOULD, SHOULD NOT and MAY are to be read as described in [RFC 2119](https://www.rfc-editor.org/rfc/rfc2119) and [RFC 8174](https://www.rfc-editor.org/rfc/rfc8174).

## Contents

1. [Overview](#1-overview)
2. [Conventions](#2-conventions)
3. [Identity](#3-identity)
4. [Transport and TLS](#4-transport-and-tls)
5. [Discovery](#5-discovery)
6. [Pairing URI](#6-pairing-uri)
7. [Framing](#7-framing)
8. [Messages](#8-messages)
9. [Session lifecycle](#9-session-lifecycle)
10. [Pairing](#10-pairing)
11. [Clipboard](#11-clipboard)
12. [File transfer](#12-file-transfer)
13. [Resuming transfers](#13-resuming-transfers)
14. [Errors](#14-errors)
15. [Versioning and extensibility](#15-versioning-and-extensibility)
16. [Test vectors](#16-test-vectors)
17. [Security considerations](#17-security-considerations)
18. [Constants](#18-constants)

---

## 1. Overview

ClipDrop links an Android phone and a Windows PC on the same local network. It syncs the clipboard and sends files without an account, a cloud server or an internet connection.

There are two roles:

| Role | Platform | Network role |
|---|---|---|
| **Host** | Windows PC | TCP server. Accepts connections from any number of paired clients. |
| **Client** | Android phone | TCP client. Keeps one connection to each paired host. |

The roles never swap. Every pair of devices talks over a single long-lived connection that carries all traffic: control messages, clipboard contents and file data.

```
  Client (phone)                                   Host (PC)
 ┌──────────────────────┐                        ┌──────────────────────┐
 │ Messages             │  JSON control frames   │ Messages             │
 │ (§8)                 │  binary chunk frames   │ (§8)                 │
 ├──────────────────────┤ ◄────────────────────► ├──────────────────────┤
 │ Frames  (§7)         │  [len][type][payload]  │ Frames  (§7)         │
 ├──────────────────────┤                        ├──────────────────────┤
 │ TLS 1.3 / 1.2 (§4)   │  mutual certificates,  │ TLS 1.3 / 1.2 (§4)   │
 │                      │  pinned fingerprints   │                      │
 ├──────────────────────┤                        ├──────────────────────┤
 │ TCP                  │ ─────── connect ─────► │ TCP :47821           │
 └──────────────────────┘                        └──────────────────────┘
```

The design follows from four platform constraints:

* **Android reads the clipboard only while an app has input focus** (Android 10 and later). Phone-to-PC clipboard sync is therefore always a user action. PC-to-phone sync can be automatic, because writing the clipboard from a foreground service is allowed.
* **Windows 10's SChannel has no TLS 1.3.** TLS 1.2 stays allowed, but only with ECDHE + AES-GCM cipher suites (§4.2).
* **Many routers block multicast.** mDNS discovery is only a convenience. The QR code and the last known address always work (§5).
* **No custom cryptography.** Both sides use their platform TLS stack. Identity comes from certificate fingerprints that are exchanged once, out of band, through the QR code.

---

## 2. Conventions

| Item | Rule |
|---|---|
| Integers on the wire | Unsigned, big-endian (network byte order). |
| Text | UTF-8 without a byte-order mark. Invalid UTF-8 is a protocol error. |
| Base64url | [RFC 4648 §5](https://www.rfc-editor.org/rfc/rfc4648#section-5) alphabet with no `=` padding. |
| Hex | Lowercase, no separators. |
| UUID (text) | [RFC 9562](https://www.rfc-editor.org/rfc/rfc9562), lowercase `8-4-4-4-12` form, for example `3f2b8c1e-7a4d-4e6b-9c0a-1d2e3f405162`. Senders generate version 4 (random) UUIDs. |
| UUID (binary) | 16 bytes in network byte order: the order in which the hex digits appear in the text form. **Note for .NET:** `Guid.ToByteArray()` uses a mixed-endian layout, so use `Guid.TryWriteBytes(span, bigEndian: true, out _)` instead. |
| Timestamp | Integer milliseconds since the Unix epoch, UTC. |
| JSON integers | No fraction or exponent. Unless a field says otherwise, the range is 0 to 2^53 − 1 (9,007,199,254,740,991). That is the largest integer every JSON parser represents exactly. |
| Sizes | KiB = 1,024 bytes, MiB = 1,048,576 bytes. |
| Device name | 1 to 64 Unicode code points, trimmed of leading and trailing whitespace. Receivers truncate longer names for display, and replace control characters before display. |

---

## 3. Identity

On first run, each device creates an identity:

* an **EC P-256 key pair** (secp256r1 / prime256v1), and
* a **self-signed X.509 v3 certificate** for that key, signed with ECDSA-with-SHA256 and valid for 25 years from creation. The subject CN SHOULD be the device name at creation time. It is informational only and is never used to decide trust. The serial number SHOULD be a random positive integer of at least 64 bits.

The certificate *is* the device's identity:

```
fingerprint = SHA-256( DER-encoded certificate )      → 32 bytes
deviceId    = base64url( fingerprint )                → 43 characters
```

Example: a fingerprint of 32 bytes of `0x00` gives the device ID `AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA`.

Rules:

* The private key never leaves the device. Android keeps it in the AndroidKeyStore, where it can't be exported. Windows keeps it in a PKCS#12 file encrypted with DPAPI for the current user.
* A device that regenerates its key pair is, by definition, a new device. Every peer must pair with it again.
* A device's name can change at any time. Peers learn the current name from `HELLO` / `HELLO_ACK` (§8.1) and update what they display. Names are never used for identity.

---

## 4. Transport and TLS

### 4.1 TCP

* The host listens on IPv4 (`0.0.0.0`). It tries port **47821** first, then the port it used last time, and finally an OS-assigned ephemeral port. The port actually bound is published through mDNS (§5) and in the pairing QR code (§6).
* Protocol version 1 uses IPv4 only.
* Implementations SHOULD set `TCP_NODELAY`, because most control frames are small and latency-sensitive.
* Liveness is detected by the protocol heartbeat (§9.4), not by TCP keep-alive.

### 4.2 TLS versions and cipher suites

Every connection is TLS with mutual certificate authentication.

| TLS version | Status | Allowed cipher suites |
|---|---|---|
| 1.3 | MUST be negotiated when both stacks support it | `TLS_AES_128_GCM_SHA256` (0x1301), `TLS_AES_256_GCM_SHA384` (0x1302), `TLS_CHACHA20_POLY1305_SHA256` (0x1303) |
| 1.2 | Allowed (needed for Windows 10) | `TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256` (0xC02B), `TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384` (0xC02C) |
| 1.1 and older, SSL | MUST NOT be offered or accepted | none |

How each side enforces this:

* **Client:** enables only TLS 1.3 and TLS 1.2, and offers only the cipher suites listed above.
* **Host:** .NET can't restrict cipher suites per connection on Windows, because `CipherSuitesPolicy` is unsupported there. So after every handshake the host checks the negotiated version and cipher suite. If either isn't in the table, the host closes the connection without sending a single frame.

### 4.3 Certificates and pinning

Both sides present their identity certificate (§3).

* The host MUST require a client certificate.
* The host SHOULD NOT send a list of acceptable certificate authorities.
* The client MUST present its identity certificate even if the host does send such a list.

Trust is decided **only** by comparing fingerprints:

1. Take the peer's leaf certificate (the first certificate in the chain it presented).
2. Compute its fingerprint (§3).
3. Compare it with the expected pin in constant time.

When validating a peer, implementations MUST NOT consult the system trust store, build or verify a chain, check host names, or check validity dates. Two self-signed certificates can't chain to anything. Device clocks can differ, so a date check would also reject a freshly paired phone whose clock runs ahead of the PC.

| Side | Expected pin |
|---|---|
| Client, while pairing | The `fp` parameter from the scanned QR code (§6). |
| Client, after pairing | The stored fingerprint of the host it is connecting to. |
| Host, outside pairing mode | Any fingerprint in its paired-device list. Any other certificate, or a missing one, MUST fail the handshake. |
| Host, while in pairing mode (§10) | Additionally, any certificate with an EC P-256 key. |

**Post-handshake check (mandatory).** After every handshake, including a resumed TLS session, each side MUST recompute the fingerprint of the peer certificate that the TLS stack reports and check it against the rules above. A resumed session may skip the certificate callback, and the pin set may have changed since the session was cached (for example, after an unpair). Implementations MAY simply disable TLS session resumption.

Other TLS details:

* Clients connect by IP address and SHOULD NOT send SNI.
* Both sides MUST send `close_notify` on an orderly shutdown when the TLS stack allows it.

### 4.4 Connections per device

* A client keeps at most one connection to each host.
* If a host authenticates a new connection from a device that already has a live session, the host MUST close the older session once the new one has completed `HELLO` (§9). This rule lets a phone recover quickly after a Wi-Fi switch, without waiting for the old connection to time out.

---

## 5. Discovery

The host advertises itself with DNS-SD over mDNS:

| Item | Value |
|---|---|
| Service type | `_clipdrop._tcp` in the `local.` domain |
| Instance name | The host's device name, truncated to 63 UTF-8 bytes. mDNS conflict resolution may append a suffix. |
| Port | The port the host is actually listening on |
| TXT `id` | The host's device ID (§3) |
| TXT `v` | The highest protocol version the host supports, as a decimal string |
| TXT `name` | The host's device name, truncated to 200 UTF-8 bytes |

Unknown TXT keys MUST be ignored.

**Discovery data is untrusted.** Anyone on the network can advertise any `id`. A client uses discovered addresses only as places to try connecting. Identity is proven solely by the certificate pin during the TLS handshake.

Client behaviour when connecting to a paired host:

1. Browse `_clipdrop._tcp` for up to 3 seconds.
2. Try every resolved service whose `id` matches a paired host.
3. If nothing matches within 3 seconds, try the last address and port at which that host was reached.
4. The user can also enter an address and port by hand. This is the fallback when multicast is blocked *and* the host's address has changed.

Manual entry reconnects to a host that is **already paired**. The pin still proves the host's identity. Pairing itself always needs the QR code (§6), because that is how the phone learns the host's fingerprint and the one-time token.

---

## 6. Pairing URI

When the user starts pairing, the host shows this URI as a QR code:

```
clipdrop://pair?v=1&h=<ipv4>[,<ipv4>…]&p=<port>&fp=<fingerprint>&t=<token>&n=<name>
```

| Parameter | Required | Format |
|---|---|---|
| `v` | yes | URI format version. Always `1` in this document. A client that sees a higher value tells the user to update the app. |
| `h` | yes | 1 to 16 comma-separated dotted-decimal IPv4 addresses, in the host's order of preference. A PC often has several network adapters, and the host can't know which one the phone shares. |
| `p` | yes | Listening port, decimal, 1 to 65535. |
| `fp` | yes | The host's fingerprint as base64url, 43 characters. This is the same string as the host's device ID. |
| `t` | yes | The pairing token: base64url of 16 random bytes, 22 characters (§10). |
| `n` | yes | The host's device name: UTF-8, percent-encoded per RFC 3986. |

Parsing rules:

* Parameters may appear in any order.
* Unknown parameters MUST be ignored.
* A duplicated or malformed known parameter makes the URI invalid. Clients reject an invalid URI with a clear message and make no connection.

Example:

```
clipdrop://pair?v=1&h=192.168.1.20,10.0.0.7&p=47821&fp=Wq3bL0aV0w0bMZbWw6rYVJ2n1bU9jJ8f0lq2cJ8tW0E&t=c2Fsdy1zYWx0LXRva2VuMQ&n=Office%20PC
```

To find the host, the client tries the addresses in `h` in order, waiting at most 3 seconds for each TCP connection (or tries them in parallel), and uses the first connection whose TLS handshake passes the `fp` pin.

---

## 7. Framing

Every message travels in one frame:

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                        length (u32, BE)                       |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|   type (u8)   |  payload (length − 1 bytes) …
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

* `length` counts the type byte plus the payload, so `1 ≤ length ≤ 1,048,576`. A frame is therefore at most 1,048,580 bytes on the wire, including the 4-byte length field.
* A receiver MUST check `length` **before** allocating a buffer. A length of 0 or more than 1,048,576 is a protocol error: the receiver sends `ERROR` with code `frame_too_large` and closes the connection (§14).
* **Control frames** carry a UTF-8 JSON object (§8.2). A zero-length payload MUST be accepted as `{}`.
* **Binary frames** carry raw bytes. In protocol version 1 the only binary frame is `TRANSFER_CHUNK` (0x33).

---

## 8. Messages

### 8.1 Message table

| Code | Name | Direction | Allowed before pairing | Payload |
|---|---|---|---|---|
| `0x01` | `HELLO` | client → host | yes | JSON |
| `0x02` | `HELLO_ACK` | host → client | yes | JSON |
| `0x03` | `PAIR_REQUEST` | client → host | yes | JSON |
| `0x04` | `PAIR_RESULT` | host → client | yes | JSON |
| `0x05` | `UNPAIR` | either | no | JSON (`{}`) |
| `0x10` | `PING` | either | no | JSON |
| `0x11` | `PONG` | either | no | JSON |
| `0x20` | `CLIP_TEXT` | either | no | JSON |
| `0x30` | `TRANSFER_OFFER` | sender → receiver | no | JSON |
| `0x31` | `TRANSFER_ACCEPT` | receiver → sender | no | JSON |
| `0x32` | `TRANSFER_REJECT` | receiver → sender | no | JSON |
| `0x33` | `TRANSFER_CHUNK` | sender → receiver | no | binary |
| `0x34` | `TRANSFER_END` | sender → receiver | no | JSON |
| `0x35` | `TRANSFER_RESULT` | receiver → sender | no | JSON |
| `0x36` | `TRANSFER_CANCEL` | either | no | JSON |
| `0x7F` | `ERROR` | either | yes | JSON |

Type codes are grouped: `0x01–0x0F` session and pairing, `0x10–0x1F` liveness, `0x20–0x2F` clipboard, `0x30–0x3F` transfer, `0x7F` error. Codes `0x80–0xFF` are reserved.

The field tables below list fields in **canonical order** (§8.2). "Optional" fields may be absent.

#### `HELLO` (0x01)

The first frame the client sends after the TLS handshake.

| Field | Type | Rules |
|---|---|---|
| `protocolVersion` | integer | The highest protocol version the sender supports. At least 1. |
| `deviceId` | string | The sender's device ID (§3). MUST equal the fingerprint of the sender's TLS certificate. |
| `deviceName` | string | The sender's current device name (§2). |
| `platform` | string | `"android"` or `"windows"`. Receivers MUST accept other values and treat them as unknown. |
| `appVersion` | string | The app version, for example `"0.1.0"`. Informational only. |

```json
{"protocolVersion":1,"deviceId":"Wq3bL0aV0w0bMZbWw6rYVJ2n1bU9jJ8f0lq2cJ8tW0E","deviceName":"Pixel 9","platform":"android","appVersion":"0.1.0"}
```

#### `HELLO_ACK` (0x02)

The host's reply to `HELLO`. It has the same fields, describing the host, with two differences:

| Field | Type | Rules |
|---|---|---|
| `protocolVersion` | integer | The **negotiated** version (§15.1). |
| `deviceId` | string | The host's device ID. |
| `deviceName` | string | The host's device name. |
| `platform` | string | `"windows"`. |
| `appVersion` | string | The host's app version. |
| `paired` | boolean | `true` if the client's fingerprint is in the host's paired-device list. |

#### `PAIR_REQUEST` (0x03)

| Field | Type | Rules |
|---|---|---|
| `token` | string | The `t` value from the pairing URI: 22 base64url characters. |

#### `PAIR_RESULT` (0x04)

| Field | Type | Rules |
|---|---|---|
| `ok` | boolean | Whether pairing succeeded. |
| `reason` | string, optional | Present exactly when `ok` is `false`. One of `invalid_token`, `expired_token`, `pairing_closed`, `too_many_attempts`. Receivers MUST accept unknown reasons. |

#### `UNPAIR` (0x05)

No fields. The payload is `{}`.

#### `PING` (0x10) and `PONG` (0x11)

| Field | Type | Rules |
|---|---|---|
| `nonce` | integer | `PING`: any value from 0 to 2^32 − 1. `PONG`: the nonce of the `PING` it answers. |

#### `CLIP_TEXT` (0x20)

| Field | Type | Rules |
|---|---|---|
| `clipId` | string | A new UUID for each clip. |
| `text` | string | The clipboard text. Not empty. |
| `timestamp` | integer | When the sender captured the clip. |

#### `TRANSFER_OFFER` (0x30)

| Field | Type | Rules |
|---|---|---|
| `transferId` | string | A UUID chosen by the sender, one per file. It is reused when the transfer resumes (§13). |
| `purpose` | string | `"file"` or `"clipboardImage"`. |
| `name` | string | The file name only, with no directory part. 1 to 255 UTF-8 bytes. |
| `relativePath` | string | For files sent as part of a folder: the directory part relative to the folder that was sent, with `/` as the separator, for example `"Photos/2024"`. `""` for a single file. |
| `size` | integer | The file size in bytes, 0 to 2^53 − 1. |
| `mime` | string | The MIME type, or `"application/octet-stream"` if it is unknown. |

#### `TRANSFER_ACCEPT` (0x31)

| Field | Type | Rules |
|---|---|---|
| `transferId` | string | From the offer. |
| `offset` | integer | The byte offset the sender must continue from: 0 for a new transfer, or the bytes already on disk when resuming (§13). 0 ≤ `offset` ≤ `size`. |

#### `TRANSFER_REJECT` (0x32)

| Field | Type | Rules |
|---|---|---|
| `transferId` | string | From the offer. |
| `reason` | string | One of `declined`, `too_large`, `no_space`, `invalid_name`, `unsupported`, `busy`. Receivers MUST accept unknown reasons. |

#### `TRANSFER_CHUNK` (0x33): binary

```
 0               16              24                        length − 1
+---------------+---------------+------------------------------+
| transferId    | offset        | data                         |
| 16 bytes UUID | u64 BE        | 1 … 262,144 bytes            |
+---------------+---------------+------------------------------+
```

* `transferId`: the binary UUID (§2).
* `offset`: the position of the first data byte within the file.
* `data`: 1 to 262,144 bytes. Senders SHOULD send full 262,144-byte chunks, except for the last one.

The largest chunk frame has `length` = 1 + 16 + 8 + 262,144 = 262,169, well under the frame limit.

#### `TRANSFER_END` (0x34)

| Field | Type | Rules |
|---|---|---|
| `transferId` | string | |
| `sha256` | string | Lowercase hex of the SHA-256 of the whole file, bytes 0 to `size`, even when the transfer was resumed. 64 characters. |

#### `TRANSFER_RESULT` (0x35)

| Field | Type | Rules |
|---|---|---|
| `transferId` | string | |
| `ok` | boolean | `true` once the file is in its final place. |
| `reason` | string, optional | Present exactly when `ok` is `false`. One of `hash_mismatch`, `io_error`, `no_space`. Receivers MUST accept unknown reasons. |

#### `TRANSFER_CANCEL` (0x36)

| Field | Type | Rules |
|---|---|---|
| `transferId` | string | |

#### `ERROR` (0x7F)

| Field | Type | Rules |
|---|---|---|
| `code` | string | See §14. |
| `message` | string | A short English description for logs, at most 256 characters. It MUST NOT contain clipboard contents, file contents, tokens or key material. |

### 8.2 JSON encoding

**Receivers are lenient.** A receiver MUST accept any JSON object that conforms to [RFC 8259](https://www.rfc-editor.org/rfc/rfc8259) in which:

* fields appear in any order,
* unknown fields are present (they MUST be ignored), and
* optional fields are `null` (treated as absent).

The following are protocol errors:

* the payload is not a JSON object, or is invalid UTF-8;
* a required field is missing, or a field has the wrong JSON type;
* a value breaks its rules, for example a negative `size` or a malformed UUID.

A receiver MAY also treat a duplicated key as a protocol error.

**Senders are canonical.** A sender MUST produce exactly this form, so that the golden test vectors (§16) re-encode to identical bytes on both platforms:

1. Fields appear in the order listed in the message tables.
2. Absent optional fields are omitted. `null` is never written.
3. No whitespace outside strings.
4. Strings use minimal escaping:
   * `"` is written as `\"` and `\` as `\\`;
   * U+0008, U+0009, U+000A, U+000C and U+000D are written as `\b`, `\t`, `\n`, `\f` and `\r`;
   * every other code point from U+0000 to U+001F is written as `\u00XX` with lowercase hex.
   * Everything else, including `/`, `<`, `>`, `&`, `'`, `+`, U+007F, U+2028, U+2029 and characters outside the Basic Multilingual Plane, is written as raw UTF-8.
5. Integers are written in decimal, with no leading zeros, fraction or exponent.
6. Booleans are written as `true` and `false`.

> **Why spell this out?** Platform JSON libraries escape text differently by default. For example, `System.Text.Json` escapes non-ASCII and HTML-sensitive characters, while `kotlinx.serialization` doesn't. Fixing one canonical form makes byte-level golden vectors possible. Lenient parsing keeps the two sides interoperable even when a sender gets the canonical form wrong.

---

## 9. Session lifecycle

### 9.1 States

```
          TCP connect
               │
               ▼
        TLS handshake ── pin or cipher-suite check fails ──► closed
               │
               ▼
   client sends HELLO ─► host validates, replies HELLO_ACK
               │
       ┌───────┴───────────┐
  paired = true      paired = false
       │                   │
       │                   ▼
       │               PAIRING ── PAIR_RESULT ok = false ──► closed
       │                   │
       │          PAIR_RESULT ok = true
       ▼                   ▼
     LIVE ◄────────────────┘
       │
   UNPAIR / heartbeat timeout / error / user quits
       ▼
     closed
```

| State | Frames allowed (in either direction) |
|---|---|
| Handshake | Client: `HELLO` first. Host: nothing until it has received `HELLO`, then `HELLO_ACK` or `ERROR`. |
| `PAIRING` | `PAIR_REQUEST`, `PAIR_RESULT`, `ERROR`. |
| `LIVE` | Everything except `HELLO`, `HELLO_ACK`, `PAIR_REQUEST` and `PAIR_RESULT`. |

Receiving a frame that isn't allowed in the current state is a protocol error. In the handshake and `PAIRING` states, the receiver sends `ERROR` with code `not_paired` and closes. In `LIVE`, it sends `ERROR` with code `protocol_error` and closes. Unknown frame types follow §15.2 in every state.

### 9.2 Handshake checks

* **The host checks the HELLO:** `HELLO.deviceId` MUST equal the fingerprint of the client's TLS certificate. A client can't claim to be a device it can't prove it is.
* **The client checks the HELLO_ACK:** `HELLO_ACK.deviceId` MUST equal the pin it connected with.
* **If a client that believes it is paired receives `paired = false`,** the host has forgotten it. This can happen if the host unpaired the client while it was offline, and the connection only got through because the host happened to be in pairing mode. The client closes the connection, marks the host as "unpaired by the PC", and MUST NOT send `PAIR_REQUEST` without a token from a newly scanned QR code.

A mismatch in either of the first two checks is a protocol error.

### 9.3 Timeouts

| Step | Limit | On expiry |
|---|---|---|
| TCP connect, per address | 3 s | Try the next address. |
| TLS handshake | 10 s | Close. |
| `HELLO` after TLS completes (host waits) | 10 s | Close. |
| `HELLO_ACK` after `HELLO` (client waits) | 10 s | Close. |
| Time spent in `PAIRING` | 30 s | The host closes the connection. |

A host SHOULD allow at most 4 connections in the `PAIRING` state at once.

### 9.4 Heartbeat

* Each side tracks the time since it last *received* a frame of any kind.
* After 15 s without receiving a frame, a side sends `PING` with a fresh nonce. It sends another `PING` after every further 15 s of silence.
* After 45 s without receiving a frame, a side closes the connection.
* A side that receives `PING` MUST answer with `PONG` carrying the same nonce, as a control frame (§9.5).

### 9.5 Send scheduling

Each connection has exactly one writer. The writer drains two FIFO queues:

| Queue | Frames | Capacity |
|---|---|---|
| Control | Every frame except `TRANSFER_CHUNK` and `TRANSFER_END` | Unbounded |
| Data | `TRANSFER_CHUNK` and `TRANSFER_END` | 8 frames. Producers suspend while it is full. |

Before writing each data frame, the writer first writes every frame waiting in the control queue. This keeps clipboard sync, heartbeats and cancellation responsive during a large transfer. The bounded data queue gives the file reader backpressure, so a fast disk can't fill memory faster than the network drains it.

`TRANSFER_END` goes in the data queue, so it can never overtake its own chunks.

Because control frames can overtake data frames, a receiver MUST tolerate a `TRANSFER_CANCEL` that arrives before the remaining chunks of the same transfer. Chunks for an unknown or cancelled transfer MUST be silently discarded.

### 9.6 Reconnecting (client)

When a session to a paired host ends for any reason other than `UNPAIR`, the client reconnects with exponential backoff:

* The delay before attempt *n* (counting from 0) is `min(30 s, 1 s × 2ⁿ)`, multiplied by a random factor between 0.8 and 1.2.
* The backoff resets after a successful `HELLO_ACK`.
* When Wi-Fi becomes available, the client resets the backoff and tries immediately.

The host never initiates connections.

### 9.7 Closing

* Either side may close at any time: send `close_notify`, then close the TCP connection.
* Before closing because of an error, a side SHOULD try to flush an `ERROR` frame, waiting at most 1 s.
* File transfers that are in progress when a session closes are paused, not failed (§13).

---

## 10. Pairing

Pairing runs once per pair of devices. Afterwards each device stores the other's fingerprint.

```
 Phone (client)                                   PC (host)
      │                                    user clicks "Pair device":
      │                                    token = 16 random bytes,
      │                                    expires in 120 s, single use
      │      QR: clipdrop://pair?…&fp=…&t=…       │
      │ ◄─────────── scanned from screen ──────── │
      │                                           │
      │ ══ TLS: client pins fp; host accepts ═══► │
      │      any client cert (pairing mode)       │
      │ ── HELLO ───────────────────────────────► │
      │ ◄────────────────── HELLO_ACK paired=false│
      │ ── PAIR_REQUEST {token} ────────────────► │ constant-time compare,
      │                                           │ check expiry, store pin,
      │ ◄─────────────────── PAIR_RESULT {ok:true}│ leave pairing mode
      │ store pin, name, last host:port           │
      │              session is LIVE              │
```

### 10.1 Host

1. **Entering pairing mode.** On the user's request, the host draws 16 bytes from a cryptographically secure random number generator. It sets the expiry to now + 120 s and the failure count to 0, then shows the QR code (§6). Only one token is active at a time: starting pairing again replaces the old token.
2. **While pairing mode is open,** the TLS layer also accepts unknown client certificates (§4.3).
3. **On `PAIR_REQUEST`,** the host checks the following in order:
   1. Pairing mode is closed → `PAIR_RESULT` with reason `pairing_closed`.
   2. The token has expired → reason `expired_token`, and pairing mode closes.
   3. The token is not valid base64url, or its decoded bytes differ from the active token (compared in constant time) → reason `invalid_token`, and the failure count goes up by 1. On the 5th failure the reason is `too_many_attempts` instead, and pairing mode closes.
   4. Otherwise pairing succeeds. The host stores the client's device ID, name and platform, and the pairing time. It closes pairing mode, which consumes the token, sends `PAIR_RESULT` with `ok` set to `true`, and the session becomes `LIVE`.
4. After any failure, the host sends `PAIR_RESULT` and closes the connection.
5. Each connection gets at most one `PAIR_REQUEST`.

### 10.2 Client

1. Parse the URI (§6). If it is invalid, stop.
2. Connect to the addresses in `h`, pinning `fp` (§4.3), and send `HELLO`.
3. On `HELLO_ACK`:
   * `paired = false`: send `PAIR_REQUEST` with the token `t`.
   * `paired = true`: the host already knows this phone, so skip straight to step 4.
4. On success, store the host's fingerprint (`fp`), its name from `HELLO_ACK`, and the address and port that worked.

### 10.3 What to tell the user on failure

This mapping is guidance for the client UI and isn't part of the wire protocol.

| What happened | Likely cause | Message |
|---|---|---|
| No address in `h` is on the phone's Wi-Fi subnet | Different network | "Your phone and PC are on different networks." |
| An address is on the phone's subnet, but the connection timed out | Firewall | "The PC didn't answer. Check that Windows Firewall allows ClipDrop on private networks." |
| The connection was refused | The app isn't listening | "ClipDrop isn't running on the PC, or it's using a different port." |
| The TLS pin didn't match | The wrong device answered | "The device that answered isn't the PC that showed this code." |
| `expired_token` | Took longer than 2 minutes | "The code expired. Show a new one on the PC." |
| `invalid_token` or `too_many_attempts` | Wrong or stale QR code | "That code isn't valid any more. Show a new one on the PC." |
| `pairing_closed` | The PC closed pairing mode | "Pairing isn't open on the PC. Click Pair device again." |

### 10.4 Unpairing

* **Sending:** a side that unpairs sends `UNPAIR`, deletes its stored pin for the peer, cancels every transfer with that peer (deleting partial files), and closes the connection.
* **Receiving:** a side that receives `UNPAIR` does the same, without replying.
* **If the peer is offline,** the device deletes the pin locally. The peer's next connection attempt then fails the TLS pin check (§4.3).

---

## 11. Clipboard

### 11.1 Text

* **Sending.** A sender builds `CLIP_TEXT`. If the encoded frame would exceed the frame limit (§7), it MUST NOT send it, and tells the user that the clip is too large to sync.
* **Receiving.** The receiver records the clip for echo suppression (§11.3), writes it to the local clipboard, and adds it to the local history if history is enabled.
* **Which clips are applied:**
  * A phone applies PC-to-phone clips only while "auto-sync PC to phone" is on.
  * A PC always applies phone-to-PC clips, because the phone only sends one when the user explicitly asks.

### 11.2 Images

* An image travels as a transfer (§12) with:
  * `purpose`: `"clipboardImage"`
  * `mime`: `"image/png"`
  * `name`: `"clipboard.png"`
  * `relativePath`: `""`
* When the transfer succeeds, the receiver places the image on its clipboard instead of saving it in the download folder.
* Clipboard-image transfers are never resumed (§13). If the connection drops, both sides drop them.
* A receiver MAY reject a clipboard image larger than 64 MiB with reason `too_large`.

### 11.3 Echo suppression and sensitive content

These rules are local behaviour, but both sides MUST follow them, or a clip would bounce back and forth between devices.

* **Echo suppression.** Each side remembers the SHA-256 of at least the last 8 clips it received or wrote. For text, that is the hash of the UTF-8 text. For images, it is the hash of the PNG bytes as received. A local clipboard change whose hash matches a remembered clip MUST NOT be sent. The Windows host MUST also ignore the clipboard update that its own write causes (it can detect this with `GetClipboardSequenceNumber`).
* **Sensitive content.** Senders MUST NOT send a clip that the platform marks as sensitive:
  * Android: `ClipDescription.EXTRA_IS_SENSITIVE`.
  * Windows: any of the clipboard formats `ExcludeClipboardContentFromMonitorProcessing` or `Clipboard Viewer Ignore`, or `CanIncludeInClipboardHistory` or `CanUploadToCloudClipboard` with the value 0.

---

## 12. File transfer

```
 Sender                                           Receiver
   │ ── TRANSFER_OFFER {id, name, size…} ───────► │ sanitise name, check space,
   │                                              │ auto-accept or ask the user
   │ ◄───── TRANSFER_ACCEPT {id, offset: 0} ───── │   (or TRANSFER_REJECT)
   │ ── TRANSFER_CHUNK [id | 0      | 256 KiB] ─► │ append to .part, update hash
   │ ── TRANSFER_CHUNK [id | 262144 | 256 KiB] ─► │
   │ ── …                                         │
   │ ── TRANSFER_END {id, sha256} ──────────────► │ compare hashes
   │ ◄──────── TRANSFER_RESULT {id, ok: true} ─── │ file moved into place
```

### 12.1 Sender

* **Offers.** The sender creates a `transferId` for each file and sends `TRANSFER_OFFER`. A folder becomes one offer per file, with `relativePath` set.
* **One transfer at a time.** A sender SHOULD stream one transfer at a time on each connection and queue the rest.
* **Streaming.**
  * The sender reads the source through a 256 KiB buffer and updates an incremental SHA-256 as it reads. It never loads the whole file into memory.
  * It sends `TRANSFER_CHUNK` frames starting at the accepted offset, and then `TRANSFER_END`.
* **Waiting for an answer.** The receiver may be asking the user, so the sender waits up to 10 minutes for `TRANSFER_ACCEPT` or `TRANSFER_REJECT`. After that, it cancels.
* **Lost source.** If the sender can no longer read the source, for example because it was deleted or access was revoked, it sends `TRANSFER_CANCEL`.

### 12.2 Receiver

**On `TRANSFER_OFFER`:**

1. **Validate the fields.** The value checks of §8.2 apply. An unknown `purpose` gets a reject with reason `unsupported`.
2. **Sanitise the name and path (§12.3).** If nothing usable remains, reject with reason `invalid_name`.
3. **Check free space.** There must be room for `size − offset` bytes. Otherwise reject with reason `no_space`.
4. **Decide.**
   * Files from a paired device are auto-accepted, if that setting is on and `size` is under the user's threshold. Otherwise the receiver asks the user.
   * Clipboard images follow §11.
5. **Answer.** Send `TRANSFER_ACCEPT` with the offset (0, or the resume offset from §13), or `TRANSFER_REJECT`.

Partial data is written to a `.part` file that only the receiver uses. Each platform's documentation names the exact location.

**On `TRANSFER_CHUNK`:**

1. Unknown or cancelled `transferId`: discard the chunk silently (§9.5).
2. `offset` doesn't equal the bytes received so far, or is at least 2^63 when read as an unsigned integer: protocol error.
3. `offset + data length > size`: protocol error.
4. Otherwise, append the data to the `.part` file and update the incremental SHA-256.
5. Report progress to the local UI at most 10 times per second.

**On `TRANSFER_END`:**

1. If the bytes received don't equal `size`, that is a protocol error.
2. Finish the hash and compare it with `sha256`.
3. **Match:** move the `.part` file to its final name. If a file of that name already exists, add ` (1)`, ` (2)`, and so on before the extension. Never overwrite a file. Then send `TRANSFER_RESULT` with `ok` set to `true`.
4. **Mismatch:** delete the `.part` file and send `TRANSFER_RESULT` with `ok` set to `false` and reason `hash_mismatch`.

**On `TRANSFER_CANCEL`,** in either direction:

* Both sides stop the transfer and mark it cancelled.
* The receiver deletes the `.part` file.
* No `TRANSFER_RESULT` follows.
* A `TRANSFER_CANCEL` for an unknown `transferId` is ignored.

**Concurrency.** A receiver MUST handle several accepted transfers at the same time, keyed by `transferId`.

### 12.3 Sanitising names and paths

Every received `name` and `relativePath` goes through the following rules before the receiver touches the file system. Both platforms apply the Windows rules, so a file received on the phone can later be sent to a PC unchanged.

1. **Reject the offer (`invalid_name`)** if:
   * `relativePath` starts with `/` or `\`;
   * `relativePath` contains a drive letter (`C:`) or a UNC prefix (`\\`);
   * any segment of `relativePath`, or `name` itself, is `.` or `..`.
2. **Split `relativePath`** on `/` and `\`, and drop empty segments.
3. **In each segment and in `name`:**
   1. Replace control characters (U+0000–U+001F) and `< > : " / \ | ? *` with `_`.
   2. Strip trailing dots and spaces.
   3. If the base name, compared case-insensitively and ignoring any extension, is `CON`, `PRN`, `AUX`, `NUL`, `COM1`–`COM9` or `LPT1`–`LPT9`, prefix it with `_`.
   4. If the result is empty, replace it with `_`.
4. **Limit lengths.** Truncate `name` to 255 UTF-8 bytes, keeping the extension. Truncate each segment to 255 UTF-8 bytes.
5. **Check containment.** Resolve the final path and confirm that it lies inside the download folder **before** opening it. If it doesn't, reject with `invalid_name`.

---

## 13. Resuming transfers

Resuming applies to `purpose = "file"` only.

**Persistence.** Both sides persist each unfinished transfer:

* `transferId`, direction and peer device ID;
* `name`, `relativePath` and `size`;
* the local path (source or `.part` file);
* bytes done, and state.

**After a session becomes `LIVE` again:**

1. **The sender** re-sends `TRANSFER_OFFER` for each unfinished outgoing transfer to that peer. The offer uses the same `transferId` and the same field values.
2. **The receiver** looks up the `transferId`:
   * **Resume.** If it has an unfinished incoming transfer with that ID from the same peer, with the same `name`, `relativePath` and `size`, and its `.part` file exists with length L ≤ `size`:
     1. It re-hashes the `.part` file to rebuild the incremental SHA-256 state.
     2. It replies `TRANSFER_ACCEPT` with `offset` = L.
     3. The offset MUST be the file's actual length on disk, not a stored counter, because the stored counter may be ahead of what was flushed.
   * **Start fresh.** Otherwise it deletes any stale `.part` file and treats the offer as a new transfer.
3. **The sender** re-hashes bytes 0 to `offset` of the source into a fresh incremental SHA-256, then streams from `offset`. `TRANSFER_END` still carries the hash of the whole file.
4. **If the hash doesn't match** (for example, because the source changed while the devices were apart), the receiver deletes the `.part` file and reports `hash_mismatch` (§12.2). If the transfer had resumed from an offset above 0 and hasn't been restarted before, the sender re-sends the offer once, with the same `transferId`. The receiver no longer has a `.part` file, so it accepts at offset 0. A second mismatch fails the transfer.

**Cleanup.** A receiver MAY delete `.part` files that have been untouched for 7 days. A later resume of that transfer simply starts from 0.

---

## 14. Errors

`ERROR` reports a problem to the peer. Nobody replies to an `ERROR`.

| Code | Meaning | After sending |
|---|---|---|
| `unknown_type` | The frame type isn't known to the receiver (§15.2). | Keep the session. |
| `protocol_error` | A malformed frame or JSON, a missing or invalid field, an invalid value, or a broken state rule. | Close. |
| `frame_too_large` | The frame length is 0 or more than 1,048,576. | Close. |
| `unsupported_version` | There is no common protocol version (§15.1). | Close. |
| `not_paired` | A frame that needs pairing arrived before pairing completed. | Close. |
| `internal` | An unexpected local failure. | Close. |

A receiver MUST accept unknown error codes, and treats them like `protocol_error`. "Close" means: flush the `ERROR` frame (best effort, at most 1 s), then close TLS and TCP (§9.7).

---

## 15. Versioning and extensibility

### 15.1 Version negotiation

`protocolVersion` is an integer that starts at 1. Each implementation supports a contiguous range of versions, from its minimum to its maximum.

1. The client sends its maximum version in `HELLO`.
2. The host computes *v* = min(client maximum, host maximum).
   * If *v* is below the host's minimum, the host sends `ERROR` with code `unsupported_version` and closes.
   * Otherwise it sends *v* in `HELLO_ACK`.
3. If *v* is outside the client's range, the client sends `ERROR` with code `unsupported_version` and closes.
4. Both sides speak version *v* for the rest of the session.

### 15.2 Compatible changes

The following changes don't need a new protocol version:

* **New optional JSON fields.** Receivers ignore unknown fields.
* **New message types that are safe to ignore.** A receiver that gets an unknown type replies `ERROR` with code `unknown_type` and otherwise ignores the frame. It still validates the frame length first. A sender that gets `unknown_type` back MUST carry on, and should fall back to behaviour that older peers understand.
* **New values for `reason`, `platform` and error `code`.** Receivers accept unknown values.

Anything else needs a new protocol version. That includes changing the meaning or format of an existing field or frame, adding a required field, and changing a limit.

---

## 16. Test vectors

`docs/test-vectors/` holds golden frames that both test suites consume. Each file is JSON:

```json
{
  "description": "Control frames with canonical JSON payloads",
  "vectors": [
    {
      "name": "hello-basic",
      "type": "HELLO",
      "message": { "protocolVersion": 1, "deviceId": "…", "deviceName": "Pixel 9", "platform": "android", "appVersion": "0.1.0" },
      "hex": "00000078017b2270726f746f636f6c56657273696f6e223a31…"
    },
    {
      "name": "length-over-limit",
      "hex": "0010000101",
      "error": "frame_too_large"
    }
  ]
}
```

Each test suite MUST do the following:

* **Positive vectors** (those with `message`):
  1. Decode `hex` with the frame codec and get the expected message.
  2. Encode that message again and get exactly `hex`.
* **Negative vectors** (those with `error`): decoding `hex` fails with that error.

The vectors cover the following:

* every message type, including unicode, escaping and emoji text in `CLIP_TEXT`;
* `TRANSFER_CHUNK` headers, including a maximal offset and data length;
* frame limits;
* pairing-URI parsing;
* fingerprint and device-ID derivation from a fixed certificate.

The vector files are written in milestone 2, together with the codecs.

---

## 17. Security considerations

[SECURITY.md](SECURITY.md) has the full threat model. In summary:

* **Trust comes only from pinned certificate fingerprints.** mDNS records, IP addresses, device names and `HELLO` fields are untrusted until the TLS pin check and the `deviceId` check (§9.2) pass.
* **The QR code is the out-of-band channel.**
  * It gives the phone the host's fingerprint, so the phone can't be fooled by an impostor host.
  * Its one-time token proves to the host that the connecting phone saw the PC's screen.
  * Someone who photographs the QR code and pairs first, within its 2-minute single-use window, gets in. The PC shows every newly paired device, and the user can revoke it.
* **Pairing mode is the only time the host accepts unknown certificates.** It is short, it closes after 5 bad tokens, and it accepts at most 4 unpaired connections at once.
* **Not defended:**
  * traffic analysis (the timing and size of messages);
  * denial of service by other devices on the network (for example, burning the 5 pairing attempts);
  * a compromised or unlocked device.
* **Never logged:** implementations never log clipboard contents, file contents, tokens or key material.

---

## 18. Constants

| Name | Value | Section |
|---|---|---|
| `PROTOCOL_VERSION` | 1 | §15 |
| `DEFAULT_PORT` | 47821 | §4.1 |
| `SERVICE_TYPE` | `_clipdrop._tcp` | §5 |
| `URI_SCHEME` | `clipdrop` | §6 |
| `MAX_FRAME_LENGTH` | 1,048,576 bytes (type + payload) | §7 |
| `MAX_CHUNK_DATA` | 262,144 bytes | §8.1 |
| `CHUNK_HEADER_SIZE` | 24 bytes | §8.1 |
| `DATA_QUEUE_CAPACITY` | 8 frames | §9.5 |
| `HEARTBEAT_IDLE` | 15 s | §9.4 |
| `HEARTBEAT_TIMEOUT` | 45 s | §9.4 |
| `CONNECT_TIMEOUT` | 3 s per address | §9.3 |
| `HANDSHAKE_TIMEOUT` | 10 s (TLS, `HELLO`, `HELLO_ACK`) | §9.3 |
| `PAIRING_SESSION_TIMEOUT` | 30 s | §9.3 |
| `MAX_PAIRING_CONNECTIONS` | 4 | §9.3 |
| `PAIRING_TOKEN_BYTES` | 16 | §10.1 |
| `PAIRING_TOKEN_TTL` | 120 s | §10.1 |
| `PAIRING_MAX_FAILURES` | 5 | §10.1 |
| `RECONNECT_BACKOFF` | 1 s doubling to 30 s, ±20 % jitter | §9.6 |
| `OFFER_RESPONSE_TIMEOUT` | 10 min | §12.1 |
| `MAX_CLIPBOARD_IMAGE` | 64 MiB (receiver MAY reject above) | §11.2 |
| `ECHO_HISTORY` | ≥ 8 clips | §11.3 |
| `PROGRESS_RATE` | ≤ 10 updates/s | §12.2 |
