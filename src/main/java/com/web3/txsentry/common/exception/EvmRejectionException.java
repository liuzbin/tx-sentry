package com.web3.txsentry.common.exception;

public class EvmRejectionException extends RuntimeException {
    public EvmRejectionException(String message) {
        super(message);
    }
}