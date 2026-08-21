# Binance WebSocket Dependency Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align the Binance Spot 11.0.1 market stream integration with the official example while keeping its Jetty 11.0.26 client stack coherent under Spring Boot 4.1.0.

**Architecture:** Continue using the existing `BinanceMarketStreamClient` abstraction and official `SpotWebSocketStreams` adapter. Replace artifact-by-artifact Jetty exclusions with a child-project Jetty 11 BOM import that does not override Spring Boot's Jetty EE11 BOM, then verify the resolved graph rather than maintaining a duplicated dependency list.

**Tech Stack:** Java 26, Spring Boot 4.1.0, Maven, Binance Spot Connector 11.0.1, Jetty WebSocket Client 11.0.26, JUnit 5.

## Global Constraints

- Keep `io.github.binance:binance-spot` at exactly `11.0.1`.
- Resolve all Binance WebSocket Jetty modules at exactly `11.0.26`; no Jetty 12.x artifact may remain in the runtime tree.
- Preserve the existing `BinanceMarketStreamClient` abstraction, virtual-thread reader, callbacks, and close semantics.
- Do not change reconnection policy, market-data selection, REST behavior, or exchange abstractions.

---

### Task 1: Simplify Jetty dependency management

**Files:**
- Modify: `pom.xml:20-170`

**Interfaces:**
- Consumes: Spring Boot parent property `jetty.version` and `io.github.binance:binance-spot:11.0.1` transitive dependencies.
- Produces: A converged Jetty 11.0.26 client dependency graph without direct Jetty declarations.

- [ ] **Step 1: Record the current dependency graph behavior**

Run:

```bash
mvn -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository -DskipTests dependency:tree -Dincludes=org.eclipse.jetty:*,org.eclipse.jetty.websocket:*
```

Expected: the current graph resolves Jetty 11.0.26 through explicit direct dependencies and exclusions.

- [ ] **Step 2: Replace the repeated configuration**

Add an independent property beside `binance-spot.version`:

```xml
<binance-jetty.version>11.0.26</binance-jetty.version>
```

Import `org.eclipse.jetty:jetty-bom:${binance-jetty.version}` in the project's `dependencyManagement`; do not override Spring Boot's `jetty.version`, because Boot also uses it for `jetty-ee11-bom`.

Keep the Binance dependency as:

```xml
<dependency>
    <groupId>io.github.binance</groupId>
    <artifactId>binance-spot</artifactId>
    <version>${binance-spot.version}</version>
</dependency>
```

Delete the Binance Jetty exclusions and every direct `org.eclipse.jetty` or `org.eclipse.jetty.websocket` dependency block.

- [ ] **Step 3: Verify dependency convergence and resolved versions**

Run:

```bash
mvn -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository -DskipTests validate dependency:tree -Dincludes=org.eclipse.jetty:*,org.eclipse.jetty.websocket:*
```

Expected: exit 0; every displayed Jetty artifact is `11.0.26`; no `12.` version appears.

### Task 2: Match the official all-market mini-ticker example

**Files:**
- Modify: `src/main/java/com/multind/bitpongo/market/OfficialBinanceMarketStreamClient.java:3-40`

**Interfaces:**
- Consumes: `SpotWebSocketStreams.allMiniTicker(AllMiniTickerRequest)` from Binance Spot 11.0.1.
- Produces: The same `StreamBlockingQueueWrapper<AllMiniTickerResponse>` consumed by the existing virtual-thread reader.

- [ ] **Step 1: Compile the existing source against 11.0.1 as the baseline**

Run:

```bash
mvn -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository -DskipTests compile
```

Expected: exit 0. This change is an external-library API alignment/configuration refactor; compilation is the contract check rather than a new behavioral unit test.

- [ ] **Step 2: Use the official request-object overload**

Add:

```java
import com.binance.connector.client.spot.websocket.stream.model.AllMiniTickerRequest;
```

Replace the subscription call with:

```java
var queue = streams.allMiniTicker(new AllMiniTickerRequest());
```

- [ ] **Step 3: Compile and run the focused market tests**

Run:

```bash
mvn -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository -Dtest='OfficialBinanceMarketStreamClientTest,BinanceMarketStreamLifecycleTest' test
```

Expected: exit 0 with both test classes passing.

### Task 3: Full verification and delivery

**Files:**
- Verify: `pom.xml`
- Verify: `src/main/java/com/multind/bitpongo/market/OfficialBinanceMarketStreamClient.java`

**Interfaces:**
- Consumes: Tasks 1 and 2.
- Produces: Verified source and dependency graph ready for a scoped commit.

- [ ] **Step 1: Run the complete test suite**

Run:

```bash
mvn -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository test
```

Expected: exit 0 with zero failures and zero errors.

- [ ] **Step 2: Inspect the scoped diff and dependency tree**

Run:

```bash
git diff -- pom.xml src/main/java/com/multind/bitpongo/market/OfficialBinanceMarketStreamClient.java docs/superpowers
mvn -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository -DskipTests dependency:tree -Dincludes=org.eclipse.jetty:*,org.eclipse.jetty.websocket:*
```

Expected: only the approved dependency/API alignment appears; all Jetty artifacts are 11.0.26.

- [ ] **Step 3: Commit only the scoped files**

```bash
git add pom.xml src/main/java/com/multind/bitpongo/market/OfficialBinanceMarketStreamClient.java docs/superpowers/specs/2026-08-21-binance-websocket-dependency-design.md docs/superpowers/plans/2026-08-21-binance-websocket-dependency.md
git commit -m "fix: align Binance websocket dependencies"
```
