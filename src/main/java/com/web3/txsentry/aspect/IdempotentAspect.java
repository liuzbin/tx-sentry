package com.web3.txsentry.aspect;

import com.web3.txsentry.annotation.Idempotent;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;

@Aspect
@Component
public class IdempotentAspect {

    private final RedissonClient redissonClient;

    public IdempotentAspect(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Around("@annotation(idempotent)")
    public Object check(ProceedingJoinPoint point, Idempotent idempotent) throws Throwable {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        // 强制要求前端/业务端在 Header 中传入业务订单号作为幂等键
        String bizOrderId = request.getHeader("Biz-Order-Id");

        if (bizOrderId == null || bizOrderId.isBlank()) {
            throw new IllegalArgumentException("Missing Biz-Order-Id in headers");
        }

        String redisKey = idempotent.keyPrefix() + bizOrderId;
        RBucket<String> bucket = redissonClient.getBucket(redisKey);

        // 分布式锁 SETNX: 保证同一个业务订单号在 120 秒内只能有一个请求穿透到 Controller
        if (!bucket.setIfAbsent("PROCESSING", Duration.ofSeconds(idempotent.expireSeconds()))) {
            throw new IllegalStateException("Duplicate withdrawal request detected.");
        }

        try {
            return point.proceed(); // 放行到 Controller
        } catch (Exception e) {
            bucket.delete(); // 如果业务校验失败抛出异常，释放锁允许重试
            throw e;
        }
    }
}