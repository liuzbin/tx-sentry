package com.web3.txsentry.event;

import com.web3.txsentry.entity.WithdrawOrder;
import org.springframework.context.ApplicationEvent;

/**
 * withdraw-single event
 */
public class WithdrawSingleEvent extends ApplicationEvent {

    public WithdrawSingleEvent(WithdrawOrder order) {
        super(order);
    }

    public WithdrawOrder getOrder() {
        return (WithdrawOrder) getSource();
    }
}