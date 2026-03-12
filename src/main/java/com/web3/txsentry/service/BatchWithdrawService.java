package com.web3.txsentry.service;

import com.web3.txsentry.entity.WithdrawOrder;
import com.web3.txsentry.mapper.citus.WithdrawOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 工业级批量提现调度服务。
 * 核心职责：提供“短事务”支持，严格执行两阶段状态跃迁（Two-Phase State Mutation）。
 * 绝对防御法则：此类的任何 @Transactional 方法内，绝对不允许出现任何 Web3j 的 RPC 网络调用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchWithdrawService {

    private final WithdrawOrderMapper withdrawMapper;

    /**
     * 阶段一：极速上锁并跃迁状态 (短事务)
     * 物理机制：利用数据库的 FOR UPDATE SKIP LOCKED 抢占当前没人处理的单子，
     * 瞬间将状态从 PENDING_BATCH 改为 PROCESSING_BATCH，然后立刻提交事务释放行锁。
     * * @param limit 本次最大抓取数量
     * @return 成功锁定并接管的订单列表
     */
    @Transactional(rollbackFor = Exception.class)
    public List<WithdrawOrder> lockAndFetchPendingBatchOrders(int limit) {

        // 1. 悲观锁抓取 PENDING_BATCH 订单 (遇到被别人锁住的行自动跳过，绝不阻塞等待)
        List<WithdrawOrder> lockedOrders = withdrawMapper.selectPendingBatchOrdersWithLock(limit);

        if (lockedOrders.isEmpty()) {
            return lockedOrders;
        }

        // 2. 提取业务 ID 列表
        List<String> bizOrderIds = lockedOrders.stream()
                .map(WithdrawOrder::getBizOrderId)
                .collect(Collectors.toList());

        // 3. 立刻将状态跃迁为 PROCESSING_BATCH
        // 这一步是防御的灵魂：事务提交后行锁消失，但状态变了，其他机器的定时任务依靠 WHERE status = 'PENDING_BATCH' 再也抓不到它们，完美防止双花。
        withdrawMapper.updateStatusBatch(bizOrderIds, "PROCESSING_BATCH");

        log.info("成功锁定并接管 {} 笔批量提现订单，状态已跃迁为 PROCESSING_BATCH", bizOrderIds.size());

        return lockedOrders;
    }

    /**
     * 阶段二 (成功分支)：上链成功，绑定 TxHash (短事务)
     * 当 BatchWithdrawJob 在内存中完成了极其耗时的 Gas 估算、签名、广播后，调用此方法落地结果。
     * * @param bizOrderIds 本批次包含的业务订单号
     * @param txHash      聚合打包产生的唯一母交易 Hash
     */
    @Transactional(rollbackFor = Exception.class)
    public void confirmBatchBroadcasted(List<String> bizOrderIds, String txHash) {
        withdrawMapper.batchUpdateToBroadcasted(bizOrderIds, txHash);
        log.info("聚合上链成功确认，{} 笔订单已死死绑定至母 TxHash: {}", bizOrderIds.size(), txHash);
    }

    /**
     * 阶段二 (失败分支)：组装或上链崩溃，状态回滚，释放给下一次调度 (短事务)
     * 如果在组装报文、请求 Infura 节点时发生任何异常，必须调用此方法，
     * 将 PROCESSING_BATCH 退回 PENDING_BATCH，防止订单变成无人问津的死锁僵尸。
     * * @param bizOrderIds 本批次包含的业务订单号
     */
    @Transactional(rollbackFor = Exception.class)
    public void revertOrdersToPending(List<String> bizOrderIds) {
        withdrawMapper.updateStatusBatch(bizOrderIds, "PENDING_BATCH");
        log.warn("聚合上链遇阻，触发物理防御降级，{} 笔订单已回滚为 PENDING_BATCH 等待下次重试", bizOrderIds.size());
    }

    /**
     * 灾难恢复：清理因为服务器宕机而变成僵尸的 PROCESSING_BATCH 订单。
     * 将卡住超过 5 分钟的订单，重新打回 PENDING_BATCH 队列。
     */
    @Transactional(rollbackFor = Exception.class)
    public void recoverZombieProcessingOrders() {
        // 这里的 SQL 逻辑很简单，需要在 Mapper 中实现：
        // UPDATE withdraw_order SET status = 'PENDING_BATCH', update_time = NOW()
        // WHERE status = 'PROCESSING_BATCH' AND update_time < NOW() - INTERVAL 5 MINUTE
        int recoveredCount = withdrawMapper.recoverZombieProcessingOrders();
        if (recoveredCount > 0) {
            log.warn("触发系统崩溃自愈机制！成功将 {} 笔死锁的 PROCESSING_BATCH 订单打回 PENDING 队列", recoveredCount);
        }
    }
}