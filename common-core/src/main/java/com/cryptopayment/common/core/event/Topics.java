package com.cryptopayment.common.core.event;

/**
 * Hằng số tên Kafka topic dùng chung — nguồn chân lý duy nhất về tên topic.
 */
public final class Topics {

    private Topics() {
    }

    public static final String USER_EVENTS = "user.events";
    public static final String WALLET_COMMANDS = "wallet.commands";
    public static final String WALLET_EVENTS = "wallet.events";
    public static final String PAYMENT_EVENTS = "payment.events";
    public static final String WALLET_DLQ = "wallet.dlq";
}
