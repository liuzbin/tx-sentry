# TxSentry: Enterprise-Grade Web3 Withdrawal Gateway

TxSentry is a high-concurrency, gas-optimized, and fault-tolerant Web3 withdrawal gateway designed for EVM-compatible blockchains. It provides a robust infrastructure for handling both native cryptocurrency (e.g., ETH) and ERC-20 token withdrawals, featuring dual-channel routing, automated mempool monitoring, and smart-contract-based batch processing to drastically reduce network fees.

## 🚀 Core Architecture & Features

### 1. Dual-Channel Routing Engine (Hybrid Model)
The system strictly isolates traffic based on business intent to maximize efficiency and user experience:
* **Single-Send Fast Lane (`PENDING_SINGLE`):** Dedicated to native ETH or urgent token withdrawals. Driven by Spring `ApplicationEvent` and `@TransactionalEventListener` for millisecond-level asynchronous execution post-database commit.
* **Batch-Send Slow Lane (`PENDING_BATCH`):** Dedicated to standard ERC-20 tokens. Orders are queued in the database and processed by a scheduled cron job (`BatchWithdrawJob`) to maximize gas savings.

### 2. Extreme Gas Optimization (Smart Batching)
* **Token Dispenser Smart Contract:** Integrates with a custom `TokenDispenser.sol` contract. It aggregates up to 50 individual ERC-20 withdrawals into a single blockchain transaction, reducing total gas consumption by up to 70%.
* **Dynamic Gas Estimation:** Utilizes `eth_estimateGas` prior to signing. This preemptively blocks doomed transactions (e.g., insufficient hot wallet balance, blacklisted addresses) off-chain, preventing wasted gas fees on reverted transactions.

### 3. Absolute Concurrency Safety
* **Double Idempotency Defense:** Protects API endpoints against replay attacks and concurrent bursts using a combination of Redis `SETNX` (L2 memory defense) and Database Unique Constraints (L1 physical defense).
* **Distributed Nonce Management:** Employs Redis atomic increments via Redisson distributed locks to guarantee strictly sequential and collision-free Nonce generation across multiple gateway nodes.
* **Pessimistic Locking & Skip Locked:** The batch aggregation engine utilizes PostgreSQL/Citus `FOR UPDATE SKIP LOCKED` combined with a "Two-Phase State Mutation" (`PENDING` -> `PROCESSING` -> `BROADCASTED`) to ensure thread safety without exhausting database connection pools during heavy Web3 RPC I/O.

### 4. Self-Healing & Mempool Resilience
The blockchain mempool is a dark forest. TxSentry includes dedicated background daemons to guarantee transaction finality:
* **Stuck Nonce Monitor (`StuckNonceMonitorJob`):** Scans the mempool for transactions stuck for over 15 minutes. Automatically calculates a competitive premium (Old Gas * 1.2 vs. Current Network Gas) and broadcasts a Replacement Transaction (Speed Up) using the same Nonce.
* **Zombie Resuscitation Protocol:** Detects transactions physically evicted from the mempool (missing for > 60 minutes) and forcefully revives them using the latest aggressive gas prices to prevent nonce-gap deadlocks.
* **Crash Recovery:** Automatically identifies and rolls back orders stuck in the `PROCESSING_BATCH` state due to sudden server outages or OOM kills.

### 5. Asynchronous On-Chain Reconciliation
* **`TxMonitorJob`:** A dedicated reconciliation engine that groups broadcasted transactions by `TxHash` to minimize RPC calls. It strictly verifies the Ethereum `TransactionReceipt` status (`0x1` for Success, `0x0` for Reverted) to execute the final lifecycle settlement in the database.

## 🛠 Tech Stack
* **Framework:** Java 17+, Spring Boot
* **Web3 Integration:** Web3j (Ethereum RPC)
* **Database:** PostgreSQL / Citus (Optimized for Sharding)
* **Caching & Locks:** Redis (Redisson)
* **Smart Contracts:** Solidity (Batch Dispenser)

## 🔄 Order Lifecycle (State Machine)
1. `PENDING_SINGLE` / `PENDING_BATCH` -> Initial state upon API acceptance.
2. `PROCESSING_BATCH` -> Transient state during gas estimation and signing (prevents dual-spending).
3. `BROADCASTED` -> Successfully pushed to the Ethereum node's mempool.
4. `SUCCESS` -> Confirmed by `TxMonitorJob` (Receipt status `0x1`).
5. `FAILED` -> Reverted on-chain (Receipt status `0x0`) or aborted off-chain due to estimation errors.

---
*Built with rigorous engineering standards for institutional digital asset management.*