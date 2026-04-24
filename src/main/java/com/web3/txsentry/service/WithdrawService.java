package com.web3.txsentry.service;

import com.web3.txsentry.common.enums.WithdrawStatusEnum;
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawService {

    private final WithdrawOrderMapper withdrawMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(rollbackFor = Exception.class)
    public String processWithdrawal(String bizOrderId, String toAddress, BigDecimal amount, String tokenAddress, boolean isUrgent) {

        WithdrawOrder order = new WithdrawOrder();
        order.setBizOrderId(bizOrderId);
        order.setToAddress(toAddress);
        order.setTokenAddress(tokenAddress);
        order.setAmount(amount);
        order.setIsUrgent(isUrgent);

        // choose route
        if (tokenAddress == null || tokenAddress.trim().isEmpty() || isUrgent) {
            // fast route：native ETH，or Urgent
            order.setStatus(WithdrawStatusEnum.PENDING_SINGLE);
            withdrawMapper.insertOrder(order);

            log.info("Routing to the fast route and publishing a wake-up event, bizOrderId:{}", bizOrderId);

            // AsyncExecutor
            eventPublisher.publishEvent(new WithdrawSingleEvent(order));

        } else {
            // batch route
            order.setStatus(WithdrawStatusEnum.PENDING_BATCH);
            withdrawMapper.insertOrder(order);

            log.info("Routing to the batch route and waiting for schedule tasks, bizOrderId:{}", bizOrderId);
        }

        return "Accepted. Processing withdrawal for order: " + bizOrderId;
    }
}