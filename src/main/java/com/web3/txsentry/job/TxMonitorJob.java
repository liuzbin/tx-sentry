package com.web3.txsentry.job;

import com.web3.txsentry.service.TxMonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TxMonitorJob {

    private final TxMonitorService txMonitorService;

    @Scheduled(fixedDelay = 15000)
    public void runTxMonitor() {
        txMonitorService.executeReconciliation();
    }
}