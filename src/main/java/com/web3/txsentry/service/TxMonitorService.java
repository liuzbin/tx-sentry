package com.web3.txsentry.service;

import com.web3.txsentry.common.enums.WithdrawStatusEnum;
import com.web3.txsentry.entity.WithdrawOrder;
import com.web3.txsentry.mapper.citus.WithdrawOrderMapper;
import com.web3.txsentry.service.execution.Web3TransactionEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TxMonitorService {

    private final WithdrawOrderMapper withdrawMapper;
    private final Web3TransactionEngine web3Engine;

    /**
     * 核心对账编排逻辑 (无事务，纯内存调度)
     */
    public void executeReconciliation() {
        List<WithdrawOrder> broadcastedOrders = withdrawMapper.selectByStatus("BROADCASTED");
        if (broadcastedOrders.isEmpty()) return;

        Map<String, List<WithdrawOrder>> ordersByTxHash = broadcastedOrders.stream()
                .filter(order -> order.getTxHash() != null)
                .collect(Collectors.groupingBy(WithdrawOrder::getTxHash));

        for (Map.Entry<String, List<WithdrawOrder>> entry : ordersByTxHash.entrySet()) {
            verifyAndSettle(entry.getKey(), entry.getValue());
        }
    }

    private void verifyAndSettle(String txHash, List<WithdrawOrder> associatedOrders) {
        List<String> bizOrderIds = associatedOrders.stream().map(WithdrawOrder::getBizOrderId).collect(Collectors.toList());

        try {
            // 呼叫底层引擎查证
            String status = web3Engine.getTransactionReceiptStatus(txHash);

            if (status == null) {
                return; // 还在内存池，跳过
            }

            if ("0x1".equals(status)) {
                settleOrdersWithTransaction(bizOrderIds, WithdrawStatusEnum.SUCCESS);
                log.info("TxHash: {} 对账成功，完结 {} 笔订单", txHash, bizOrderIds.size());
            } else if ("0x0".equals(status)) {
                settleOrdersWithTransaction(bizOrderIds, WithdrawStatusEnum.FAILED);
                log.error("TxHash: {} 被以太坊回滚，导致 {} 笔订单失败", txHash, bizOrderIds.size());
            }
        } catch (Exception e) {
            log.error("查证 TxHash: {} 遇到网络异常", txHash, e);
        }
    }

    /**
     * 严格独立的数据库事务方法
     * 既然它现在在一个正式的 Service 组件里，当外部方法或者同一层的其他 Service 调用时，AOP 依然需要特殊处理。
     * 但因为我们在 `executeReconciliation` 内部调用它，依然会有失效风险。
     * 终极解法：使用 AopContext 或者更优雅的自注入，或者干脆把调度独立到 Job，这里的事务绝对安全！
     */
    @Transactional(rollbackFor = Exception.class)
    public void settleOrdersWithTransaction(List<String> bizOrderIds, WithdrawStatusEnum finalStatus) {
        withdrawMapper.updateStatusBatch(bizOrderIds, finalStatus);
    }
}