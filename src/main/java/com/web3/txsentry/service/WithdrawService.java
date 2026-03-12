package com.web3.txsentry.service;

import com.web3.txsentry.entity.WithdrawOrder;
import com.web3.txsentry.event.WithdrawSingleEvent;
import com.web3.txsentry.mapper.citus.WithdrawOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * main service for handling withdrawal requests.
 * 具备工业级冷酷路由能力，利用 Spring 事件总线解耦上链逻辑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawService {

    private final WithdrawOrderMapper withdrawMapper;
    private final ApplicationEventPublisher eventPublisher; // 替换了原先强耦合的 asyncExecutor

    /**
     * 1. business entry: 物理路由落库并触发异步事件。
     */
    @Transactional(rollbackFor = Exception.class)
    public String processWithdrawal(String bizOrderId, String toAddress, BigDecimal amount, String tokenAddress, boolean isUrgent) {

        WithdrawOrder order = new WithdrawOrder();
        order.setBizOrderId(bizOrderId);
        order.setToAddress(toAddress);
        order.setTokenAddress(tokenAddress);
        order.setAmount(amount);
        order.setIsUrgent(isUrgent); // 记录用户的加急意图

        // 路由判断
        if (tokenAddress == null || tokenAddress.trim().isEmpty() || isUrgent) {
            // 快车道：原生 ETH，或者用户强制加急的代币
            order.setStatus("PENDING_SINGLE");
            withdrawMapper.insertOrder(order);

            log.info("路由至快车道 (PENDING_SINGLE) 并发布唤醒事件: {}", bizOrderId);

            // 抛出内存事件，唤醒底层的 AsyncExecutor
            eventPublisher.publishEvent(new WithdrawSingleEvent(order));

        } else {
            // 慢车道：普通的代币提现，为了极度省钱，强行压入聚合队列
            order.setStatus("PENDING_BATCH");
            withdrawMapper.insertOrder(order);

            log.info("路由至慢车道 (PENDING_BATCH)，静默等待聚合引擎捞取: {}", bizOrderId);
        }

        return "Accepted. Processing withdrawal for order: " + bizOrderId;
    }
}