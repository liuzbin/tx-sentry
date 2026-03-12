package com.web3.txsentry.aspect;

import com.web3.txsentry.annotation.Idempotent;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class IdempotentAspect {

    private final RedissonClient redissonClient;

    @Around("@annotation(idempotent)")
    public Object checkIdempotent(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {

        // 1. get the current http request context
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return joinPoint.proceed();
        }
        HttpServletRequest request = attributes.getRequest();

        // 2. extract the unique business order id from the request header.
        // this maps to the bizorderid in our withdraworder entity.
        String bizOrderId = request.getHeader("biz-order-id");
        if (bizOrderId == null || bizOrderId.trim().isEmpty()) {
            throw new IllegalArgumentException("missing biz-order-id in header");
        }

        // 3. construct the redis key using the annotation's prefix
        String redisKey = idempotent.keyPrefix() + bizOrderId;
        RBucket<String> bucket = redissonClient.getBucket(redisKey);

        // 4. attempt to acquire the lock using setnx semantics
        boolean isLocked = bucket.setIfAbsent("processing", Duration.ofSeconds(idempotent.expireSeconds()));

        if (!isLocked) {
            log.warn("duplicate request intercepted for bizOrderId: {}", bizOrderId);
            throw new IllegalStateException("duplicate request detected, please try again later");
        }

        try {
            // 5. execute the actual target method (e.g., withdraw controller logic)
            return joinPoint.proceed();

        } catch (IllegalArgumentException e) {
            // 6. if the failure is due to bad parameters (e.g., invalid address format),
            // delete the lock immediately so the user can correct it and retry.
            log.info("business validation failed, releasing lock for bizOrderId: {}", bizOrderId);
            bucket.delete();
            throw e;

        } catch (Exception e) {
            // 7. for unknown system errors (e.g., database timeout),
            // keep the lock to prevent retry avalanches from crushing the system.
            log.error("system error occurred, keeping lock for bizOrderId: {}", bizOrderId, e);
            throw e;
        }
    }
}