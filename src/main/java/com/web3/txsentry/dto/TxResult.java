package com.web3.txsentry.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TxResult {
    private String txHash;
    private long nonce;
}