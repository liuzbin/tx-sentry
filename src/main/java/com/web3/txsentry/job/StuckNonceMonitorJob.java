package com.web3.txsentry.job;

import com.web3.txsentry.common.constant.TokenDictionary;
import com.web3.txsentry.entity.WithdrawOrder;
import com.web3.txsentry.mapper.citus.WithdrawOrderMapper;
import com.web3.txsentry.service.execution.Web3TransactionEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

@Slf4j
@Component
@RequiredArgsConstructor
public class StuckNonceMonitorJob {

    private final WithdrawOrderMapper withdrawMapper;
    private final Web3TransactionEngine web3Engine;
    private final TokenDictionary tokenDictionary;

    @Value("${web3.wallet.private-key}")
    private String privateKey;

    /**
     * 每 1 分钟执行一次高频雷达扫描
     */
    @Scheduled(fixedDelay = 60000)
    public void scanAndHealMempool() {
        try {
            Credentials credentials = Credentials.create(privateKey);
            String hotWalletAddress = credentials.getAddress();

            // 1. 获取链上目前正在死等的确切 Nonce (例如链上说：我正在等 Nonce = 100)
            long expectedNonce = web3Engine.getNextExpectedChainNonce(hotWalletAddress);
            log.info("自愈雷达扫描：链上目前阻塞在 Nonce = {}", expectedNonce);

            // 2. 去数据库里寻找这条持有 Nonce 100 的记录
            WithdrawOrder order = withdrawMapper.selectByNonce(expectedNonce);

            if (order != null) {
                // ==========================================
                // 战法一：单子在数据库里！说明是网卡了或者 Gas 太低，执行 [提价重发 Speed Up]
                // ==========================================
                // 如果这个单子落库时间还没超过 5 分钟，说明可能还在正常排队，给它一点时间，不着急提价
                if (order.getUpdateTime().isAfter(LocalDateTime.now().minusMinutes(5))) {
                    return;
                }

                log.warn("触发战法一：订单 {} (Nonce: {}) 卡顿超过 5 分钟，执行 EIP-1559 原单溢价重发", order.getBizOrderId(), expectedNonce);
                executeSpeedUp(order, expectedNonce);

            } else {
                // ==========================================
                // 战法二：单子不在数据库！幽灵空洞嫌疑出现，启动时间边界推断法
                // ==========================================

                // 去数据库查一查，有没有比 expectedNonce (100) 还要大的记录 (比如 101)？
                WithdrawOrder nextOrder = withdrawMapper.selectNextNonceOrder(expectedNonce);

                if (nextOrder != null) {
                    // 找到了 101！检查 101 的创建时间，如果 101 已经是 3 分钟之前创建的了，那 100 绝对是死在内存里了！
                    if (nextOrder.getCreateTime().isBefore(LocalDateTime.now().minusMinutes(3))) {
                        log.error("触发战法二：幽灵 Nonce {} 确诊！后置单据 {} 已存活超 3 分钟。立即发射 0 ETH 空炮覆盖！", expectedNonce, nextOrder.getBizOrderId());
                        executeCancelDrop(expectedNonce, hotWalletAddress);
                    }
                } else {
                    // 如果连大于 100 的单子也没有，说明系统刚好就发到了 100，此时线程还在执行中，还没落库，安全等待即可。
                    log.debug("Nonce {} 未查到记录，但也无后置记录，判断为正常业务延迟，暂不干预。", expectedNonce);
                }
            }
        } catch (Exception e) {
            log.error("自愈雷达运行异常", e);
        }
    }

    /**
     * 执行战法一：原样提价
     */
    private void executeSpeedUp(WithdrawOrder order, long nonce) {
        try {
            boolean isContractCall = order.getTokenAddress() != null && !order.getTokenAddress().isEmpty();
            String payloadData = "";
            BigInteger valueInWei = BigInteger.ZERO;
            String toAddress = isContractCall ? order.getTokenAddress() : order.getToAddress();

            if (isContractCall) {
                int decimals = tokenDictionary.getDecimals(order.getTokenAddress());
                BigDecimal multiplier = BigDecimal.valueOf(Math.pow(10, decimals));
                BigInteger tokenAmount = order.getAmount().multiply(multiplier).toBigInteger();
                Function function = new Function("transfer", Arrays.asList(new Address(order.getToAddress()), new Uint256(tokenAmount)), Collections.emptyList());
                payloadData = FunctionEncoder.encode(function);
            } else {
                valueInWei = org.web3j.utils.Convert.toWei(order.getAmount(), org.web3j.utils.Convert.Unit.ETHER).toBigInteger();
            }

            // 调用引擎开后门，isCancel = false
            String newTxHash = web3Engine.executeSpeedUpOrCancel(toAddress, valueInWei, payloadData, isContractCall, nonce, false);
            withdrawMapper.updateTxHashForSpeedUp(order.getBizOrderId(), newTxHash);
            log.info("订单 {} 原单提价成功，新 TxHash: {}", order.getBizOrderId(), newTxHash);

        } catch (Exception e) {
            log.error("执行提价重发失败", e);
        }
    }

    /**
     * 执行战法二：发射空炮填充空洞
     */
    private void executeCancelDrop(long ghostNonce, String hotWalletAddress) {
        try {
            // 调用引擎开后门，发给自己，金额为 0，isCancel = true
            String cancelTxHash = web3Engine.executeSpeedUpOrCancel(hotWalletAddress, BigInteger.ZERO, "", false, ghostNonce, true);
            log.warn("幽灵 Nonce {} 已被空炮物理填平！拦截成功，疏通 TxHash: {}", ghostNonce, cancelTxHash);
        } catch (Exception e) {
            log.error("空炮发射失败，Nonce 依然堵塞", e);
        }
    }
}