package com.web3.txsentry.controller;

import com.web3.txsentry.annotation.Idempotent;
import com.web3.txsentry.dto.WithdrawRequest;
import com.web3.txsentry.service.WithdrawService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/withdraw")
@RequiredArgsConstructor
public class WithdrawController {

    private final WithdrawService withdrawService;

    @PostMapping("/submit")
    @Idempotent // 触发 Redis 去重锁
    public ResponseEntity<String> submitWithdraw(
            @RequestHeader("Biz-Order-Id") String bizOrderId,
            @RequestBody WithdrawRequest request) {

        // 参数基础校验
        if (request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return ResponseEntity.badRequest().body("Amount must be greater than zero");
        }

        String result = withdrawService.processWithdrawal(bizOrderId, request.getToAddress(), request.getAmount());
        return ResponseEntity.ok(result);
    }
}