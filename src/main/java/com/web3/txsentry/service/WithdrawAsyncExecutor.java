package com.web3.txsentry.service;

import com.web3.txsentry.entity.WithdrawOrder;
import com.web3.txsentry.mapper.citus.WithdrawOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.EthGasPrice;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.utils.Convert;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * dedicated asynchronous executor for web3 transactions.
 * separated from the main service to guarantee spring @async proxy mechanism functions correctly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WithdrawAsyncExecutor {

    private final WithdrawOrderMapper withdrawMapper;
    private final Web3j web3j;
    private final NonceService nonceService; // we will implement this next using redisson

    @Value("${web3.wallet.private-key}")
    private String privateKey;

    @Value("${web3.network.chain-id}")
    private long chainId;

    /**
     * 2. core execution: fetch nonce, sign locally, and broadcast.
     * runs in a separate thread pool managed by spring.
     */
    @Async("web3AsyncThreadPool") // strongly recommend defining a specific thread pool for web3 IO
    public void executeBlockchainBroadcast(WithdrawOrder order) {
        String bizOrderId = order.getBizOrderId();

        try {
            // a. load credentials securely in memory
            Credentials credentials = Credentials.create(privateKey);
            String hotWalletAddress = credentials.getAddress();

            // b. acquire a strictly sequential nonce from our distributed redis lock service
            long nonce = nonceService.getNextNonce(hotWalletAddress);

            // c. fetch current gas price
            EthGasPrice ethGasPrice = web3j.ethGasPrice().send();
            BigInteger gasPrice = ethGasPrice.getGasPrice();
            BigInteger gasLimit = BigInteger.valueOf(21000L); // standard eth transfer limit

            // d. construct the raw transaction using accurate wei conversion
            BigDecimal amountInEther = order.getAmount();
            BigInteger valueInWei = Convert.toWei(amountInEther, Convert.Unit.ETHER).toBigInteger();

            RawTransaction rawTransaction = RawTransaction.createEtherTransaction(
                    BigInteger.valueOf(nonce),
                    gasPrice,
                    gasLimit,
                    order.getToAddress(),
                    valueInWei
            );

            // e. local ecdsa signature
            byte[] signedMessage = TransactionEncoder.signMessage(rawTransaction, chainId, credentials);
            String hexValue = Numeric.toHexString(signedMessage);

            // f. broadcast to the network
            EthSendTransaction ethSendTransaction = web3j.ethSendRawTransaction(hexValue).send();

            if (ethSendTransaction.hasError()) {
                log.error("broadcast failed for order {}. error: {}", bizOrderId, ethSendTransaction.getError().getMessage());
                // note: if failed, we must handle nonce rollback or mark as failed in db
                withdrawMapper.updateStatusAndTxHash(bizOrderId, "FAILED", null);
                return;
            }

            String txHash = ethSendTransaction.getTransactionHash();

            // g. update citus database using the strict shard key routing
            order.setTxHash(txHash);
            order.setNonce(nonce);
            order.setStatus("BROADCASTED");

            withdrawMapper.updateStatusAndTxHash(bizOrderId, order.getStatus(), order.getTxHash());
            log.info("order {} successfully broadcasted. txhash: {}, nonce: {}", bizOrderId, txHash, nonce);

        } catch (Exception e) {
            log.error("critical execution error for order {}", bizOrderId, e);
            withdrawMapper.updateStatusAndTxHash(bizOrderId, "ERROR", null);
        }
    }
}