package com.web3.txsentry.service;

import com.web3.txsentry.entity.WithdrawOrder;
import com.web3.txsentry.mapper.citus.WithdrawOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * main service for handling withdrawal requests.
 * responsible for synchronous database persistence and triggering asynchronous execution.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawService {

    private final WithdrawOrderMapper withdrawMapper;
    // inject the dedicated async executor to ensure spring aop proxy works correctly
    private final WithdrawAsyncExecutor asyncExecutor;

    /**
     * 1. business entry: persist to citus db and trigger async broadcast.
     * this method executes within a database transaction.
     */
    @Transactional(rollbackFor = Exception.class)
    public String processWithdrawal(String bizOrderId, String toAddress, BigDecimal amount) {

        WithdrawOrder order = new WithdrawOrder();
        order.setBizOrderId(bizOrderId);
        order.setToAddress(toAddress);
        order.setAmount(amount);
        order.setStatus("PENDING");

        // route to the correct citus worker node via biz_order_id
        withdrawMapper.insertOrder(order);
        log.info("order {} persisted to database, triggering async execution.", bizOrderId);

        // trigger the truly asynchronous method
        asyncExecutor.executeBlockchainBroadcast(order);

        // return immediately to the frontend
        return "Accepted. Processing withdrawal for order: " + bizOrderId;
    }
}