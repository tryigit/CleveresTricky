# Bolt's Journal

## 2024-05-22 - [Redundant KeyPair Storage]
**Learning:** The `KeyBox` record was storing both the Bouncy Castle `PEMKeyPair` (intermediate) and the Java `KeyPair` (final). This doubled the object overhead for every key loaded.
**Action:** Always check if intermediate parsing objects are being stored in long-lived data structures. Remove them once the final object is created.
