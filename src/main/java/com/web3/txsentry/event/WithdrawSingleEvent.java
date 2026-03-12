package com.web3.txsentry.event;

import com.web3.txsentry.entity.WithdrawOrder;
import org.springframework.context.ApplicationEvent;

/**
 * 单发提现事件。
 * 当订单被路由至 PENDING_SINGLE 快车道时触发。
 */
public class WithdrawSingleEvent extends ApplicationEvent {

    public WithdrawSingleEvent(WithdrawOrder order) {
        super(order);
    }

    public WithdrawOrder getOrder() {
        return (WithdrawOrder) getSource();
    }
}