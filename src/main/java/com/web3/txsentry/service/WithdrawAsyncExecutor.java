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
            // 1. load credentials securely in memory
            Credentials credentials = Credentials.create(privateKey);
            String hotWalletAddress = credentials.getAddress();

            // 2. fetch current gas price
            EthGasPrice ethGasPrice = web3j.ethGasPrice().send();
            BigInteger gasPrice = ethGasPrice.getGasPrice();

            // 3. dynamically build the raw transaction based on token type
            // First obtain the gas price, then build the transaction to prevent nonce blocking caused by an error returned when estimating the gas price of the token.
            RawTransaction rawTransactionWithoutNonce = buildRawTransactionWithoutNonce(order, gasPrice, hotWalletAddress);

            // 4. acquire a strictly sequential nonce from our distributed redis lock service
            long nonce = nonceService.getNextNonce(hotWalletAddress);

            // 5. Inject Nonce into the transaction object
            RawTransaction finalRawTransaction = RawTransaction.createTransaction(
                    BigInteger.valueOf(nonce),
                    rawTransactionWithoutNonce.getGasPrice(),
                    rawTransactionWithoutNonce.getGasLimit(),
                    rawTransactionWithoutNonce.getTo(),
                    rawTransactionWithoutNonce.getValue(),
                    rawTransactionWithoutNonce.getData()
            );

            // 6. local ecdsa signature
            byte[] signedMessage = TransactionEncoder.signMessage(finalRawTransaction, chainId, credentials);
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
    private RawTransaction buildRawTransactionWithoutNonce(WithdrawOrder order, BigInteger gasPrice, String hotWalletAddress) {
        String tokenAddress = order.getTokenAddress();
        String toAddress = order.getToAddress();

        if (tokenAddress == null || tokenAddress.trim().isEmpty()) {
            // branch a: native eth transfer
            BigInteger valueInWei = Convert.toWei(order.getAmount(), Convert.Unit.ETHER).toBigInteger();
            BigInteger gasLimit = BigInteger.valueOf(21000L);

            return RawTransaction.createEtherTransaction(
                    BigInteger.ZERO,  // placeholder
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

            // Dynamically Estimating Gas Limit
            BigInteger dynamicGasLimit = estimateSmartContractGas(hotWalletAddress, tokenAddress, encodedFunction);

            // transaction to the contract address, with 0 eth value, and payload data
            return RawTransaction.createTransaction(
                    BigInteger.ZERO,
                    gasPrice,
                    dynamicGasLimit,
                    tokenAddress,
                    BigInteger.ZERO,
                    encodedFunction
            );
        }
    }

    /**
     * Gas estimation engine. Sends simulated execution requests to Ethereum nodes, with added security redundancy.
     */
    private BigInteger estimateSmartContractGas(String fromAddress, String contractAddress, String data) {
        try {
            // 注意：这里使用的是 request 包下的 Transaction，它是一个用于只读查询/模拟执行的只读对象
            org.web3j.protocol.core.methods.request.Transaction estimateTx =
                    org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(
                            fromAddress,
                            contractAddress,
                            data
                    );

            // 发起 RPC 模拟请求
            org.web3j.protocol.core.methods.response.EthEstimateGas estimateResponse =
                    web3j.ethEstimateGas(estimateTx).send();

            if (estimateResponse.hasError()) {
                // 如果估算报错，大概率是因为热钱包里的 USDT 余额不足，或者合约黑名单拦截，这笔交易如果发出去，必定 Revert
                log.warn("Gas estimation failed (transaction will likely revert). Error: {}", estimateResponse.getError().getMessage());
                throw new RuntimeException("Gas estimation failed, transaction will revert: " + estimateResponse.getError().getMessage());
            }

            // 获取节点给出的极度精确的模拟消耗值
            BigInteger exactEstimatedGas = estimateResponse.getAmountUsed();

            // 乘以 1.2 的缓冲系数 (Buffer)，保证上链时的绝对安全
            BigDecimal bufferedGas = new BigDecimal(exactEstimatedGas).multiply(new BigDecimal("1.2"));

            log.info("Dynamic gas estimated: exact {}, buffered {}", exactEstimatedGas, bufferedGas.toBigInteger());

            return bufferedGas.toBigInteger();

        } catch (Exception e) {
            log.error("Network error during gas estimation, falling back to default limit.", e);
            // 节点网络抖动时的降级策略
            return BigInteger.valueOf(100000L);
        }
    }
}