# Test vectors

Golden frames shared by the Android and Windows test suites. [PROTOCOL.md §16](../PROTOCOL.md#16-test-vectors) defines the file format and what each suite must do with every vector:

* **Positive vectors:** decode the hex, compare the result with the expected message, then re-encode it and get identical bytes.
* **Negative vectors:** decode the hex and get the expected error.

The vectors are written in milestone 2, together with the codecs.
