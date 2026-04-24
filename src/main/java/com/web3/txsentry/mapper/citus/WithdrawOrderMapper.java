package com.web3.txsentry.mapper.citus;

import com.web3.txsentry.common.enums.WithdrawStatusEnum;
import com.web3.txsentry.entity.WithdrawOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * mapper interface for withdraw orders.
 * highly optimized for citus distributed queries by enforcing bizorderid routing.
 */
@Mapper
public interface WithdrawOrderMapper {

    /**
     * insert a new withdrawal order.
     */
    void insertOrder(WithdrawOrder order);

    // Broadcast Success
    void updateBroadcastSuccess(@Param("bizOrderId") String bizOrderId,
                                @Param("txHash") String txHash,
                                @Param("nonce") Long nonce);

    // Broadcast Failed
    void updateFailedStatus(@Param("bizOrderId") String bizOrderId,
                            @Param("status") WithdrawStatusEnum status);

    /**
     * updateStatusBatch
     */
    void updateStatusBatch(@Param("bizOrderIds") List<String> bizOrderIds, @Param("newStatus") WithdrawStatusEnum newStatus);

    /**
     * batchUpdateToBroadcasted
     */
    void batchUpdateToBroadcasted(@Param("bizOrderIds") List<String> bizOrderIds, @Param("txHash") String txHash);

    /**
     * clean zombie orders - PROCESSING_BATCH, return orders that have been stuck for more than 5min to PENDING_BATCH queue
     *
     */
    void recoverZombieProcessingOrders();

    /**
     * 专门用于提价覆盖的更新方法（重置 update_time，替换 tx_hash）
     */
    void updateTxHashForSpeedUp(@Param("bizOrderId") String bizOrderId, @Param("newTxHash") String newTxHash);

    /**
     * query an order by its shard key.
     */
    WithdrawOrder selectByBizOrderId(@Param("bizOrderId") String bizOrderId);

    /**
     * select orders by their current status. (Used by TxMonitorJob)
     */
    List<WithdrawOrder> selectByStatus(@Param("status") String status);

    /**
     * 查询长时间卡在 BROADCASTED 状态的订单 (Used by StuckNonceMonitorJob)
     */
    List<WithdrawOrder> selectStuckOrders(@Param("status") String status, @Param("thresholdTime") LocalDateTime thresholdTime);


    // ==========================================
    // 批量聚合打包 (Batch) 专属的高级状态机接口
    // ==========================================

    /**
     * 阶段一：悲观锁抓取 PENDING_BATCH 订单，跳过已被锁定的行。
     */
    List<WithdrawOrder> selectPendingBatchOrdersWithLock(@Param("limit") int limit);

    /**
     * 幽灵雷达：精确查询持有指定 Nonce 的订单
     * 用于战法一：判断卡死在内存池的 Nonce 是否在数据库中有记录
     *
     * @param nonce 链上正在等待的 Nonce
     * @return 对应的订单记录
     */
    WithdrawOrder selectByNonce(@Param("nonce") long nonce);

    /**
     * 幽灵雷达：时间边界推断法
     * 查询数据库中所有大于给定 Nonce 的记录中，最小的那一条（即紧挨着它的后置记录）
     * 用于战法二：确诊某个 Nonce 是否彻底变成了幽灵空洞
     *
     * @param nonce 当前缺失的 Nonce
     * @return 紧挨着的后置订单记录
     */
    WithdrawOrder selectNextNonceOrder(@Param("nonce") long nonce);

    /**
     * 提价覆盖 (Speed Up) 成功后，更新订单的 TxHash
     * 注意：不能修改 Nonce，也不能修改 Status (它本来就是 BROADCASTED)
     *
     * @param bizOrderId 业务订单号
     * @param newTxHash 提价重发后产生的新 Hash
     */
}