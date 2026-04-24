package com.web3.txsentry.service;

import com.web3.txsentry.common.constant.TokenDictionary;
import com.web3.txsentry.common.enums.WithdrawStatusEnum;
import com.web3.txsentry.common.exception.EvmRejectionException;
import com.web3.txsentry.dto.TxResult;
import com.web3.txsentry.entity.WithdrawOrder;
import com.web3.txsentry.event.WithdrawSingleEvent;
import com.web3.txsentry.mapper.citus.WithdrawOrderMapper;
import com.web3.txsentry.service.execution.Web3TransactionEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.utils.Convert;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;

/**
 * Single withdrawal service
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SingleWithdrawService {

    private final Web3TransactionEngine web3Engine;
    private final WithdrawOrderMapper withdrawMapper;
    private final TokenDictionary tokenDictionary;

    @Async("web3AsyncThreadPool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleSingleWithdrawEvent(WithdrawSingleEvent event) {
        WithdrawOrder order = event.getOrder();
        String bizOrderId = order.getBizOrderId();
        log.info("Single service triggered for order: {}", bizOrderId);

        try {
            String toAddress;
            BigInteger valueInWei;
            String payloadData;
            boolean isContractCall;

            // 1. parse route
            if (order.getTokenAddress() == null || order.getTokenAddress().trim().isEmpty()) {
                // native ETH branch
                toAddress = order.getToAddress();
                valueInWei = Convert.toWei(order.getAmount(), Convert.Unit.ETHER).toBigInteger();
                payloadData = "";
                isContractCall = false;
            } else {
                // ERC-20 contract branch
                int decimals = tokenDictionary.getDecimals(order.getTokenAddress());
                BigDecimal multiplier = BigDecimal.valueOf(Math.pow(10, decimals));
                BigInteger tokenAmount = order.getAmount().multiply(multiplier).toBigInteger();

                Function function = new Function("transfer", Arrays.asList(new Address(order.getToAddress()), new Uint256(tokenAmount)), Collections.emptyList());

                toAddress = order.getTokenAddress();
                valueInWei = BigInteger.ZERO;
                payloadData = FunctionEncoder.encode(function);
                isContractCall = true;
            }

            // 2. underlying ENGINE: block and wait for ETH response
            TxResult txResult = web3Engine.executeTransaction(toAddress, valueInWei, payloadData, isContractCall);

            // 3. update status: Success
            withdrawMapper.updateBroadcastSuccess(bizOrderId, txResult.getTxHash(), txResult.getNonce());

        } catch (EvmRejectionException e) {
            log.error("Single withdraw rejected by EVM for order {}. Reason: {}", bizOrderId, e.getMessage());
            // business failed: Request Denied
            withdrawMapper.updateFailedStatus(bizOrderId, WithdrawStatusEnum.FAILED);

        } catch (Exception e) {
            log.error("Critical internal system error for order {}", bizOrderId, e);
            // system exception: System Error
            withdrawMapper.updateFailedStatus(bizOrderId, WithdrawStatusEnum.ERROR);
        }
    }
}