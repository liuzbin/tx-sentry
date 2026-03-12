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
     * citus will automatically hash the bizorderid to place it on the correct worker node.
     *
     * @param order the withdraw order entity
     */
    void insertOrder(WithdrawOrder order);

    /**
     * update the transaction status and tx hash.
     * strictly requires bizorderid to avoid cross-shard broadcasting in citus.
     *
     * @param bizOrderId the unique business order id (shard key)
     * @param status     the new status
     * @param txHash     the transaction hash (can be null if failed)
     */
    void updateStatusAndTxHash(@Param("bizOrderId") String bizOrderId,
                               @Param("status") String status,
                               @Param("txHash") String txHash);

    /**
     * query an order by its shard key.
     * this ensures a pure point-lookup routed to a single worker node.
     *
     * @param bizOrderId the unique business order id (shard key)
     * @return the withdraw order entity
     */
    WithdrawOrder selectByBizOrderId(@Param("bizOrderId") String bizOrderId);

    /**
     * select orders by their current status.
     */
    List<WithdrawOrder> selectByStatus(@Param("status") String status);

    /**
     * 查询长时间卡在 BROADCASTED 状态的订单
     */
    List<WithdrawOrder> selectStuckOrders(@Param("status") String status, @Param("thresholdTime") LocalDateTime thresholdTime);

    /**
     * 专门用于提价覆盖的更新方法（重置 update_time，替换 tx_hash）
     */
    void updateTxHashForSpeedUp(@Param("bizOrderId") String bizOrderId, @Param("newTxHash") String newTxHash);
}