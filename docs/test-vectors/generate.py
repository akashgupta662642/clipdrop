#!/usr/bin/env python3
"""Generate the ClipDrop golden test vectors (docs/PROTOCOL.md §16).

This script is a third, independent encoder for the protocol. Python's
json.dumps(ensure_ascii=False, separators=(",", ":")) writes exactly the
canonical JSON form of PROTOCOL.md §8.2:

* \\" and \\\\ escaped;
* \\b \\t \\n \\f \\r for those control characters;
* \\u00xx (lowercase) for the remaining C0 controls;
* everything else as raw UTF-8.

The Kotlin and .NET codecs must reproduce these bytes exactly.

Usage:  python docs/test-vectors/generate.py
Only the Python standard library is needed.
"""

from __future__ import annotations

import base64
import hashlib
import json
import struct
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent

MAX_JSON_INTEGER = 2**53 - 1

TYPE_CODES = {
    "HELLO": 0x01,
    "HELLO_ACK": 0x02,
    "PAIR_REQUEST": 0x03,
    "PAIR_RESULT": 0x04,
    "UNPAIR": 0x05,
    "PING": 0x10,
    "PONG": 0x11,
    "CLIP_TEXT": 0x20,
    "TRANSFER_OFFER": 0x30,
    "TRANSFER_ACCEPT": 0x31,
    "TRANSFER_REJECT": 0x32,
    "TRANSFER_CHUNK": 0x33,
    "TRANSFER_END": 0x34,
    "TRANSFER_RESULT": 0x35,
    "TRANSFER_CANCEL": 0x36,
    "ERROR": 0x7F,
}

# A self-signed EC P-256 certificate (CN=ClipDrop Test Vector, ecdsa-with-SHA256,
# valid 2026-11-15 to 2051-11-15). Generated once with OpenSSL; its private key
# was discarded because only the certificate bytes matter here.
IDENTITY_CERT_DER = bytes.fromhex(
    "308201943082013ba0030201020214449dee4313d9d88e2769e5a039fc37c58a5c11fb300a06082a8648ce3d04030230"
    "1f311d301b06035504030c14436c697044726f70205465737420566563746f723020170d323631313135313034333434"
    "5a180f32303531313131353130343334345a301f311d301b06035504030c14436c697044726f70205465737420566563"
    "746f723059301306072a8648ce3d020106082a8648ce3d03010703420004e9debf6ab5e48db3890e105499139388ed82"
    "5664bf736fe1e36766630229250a27135f3c02707ea6f75231e43cf24fa30a8bd0b4048f892a348c01526931d93da353"
    "3051301d0603551d0e041604142670f6846169683e62bf473f9fb79eece8172183301f0603551d230418301680142670"
    "f6846169683e62bf473f9fb79eece8172183300f0603551d130101ff040530030101ff300a06082a8648ce3d04030203"
    "470030440220270947ab8869c97b5129fdb8c7ad29193bca169b508e415320271af52fc3b5b402203ff37e1a6dbe92a7"
    "6a25b91bbd5fd93e80da75edc6dd082f04c2e6867248a71f"
)


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def canonical(obj: object) -> bytes:
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def frame(type_code: int, payload: bytes) -> bytes:
    return struct.pack(">IB", 1 + len(payload), type_code) + payload


def chunk_payload(transfer_id: str, offset: int, data: bytes) -> bytes:
    return uuid.UUID(transfer_id).bytes + struct.pack(">Q", offset) + data


PHONE_ID = b64url(hashlib.sha256(IDENTITY_CERT_DER).digest())
HOST_ID = b64url(hashlib.sha256(b"clipdrop test host").digest())
TOKEN = b64url(bytes(range(16)))

CLIP_ID = "3f2b8c1e-7a4d-4e6b-9c0a-1d2e3f405162"
TRANSFER_A = "a3bb189e-8bf9-4888-9912-ace4e6543002"
TRANSFER_B = "00000000-0000-4000-8000-000000000000"
TRANSFER_C = "ffffffff-ffff-4fff-bfff-ffffffffffff"
TIMESTAMP = 1_763_200_000_000

TRICKY_TEXT = (
    'quote " backslash \\ slash / tab \t newline \n return \r backspace \b formfeed \f '
    "nul \u0000 unit-sep \u001f del \u007f line-sep \u2028 para-sep \u2029 "
    "html <a href='x'>&amp;</a> plus + done"
)
UNICODE_TEXT = "naïve café — 日本語テキスト 😀👍🏽 עברית Ελληνικά"


# ---------------------------------------------------------------- frames.json


def positive(name: str, type_name: str, message: dict) -> dict:
    code = TYPE_CODES[type_name]
    if type_name == "TRANSFER_CHUNK":
        payload = chunk_payload(message["transferId"], message["offset"], bytes.fromhex(message["dataHex"]))
    else:
        payload = canonical(message)
    return {"name": name, "type": type_name, "typeCode": code, "message": message, "hex": frame(code, payload).hex()}


def lenient(name: str, type_name: str, raw_payload: bytes, message: dict) -> dict:
    """Valid but non-canonical input: decodes to `message`, re-encodes canonically."""
    code = TYPE_CODES[type_name]
    return {
        "name": name,
        "type": type_name,
        "typeCode": code,
        "message": message,
        "hex": frame(code, raw_payload).hex(),
        "reencodedHex": frame(code, canonical(message)).hex(),
    }


def unknown(name: str, type_code: int, payload: bytes) -> dict:
    return {
        "name": name,
        "type": "UNKNOWN",
        "typeCode": type_code,
        "message": {"payloadHex": payload.hex()},
        "hex": frame(type_code, payload).hex(),
    }


def hello(platform: str = "android") -> dict:
    return {
        "protocolVersion": 1,
        "deviceId": PHONE_ID,
        "deviceName": "Pixel 9",
        "platform": platform,
        "appVersion": "0.1.0",
    }


def frames() -> list[dict]:
    offer_file = {
        "transferId": TRANSFER_A,
        "purpose": "file",
        "name": "report.pdf",
        "relativePath": "",
        "size": 1_048_577,
        "mime": "application/pdf",
    }
    return [
        positive("hello-basic", "HELLO", hello()),
        positive("hello-unknown-platform", "HELLO", hello(platform="linux")),
        positive(
            "hello-ack-paired",
            "HELLO_ACK",
            {
                "protocolVersion": 1,
                "deviceId": HOST_ID,
                "deviceName": "Office PC",
                "platform": "windows",
                "appVersion": "0.1.0",
                "paired": True,
            },
        ),
        positive(
            "hello-ack-unpaired-unicode-name",
            "HELLO_ACK",
            {
                "protocolVersion": 1,
                "deviceId": HOST_ID,
                "deviceName": "Akash’s ThinkPad 💻",
                "platform": "windows",
                "appVersion": "0.1.0",
                "paired": False,
            },
        ),
        positive("pair-request", "PAIR_REQUEST", {"token": TOKEN}),
        positive("pair-result-ok", "PAIR_RESULT", {"ok": True}),
        positive("pair-result-expired", "PAIR_RESULT", {"ok": False, "reason": "expired_token"}),
        positive("pair-result-unknown-reason", "PAIR_RESULT", {"ok": False, "reason": "some_future_reason"}),
        positive("unpair", "UNPAIR", {}),
        positive("ping-zero", "PING", {"nonce": 0}),
        positive("ping-max", "PING", {"nonce": 2**32 - 1}),
        positive("pong", "PONG", {"nonce": 305_419_896}),
        positive("clip-text-ascii", "CLIP_TEXT", {"clipId": CLIP_ID, "text": "Hello, PC!", "timestamp": TIMESTAMP}),
        positive("clip-text-unicode", "CLIP_TEXT", {"clipId": CLIP_ID, "text": UNICODE_TEXT, "timestamp": TIMESTAMP}),
        positive("clip-text-escaping", "CLIP_TEXT", {"clipId": CLIP_ID, "text": TRICKY_TEXT, "timestamp": TIMESTAMP}),
        positive(
            "clip-text-every-c0-control",
            "CLIP_TEXT",
            {"clipId": CLIP_ID, "text": "".join(chr(c) for c in range(0x20)), "timestamp": 0},
        ),
        positive("transfer-offer-file", "TRANSFER_OFFER", offer_file),
        positive(
            "transfer-offer-folder-entry",
            "TRANSFER_OFFER",
            {
                "transferId": TRANSFER_B,
                "purpose": "file",
                "name": "Café menu (final).jpg",
                "relativePath": "Photos/2024/Trip",
                "size": 0,
                "mime": "image/jpeg",
            },
        ),
        positive(
            "transfer-offer-clipboard-image",
            "TRANSFER_OFFER",
            {
                "transferId": TRANSFER_C,
                "purpose": "clipboardImage",
                "name": "clipboard.png",
                "relativePath": "",
                "size": 48_213,
                "mime": "image/png",
            },
        ),
        positive(
            "transfer-offer-max-size-unknown-purpose",
            "TRANSFER_OFFER",
            {
                "transferId": TRANSFER_A,
                "purpose": "video",
                "name": "big.bin",
                "relativePath": "",
                "size": MAX_JSON_INTEGER,
                "mime": "application/octet-stream",
            },
        ),
        positive("transfer-accept-new", "TRANSFER_ACCEPT", {"transferId": TRANSFER_A, "offset": 0}),
        positive("transfer-accept-resume", "TRANSFER_ACCEPT", {"transferId": TRANSFER_A, "offset": 786_432}),
        positive("transfer-reject", "TRANSFER_REJECT", {"transferId": TRANSFER_A, "reason": "no_space"}),
        positive(
            "transfer-chunk-small",
            "TRANSFER_CHUNK",
            {"transferId": TRANSFER_A, "offset": 0, "dataHex": b"hello".hex()},
        ),
        positive(
            "transfer-chunk-1kib-all-byte-values",
            "TRANSFER_CHUNK",
            {"transferId": TRANSFER_C, "offset": 262_144, "dataHex": (bytes(range(256)) * 4).hex()},
        ),
        positive(
            "transfer-chunk-max-offset",
            "TRANSFER_CHUNK",
            {"transferId": TRANSFER_B, "offset": MAX_JSON_INTEGER, "dataHex": "ff"},
        ),
        positive(
            "transfer-end",
            "TRANSFER_END",
            {"transferId": TRANSFER_A, "sha256": hashlib.sha256(b"hello").hexdigest()},
        ),
        positive("transfer-result-ok", "TRANSFER_RESULT", {"transferId": TRANSFER_A, "ok": True}),
        positive(
            "transfer-result-hash-mismatch",
            "TRANSFER_RESULT",
            {"transferId": TRANSFER_A, "ok": False, "reason": "hash_mismatch"},
        ),
        positive("transfer-cancel", "TRANSFER_CANCEL", {"transferId": TRANSFER_A}),
        positive("error", "ERROR", {"code": "unknown_type", "message": "Unknown frame type 0x42"}),
        unknown("unknown-type-json", 0x42, b'{"future":true}'),
        unknown("unknown-type-binary", 0x90, bytes([0x00, 0x01, 0xFE, 0xFF])),
        unknown("unknown-type-empty", 0x7E, b""),
        # Valid but non-canonical input.
        lenient("unpair-empty-payload", "UNPAIR", b"", {}),
        lenient(
            "hello-whitespace-and-reordered",
            "HELLO",
            (
                '{ "appVersion" : "0.1.0",\n  "platform":"android", "deviceName":"Pixel 9",\r\n'
                f'\t"deviceId": "{PHONE_ID}", "protocolVersion": 1 }}'
            ).encode(),
            hello(),
        ),
        lenient(
            "hello-unknown-fields",
            "HELLO",
            (
                '{"protocolVersion":1,"future":{"nested":[1,2,{"x":null}]},'
                f'"deviceId":"{PHONE_ID}","deviceName":"Pixel 9","platform":"android",'
                '"appVersion":"0.1.0","flag":false,"n":-1.5e3}'
            ).encode(),
            hello(),
        ),
        lenient("pair-result-null-reason", "PAIR_RESULT", b'{"ok":true,"reason":null}', {"ok": True}),
        lenient(
            "clip-text-escaped-characters",
            "CLIP_TEXT",
            (
                f'{{"clipId":"{CLIP_ID}","text":"caf\\u00e9 \\ud83d\\ude00 \\/ \\u0041 \\u001F",'
                f'"timestamp":{TIMESTAMP}}}'
            ).encode(),
            {"clipId": CLIP_ID, "text": "café 😀 / A \u001f", "timestamp": TIMESTAMP},
        ),
    ]


# -------------------------------------------------------- invalid-frames.json


def bad_frame(name: str, hex_bytes: str, error: str) -> dict:
    return {"name": name, "hex": hex_bytes, "error": error}


def bad_message(name: str, type_name: str, raw_payload: bytes) -> dict:
    return {"name": name, "hex": frame(TYPE_CODES[type_name], raw_payload).hex(), "error": "protocol_error"}


def bad_chunk(name: str, payload: bytes) -> dict:
    return {"name": name, "hex": frame(TYPE_CODES["TRANSFER_CHUNK"], payload).hex(), "error": "protocol_error"}


def json_bytes(obj: dict) -> bytes:
    return canonical(obj)


def invalid_frames() -> list[dict]:
    good_hello = hello()
    tid = uuid.UUID(TRANSFER_A).bytes
    return [
        # Frame codec errors.
        bad_frame("length-zero", "00000000", "frame_too_large"),
        bad_frame("length-over-limit", "0010000101", "frame_too_large"),
        bad_frame("length-max-u32", "ffffffff01", "frame_too_large"),
        bad_frame("truncated-length", "000000", "truncated"),
        bad_frame("truncated-type", "00000001", "truncated"),
        bad_frame("truncated-payload", "00000005017b22", "truncated"),
        # Message codec errors: the frame itself is fine.
        bad_message("payload-not-json", "HELLO", b"hello"),
        bad_message("payload-json-array", "HELLO", b"[1,2,3]"),
        bad_message("payload-json-string", "PING", b'"ping"'),
        bad_message("payload-trailing-garbage", "PING", b'{"nonce":1}x'),
        bad_message("payload-invalid-utf8", "CLIP_TEXT", f'{{"clipId":"{CLIP_ID}","text":"'.encode() + b"\xc3\x28" + f'","timestamp":{TIMESTAMP}}}'.encode()),
        bad_message("payload-cesu-surrogate-bytes", "CLIP_TEXT", f'{{"clipId":"{CLIP_ID}","text":"'.encode() + b"\xed\xa0\x80" + f'","timestamp":{TIMESTAMP}}}'.encode()),
        bad_message("payload-lone-surrogate-escape", "CLIP_TEXT", f'{{"clipId":"{CLIP_ID}","text":"a\\ud800b","timestamp":{TIMESTAMP}}}'.encode()),
        bad_message("payload-reversed-surrogate-escapes", "CLIP_TEXT", f'{{"clipId":"{CLIP_ID}","text":"\\ude00\\ud83d","timestamp":{TIMESTAMP}}}'.encode()),
        bad_message("hello-missing-device-id", "HELLO", json_bytes({k: v for k, v in good_hello.items() if k != "deviceId"})),
        bad_message("hello-version-as-string", "HELLO", json_bytes({**good_hello, "protocolVersion": "1"})),
        bad_message("hello-version-fraction", "HELLO", json_bytes(good_hello).replace(b'"protocolVersion":1', b'"protocolVersion":1.0')),
        bad_message("hello-version-exponent", "HELLO", json_bytes(good_hello).replace(b'"protocolVersion":1', b'"protocolVersion":1e0')),
        bad_message("hello-version-zero", "HELLO", json_bytes({**good_hello, "protocolVersion": 0})),
        bad_message("hello-version-too-large", "HELLO", json_bytes({**good_hello, "protocolVersion": 2**31})),
        bad_message("hello-device-id-too-short", "HELLO", json_bytes({**good_hello, "deviceId": PHONE_ID[:-1]})),
        bad_message("hello-device-id-not-base64url", "HELLO", json_bytes({**good_hello, "deviceId": PHONE_ID[:-1] + "+"})),
        bad_message("hello-device-name-blank", "HELLO", json_bytes({**good_hello, "deviceName": " \t "})),
        bad_message("hello-device-name-null", "HELLO", json_bytes({**good_hello, "deviceName": None})),
        bad_message("hello-ack-paired-as-string", "HELLO_ACK", json_bytes({**good_hello, "platform": "windows", "paired": "true"})),
        bad_message("hello-ack-missing-paired", "HELLO_ACK", json_bytes({**good_hello, "platform": "windows"})),
        bad_message("pair-result-failure-without-reason", "PAIR_RESULT", b'{"ok":false}'),
        bad_message("pair-result-success-with-reason", "PAIR_RESULT", b'{"ok":true,"reason":"expired_token"}'),
        bad_message("ping-negative-nonce", "PING", b'{"nonce":-1}'),
        bad_message("ping-nonce-too-large", "PING", b'{"nonce":4294967296}'),
        bad_message("clip-text-empty", "CLIP_TEXT", json_bytes({"clipId": CLIP_ID, "text": "", "timestamp": TIMESTAMP})),
        bad_message("clip-text-uppercase-uuid", "CLIP_TEXT", json_bytes({"clipId": CLIP_ID.upper(), "text": "x", "timestamp": TIMESTAMP})),
        bad_message("clip-text-uuid-without-dashes", "CLIP_TEXT", json_bytes({"clipId": CLIP_ID.replace("-", ""), "text": "x", "timestamp": TIMESTAMP})),
        bad_message("clip-text-short-uuid", "CLIP_TEXT", json_bytes({"clipId": "1-1-1-1-1", "text": "x", "timestamp": TIMESTAMP})),
        bad_message("clip-text-negative-timestamp", "CLIP_TEXT", json_bytes({"clipId": CLIP_ID, "text": "x", "timestamp": -1})),
        bad_message(
            "transfer-offer-negative-size",
            "TRANSFER_OFFER",
            json_bytes({"transferId": TRANSFER_A, "purpose": "file", "name": "a", "relativePath": "", "size": -1, "mime": "a/b"}),
        ),
        bad_message(
            "transfer-offer-size-above-2^53",
            "TRANSFER_OFFER",
            json_bytes({"transferId": TRANSFER_A, "purpose": "file", "name": "a", "relativePath": "", "size": MAX_JSON_INTEGER + 1, "mime": "a/b"}),
        ),
        bad_message(
            "transfer-offer-missing-relative-path",
            "TRANSFER_OFFER",
            json_bytes({"transferId": TRANSFER_A, "purpose": "file", "name": "a", "size": 1, "mime": "a/b"}),
        ),
        bad_message("transfer-accept-negative-offset", "TRANSFER_ACCEPT", json_bytes({"transferId": TRANSFER_A, "offset": -5})),
        bad_message("transfer-end-uppercase-hash", "TRANSFER_END", json_bytes({"transferId": TRANSFER_A, "sha256": hashlib.sha256(b"x").hexdigest().upper()})),
        bad_message("transfer-end-short-hash", "TRANSFER_END", json_bytes({"transferId": TRANSFER_A, "sha256": "abc123"})),
        bad_message("transfer-result-failure-without-reason", "TRANSFER_RESULT", json_bytes({"transferId": TRANSFER_A, "ok": False})),
        bad_message("error-missing-message", "ERROR", b'{"code":"internal"}'),
        bad_chunk("chunk-header-only", tid + struct.pack(">Q", 0)),
        bad_chunk("chunk-short-header", tid + b"\x00\x00\x00"),
        bad_chunk("chunk-empty-payload", b""),
        bad_chunk("chunk-offset-above-2^53", tid + struct.pack(">Q", MAX_JSON_INTEGER + 1) + b"x"),
        bad_chunk("chunk-offset-top-bit-set", tid + struct.pack(">Q", 2**63) + b"x"),
        bad_chunk("chunk-data-too-long", tid + struct.pack(">Q", 0) + bytes(262_145)),
    ]


# ---------------------------------------------------------- pairing-uri.json

HOST_FP = HOST_ID
VALID_QUERY = f"v=1&h=192.168.1.20&p=47821&fp={HOST_FP}&t={TOKEN}&n=Office%20PC"


def uri_ok(name: str, uri: str, hosts: list[str], port: int, display_name: str) -> dict:
    return {
        "name": name,
        "uri": uri,
        "expected": {
            "version": 1,
            "hosts": hosts,
            "port": port,
            "fingerprint": HOST_FP,
            "token": TOKEN,
            "name": display_name,
        },
    }


def uri_bad(name: str, uri: str, error: str = "invalid") -> dict:
    return {"name": name, "uri": uri, "error": error}


def replace_param(key: str, value: str | None) -> str:
    parts = []
    for part in VALID_QUERY.split("&"):
        k = part.split("=", 1)[0]
        if k == key:
            if value is not None:
                parts.append(f"{key}={value}")
        else:
            parts.append(part)
    return "clipdrop://pair?" + "&".join(parts)


def pairing_uris() -> list[dict]:
    return [
        uri_ok("basic", "clipdrop://pair?" + VALID_QUERY, ["192.168.1.20"], 47821, "Office PC"),
        uri_ok(
            "several-hosts-reordered-unknown-param",
            f"clipdrop://pair?n=Lab%20Box&x=ignored&t={TOKEN}&fp={HOST_FP}&p=50123&h=192.168.1.20,10.0.0.7,172.16.5.4&v=1&future=",
            ["192.168.1.20", "10.0.0.7", "172.16.5.4"],
            50123,
            "Lab Box",
        ),
        uri_ok("uppercase-scheme-and-host", "CLIPDROP://PAIR?" + VALID_QUERY, ["192.168.1.20"], 47821, "Office PC"),
        uri_ok("plus-is-literal", replace_param("n", "Dev+Box"), ["192.168.1.20"], 47821, "Dev+Box"),
        uri_ok("utf8-name", replace_param("n", "Akash%E2%80%99s%20PC%20%F0%9F%92%BB"), ["192.168.1.20"], 47821, "Akash’s PC 💻"),
        uri_ok("name-trimmed", replace_param("n", "%20%20Office%20%20"), ["192.168.1.20"], 47821, "Office"),
        uri_ok("port-bounds-low", replace_param("p", "1"), ["192.168.1.20"], 1, "Office PC"),
        uri_ok("port-bounds-high", replace_param("p", "65535"), ["192.168.1.20"], 65535, "Office PC"),
        uri_ok("host-octet-bounds", replace_param("h", "0.0.0.0,255.255.255.255"), ["0.0.0.0", "255.255.255.255"], 47821, "Office PC"),
        uri_ok("sixteen-hosts", replace_param("h", ",".join(f"10.0.0.{i}" for i in range(1, 17))), [f"10.0.0.{i}" for i in range(1, 17)], 47821, "Office PC"),
        uri_bad("newer-version", replace_param("v", "2"), "unsupported_version"),
        uri_bad("version-zero", replace_param("v", "0")),
        uri_bad("version-leading-zero", replace_param("v", "01")),
        uri_bad("wrong-scheme", "https://pair?" + VALID_QUERY),
        uri_bad("wrong-host", "clipdrop://connect?" + VALID_QUERY),
        uri_bad("no-query", "clipdrop://pair"),
        uri_bad("missing-token", replace_param("t", None)),
        uri_bad("missing-name", replace_param("n", None)),
        uri_bad("missing-hosts", replace_param("h", None)),
        uri_bad("duplicate-port", "clipdrop://pair?" + VALID_QUERY + "&p=47822"),
        uri_bad("port-zero", replace_param("p", "0")),
        uri_bad("port-too-large", replace_param("p", "65536")),
        uri_bad("port-leading-zero", replace_param("p", "047821")),
        uri_bad("port-not-number", replace_param("p", "http")),
        uri_bad("host-three-octets", replace_param("h", "192.168.1")),
        uri_bad("host-octet-too-large", replace_param("h", "192.168.1.256")),
        uri_bad("host-octet-leading-zero", replace_param("h", "192.168.01.20")),
        uri_bad("host-empty-entry", replace_param("h", "192.168.1.20,,10.0.0.7")),
        uri_bad("host-name-not-ip", replace_param("h", "my-pc.local")),
        uri_bad("seventeen-hosts", replace_param("h", ",".join(f"10.0.0.{i}" for i in range(1, 18)))),
        uri_bad("fingerprint-too-short", replace_param("fp", HOST_FP[:-1])),
        uri_bad("fingerprint-standard-base64", replace_param("fp", HOST_FP[:-1] + "%2B")),
        uri_bad("token-too-long", replace_param("t", TOKEN + "A")),
        uri_bad("token-padded", replace_param("t", TOKEN + "%3D%3D")),
        uri_bad("name-invalid-utf8", replace_param("n", "%FF%FE")),
        uri_bad("name-bad-percent-escape", replace_param("n", "Office%2")),
        uri_bad("name-blank", replace_param("n", "%20%20")),
    ]


# ------------------------------------------------------------- identity.json


def identity() -> dict:
    digest = hashlib.sha256(IDENTITY_CERT_DER).digest()
    return {
        "description": "A self-signed EC P-256 certificate and the identity derived from it (PROTOCOL.md §3).",
        "certificateDerHex": IDENTITY_CERT_DER.hex(),
        "fingerprintHex": digest.hex(),
        "deviceId": b64url(digest),
        "extraDeviceIds": [
            {"fingerprintHex": "00" * 32, "deviceId": b64url(bytes(32))},
            {"fingerprintHex": "ff" * 32, "deviceId": b64url(b"\xff" * 32)},
        ],
    }


# ---------------------------------------------------------------------- main


def write(file_name: str, content: dict) -> None:
    text = json.dumps(content, ensure_ascii=False, indent=2) + "\n"
    (HERE / file_name).write_text(text, encoding="utf-8", newline="\n")
    print(f"wrote {file_name}")


def main() -> None:
    write("frames.json", {
        "description": "Positive frame and message vectors (PROTOCOL.md §16).",
        "vectors": frames(),
    })
    write("invalid-frames.json", {
        "description": "Negative vectors: frame-codec errors and message-codec protocol errors (PROTOCOL.md §16).",
        "vectors": invalid_frames(),
    })
    write("pairing-uri.json", {
        "description": "Pairing URIs, valid and invalid (PROTOCOL.md §6).",
        "vectors": pairing_uris(),
    })
    write("identity.json", identity())


if __name__ == "__main__":
    main()
