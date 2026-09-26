# AeroKV

> **A High-Performance Concurrent In-Memory Key-Value Storage Engine Built from Scratch in Java**

<p align="center">

![Java](https://img.shields.io/badge/Java-21-blue?style=for-the-badge)
![Maven](https://img.shields.io/badge/Build-Maven-C71A36?style=for-the-badge)
![Platform](https://img.shields.io/badge/Platform-Cross--Platform-lightgrey?style=for-the-badge)
![Protocol](https://img.shields.io/badge/Protocol-TCP-success?style=for-the-badge)
![License](https://img.shields.io/badge/License-MIT-green?style=for-the-badge)

</p>

---

AeroKV is a **high-performance, multi-threaded, in-memory key-value storage engine** developed entirely from scratch in Java. It is designed to explore the core concepts behind modern caching systems and in-memory databases by implementing essential storage engine components rather than relying on existing frameworks or libraries.

The project combines **custom LRU eviction**, **segmented lock striping for concurrent access**, **lazy TTL expiration**, **optional password authentication**, and **Write-Ahead Logging (WAL) with startup compaction and fsync'd durability** to provide fast data access while ensuring durability and crash recovery. Communication is performed over a lightweight **TCP text-based protocol**, allowing clients to interact with the server using simple `SET`, `PUT`, `GET`, `DEL`, and `AUTH` commands.

AeroKV demonstrates how concurrency control, memory management, persistence, and network communication work together to build a reliable storage engine capable of serving multiple clients simultaneously.


## Project Overview

Modern applications such as e-commerce platforms, financial systems, gaming servers, and social media applications rely heavily on in-memory data stores to deliver responses with minimal latency. While databases provide durable storage, repeatedly accessing disk-based systems for frequently requested data introduces significant performance overhead.

AeroKV was built to explore the internal architecture of modern in-memory storage engines by implementing their core building blocks from scratch in Java. Instead of relying on existing caching frameworks or libraries, the project focuses on understanding how high-performance storage systems manage concurrency, memory, persistence, and data lifecycle.

The storage engine supports concurrent client access over raw TCP sockets using a custom lock-striping mechanism to minimize thread contention. A custom LRU cache manages memory efficiently by evicting the least recently used entries when capacity limits are reached. To improve reliability, every write operation is first persisted using Write-Ahead Logging (WAL), allowing the engine to recover its in-memory state after an unexpected shutdown. Additionally, lazy Time-To-Live (TTL) expiration ensures that expired entries are automatically removed when accessed without requiring continuous background cleanup.

By combining networking, concurrent programming, custom data structures, and persistence techniques, AeroKV demonstrates many of the fundamental concepts used in the design of modern high-performance storage systems.

## Tech Stack

| Category                 | Technology                                      | Purpose                                                                                                  |
| ------------------------ | ----------------------------------------------- | -------------------------------------------------------------------------------------------------------- |
| **Programming Language** | Java 21 (Compatible with Java 11+)              | Core application development and concurrency implementation                                              |
| **Build Tool**           | Maven                                           | Dependency management, project build, and execution                                                      |
| **Networking**           | Java TCP Sockets                                | Enables client-server communication over a lightweight text-based protocol                               |
| **Concurrency**          | Custom Segmented Lock Striping                  | Reduces lock contention by allowing multiple threads to operate on different cache segments concurrently |
| **Caching**              | Custom LRU Cache (HashMap + Doubly Linked List) | Provides constant-time (`O(1)`) lookup, insertion, and least recently used eviction                      |
| **Expiration**           | Lazy Time-To-Live (TTL)                         | Removes expired entries during access without requiring a background cleanup thread                      |
| **Persistence**          | Write-Ahead Logging (WAL)                       | Ensures durability by persisting write operations before updating the in-memory cache                    |
| **Recovery**             | WAL Replay                                      | Restores the cache state by replaying log records during server startup                                  |
| **Log Compaction**       | Startup WAL Rewrite                             | Rewrites the log to only the current live entries after recovery, bounding disk usage and future restart time |
| **Storage**              | In-Memory                                       | Delivers low-latency data access by storing entries in main memory                                       |
| **Protocol**             | TCP Text Protocol                               | Supports simple line-based `SET`, `PUT`, `GET`, and `DEL` commands for client interaction                 |
| **CI**                   | GitHub Actions                                  | Builds the project and runs the production readiness suite against a live server on every push/PR        |

## System Architecture

AeroKV follows a layered architecture where each client request passes through a series of well-defined components before a response is returned. The server listens for incoming TCP connections, parses client commands, applies concurrency control using segmented lock striping, persists write operations through Write-Ahead Logging (WAL), and finally stores or retrieves data from the in-memory cache.

This separation of responsibilities keeps the storage engine modular, improves maintainability, and allows individual components to evolve independently.

### Architecture Components

| Component                 | Responsibility                                                                                                  |
| ------------------------- | --------------------------------------------------------------------------------------------------------------- |
| **TCP Server**            | Accepts incoming client connections and manages request processing.                                             |
| **Command Parser**        | Parses incoming text-based commands (`SET`, `PUT`, `GET`, `DEL`, `PING`) and validates request syntax.          |
| **Lock Striping Layer**   | Maps keys to lock segments, allowing multiple threads to operate concurrently while minimizing lock contention. |
| **LRU Cache Engine**      | Stores key-value pairs in memory and performs least recently used eviction when capacity limits are reached.    |
| **TTL Manager**           | Validates the expiration time of entries during read operations and removes expired keys when accessed.         |
| **Write-Ahead Log (WAL)** | Persists every write and delete operation to disk before updating the in-memory cache to ensure durability.     |
| **Recovery Engine**       | Rebuilds the cache during server startup by replaying all entries from the WAL file, then compacts the log to just the live entries. |

### Request Flow

#### Write Operation (`SET` / `PUT`)

1. Client sends a write request to the TCP server.
2. The command parser validates and extracts the request parameters.
3. The corresponding lock stripe is acquired based on the key.
4. The operation is synchronously appended to the Write-Ahead Log (WAL).
5. The in-memory LRU cache is updated.
6. The lock is released.
7. The server returns `OK` to the client.

#### Read Operation (`GET`)

1. Client sends a read request.
2. The command parser validates the request.
3. The corresponding lock stripe is acquired.
4. The cache is searched for the requested key.
5. If the key has expired, it is removed immediately and `ERR_EXPIRED` is returned.
6. If the key is not present, `ERR_NOT_FOUND` is returned.
7. Otherwise, the stored value is returned to the client.
8. The lock is released.

### Architecture Diagram

```mermaid
flowchart LR

    Client --> TCP["TCP Server"]
    TCP --> Parser["Command Parser"]
    Parser --> Lock["Lock Striping Layer"]
    Lock --> Cache["LRU Cache Engine"]
    Lock --> WAL["Write-Ahead Log"]
    WAL --> Disk["Log File"]
    Cache --> Response["Response to Client"]

    Disk --> Recovery["Recovery Engine"]
    Recovery --> Cache
```

## Project Structure

The project follows the standard Maven directory layout and is organized into incremental modules. Each package represents a distinct development phase, gradually evolving AeroKV from a basic key-value storage abstraction into a concurrent, network-accessible in-memory storage engine with persistence and recovery capabilities.

```text
AeroKV/
├── pom.xml
├── README.md
│
├── src/
│   ├── app.py                  # Sample Python client
│   ├── benchmark.py            # Multi-threaded benchmark utility
│   ├── production_suite.py     # End-to-end production readiness suite
│   │
│   └── main/
│       └── java/
│           ├── day01/          # Core abstractions and logging
│           ├── day02/          # Custom HashMap implementation
│           ├── day03/          # Custom LRU cache
│           ├── day04/          # Concurrent cache with lock striping
│           ├── day05/          # TCP server, persistence, TTL, and recovery
│           └── day06/          # Application entry point
│
└── target/                     # Maven build output (generated)
```

### Package Overview

| Package   | Responsibility                                                                                                      |
| --------- | ------------------------------------------------------------------------------------------------------------------- |
| **day01** | Defines the core storage abstraction and logging utilities used throughout the project.                             |
| **day02** | Implements a custom HashMap with separate chaining to provide efficient key-value storage.                          |
| **day03** | Builds a custom LRU cache using the HashMap and a Doubly Linked List for constant-time cache operations.            |
| **day04** | Introduces thread safety through segmented lock striping, enabling concurrent cache access with reduced contention. |
| **day05** | Implements the TCP server, asynchronous log persistence, crash recovery, and TTL-based cache entries.               |
| **day06** | Contains the application entry point responsible for initializing and starting the AeroKV server.                   |

## Getting Started

### Prerequisites

Before running AeroKV, ensure the following software is installed on your system:

* Java Development Kit (JDK) 21 (Compatible with Java 11 or later)
* Apache Maven 3.9 or later
* Python 3.x (Optional, for running the sample client and benchmark utility)

### Clone the Repository

```bash
git clone https://github.com/meetcodesjava/AeroKV.git
cd AeroKV
```

### Build the Project

Compile the project using Maven:

```bash
mvn clean compile
```

### Run the Server

Start the AeroKV server:

```bash
mvn exec:java -Dexec.mainClass="day06.AeroKVServerApp"
```

The server starts on **port 8080**, restores previously persisted data (if available), and begins accepting TCP client connections.

### Run the Sample Python Client

Open a new terminal and execute:

```bash
cd src
python app.py
```

The sample client demonstrates storing and retrieving data from the AeroKV server using the TCP protocol.

### Run the Benchmark

To evaluate concurrent request throughput, execute:

```bash
cd src
python benchmark.py
```

The benchmark creates multiple concurrent client threads that perform `SET` and `GET` operations and reports the total execution time, successful operations, and throughput.

### Run the Production Readiness Suite

To validate AeroKV end-to-end the way a real deployment would exercise it — wire protocol correctness, TTL expiry timing, deterministic LRU eviction, a Zipfian (hot-key) mixed read/write workload ramped across 10/50/100 concurrent clients, connection-churn latency, and a sustained soak test that checks for throughput decay over time — execute:

```bash
cd src
python production_suite.py
```

Useful flags:

| Flag                    | Purpose                                                        |
| ------------------------ | --------------------------------------------------------------- |
| `--host` / `--port`      | Target a non-default server address.                            |
| `--soak-seconds N`       | Run the sustained load phase for `N` seconds (default: 20).     |
| `--skip-soak`            | Skip the soak phase for a faster run during iterative development. |

The suite prints pass/fail for every correctness check plus throughput and latency percentiles (p50/p95/p99) for each load phase, and exits non-zero if any correctness check fails.

## Core Features

### Custom HashMap Implementation

AeroKV implements a custom HashMap using separate chaining for collision handling instead of relying on Java's built-in collections. This serves as the core storage layer and provides efficient average-case constant-time key lookup and insertion.

### Custom LRU Cache

The storage engine uses a custom Least Recently Used (LRU) cache built with a Doubly Linked List and the custom HashMap. Recently accessed entries are promoted to the head of the list, while the least recently used entry is automatically evicted when the configured cache capacity is reached.

### Thread-Safe Concurrent Access

To support multiple simultaneous clients, AeroKV employs segmented lock striping. Keys are mapped to independent lock stripes using their hash values, allowing concurrent operations on different keys while reducing lock contention.

### Lazy TTL Expiration

Each cached entry can be assigned a configurable Time-To-Live (TTL). Expiration is evaluated during read operations, ensuring expired entries are removed when accessed without requiring a background cleanup thread.

### Asynchronous Log Persistence

Write and delete operations are queued and persisted to disk by a dedicated background writer thread. During server startup, the persisted log is replayed to restore previously stored entries into the in-memory cache.

### WAL Compaction

Immediately after recovery, and before the async writer thread reopens the log for append, AeroKV rewrites the WAL to contain only the current live entries as a single `SET` per key — discarding the accumulated history of overwrites, deletes, and expired entries. This keeps both disk usage and the next restart's replay time bounded by the cache's live size rather than growing forever with write volume. (Verified: a log that grew to 87,177 entries / ~2.0MB compacted down to 1,000 live entries / ~19KB on restart.)

### Durable, Backpressured WAL Writes

Every write is fsync'd (`FileDescriptor.sync()`) to physical disk, not just flushed to the OS buffer, so a completed write really survives a crash immediately after the client receives `OK`. Concurrent writes are grouped into a single flush+fsync per batch ("group commit") so durability doesn't come at the cost of one disk sync per individual write. The write queue itself is capped (10,000 entries); once full, a writing client blocks until there's room instead of the queue growing without limit.

### Password Authentication (Optional)

If `AEROKV_PASSWORD` is set, every connection must send `AUTH,<password>` before any other command is accepted — `PING` remains open for liveness checks. If no password is configured, the server behaves exactly as before (open access), so this is opt-in.

### Value Size Limit

`SET`/`PUT` payloads larger than 5&nbsp;MB are rejected with `ERR_VALUE_TOO_LARGE` instead of being accepted into memory unbounded, protecting the JVM heap from a single oversized client payload.

### Total Memory Budget

Beyond the per-value 5MB cap and the entry-count capacity, AeroKV can also enforce a total-bytes budget across the whole cache (default 256MB, configurable). When a new value would push total memory over budget, the least-recently-used entries are evicted first — the same policy already used for count-based eviction, just also triggered by total size.

### Externalized Configuration

Port, cache capacity, lock stripe count, WAL file path, memory budget, password, and thread pool size are all resolved from CLI arguments, then environment variables, then built-in defaults — nothing about the deployment target is hardcoded in source, and the default WAL path is OS-portable (uses the system temp directory).

### Multi-Threaded TCP Server

AeroKV exposes its storage engine through a lightweight TCP server backed by a fixed-size thread pool, where one thread stays attached to a connection for that connection's entire lifetime. This means the thread pool size is a real ceiling on concurrent connections, not just a performance knob — size it at least as large as the number of clients you expect connected at once (default 200, configurable via `AEROKV_THREADS`).

### Text-Based Command Protocol

Clients communicate with AeroKV using a lightweight, line-delimited TCP protocol. The current implementation supports `SET`/`PUT` for storing data with an optional TTL, `GET` for retrieving cached values, `DEL` for explicit removal, `AUTH` for authenticating when a password is configured, and `PING` for liveness checks.

### Crash Recovery

On startup, AeroKV scans the persisted log file and replays stored operations to reconstruct the in-memory cache. This allows previously persisted data to be restored automatically after a server restart.

## Performance Benchmark

Current numbers below are from an isolated run of `production_suite.py` against a freshly started server (no other test script running against it at the same time — running multiple test scripts back-to-back on one live instance leaves behind keys and lingering sockets that skew later results, so measurements below are all from a single clean run).

### Test Environment

| Component            | Specification                              |
| -------------------- | ------------------------------------------ |
| **Processor**        | 11th Gen Intel® Core™ i3-1115G4 @ 3.00 GHz |
| **Memory**           | 8 GB RAM                                   |
| **Storage**          | 256 GB SSD                                 |
| **Operating System** | Windows 11                                 |
| **Java Version**     | JDK 21.0.12                                |
| **Benchmark Tool**   | `src/production_suite.py`                  |

### Simple Persistent-Connection Benchmark (`benchmark.py`)

10 threads, each holding one persistent connection, running 2,000 total `SET` operations:

| Metric                    |                    Result |
| ------------------------- | -------------------------: |
| **Execution Time**        |             ~0.08 seconds |
| **Successful Operations** |                      2,000 |
| **Average Throughput**    | ~24,600 operations/second |

### Mixed Zipfian Workload, Ramped Concurrency (`production_suite.py`, phase 4)

85% reads / 15% writes, hot-key-skewed (Zipfian) access pattern over a 500-key pool, 300 ops per thread:

| Concurrent Clients | Throughput (ops/sec) | Avg Latency | p50    | p95    | p99    | Errors |
| ------------------: | ---------------------: | -----------: | ------: | ------: | ------: | ------: |
| 10                  |                 ~28,000 |      0.186ms | 0.065ms | 0.161ms | 1.532ms |      0 |
| 50                  |                 ~39,600 |      0.660ms | 0.095ms | 0.324ms | 20.1ms  |      0 |
| 100                 |                 ~46,900 |      1.132ms | 0.179ms | 0.568ms | 27.5ms  |      0 |
| 100 (with `AUTH`)   |                 ~45,600 |      1.249ms | 0.178ms | 2.119ms | 9.1ms   |      0 |

(The higher p99 at 50/100 clients vs. an earlier run reflects real per-connection queueing on this machine, not errors — every operation still succeeded.)

### Connection Churn (`production_suite.py`, phase 5)

25 concurrent clients, each opening a fresh TCP connection per request (500 total connect+operate+disconnect cycles):

| Metric                | Result |
| ---------------------- | ------: |
| **Connections/sec**   | ~4,490 |
| **Avg Latency**       | 1.90ms |
| **p99 Latency**       | 19.6ms |
| **Errors**            |      0 |

### Sustained Soak Test (`production_suite.py`, phase 6)

20 threads, steady mixed load for 15 seconds, throughput sampled every second:

| Metric                             |    Result |
| ------------------------------------ | ---------: |
| **Early-window avg throughput**     | ~50,000 ops/sec |
| **Late-window avg throughput**      | ~50,500 ops/sec |
| **Total operations**                |   753,476 |
| **Errors**                          |        0 |

Throughput held steady (in fact rose slightly, likely JIT warm-up) rather than degrading over the run, with zero errors — no sign of lock starvation, memory pressure, or connection drops under sustained load in this test. (An earlier version of this run showed 10-30 errors here; those were traced to the idle-socket-timeout bug described in [Future Improvements](#future-improvements) and are gone after that fix.)

> **Note:** These results are from a local development machine and a single run each. They demonstrate the engine is fast enough for the workloads tested, not a guarantee of performance in a different environment, hardware, JVM configuration, or under adversarial/pathological workloads. Re-run `production_suite.py` yourself for numbers specific to your machine.

## Future Improvements

AeroKV is a from-scratch learning project, correct and load-tested for its current feature set (see the [Production Readiness](#production-readiness) section below), but not yet hardened for real production deployment.

**Already fixed** (previously listed here as gaps; kept for anyone comparing against an older version of this README):

* ~~Authentication~~ — optional `AUTH`/`AEROKV_PASSWORD` support added.
* ~~Memory bound by total size~~ — total-byte budget with LRU eviction added (`AEROKV_MAX_MEMORY_BYTES`).
* ~~fsync on WAL writes~~ — writes are now fsync'd to physical disk (batched via group commit).
* ~~Bounded WAL write queue~~ — the write queue is now capped, with backpressure instead of unbounded growth.
* ~~Shutdown sentinel written into the log~~ — replaced with an out-of-band signal; `stop()` is now actually wired to a JVM shutdown hook (previously it was never called at all).
* ~~Idle-socket timeout too aggressive~~ — found while testing the auth feature under load: the original 1-second idle timeout combined with a fixed 10-thread pool could kill a client's connection mid-wait under realistic concurrency, well before the client had done anything wrong. Raised to 60s and made the thread pool size configurable (default 200; this server pins one thread per connection for its lifetime, so pool size is a hard concurrency ceiling, not just a tuning knob).

**Situational** — real for some deployments, unnecessary for others depending on where and how this actually runs:

* **TLS** – Only matters if traffic crosses a network you don't fully trust; unnecessary for `localhost`-only or fully private-network use.
* **Replication / Clustering** – Real requirement for high-availability deployments; overkill for a single-app cache or side project.

**Nice-to-have, not blocking**:

* **Additional Commands** – `MGET`/`MSET` for batched operations, and cache introspection commands (`STATS`, `DBSIZE`).
* **Active TTL Cleanup** – A background sweep to proactively remove expired entries, rather than relying solely on lazy expiration at read time.
* **TTL Persistence Across Restart** – TTLs are currently reset to "no expiry" on WAL replay/compaction; persisting remaining TTL would preserve exact expiration semantics across restarts.
* **Observability** – Expose throughput, error rate, and WAL queue depth (e.g. via a metrics endpoint or structured logs) for use in production monitoring.
* **Event-Driven I/O** – The server is thread-per-connection (one OS thread pinned per live connection); an NIO/event-loop design would remove the thread-pool-size-as-concurrency-ceiling constraint entirely instead of just raising the ceiling.

## Configuration

Server configuration is resolved in this order: **CLI argument → environment variable → built-in default**. Nothing about the deployment target is hardcoded in source.

```bash
# CLI args, positional and all optional:
# port capacity stripes logFilePath maxMemoryBytes password threads
mvn exec:java -Dexec.mainClass="day06.AeroKVServerApp" \
  -Dexec.args="9090 5000 32 /var/lib/aerokv/wal.log 536870912 mySecret 200"

# or via environment variables
AEROKV_PORT=9090 AEROKV_CAPACITY=5000 AEROKV_STRIPES=32 AEROKV_LOG_PATH=/var/lib/aerokv/wal.log \
AEROKV_MAX_MEMORY_BYTES=536870912 AEROKV_PASSWORD=mySecret AEROKV_THREADS=200 \
  mvn exec:java -Dexec.mainClass="day06.AeroKVServerApp"
```

| Parameter               | Env Var                    | Default Value                   | Description                                                                                  |
| ------------------------- | ---------------------------- | --------------------------------- | ------------------------------------------------------------------------------------------------ |
| **Server Port**          | `AEROKV_PORT`                | `8080`                            | TCP port used by the AeroKV server to accept client connections.                              |
| **Cache Capacity**       | `AEROKV_CAPACITY`            | `1000`                            | Maximum number of entries that can be stored in the in-memory LRU cache.                      |
| **Lock Stripes**         | `AEROKV_STRIPES`             | `16`                               | Number of lock segments used to reduce contention during concurrent cache operations.         |
| **Log File Path**        | `AEROKV_LOG_PATH`            | `<system temp dir>/aerokv.log`    | File used to persist write/delete operations and restore cached data during startup.          |
| **Max Total Memory**     | `AEROKV_MAX_MEMORY_BYTES`    | `268435456` (256MB)                | Total byte budget across all cached values; least-recently-used entries are evicted to stay under it. `0` disables the check. |
| **Password**             | `AEROKV_PASSWORD`            | *(unset — auth disabled)*         | If set, every connection must send `AUTH,<password>` before any other command.                |
| **Thread Pool Size**     | `AEROKV_THREADS`             | `200`                              | Worker threads available to process client connections. One thread is pinned per connection for its whole lifetime, so this is a hard ceiling on concurrent connections, not just a tuning knob. |
| **Max Value Size**       | *(not configurable)*         | `5 MB`                             | `SET`/`PUT` payloads larger than this are rejected with `ERR_VALUE_TOO_LARGE`.                |
| **Idle Socket Timeout**  | *(not configurable)*         | `60,000 ms`                        | A connection idle longer than this is closed to free its worker thread back to the pool.      |
| **Java Version**         | —                             | `JDK 21`                           | Recommended Java version used for development and testing.                                    |


## Time Complexity

The following table summarizes the average-case time complexity of the primary operations performed by AeroKV.

| Operation                     | Average Time Complexity |
| ----------------------------- | ----------------------: |
| **SET**                       |                  `O(1)` |
| **GET**                       |                  `O(1)` |
| **HashMap Lookup**            |                  `O(1)` |
| **HashMap Insertion**         |                  `O(1)` |
| **LRU Update (Move to Head)** |                  `O(1)` |
| **LRU Eviction**              |                  `O(1)` |
| **Lock Stripe Selection**     |                  `O(1)` |
| **TTL Validation**            |                  `O(1)` |
| **Log Queue Insertion**       |                  `O(1)` |

> **Note:** The above complexities represent the average case. HashMap operations may degrade in the presence of excessive hash collisions, while log persistence time depends on the underlying storage device and operating system.

## Production Readiness

AeroKV is correctness- and load-tested for the feature set it implements today — see `src/production_suite.py`, which is run automatically on every push via [GitHub Actions](.github/workflows/ci.yml). Recent isolated local runs: 30/30 correctness checks passed with auth disabled, 37/37 passed with `AEROKV_PASSWORD` set (7 extra AUTH-specific checks), a WAL recovery + compaction cycle that replayed 87,177 log entries and compacted the log from ~2.0 MB down to ~19 KB, and a mixed Zipfian workload sustaining ~45,600 ops/sec at 100 concurrent authenticated clients with zero errors (full numbers in [Performance Benchmark](#performance-benchmark)).

That said, it is **not yet ready for production deployment as-is**. The gaps that still matter — TLS, replication/failover, and a few nice-to-have protocol/observability features — are listed in [Future Improvements](#future-improvements). The previously-listed critical gaps (no auth, memory bounded only by entry count, WAL writes not fsync'd, unbounded write queue, and an idle-timeout/thread-pool bug found while fixing those) have been addressed and verified under load. It's best understood as a well-tested reference implementation of the core techniques (lock striping, LRU eviction, lazy TTL, WAL + compaction, auth, memory budgeting), not a drop-in Redis replacement — it still lacks TLS and multi-node replication.

## License

AeroKV is released under the [MIT License](LICENSE). You're free to use, modify, and distribute it (including commercially), provided the original copyright notice is retained.

## Author

**Meet Limbachiya**

Backend Developer | Java | Spring Boot | Data Structures & Algorithms | Concurrent Systems

* **GitHub:** [github.com/meetcodesjava](https://github.com/meetcodesjava)
* **LinkedIn:** [linkedin.com/in/meetlimbachiya](https://www.linkedin.com/in/meetlimbachiya/)
* **Project Repository:** [AeroKV Repository](https://github.com/meetcodesjava/AeroKV)

---

## Additions in this fork (for HoldLatch)

This fork keeps everything above and adds what a ticket-reservation service needs. Nothing here changes the behaviour of `SET`, `GET` or `DEL`.

| Command | Meaning |
|---|---|
| `HOLD,key,owner,ttlMillis` | Atomic acquire: succeeds only if nobody holds `key`. Replies `OK`, `ERR_CONFLICT` or `ERR_CAPACITY`. |
| `MHOLD,k1\|k2\|k3,owner,ttlMillis` | All-or-nothing acquire of several keys, safe against deadlock (stripes are locked in a fixed order). |
| `RELEASE,key` | Unconditional delete. |
| `RELEASEIF,key,owner` | Compare-and-delete: only releases if `owner` still holds it, so a late release can never free someone else's hold. Replies `OK` or `ERR_NOT_HELD`. |

- **Holds are pinned.** Cache entries created with `SET` are ordinary cache data and are evicted least-recently-used first. Entries created by `HOLD`/`MHOLD` are never evicted while alive; if the cache is full of live holds a new hold gets `ERR_CAPACITY` instead of silently dropping someone's reservation.
- **The write-ahead log keeps the countdown.** Each entry is logged with its absolute expiry time, so after a restart a hold resumes with its *remaining* time (already-expired ones are dropped), and holds come back pinned. Log compaction preserves both.
- **Virtual threads** serve connections, so there is no fixed connection ceiling.
- **Concurrency fix:** reads move an entry within the shared LRU list, so they now take the same lock as writers (a stress test that crashed the JVM before the fix runs clean).
- **Container:** `docker build -t aerokv .` (the write-ahead log is on the `/data` volume).

Configuration is by environment variable: `AEROKV_PORT`, `AEROKV_CAPACITY`, `AEROKV_STRIPES`, `AEROKV_LOG_PATH`, `AEROKV_MAX_MEMORY_BYTES`, `AEROKV_PASSWORD`.
