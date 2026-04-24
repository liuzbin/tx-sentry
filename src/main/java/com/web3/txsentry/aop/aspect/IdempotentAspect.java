package com.web3.txsentry.aop.aspect;

import com.web3.txsentry.aop.annotation.Idempotent;
import com.web3.txsentry.dto.WithdrawRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class IdempotentAspect {

    private final RedissonClient redissonClient;

    @Around("@annotation(idempotent)")
    public Object checkIdempotent(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {

        // 1. get bizOrderId as unique key for anti-duplicate
        String bizOrderId = null;
        Object[] args = joinPoint.getArgs();
        for (Object arg : args) {
            if (arg instanceof WithdrawRequest) {
                bizOrderId = ((WithdrawRequest) arg).getBizOrderId();
                break;
            }
        }
        if (bizOrderId == null || bizOrderId.trim().isEmpty()) {
            throw new IllegalArgumentException("missing bizOrderId in request body");
        }

        // 2. construct the redis key using the annotation's prefix
        String redisKey = idempotent.keyPrefix() + bizOrderId;
        RBucket<String> bucket = redissonClient.getBucket(redisKey);

        // 3. attempt to acquire the lock using setnx semantics
        boolean isLocked = bucket.setIfAbsent("processing", Duration.ofSeconds(idempotent.expireSeconds()));

        if (!isLocked) {
            log.warn("duplicate request intercepted for bizOrderId: {}", bizOrderId);
            throw new IllegalStateException("duplicate request detected, please try again later");
        }

        try {
            // 4. execute the actual target method (e.g., withdraw controller logic)
            return joinPoint.proceed();

        } catch (IllegalArgumentException e) {
            // 5. delete the lock immediately so the user can correct it and retry.
            log.info("business validation failed, releasing lock for bizOrderId: {}", bizOrderId);
            bucket.delete();
            throw e;

        } catch (Exception e) {
            // 6. keep the lock to prevent retry avalanches from crushing the system.
            log.error("system error occurred, keeping lock for bizOrderId: {}", bizOrderId, e);
            throw e;
        }
    }
}