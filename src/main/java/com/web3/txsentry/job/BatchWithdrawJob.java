package com.web3.txsentry.job;

import com.web3.txsentry.entity.WithdrawOrder;
import com.web3.txsentry.service.BatchWithdrawService;
import com.web3.txsentry.constant.TokenDictionary;
import com.web3.txsentry.service.WithdrawAsyncExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 工业级提现聚合引擎。
 * 负责将同币种的零散提现订单，打包成单一的智能合约数组调用，达成极致降本。
 * 严格遵循“两阶段状态跃迁”模式，将数据库行锁与耗时的外部 Web3 网络请求彻底物理隔离。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchWithdrawJob {

    private final BatchWithdrawService batchWithdrawService;
    private final WithdrawAsyncExecutor asyncExecutor;
    private final TokenDictionary tokenDictionary;

    // 预先部署在以太坊上的分发器合约地址 (TokenDispenser)
    @Value("${web3.dispenser-contract}")
    private String dispenserContractAddress;

    /**
     * 每 10 秒执行一次扫表与聚合打包
     */
    @Scheduled(fixedDelay = 10000)
    public void processBatchWithdrawals() {

        // 1. 【短事务锁定】抢占最多 200 笔 PENDING_BATCH 订单，并瞬间将状态跃迁为 PROCESSING_BATCH
        // 此刻数据库行锁已释放，安全进入纯内存计算与耗时网络请求阶段
        List<WithdrawOrder> processingOrders = batchWithdrawService.lockAndFetchPendingBatchOrders(200);

        if (processingOrders.isEmpty()) {
            return;
        }

        // 2. 【路由隔离】按 tokenAddress 进行物理分组
        Map<String, List<WithdrawOrder>> groupedOrders = processingOrders.stream()
                .collect(Collectors.groupingBy(WithdrawOrder::getTokenAddress));

        log.info("捞取到 {} 笔批量提现订单，分为 {} 个币种组别进行处理", processingOrders.size(), groupedOrders.size());

        // 3. 遍历每个币种组，独立打包上链
        for (Map.Entry<String, List<WithdrawOrder>> entry : groupedOrders.entrySet()) {
            String currentTokenAddress = entry.getKey();
            List<WithdrawOrder> ordersForThisToken = entry.getValue();

            // 4. 【区块防爆切分】即便同一个币种有 150 单，我们也将其按 50 单一批切分，防止单笔交易 Gas 超出上限
            List<List<WithdrawOrder>> partitions = partitionList(ordersForThisToken, 50);

            for (List<WithdrawOrder> batch : partitions) {
                executeSingleBatch(currentTokenAddress, batch);
            }
        }
    }

    /**
     * 执行单一币种、单一批次（<=50单）的打包组装与上链动作
     */
    private void executeSingleBatch(String tokenAddress, List<WithdrawOrder> batchOrders) {

        // 提前抽取所有的业务订单号，用于后续的状态扭转（成功或回滚）
        List<String> bizOrderIds = batchOrders.stream()
                .map(WithdrawOrder::getBizOrderId)
                .collect(Collectors.toList());

        try {
            // A. 动态获取该代币的物理精度，消除一切特例硬编码
            int tokenDecimals = tokenDictionary.getDecimals(tokenAddress);
            BigDecimal multiplier = BigDecimal.valueOf(Math.pow(10, tokenDecimals));

            List<Address> recipientAddresses = new ArrayList<>();
            List<Uint256> transferAmounts = new ArrayList<>();

            for (WithdrawOrder order : batchOrders) {
                recipientAddresses.add(new Address(order.getToAddress()));
                // 精度转换
                BigInteger amountInLowestUnit = order.getAmount().multiply(multiplier).toBigInteger();
                transferAmounts.add(new Uint256(amountInLowestUnit));
            }

            // B. 组装调用 Dispenser 合约的报文
            Function batchFunction = new Function(
                    "batchTransferToken",
                    Arrays.asList(
                            new Address(tokenAddress),
                            new DynamicArray<>(Address.class, recipientAddresses),
                            new DynamicArray<>(Uint256.class, transferAmounts)
                    ),
                    Collections.emptyList()
            );

            String encodedBatchData = FunctionEncoder.encode(batchFunction);

            // C. 构造虚拟的母订单并发送
            WithdrawOrder batchMotherOrder = new WithdrawOrder();
            // 使用时间戳和代币地址前缀保证追踪性，将安全截取与 UUID 防撞码逻辑合并在这里
            String shortToken = tokenAddress != null && tokenAddress.length() > 6
                    ? tokenAddress.substring(0, 6)
                    : "UNKNOWN";
            String uniqueBatchId = "BATCH-" + System.currentTimeMillis() + "-" + shortToken + "-" + UUID.randomUUID().toString().substring(0, 4);
            batchMotherOrder.setBizOrderId(uniqueBatchId);
            batchMotherOrder.setToAddress(dispenserContractAddress);
            batchMotherOrder.setAmount(BigDecimal.ZERO); // 不发送原生 ETH，只触发合约调用

            // D. 极其耗时的外部网络调用 (发起交易并等待以太坊节点响应)
            String batchTxHash = asyncExecutor.executeBatchBroadcast(batchMotherOrder, encodedBatchData);

            if (batchTxHash != null) {
                // E. 【短事务确认】将这一批订单状态更新为 BROADCASTED，并死死绑定母 TxHash
                batchWithdrawService.confirmBatchBroadcasted(bizOrderIds, batchTxHash);
            }

        } catch (Exception e) {
            log.error("币种 {} (批次包含 {} 单) 聚合打包网络请求彻底崩溃，执行降级回滚", tokenAddress, bizOrderIds.size(), e);
            // F. 【短事务回滚】极其重要的防御：如果组装、Gas估算或网络广播报错，
            // 必须将这批订单从 PROCESSING_BATCH 退回 PENDING_BATCH，避免它们变成死锁的僵尸订单
            batchWithdrawService.revertOrdersToPending(bizOrderIds);
        }
    }

    /**
     * 简单的 List 切分工具方法
     */
    private <T> List<List<T>> partitionList(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(new ArrayList<>(list.subList(i, Math.min(i + size, list.size()))));
        }
        return partitions;
    }

    /**
     * 每 1 分钟执行一次僵尸订单清道夫
     */
    @Scheduled(fixedDelay = 60000)
    public void recoverZombies() {
        batchWithdrawService.recoverZombieProcessingOrders();
    }
}