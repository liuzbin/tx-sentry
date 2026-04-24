package com.web3.txsentry.common.enums;

import lombok.Getter;

/**
 * Enterprise-grade state machine enumeration for withdrawal orders.
 */
@Getter
public enum WithdrawStatusEnum {

    // ==========================================
    // 1. Pending States (Queued)
    // ==========================================
    PENDING_SINGLE("Fast Lane: Queued for single asynchronous execution", false),
    PENDING_BATCH("Slow Lane: Queued for batch aggregation job", false),

    // ==========================================
    // 2. Processing States (In-Flight)
    // ==========================================
    PROCESSING_BATCH("Processing: Locked by scheduled job (Double-spend prevention)", false),

    // ==========================================
    // 3. On-Chain Pending
    // ==========================================
    BROADCASTED("Broadcasted: Pushed to mempool, awaiting confirmation", false),

    // ==========================================
    // 4. Terminal States
    // ==========================================
    SUCCESS("Success: On-chain confirmed (Receipt 0x1)", true),
    FAILED("Failed: On-chain reverted or pre-flight check failed", true),
    ERROR("Error: Local assembly or critical network failure", true);

    private final String description;

    /**
     * Indicates whether the order has reached a final state.
     * Prevents "zombie resurrection" bugs during retry logic.
     */
    private final boolean isTerminal;

    WithdrawStatusEnum(String description, boolean isTerminal) {
        this.description = description;
        this.isTerminal = isTerminal;
    }
}