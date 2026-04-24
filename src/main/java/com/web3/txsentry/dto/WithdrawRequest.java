package com.web3.txsentry.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import java.math.BigDecimal;

/**
 * withdraw input parameters
 */
@Data
public class WithdrawRequest {
    @NotBlank(message = "bizOrderId can not be blank")
    private String bizOrderId;  // business order id

    @NotBlank(message = "toAddress can not be blank")
    private String toAddress;  // target address

    private String tokenAddress;  // if not null, the target is a smart contract

    @NotNull(message = "amount can not be null")
    @Positive(message = "amount must > 0")
    private BigDecimal amount;  // amount of transaction

    private boolean isUrgent = false;  // if Urgent, use single fast track; otherwise use low-cost batch track
}