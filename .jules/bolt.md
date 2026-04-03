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

## 2026-05-28 - [HashMap Overhead in Tries]
**Learning:** `PackageTrie` was using `HashMap<Char, Node>` for child storage. For package names (low branching factor, mostly linear segments), this introduced significant memory and CPU overhead (boxing `Char`, hashing, `Entry` objects). Replacing it with parallel arrays (`CharArray` + `Array<Node>`) and linear scanning yielded a ~2.3x speedup.
**Action:** For small collections or specialized trees with low branching factors (like tries for strings), prefer simple arrays over `HashMap` to avoid object overhead and indirect access.

## 2026-02-10 - [Lambda Allocation in Hot Cache Paths]
**Learning:** `ConcurrentHashMap.computeIfAbsent` allocates a `Function` lambda instance on every call if the lambda captures variables (like `this`), even if the key is already present. In hot paths (like permission checks), this creates significant GC pressure.
**Action:** In hot paths using `computeIfAbsent`, check for the existence of the key (e.g., `map[key]`) before calling `computeIfAbsent` to avoid allocation in the hit case.

## 2026-06-15 - [Thundering Herd on Cache Miss]
**Learning:** `Config.getPackages(uid)` used a simple check-then-act pattern for caching. When multiple threads requested the same UID simultaneously (e.g., during app startup), they would all miss the cache and trigger expensive IPC calls (`getPackagesForUid`), causing a "thundering herd" effect.
**Action:** Use `ConcurrentHashMap.compute` (or `computeIfAbsent`) to atomically handle cache misses. This ensures only one thread performs the expensive operation while others wait for the result. Be careful to check the cache *inside* the compute block (double-check locking) if optimistic reads are used.

## 2026-06-16 - [Regex Overhead in Hot Paths]
**Learning:** `WebServer.isSafeHost` was using `Regex.matches()` for IPv4/IPv6 validation on every request. This caused `Matcher` allocation and regex engine overhead. Replacing it with manual character loop validation yielded an 8.4x speedup (1258ns -> 149ns).
**Action:** For simple string validation patterns in hot paths, prefer manual loops over `Regex` to avoid allocation and overhead.

## 2026-06-17 - [Redundant Regex Compilation]
**Learning:** WebServer's `serve` method and `validateContent` were compiling `Regex` objects inside loops (for permission and filename validation). This is a classic "compilation in loop" anti-pattern that wastes CPU cycles.
**Action:** Move regex patterns to `private val` constants at the class or file level to compile them once and reuse.

## 2026-06-18 - [Expensive Date Formatting in Hot Paths]
**Learning:** `Config.getPatchLevel` was re-calculating dynamic patch dates (e.g., "today") on every call, involving `Instant`, `ZoneId`, `LocalDate`, `String.format` (3x), and regex replacement. This is heavy for a method called during key attestation. Caching the calculated `Int` result for 1 hour yielded a ~3x-7x speedup.
**Action:** Cache the result of expensive date/time dependent calculations (like dynamic configuration templates) for a reasonable TTL to avoid redundant object allocation and formatting overhead in hot paths.

## 2024-03-19 - [Repeated Allocations in /proc Parsing]
**Learning:** `WebServer.getCpuUsagePercent` was reading `/proc/self/stat` and `/proc/stat` using `File.readText().split(Regex)`. `readText` allocates a `String` for the entire file (which for `/proc/stat` can be large) and `split` creates multiple objects. This adds unnecessary memory pressure for a simple stats read.
**Action:** Use a pre-allocated `ByteArray` and `FileInputStream.read()` to process just the first line without loading the entire file into a `String`.
## 2025-10-18 - String and array allocations optimization in WebServer and AutoCleaner
**Learning:** Replacing String.split() and regex processing with lineSequence() and manual index-based parsing via substring() or indexOf() severely drops array/List allocations in large file validation.
**Action:** Always prefer manual parsing without allocations in string hot-paths, especially File validation code, instead of split() or Regexes.
