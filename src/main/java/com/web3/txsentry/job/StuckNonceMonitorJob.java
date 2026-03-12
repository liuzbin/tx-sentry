package com.web3.txsentry.job;

import com.web3.txsentry.entity.WithdrawOrder;
import com.web3.txsentry.mapper.citus.WithdrawOrderMapper;
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
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.EthGasPrice;
import org.web3j.protocol.core.methods.response.Transaction;
import org.web3j.utils.Convert;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 工业级内存池监控与防卡死引擎。
 * 负责扫描长时间处于 BROADCASTED 状态的订单，并通过 Gas 提价覆盖 (Speed Up) 疏通 Nonce 拥堵。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StuckNonceMonitorJob {

    private final WithdrawOrderMapper withdrawMapper;
    private final Web3j web3j;

    @Value("${web3.wallet.private-key}")
    private String privateKey;

    @Value("${web3.network.chain-id}")
    private long chainId;

    // 定义“卡死”的时间阈值，例如 15 分钟没有被打包，视为被内存池挂起
    private static final int STUCK_THRESHOLD_MINUTES = 15;

    /**
     * 每 3 分钟执行一次扫描
     */
    @Scheduled(fixedDelay = 180000)
    public void monitorAndSpeedUpStuckTransactions() {
        LocalDateTime thresholdTime = LocalDateTime.now().minusMinutes(STUCK_THRESHOLD_MINUTES);

        // 1. 查询超过阈值时间且依然处于 BROADCASTED 的订单
        List<WithdrawOrder> stuckOrders = withdrawMapper.selectStuckOrders("BROADCASTED", thresholdTime);

        if (stuckOrders.isEmpty()) {
            return;
        }

        log.warn("检测到 {} 笔疑似卡死的交易，开始执行提价覆盖策略 (Speed Up)...", stuckOrders.size());

        Credentials credentials = Credentials.create(privateKey);

        for (WithdrawOrder order : stuckOrders) {
            executeSpeedUp(order, credentials);
        }
    }

    private void executeSpeedUp(WithdrawOrder order, Credentials credentials) {
        String bizOrderId = order.getBizOrderId();
        String oldTxHash = order.getTxHash();
        long nonce = order.getNonce(); // 必须复用同一个 Nonce

        try {
            // 2. 去内存池里寻找这笔原交易，获取它当时的 Gas Price
            Optional<Transaction> txOpt = web3j.ethGetTransactionByHash(oldTxHash).send().getTransaction();
            if (txOpt.isEmpty()) {
                // 防僵尸交易: 如果在内存池查不到，且距离上次广播已经超过 1小时,这意味着它 100% 被内存池物理清除了，必须立刻强行复活，否则整个 Nonce 队列死锁
                java.time.Duration duration = java.time.Duration.between(order.getUpdateTime(), LocalDateTime.now());
                if (duration.toMinutes() >= 60) {
                    log.error("极度危险：订单 {} (Nonce: {}) 已在内存池彻底丢失超过 1 小时，触发僵尸交易强行复活协议！", bizOrderId, nonce);
                    resuscitateZombieTransaction(order, credentials, nonce);
                } else {
                    // 如果还不到 1 小时，说明可能是刚出块导致内存池查不到，交由 TxMonitorJob 去查回执
                    log.debug("内存池中未发现 txHash: {}，可能已出块，交由结算定时任务处理", oldTxHash);
                }
                return;
            }

            Transaction pendingTx = txOpt.get();
            if (pendingTx.getBlockNumber() != null) {
                // 已经上链，不需要覆盖
                return;
            }

            BigInteger oldGasPrice = pendingTx.getGasPrice();

            // 3. 获取当前全网最新的 Gas Price
            EthGasPrice ethGasPrice = web3j.ethGasPrice().send();
            BigInteger currentNetworkGasPrice = ethGasPrice.getGasPrice();

            // 4. 计算新的 Gas Price
            // 取 oldGasPrice * 1.2 和 currentNetworkGasPrice 的最大值，确保一定能覆盖并迅速打包
            BigInteger minRequiredGasPrice = new BigDecimal(oldGasPrice).multiply(new BigDecimal("1.2")).toBigInteger();
            BigInteger newGasPrice = currentNetworkGasPrice.compareTo(minRequiredGasPrice) > 0
                    ? currentNetworkGasPrice
                    : minRequiredGasPrice;

            log.info("订单 {} (Nonce: {}) 正在提价重发。旧 GasPrice: {}, 新 GasPrice: {}",
                    bizOrderId, nonce, oldGasPrice, newGasPrice);
            broadcastReplacement(order, nonce, newGasPrice, credentials);
        } catch (Exception e) {
            log.error("执行提价覆盖时发生异常，订单: {}", bizOrderId, e);
        }
    }

    /**
     * 僵尸交易强行复活
     * 无视旧状态，直接取全网最新 Gas 并上浮 20%，强行覆盖那个死锁的 Nonce
     */
    private void resuscitateZombieTransaction(WithdrawOrder order, Credentials credentials, long nonce) throws Exception {
        // 1. 获取全网当前最新 Gas Price
        BigInteger currentNetworkGasPrice = web3j.ethGasPrice().send().getGasPrice();

        // 2. 既然已经卡死 1 小时，说明网络极度拥堵。直接在当前网络价基础上再溢价 20%，确保一击必杀
        BigInteger aggressiveGasPrice = new BigDecimal(currentNetworkGasPrice).multiply(new BigDecimal("1.2")).toBigInteger();

        log.info("正在使用极度激进的 GasPrice {} 复活僵尸订单 {}", aggressiveGasPrice, order.getBizOrderId());

        // 3. 复用底层的广播逻辑
        broadcastReplacement(order, nonce, aggressiveGasPrice, credentials);
    }

    /**
     * 重新构建交易报文，逻辑与 AsyncExecutor 保持绝对一致，但强制注入传入的 Nonce
     */
    private RawTransaction buildRawTransactionWithExactNonce(WithdrawOrder order, long nonce, BigInteger gasPrice) {
        String tokenAddress = order.getTokenAddress();
        String toAddress = order.getToAddress();

        if (tokenAddress == null || tokenAddress.trim().isEmpty()) {
            BigInteger valueInWei = Convert.toWei(order.getAmount(), Convert.Unit.ETHER).toBigInteger();
            return RawTransaction.createEtherTransaction(BigInteger.valueOf(nonce), gasPrice, BigInteger.valueOf(21000L), toAddress, valueInWei);
        } else {
            int tokenDecimals = 6;
            BigDecimal multiplier = BigDecimal.valueOf(Math.pow(10, tokenDecimals));
            BigInteger tokenAmount = order.getAmount().multiply(multiplier).toBigInteger();

            Function function = new Function("transfer", Arrays.asList(new Address(toAddress), new Uint256(tokenAmount)), Collections.emptyList());
            String encodedFunction = FunctionEncoder.encode(function);

            // 提价重发时，GasLimit 给一个安全的宽松值
            BigInteger gasLimit = BigInteger.valueOf(100000L);
            return RawTransaction.createTransaction(BigInteger.valueOf(nonce), gasPrice, gasLimit, tokenAddress, BigInteger.ZERO, encodedFunction);
        }
    }

    private void broadcastReplacement(WithdrawOrder order, long nonce, BigInteger newGasPrice, Credentials credentials) throws Exception {
        RawTransaction newRawTransaction = buildRawTransactionWithExactNonce(order, nonce, newGasPrice);

        byte[] signedMessage = TransactionEncoder.signMessage(newRawTransaction, chainId, credentials);
        String newHexValue = Numeric.toHexString(signedMessage);

        org.web3j.protocol.core.methods.response.EthSendTransaction ethSendTransaction =
                web3j.ethSendRawTransaction(newHexValue).send();

        if (ethSendTransaction.hasError()) {
            log.error("订单 {} 覆盖广播失败: {}", order.getBizOrderId(), ethSendTransaction.getError().getMessage());
            return;
        }

        String newTxHash = ethSendTransaction.getTransactionHash();
        withdrawMapper.updateTxHashForSpeedUp(order.getBizOrderId(), newTxHash);
        log.info("订单 {} 覆盖广播成功！新的 TxHash: {}", order.getBizOrderId(), newTxHash);
    }
}