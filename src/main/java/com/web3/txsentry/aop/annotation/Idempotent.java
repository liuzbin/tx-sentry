package com.web3.txsentry.aop.annotation;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
    String keyPrefix() default "idem:withdraw:";
    long expireSeconds() default 120; // 锁定时间
}