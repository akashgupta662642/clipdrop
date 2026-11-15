# Test vectors

Golden data shared by the Android and Windows test suites. [PROTOCOL.md §16](../PROTOCOL.md#16-test-vectors) defines the format and what each suite must do with every vector.

| File | Vectors |
|---|---|
| `frames.json` | Every message type, unknown types, unicode and escaping, plus valid-but-non-canonical inputs, which carry `reencodedHex` |
| `invalid-frames.json` | Frame errors (`frame_too_large`, `truncated`) and every decode-time protocol error in §8.2 |
| `pairing-uri.json` | Valid and invalid pairing URIs (§6) |
| `identity.json` | A fixed certificate with its fingerprint and device ID (§3) |

`generate.py` writes all four files using only the Python standard library. Its JSON encoder happens to produce exactly the canonical form of §8.2, which makes it an independent third implementation. The Kotlin and .NET codecs must reproduce its bytes, so neither can quietly agree with the other on a mistake.

```bash
python docs/test-vectors/generate.py
```

Don't edit the JSON files by hand: change the generator, regenerate, and commit both.
