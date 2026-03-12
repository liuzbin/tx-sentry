package com.web3.txsentry.mapper.citus;

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
     * insert a new withdraw order.
     */
    void insertOrder(WithdrawOrder order);

    /**
     * update the transaction status and tx hash. (Used by Single Send)
     */
    void updateStatusAndTxHash(@Param("bizOrderId") String bizOrderId,
                               @Param("status") String status,
                               @Param("txHash") String txHash);

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

    /**
     * 专门用于提价覆盖的更新方法（重置 update_time，替换 tx_hash）
     */
    void updateTxHashForSpeedUp(@Param("bizOrderId") String bizOrderId, @Param("newTxHash") String newTxHash);

    // ==========================================
    // 批量聚合打包 (Batch) 专属的高级状态机接口
    // ==========================================

    /**
     * 阶段一：悲观锁抓取 PENDING_BATCH 订单，跳过已被锁定的行。
     */
    List<WithdrawOrder> selectPendingBatchOrdersWithLock(@Param("limit") int limit);

    /**
     * 通用的批量状态跃迁方法 (用于切换至 PROCESSING_BATCH 或回滚至 PENDING_BATCH)
     */
    int updateStatusBatch(@Param("bizOrderIds") List<String> bizOrderIds, @Param("newStatus") String newStatus);

    /**
     * 阶段二：成功上链后，将这批订单的状态统一更新为 BROADCASTED，并死死绑定同一个聚合母 TxHash。
     */
    int batchUpdateToBroadcasted(@Param("bizOrderIds") List<String> bizOrderIds, @Param("txHash") String txHash);

    /**
     * 灾难恢复：清理因为服务器宕机而变成僵尸的 PROCESSING_BATCH 订单。
     * 将卡住超过 5 分钟的订单，重新打回 PENDING_BATCH 队列。
     */
    int recoverZombieProcessingOrders();
}