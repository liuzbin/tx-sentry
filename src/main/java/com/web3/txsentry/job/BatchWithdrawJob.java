package com.web3.txsentry.job;

import com.web3.txsentry.service.BatchWithdrawService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BatchWithdrawJob {

    private final BatchWithdrawService batchWithdrawService;

    /**
     * Execute batch process every 10 seconds
     */
    @Scheduled(fixedDelay = 10000)
    public void runBatchPipeline() {
        batchWithdrawService.executeBatchProcess();
    }

    /**
     * Execute zombie order every 10 minutes
     */
    @Scheduled(fixedDelay = 60000)
    public void runZombieRecovery() {
        batchWithdrawService.recoverZombieOrders();
    }
}