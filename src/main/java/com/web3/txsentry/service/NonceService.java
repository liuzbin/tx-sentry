package com.web3.txsentry.service;

import org.redisson.api.RAtomicLong;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class NonceService {
    private final RedissonClient redissonClient;

    public NonceService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    public long getNextNonce(String hotWalletAddress) {
        String lockKey = "lock:nonce:" + hotWalletAddress;
        String nonceKey = "nonce:" + hotWalletAddress;

        RLock lock = redissonClient.getLock(lockKey);
        try {
            // 最多等 3 秒拿锁，锁持有 10 秒防死锁
            if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                RAtomicLong atomicNonce = redissonClient.getAtomicLong(nonceKey);

                // 【预留接口】如果 Redis 宕机重启，需要去链上查询最新 Nonce 并塞回 Redis
                if (!atomicNonce.isExists()) {
                    long onChainNonce = 0L; // TODO: web3j.ethGetTransactionCount()
                    atomicNonce.set(onChainNonce);
                }
                return atomicNonce.getAndIncrement();
            } else {
                throw new RuntimeException("System busy: Cannot acquire nonce lock");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Nonce lock interrupted");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}