# Bolt's Journal

## 2024-05-22 - [Redundant KeyPair Storage]
**Learning:** The `KeyBox` record was storing both the Bouncy Castle `PEMKeyPair` (intermediate) and the Java `KeyPair` (final). This doubled the object overhead for every key loaded.
**Action:** Always check if intermediate parsing objects are being stored in long-lived data structures. Remove them once the final object is created.

## 2024-05-24 - [Redundant IPC Calls in Hot Paths]
**Learning:** `CertHack.createApplicationId` and `Config.getPatchLevel` were making direct IPC calls to `PackageManager` for every key generation/hacking attempt. This is expensive. `Config` already had an LRU cache (`packageCache`) but it was private and underutilized.
**Action:** Expose existing internal caches (safely) to related components (like `CertHack`) instead of re-fetching data via IPC.

## 2026-02-03 - [Synchronized Streams in Recursive Encoders]
**Learning:** `CborEncoder` was using `ByteArrayOutputStream` for every recursive call. Since `ByteArrayOutputStream` methods are synchronized, this added significant overhead for complex nested structures (like RKP payloads). Replacing it with a non-synchronized `FastByteArrayOutputStream` yielded a >50% speedup.
**Action:** In high-throughput serialization logic, avoid synchronized streams (like `ByteArrayOutputStream`) if thread confinement is guaranteed. Use custom unsynchronized implementations or buffers.

## 2026-05-21 - [Repeated Allocations in Comparators]
**Learning:** `CborEncoder` was repeatedly calling `String.getBytes` inside the `Map` key comparator, leading to O(N log N) allocations and encoding operations. Pre-computing the UTF-8 bytes for keys reduced this to O(N) and improved map encoding speed by >50%.
**Action:** When sorting objects based on a property that requires computation (like encoding or hashing), pre-compute that property once and store it, or use a wrapper object, to avoid repeated work during comparisons.

## 2024-05-27 - [Lazy Initialization of Static Assets]
**Learning:** `WebServer.getHtml()` was reconstructing a large static HTML string (and allocating char arrays) on every request. This caused unnecessary garbage collection pressure.
**Action:** Cache static response content (like HTML templates) in lazy properties or static constants to avoid re-allocation.
