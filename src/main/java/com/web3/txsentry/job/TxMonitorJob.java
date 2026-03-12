package com.web3.txsentry.job;

import com.web3.txsentry.entity.WithdrawOrder;
import com.web3.txsentry.mapper.citus.WithdrawOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.util.List;
import java.util.Optional;

/**
 * scheduled job to monitor the on-chain status of broadcasted transactions.
 * bridges the gap between the asynchronous mempool and our local citus database.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TxMonitorJob {

    private final WithdrawOrderMapper withdrawMapper;
    private final Web3j web3j;

    /**
     * runs periodically (e.g., every 15 seconds) to poll the blockchain.
     * delay can be adjusted based on the target chain's average block time.
     */
    @Scheduled(fixedDelay = 15000)
    public void monitorBroadcastedTransactions() {

        // 1. fetch all orders currently in 'BROADCASTED' state.
        // architectural note: this query lacks the shard key (biz_order_id),
        // meaning citus will execute a scatter-gather query across all worker nodes.
        // this is generally acceptable for background worker threads, but not for high-tps apis.
        List<WithdrawOrder> pendingOrders = withdrawMapper.selectByStatus("BROADCASTED");

        if (pendingOrders.isEmpty()) {
            return; // no pending transactions, sleep until next cycle
        }

        log.info("found {} transactions in BROADCASTED state, checking on-chain receipts...", pendingOrders.size());

        for (WithdrawOrder order : pendingOrders) {
            checkOnChainStatus(order);
        }
    }

    private void checkOnChainStatus(WithdrawOrder order) {
        String txHash = order.getTxHash();
        String bizOrderId = order.getBizOrderId();

        try {
            // 2. query the ethereum node for the transaction receipt
            EthGetTransactionReceipt receiptResponse = web3j.ethGetTransactionReceipt(txHash).send();
            Optional<TransactionReceipt> receiptOpt = receiptResponse.getTransactionReceipt();

            if (receiptOpt.isPresent()) {
                TransactionReceipt receipt = receiptOpt.get();

                // 3. parse the exact on-chain execution status
                // "0x1" means the transaction was executed successfully.
                // "0x0" means it reverted (e.g., out of gas, contract execution failed).
                String onChainStatus = receipt.getStatus();

                if ("0x1".equals(onChainStatus)) {
                    log.info("tx {} confirmed successfully. updating order {}.", txHash, bizOrderId);
                    // crucial: state modification MUST route via bizOrderId
                    withdrawMapper.updateStatusAndTxHash(bizOrderId, "SUCCESS", txHash);

                } else if ("0x0".equals(onChainStatus)) {
                    log.error("tx {} failed on-chain (reverted). updating order {}.", txHash, bizOrderId);
                    // crucial: state modification MUST route via bizOrderId
                    withdrawMapper.updateStatusAndTxHash(bizOrderId, "FAILED", txHash);
                }
            } else {
                // 4. receipt is not present. the transaction is still pending in the mempool.
                // we do nothing and wait for the next cron job cycle.
                log.debug("tx {} is still pending in the mempool...", txHash);
            }

        } catch (Exception e) {
            log.error("network error while querying receipt for txHash: {}", txHash, e);
            // do not change database status on network errors; retry on next cycle.
        }
    }
}