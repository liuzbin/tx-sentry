package com.web3.txsentry.controller;

import com.web3.txsentry.aop.annotation.Idempotent;
import com.web3.txsentry.dto.ApiResponse;
import com.web3.txsentry.dto.WithdrawRequest;
import com.web3.txsentry.service.WithdrawService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/withdraw")
@RequiredArgsConstructor
public class WithdrawController {

    private final WithdrawService withdrawService;

    /**
     * Transaction execution layer
     * Only handles transaction execution and responsible for orderId
     */
    @PostMapping("/submit")
    @Idempotent // Check duplicate submission
    public ApiResponse<String> submitWithdraw(
            @Valid @RequestBody WithdrawRequest request) {

        try {
            String result = withdrawService.processWithdrawal(
                    request.getBizOrderId(),
                    request.getToAddress(),
                    request.getAmount(),
                    request.getTokenAddress(),
                    request.isUrgent()
            );
            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }
}