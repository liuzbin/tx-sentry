package com.web3.txsentry.service.execution;

import com.web3.txsentry.dto.TxResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Public Web3 transaction execution engine.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Web3TransactionEngine {

    private final Web3j web3j;
    private final NonceService nonceService;

    @Value("${web3.wallet.private-key}")
    private String privateKey;

    @Value("${web3.network.chain-id}")
    private long chainId;  // distinguish between testnet and mainnet

    /**
     * Execute transaction
     *
     * @param toAddress Target address
     * @param valueInWei Native ETH value
     * @param payloadData Data called in contract
     * @param isContractCall is contract call: for estimating gas price limit
     * @return Hash and Nonce
     */
    public TxResult executeTransaction(String toAddress, BigInteger valueInWei, String payloadData, boolean isContractCall) throws Exception {
        Credentials credentials = Credentials.create(privateKey);
        String hotWalletAddress = credentials.getAddress();

        // 1. EIP-1559 dynamic gas calculation engine
        EthBlock.Block latestBlock = web3j.ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false).send().getBlock();
        BigInteger baseFee = latestBlock.getBaseFeePerGas();
        BigInteger priorityFee = web3j.ethMaxPriorityFeePerGas().send().getMaxPriorityFeePerGas();

        // MaxFee = (BaseFee * 2) + PriorityFee
        BigInteger maxFeePerGas = baseFee.multiply(BigInteger.valueOf(2)).add(priorityFee);

        // Dynamically estimate the limit
        BigInteger gasLimit = isContractCall
                ? estimateSmartContractGas(hotWalletAddress, toAddress, payloadData)
                : BigInteger.valueOf(21000L);

        // 2. Get distributed nonce
        long nonce = nonceService.getNextNonce(hotWalletAddress);

        // 3. Assemble EIP-1559 (Type 2), sign and then broadcast
        RawTransaction tx = RawTransaction.createTransaction(
                chainId,
                BigInteger.valueOf(nonce),
                gasLimit,
                toAddress,
                valueInWei,
                payloadData,
                priorityFee,
                maxFeePerGas
        );

        byte[] signedMessage = TransactionEncoder.signMessage(tx, chainId, credentials);
        String hexValue = Numeric.toHexString(signedMessage);

        EthSendTransaction response = web3j.ethSendRawTransaction(hexValue).send();

        if (response.hasError()) {
            throw new RuntimeException("EVM Node Rejection: " + response.getError().getMessage());
        }

        log.info("Transaction injected successfully. Hash: {}, Nonce: {}", response.getTransactionHash(), nonce);
        return new TxResult(response.getTransactionHash(), nonce);
    }

    /**
     * Smart Contract gas estimator
     */
    private BigInteger estimateSmartContractGas(String fromAddress, String contractAddress, String data) {
        try {
            org.web3j.protocol.core.methods.request.Transaction estimateTx =
                    org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(fromAddress, contractAddress, data);

            org.web3j.protocol.core.methods.response.EthEstimateGas estimateResponse = web3j.ethEstimateGas(estimateTx).send();

            if (estimateResponse.hasError()) {
                throw new RuntimeException("Gas estimation reverted: " + estimateResponse.getError().getMessage());
            }

            // multiply 1.2 as Buffer
            return new BigDecimal(estimateResponse.getAmountUsed()).multiply(new BigDecimal("1.2")).toBigInteger();
        } catch (Exception e) {
            log.error("Gas estimation failed, using fallback limit 100000", e);
            return BigInteger.valueOf(100000L);
        }
    }

    /**
     * 获取指定地址在链上确切等待的下一个 Nonce
     */
    public long getNextExpectedChainNonce(String address) throws Exception {
        // LATEST 代表链上已确认的最新区块。它的 TransactionCount 就是下一个必须出现的 Nonce
        return web3j.ethGetTransactionCount(address, DefaultBlockParameterName.LATEST)
                .send().getTransactionCount().longValue();
    }

    /**
     * 引擎后门：强制使用指定 Nonce，并执行 EIP-1559 溢价覆盖 (Speed Up / Cancel)
     *
     * @param isCancel 如果为 true，则是发给自己 0 ETH 的空炮；如果为 false，则是原单提价重发
     */
    public String executeSpeedUpOrCancel(String toAddress, BigInteger valueInWei, String payloadData, boolean isContractCall, long specifiedNonce, boolean isCancel) throws Exception {
        Credentials credentials = Credentials.create(privateKey);
        String hotWalletAddress = credentials.getAddress();

        // 1. 获取最新 BaseFee 和 PriorityFee
        org.web3j.protocol.core.methods.response.EthBlock.Block latestBlock =
                web3j.ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false).send().getBlock();
        BigInteger baseFee = latestBlock.getBaseFeePerGas();
        BigInteger currentPriorityFee = web3j.ethMaxPriorityFeePerGas().send().getMaxPriorityFeePerGas();

        // 2. 【核心提价逻辑】以太坊节点要求，覆盖交易的小费必须比原交易高至少 10%
        // 我们直接极其激进地给当前网络小费上浮 50%，BaseFee 预留 2 倍 Buffer，确保一击必杀插队成功
        BigInteger aggressivePriorityFee = new BigDecimal(currentPriorityFee).multiply(new BigDecimal("1.5")).toBigInteger();
        BigInteger maxFeePerGas = baseFee.multiply(BigInteger.valueOf(2)).add(aggressivePriorityFee);

        // 3. 根据是否为空炮，计算 Limit
        BigInteger gasLimit;
        if (isCancel) {
            gasLimit = BigInteger.valueOf(21000L); // 0 ETH 空转账，21000 足够
            toAddress = hotWalletAddress;          // 目标地址写自己
            valueInWei = BigInteger.ZERO;
            payloadData = "";
        } else {
            gasLimit = isContractCall ? estimateSmartContractGas(hotWalletAddress, toAddress, payloadData) : BigInteger.valueOf(21000L);
        }

        // 4. 组装溢价报文 (强行注入 specifiedNonce)
        RawTransaction tx = RawTransaction.createTransaction(
                chainId, BigInteger.valueOf(specifiedNonce), gasLimit, toAddress, valueInWei, payloadData, aggressivePriorityFee, maxFeePerGas
        );

        byte[] signedMessage = TransactionEncoder.signMessage(tx, chainId, credentials);
        String hexValue = Numeric.toHexString(signedMessage);

        org.web3j.protocol.core.methods.response.EthSendTransaction response = web3j.ethSendRawTransaction(hexValue).send();

        if (response.hasError()) {
            throw new RuntimeException("SpeedUp/Cancel Reverted by Node: " + response.getError().getMessage());
        }
        return response.getTransactionHash();
    }

    /**
     * 查证交易在链上的真实回执状态
     * @return "0x1" 成功, "0x0" 失败, null 表示还在排队未出块
     */
    public String getTransactionReceiptStatus(String txHash) throws Exception {
        org.web3j.protocol.core.methods.response.EthGetTransactionReceipt response =
                web3j.ethGetTransactionReceipt(txHash).send();

        if (response.getTransactionReceipt().isEmpty()) {
            return null; // 还没被打包
        }
        return response.getTransactionReceipt().get().getStatus();
    }
}