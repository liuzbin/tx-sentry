package com.web3.txsentry.job;

import com.web3.txsentry.entity.WithdrawOrder;
import com.web3.txsentry.mapper.citus.WithdrawOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 工业级异步链上对账引擎。
 * 适配混合路由架构（单发/聚合），通过 TxHash 分组大幅削减 RPC 请求，
 * 仅依赖 Receipt 的 0x1/0x0 状态进行冷酷决断。
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

        // 1. 抓取所有处于已广播状态的订单
        List<WithdrawOrder> broadcastedOrders = withdrawMapper.selectByStatus("BROADCASTED");

        if (broadcastedOrders.isEmpty()) {
            return;
        }

        // 2. 【核心重构】：按 TxHash 进行物理分组
        // 无论这是单发的 1 个订单，还是聚合打包的 50 个订单，它们在内存里都会被归拢到一个 TxHash 下
        Map<String, List<WithdrawOrder>> ordersByTxHash = broadcastedOrders.stream()
                .filter(order -> order.getTxHash() != null)
                .collect(Collectors.groupingBy(WithdrawOrder::getTxHash));

        log.info("对账引擎启动：捞取到 {} 笔 BROADCASTED 订单，合并为 {} 个独立 TxHash 进行查证",
                broadcastedOrders.size(), ordersByTxHash.size());

        for (Map.Entry<String, List<WithdrawOrder>> entry : ordersByTxHash.entrySet()) {
            String txHash = entry.getKey();
            List<WithdrawOrder> associatedOrders = entry.getValue();

            verifyAndSettle(txHash, associatedOrders);
        }
    }

    /**
     * 对单一 TxHash 进行查证并批量结算
     */
    private void verifyAndSettle(String txHash, List<WithdrawOrder> associatedOrders) {

        // 提取这批订单的业务 ID，用于批量更新
        List<String> bizOrderIds = associatedOrders.stream()
                .map(WithdrawOrder::getBizOrderId)
                .collect(Collectors.toList());

        try {
            // 3. 极其克制的网络请求：每个 TxHash 只查一次以太坊节点！
            Optional<TransactionReceipt> receiptOpt = web3j.ethGetTransactionReceipt(txHash).send().getTransactionReceipt();

            if (receiptOpt.isEmpty()) {
                // 内存池排队中，尚未出块，跳过等待下次轮询
                return;
            }

            TransactionReceipt receipt = receiptOpt.get();
            String status = receipt.getStatus();

            // 4. 冷酷的状态决断
            if ("0x1".equals(status)) {
                // 交易成功：复用现有的 updateStatusBatch 方法，瞬间完结这批订单
                settleOrders(bizOrderIds, "SUCCESS");
                log.info("对账成功 [0x1]！TxHash: {}，已完结 {} 笔订单", txHash, bizOrderIds.size());

            } else if ("0x0".equals(status)) {
                // 交易回滚：极度危险的信号
                // 如果是聚合订单，意味着 50 个人全部失败。这里直接标记为 FAILED，交由人工或上游重试
                settleOrders(bizOrderIds, "FAILED");
                log.error("对账失败 [0x0] (Reverted)！TxHash: {}，导致 {} 笔订单全部失败", txHash, bizOrderIds.size());

            } else {
                log.warn("未知的 Receipt 状态: {} for TxHash: {}", status, txHash);
            }

        } catch (Exception e) {
            log.error("查证 TxHash: {} 遇到网络异常", txHash, e);
        }
    }

    /**
     * 包装一层事务，确保同批订单的状态跃迁是原子性的
     */
    @Transactional(rollbackFor = Exception.class)
    public void settleOrders(List<String> bizOrderIds, String finalStatus) {
        withdrawMapper.updateStatusBatch(bizOrderIds, finalStatus);
    }
}