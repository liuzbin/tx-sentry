package com.web3.txsentry.dto;

import lombok.Data;
import java.math.BigDecimal;

/**
 * 提现请求入参对象
 */
@Data
public class WithdrawRequest {
    private String toAddress;
    private String tokenAddress;
    private BigDecimal amount;
}