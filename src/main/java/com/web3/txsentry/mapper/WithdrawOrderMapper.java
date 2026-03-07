package com.web3.txsentry.mapper;

import com.web3.txsentry.entity.WithdrawOrder;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WithdrawOrderMapper {

    @Insert("INSERT INTO withdraw_order(biz_order_id, to_address, amount, status, create_time, update_time) " +
            "VALUES(#{bizOrderId}, #{toAddress}, #{amount}, 'PENDING', NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertOrder(WithdrawOrder order);

    @Update("UPDATE withdraw_order SET status = #{status}, tx_hash = #{txHash}, nonce = #{nonce}, update_time = NOW() " +
            "WHERE id = #{id} AND status = 'PENDING'")
    int updateToBroadcasted(WithdrawOrder order);
}