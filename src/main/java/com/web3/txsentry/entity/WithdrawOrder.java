package com.web3.txsentry.entity;

import com.web3.txsentry.common.enums.WithdrawStatusEnum;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class WithdrawOrder {
    private Long id;
    private String bizOrderId;      // A unique order number used for reconciliation
    private String toAddress;       // withdraw target address
    private String tokenAddress;    // if null, withdraw tokens; or withdraw native coin
    private BigDecimal amount;      // withdraw amount
    private Boolean isUrgent;       // if urgent, fast single route; or slow batch route
    private WithdrawStatusEnum status;          // staus: PENDING, BROADCASTED, CONFIRMED, FAILED
    private String txHash;          // transaction hash code
    private Long nonce;             // Nonce of the current transaction
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}