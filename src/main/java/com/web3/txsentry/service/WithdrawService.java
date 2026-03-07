package com.web3.txsentry.service;

import com.web3.txsentry.entity.WithdrawOrder;
import com.web3.txsentry.mapper.WithdrawOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawService {

    private final WithdrawOrderMapper withdrawMapper;
    private final NonceService nonceService;

    // 公司用于发钱的热钱包地址
    private static final String HOT_WALLET = "0xAdminWalletAddress...";

    /**
     * 1. 核心提现入口（同步方法，快速返回）
     */
    @Transactional
    public String processWithdrawal(String bizOrderId, String toAddress, BigDecimal amount) {
        // 1. 落地数据库，状态为 PENDING。如果 bizOrderId 唯一键冲突，这里会抛出异常，也是一层防重。
        WithdrawOrder order = new WithdrawOrder();
        order.setBizOrderId(bizOrderId);
        order.setToAddress(toAddress);
        order.setAmount(amount);
        withdrawMapper.insertOrder(order);

        // 2. 扔给异步线程去和区块链交互，不阻塞 Tomcat 的 HTTP 线程
        executeBlockchainBroadcast(order);

        return "Withdrawal accepted. Order ID: " + order.getId();
    }

    /**
     * 2. 异步广播调度（后台执行）
     */
    @Async
    public void executeBlockchainBroadcast(WithdrawOrder order) {
        try {
            // A. 获取绝对安全的递增 Nonce
            long nonce = nonceService.getNextNonce(HOT_WALLET);

            // B. TODO: 调用 Web3j 预估 Gas，构建交易并进行本地 ECDSA 私钥签名
            String signedTxHex = "0xSignedData...";
            String expectedTxHash = "0xExpectedHash..."; // 签名后即可算出 Hash

            // C. 乐观更新数据库状态为 BROADCASTED
            order.setNonce(nonce);
            order.setTxHash(expectedTxHash);
            order.setStatus("BROADCASTED");
            withdrawMapper.updateToBroadcasted(order);

            // D. TODO: 调用 Web3j 实际广播到网络 (ethSendRawTransaction)
            log.info("Tx broadcasted: hash={}, nonce={}", expectedTxHash, nonce);

        } catch (Exception e) {
            log.error("Failed to broadcast order {}", order.getId(), e);
            // 补偿逻辑：如果这一步失败，状态依然是 PENDING，后续会由定时任务捞起重试
        }
    }
}