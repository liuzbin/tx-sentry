package com.web3.txsentry.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;

import java.math.BigInteger;

/**
 * distributed nonce management service for web3 transactions.
 * guarantees strictly monotonically increasing nonces even under extreme concurrency.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NonceService {

    private final RedissonClient redissonClient;
    private final Web3j web3j;

    private static final String NONCE_KEY_PREFIX = "web3:nonce:";
    private static final String NONCE_LOCK_PREFIX = "lock:nonce:init:";

    /**
     * get the next strictly sequential nonce for the specified hot wallet address.
     * utilizes redis atomic increment to ensure thread safety across distributed nodes.
     *
     * @param address the evm hot wallet address
     * @return the next valid nonce
     */
    public long getNextNonce(String address) {
        String redisKey = NONCE_KEY_PREFIX + address.toLowerCase();
        RAtomicLong atomicNonce = redissonClient.getAtomicLong(redisKey);

        // 1. cold start phase: if the nonce is not in redis, we must fetch it from the blockchain.
        if (!atomicNonce.isExists()) {
            initializeNonceFromChain(address, atomicNonce);
        }

        // 2. core magic: atomically get the current value and increment it by 1 in redis.
        // this is executed entirely in the redis server's single-threaded engine.
        long assignedNonce = atomicNonce.getAndIncrement();

        log.info("assigned nonce {} for address {}", assignedNonce, address);
        return assignedNonce;
    }

    /**
     * safely initialize the redis nonce counter by querying the blockchain.
     * uses double-checked locking to prevent multiple threads from initializing simultaneously.
     */
    private void initializeNonceFromChain(String address, RAtomicLong atomicNonce) {
        String lockKey = NONCE_LOCK_PREFIX + address.toLowerCase();
        RLock initLock = redissonClient.getLock(lockKey);

        // block other threads from querying the node during initialization
        initLock.lock();
        try {
            // double-check inside the lock in case another thread already initialized it
            if (!atomicNonce.isExists()) {
                try {
                    // query the "pending" block to get the most accurate next nonce
                    EthGetTransactionCount response = web3j.ethGetTransactionCount(
                            address, DefaultBlockParameterName.PENDING).send();

                    BigInteger onChainNonce = response.getTransactionCount();

                    // seed the redis atomic long with the blockchain's truth
                    atomicNonce.set(onChainNonce.longValue());
                    log.info("initialized redis nonce for {} to {}", address, onChainNonce);

                } catch (Exception e) {
                    log.error("failed to fetch initial nonce from blockchain for address {}", address, e);
                    throw new RuntimeException("nonce initialization failed", e);
                }
            }
        } finally {
            // absolutely ensure the lock is released
            initLock.unlock();
        }
    }
}