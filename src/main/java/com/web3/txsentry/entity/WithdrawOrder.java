package com.web3.txsentry.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class WithdrawOrder {
    private Long id;
    private String bizOrderId;      // 业务端传来的唯一订单号，用于对账
    private String toAddress;       // 提现目标地址
    private String tokenAddress;    // 如果非空，则表示提现的是代币，否则则为原生币
    private BigDecimal amount;      // 提现金额
    private String status;          // 状态: PENDING, BROADCASTED, CONFIRMED, FAILED
    private String txHash;          // 链上交易哈希
    private Long nonce;             // 该笔交易使用的 Nonce
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}