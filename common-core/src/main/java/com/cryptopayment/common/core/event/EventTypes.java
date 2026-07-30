package com.cryptopayment.common.core.event;

/**
 * Hằng số tên event dùng chung toàn hệ thống — tránh gõ chuỗi rời rạc dễ sai chính tả.
 * Đây là "contract" ổn định giữa các service, nên đặt ở common-core.
 */
public final class EventTypes {

    private EventTypes() {
    }

    // user.events
    public static final String USER_REGISTERED = "UserRegistered";

    // wallet.commands
    public static final String DEBIT_COMMAND = "DebitCommand";
    public static final String CREDIT_COMMAND = "CreditCommand";
    public static final String DEBIT_REVERSE_COMMAND = "DebitReverseCommand";

    // wallet.events
    public static final String DEBIT_COMPLETED = "DebitCompleted";
    public static final String DEBIT_FAILED = "DebitFailed";
    public static final String CREDIT_COMPLETED = "CreditCompleted";
    public static final String CREDIT_FAILED = "CreditFailed";
    public static final String DEBIT_REVERSED = "DebitReversed";

    // payment.events
    public static final String TRANSFER_INITIATED = "TransferInitiated";
    public static final String TRANSFER_COMPLETED = "TransferCompleted";
    public static final String TRANSFER_FAILED = "TransferFailed";
}
