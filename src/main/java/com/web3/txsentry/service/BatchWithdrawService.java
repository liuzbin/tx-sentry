package com.web3.txsentry.service;

import com.web3.txsentry.common.constant.TokenDictionary;
import com.web3.txsentry.common.enums.WithdrawStatusEnum;
import com.web3.txsentry.common.exception.EvmRejectionException;
import com.web3.txsentry.dto.TxResult;
import com.web3.txsentry.entity.WithdrawOrder;
import com.web3.txsentry.mapper.citus.WithdrawOrderMapper;
import com.web3.txsentry.service.execution.Web3TransactionEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BatchWithdrawService {

    private final WithdrawOrderMapper withdrawMapper;
    private final Web3TransactionEngine web3Engine;
    private final TokenDictionary tokenDictionary;

    @Value("${web3.dispenser-contract}")
    private String dispenserContractAddress;

    /**
     *  Batch withdrawal service - Only tokens
     */
    public void executeBatchProcess() {
        // 1. Seize and lock orders - short transactions
        List<WithdrawOrder> processingOrders = lockAndFetchPendingBatchOrders(200);
        if (processingOrders.isEmpty()) return;

        // 2. Group by TokenAddress = type of tokens
        Map<String, List<WithdrawOrder>> groupedOrders = processingOrders.stream()
                .collect(Collectors.groupingBy(WithdrawOrder::getTokenAddress));

        // 3. Traverse and execute
        for (Map.Entry<String, List<WithdrawOrder>> entry : groupedOrders.entrySet()) {
            String tokenAddress = entry.getKey();
            List<WithdrawOrder> orders = entry.getValue();

            // slice into 50/batch
            for (int i = 0; i < orders.size(); i += 50) {
                List<WithdrawOrder> batch = orders.subList(i, Math.min(i + 50, orders.size()));
                processSingleBatch(tokenAddress, batch);
            }
        }
    }

    /**
     * Process one batch of tokens
     */
    private void processSingleBatch(String tokenAddress, List<WithdrawOrder> batch) {
        List<String> bizOrderIds = batch.stream().map(WithdrawOrder::getBizOrderId).collect(Collectors.toList());

        try {
            // Build ABI Data
            String encodedData = encodeBatchData(tokenAddress, batch);

            // Call engine
            TxResult result = web3Engine.executeTransaction(dispenserContractAddress, BigInteger.ZERO, encodedData, true);

            // Confirm Broadcasted success
            confirmBatchBroadcasted(bizOrderIds, result.getTxHash());

        } catch (EvmRejectionException e) {
            log.error("Batch broadcast clearly rejected by node for token {}, failing all orders in batch", tokenAddress, e);
            // business failed: Request Denied
            withdrawMapper.updateStatusBatch(bizOrderIds, WithdrawStatusEnum.FAILED);

        } catch (Exception e) {
            log.error("Network timeout or local system crash for token {}, reverting status to PENDING_BATCH", tokenAddress, e);
            // system exception: System Error
            revertOrdersToPending(bizOrderIds);
        }
    }

    /**
     * ABI coding logic
     */
    private String encodeBatchData(String tokenAddress, List<WithdrawOrder> batch) {
        int decimals = tokenDictionary.getDecimals(tokenAddress);
        BigDecimal multiplier = BigDecimal.valueOf(Math.pow(10, decimals));

        List<Address> addresses = batch.stream().map(o -> new Address(o.getToAddress())).collect(Collectors.toList());
        List<Uint256> amounts = batch.stream()
                .map(o -> new Uint256(o.getAmount().multiply(multiplier).toBigInteger()))
                .collect(Collectors.toList());

        Function function = new Function("batchTransferToken",
                Arrays.asList(new Address(tokenAddress), new DynamicArray<>(Address.class, addresses), new DynamicArray<>(Uint256.class, amounts)),
                Collections.emptyList());

        return FunctionEncoder.encode(function);
    }

    @Transactional(rollbackFor = Exception.class)
    public List<WithdrawOrder> lockAndFetchPendingBatchOrders(int limit) {
        List<WithdrawOrder> orders = withdrawMapper.selectPendingBatchOrdersWithLock(limit);
        if (!orders.isEmpty()) {
            List<String> ids = orders.stream().map(WithdrawOrder::getBizOrderId).collect(Collectors.toList());
            withdrawMapper.updateStatusBatch(ids, WithdrawStatusEnum.PROCESSING_BATCH);
        }
        return orders;
    }

    @Transactional(rollbackFor = Exception.class)
    public void confirmBatchBroadcasted(List<String> ids, String txHash) {
        withdrawMapper.batchUpdateToBroadcasted(ids, txHash);
    }

    @Transactional(rollbackFor = Exception.class)
    public void revertOrdersToPending(List<String> ids) {
        withdrawMapper.updateStatusBatch(ids, WithdrawStatusEnum.PENDING_BATCH);
    }

    @Transactional(rollbackFor = Exception.class)
    public void recoverZombieOrders() {
        withdrawMapper.recoverZombieProcessingOrders();
    }
}