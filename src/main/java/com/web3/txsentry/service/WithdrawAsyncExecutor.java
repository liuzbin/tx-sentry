package com.web3.txsentry.service;

import com.web3.txsentry.entity.WithdrawOrder;
import com.web3.txsentry.mapper.citus.WithdrawOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
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
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.utils.Convert;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;

/**
 * dedicated asynchronous executor for web3 transactions.
 * fully supports both native eth and erc-20 token (e.g., usdt) transfers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WithdrawAsyncExecutor {

    private final WithdrawOrderMapper withdrawMapper;
    private final Web3j web3j;
    private final NonceService nonceService;

    @Value("${web3.wallet.private-key}")
    private String privateKey;

    @Value("${web3.network.chain-id}")
    private long chainId;

    /**
     * core execution: fetch nonce, sign locally, and broadcast.
     * runs in a separate thread pool managed by spring.
     */
    @Async("web3AsyncThreadPool")
    public void executeBlockchainBroadcast(WithdrawOrder order) {
        String bizOrderId = order.getBizOrderId();

        try {
            // a. load credentials securely in memory
            Credentials credentials = Credentials.create(privateKey);
            String hotWalletAddress = credentials.getAddress();

            // b. acquire a strictly sequential nonce from our distributed redis lock service
            long nonce = nonceService.getNextNonce(hotWalletAddress);

            // c. fetch current gas price (can be optimized later for dynamic gas bumps)
            EthGasPrice ethGasPrice = web3j.ethGasPrice().send();
            BigInteger gasPrice = ethGasPrice.getGasPrice();

            // d. dynamically build the raw transaction based on token type
            RawTransaction rawTransaction = buildRawTransaction(order, nonce, gasPrice);

            // e. local ecdsa signature
            byte[] signedMessage = TransactionEncoder.signMessage(rawTransaction, chainId, credentials);
            String hexValue = Numeric.toHexString(signedMessage);

            // f. broadcast to the network
            EthSendTransaction ethSendTransaction = web3j.ethSendRawTransaction(hexValue).send();

            if (ethSendTransaction.hasError()) {
                log.error("broadcast failed for order {}. error: {}", bizOrderId, ethSendTransaction.getError().getMessage());
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

    /**
     * private router to construct the correct transaction payload (ETH vs ERC-20)
     */
    private RawTransaction buildRawTransaction(WithdrawOrder order, long nonce, BigInteger gasPrice) {
        String tokenAddress = order.getTokenAddress();
        String toAddress = order.getToAddress();

        if (tokenAddress == null || tokenAddress.trim().isEmpty()) {
            // branch a: native eth transfer
            BigInteger valueInWei = Convert.toWei(order.getAmount(), Convert.Unit.ETHER).toBigInteger();
            BigInteger gasLimit = BigInteger.valueOf(21000L);

            return RawTransaction.createEtherTransaction(
                    BigInteger.valueOf(nonce),
                    gasPrice,
                    gasLimit,
                    toAddress,
                    valueInWei
            );
        } else {
            // branch b: erc-20 token transfer (e.g., usdt)
            // warning: token decimals must be accurate. usdt is typically 6.
            int tokenDecimals = 6;
            BigDecimal multiplier = BigDecimal.valueOf(Math.pow(10, tokenDecimals));
            BigInteger tokenAmountInLowestUnit = order.getAmount().multiply(multiplier).toBigInteger();

            // abi encoding for transfer(address,uint256)
            Function function = new Function(
                    "transfer",
                    Arrays.asList(new Address(toAddress), new Uint256(tokenAmountInLowestUnit)),
                    Collections.emptyList()
            );

            String encodedFunction = FunctionEncoder.encode(function);
            BigInteger gasLimit = BigInteger.valueOf(100000L); // safe limit for contract calls

            // transaction to the contract address, with 0 eth value, and payload data
            return RawTransaction.createTransaction(
                    BigInteger.valueOf(nonce),
                    gasPrice,
                    gasLimit,
                    tokenAddress,
                    BigInteger.ZERO,
                    encodedFunction
            );
        }
    }
}