# Crypto Payment Platform — Project Specification

> Tài liệu tổng hợp kịch bản nghiệp vụ, kiến trúc hệ thống, tech stack và patterns để implement dự án **Mini Crypto Payment Platform** phục vụ phỏng vấn Senior Backend Engineer.

---

## 1. Tổng quan dự án

### Mục tiêu
Xây dựng một hệ thống thanh toán crypto thu nhỏ (giống Binance Pay) để demonstrate các kỹ năng:
- Event-Driven Architecture với Apache Kafka
- Saga Pattern (Orchestration) cho distributed transaction
- Các patterns quan trọng trong Fintech: Outbox, Idempotency, CQRS, Optimistic Locking

### Tech Stack

| Thành phần | Công nghệ |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.5.x |
| Build tool | Maven |
| Database | PostgreSQL 15 (4 database riêng biệt) |
| Message broker | Apache Kafka + Zookeeper |
| Cache | Redis 7 |
| Containerization | Docker Compose |

---

## 2. Cấu trúc dự án

```
crypto-payment/                        ← Git monorepo (1 repo duy nhất)
├── user-service/                      ← Spring Boot, port 8081
│   ├── src/
│   └── pom.xml
├── wallet-service/                    ← Spring Boot, port 8082
│   ├── src/
│   └── pom.xml
├── payment-service/                   ← Spring Boot, port 8083
│   ├── src/
│   └── pom.xml
├── notification-service/              ← Spring Boot, port 8084
│   ├── src/
│   └── pom.xml
├── audit-service/                     ← Spring Boot, port 8085
│   ├── src/
│   └── pom.xml
├── infrastructure/
│   ├── docker-compose.yml
│   └── init-db/
│       ├── 01-create-databases.sql
│       ├── 02-user-db.sql
│       ├── 03-wallet-db.sql
│       ├── 04-payment-db.sql
│       ├── 05-notification-db.sql
│       └── 06-audit-db.sql
├── .gitignore
└── README.md
```

### Nguyên tắc kiến trúc
- **Database per Service**: mỗi service có database riêng, không query chéo
- **5 project độc lập**: không dùng Maven multi-module, mỗi service deploy riêng biệt
- **Giao tiếp bất đồng bộ**: ưu tiên Kafka events thay vì REST call trực tiếp giữa các service
- **Centralized event trace**: `audit-service` consume mọi topic → ghi `event_store` (DB riêng), trace toàn bộ hành trình giao dịch mà KHÔNG phá database-per-service

---

## 3. Mô tả từng Service

### 3.1 user-service (port 8081)
**Trách nhiệm:** Quản lý tài khoản người dùng, xác thực JWT

**Database:** `user_db`

**Dependencies:**
- Spring Web, Spring Data JPA, PostgreSQL Driver
- Spring Security, Spring for Apache Kafka
- Validation, Lombok, Spring Boot DevTools

**Tables:**
```sql
users (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  username VARCHAR(50) UNIQUE NOT NULL,
  email VARCHAR(100) UNIQUE NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  status VARCHAR(20) DEFAULT 'ACTIVE',  -- ACTIVE, SUSPENDED
  created_at TIMESTAMP DEFAULT NOW()
)
```

**Kafka Events Published:**
- `UserRegistered` → topic: `user.events`

---

### 3.2 wallet-service (port 8082)
**Trách nhiệm:** Quản lý ví và số dư người dùng

**Database:** `wallet_db`

**Dependencies:**
- Spring Web, Spring Data JPA, PostgreSQL Driver
- Spring for Apache Kafka, Spring Data Redis
- Validation, Lombok, Spring Boot DevTools

**Tables:**
```sql
wallets (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID UNIQUE NOT NULL,
  balance DECIMAL(20,8) DEFAULT 0,
  currency VARCHAR(10) DEFAULT 'USDT',
  version BIGINT DEFAULT 0,            -- Optimistic Locking
  created_at TIMESTAMP DEFAULT NOW()
)

idempotency_keys (
  idempotency_key VARCHAR(255) PRIMARY KEY,  -- userId:transactionId
  status VARCHAR(20),
  created_at TIMESTAMP DEFAULT NOW()
)
```

**Kafka Events Consumed:**
- `UserRegistered` ← topic: `user.events` → tự động tạo ví mới
- `DebitCommand` ← topic: `wallet.commands` → trừ tiền user A
- `CreditCommand` ← topic: `wallet.commands` → cộng tiền user B
- `DebitReverseCommand` ← topic: `wallet.commands` → hoàn tiền user A (rollback)

**Kafka Events Published:**
- `DebitCompleted` → topic: `wallet.events`
- `DebitFailed` → topic: `wallet.events`
- `CreditCompleted` → topic: `wallet.events`
- `CreditFailed` → topic: `wallet.events`
- `DebitReversed` → topic: `wallet.events`

---

### 3.3 payment-service (port 8083)
**Trách nhiệm:** Saga Orchestrator — điều phối toàn bộ luồng giao dịch P2P

**Database:** `payment_db`

**Dependencies:**
- Spring Web, Spring Data JPA, PostgreSQL Driver
- Spring for Apache Kafka, Spring Data Redis
- Validation, Lombok, Spring Boot DevTools

**Tables:**
```sql
transactions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  sender_id UUID NOT NULL,
  receiver_id UUID NOT NULL,
  amount DECIMAL(20,8) NOT NULL,
  currency VARCHAR(10) DEFAULT 'USDT',
  status VARCHAR(20) DEFAULT 'INITIATED',  -- INITIATED, PROCESSING, COMPLETED, FAILED, REVERSED
  saga_state VARCHAR(30),                  -- DEBIT_PENDING, CREDIT_PENDING, COMPLETED, COMPENSATING, REVERSED, FAILED, COMPENSATION_FAILED
  created_at TIMESTAMP DEFAULT NOW(),
  updated_at TIMESTAMP DEFAULT NOW()
)

outbox_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  aggregate_id UUID NOT NULL,              -- transaction_id
  event_type VARCHAR(100) NOT NULL,        -- 'DebitCommand', 'CreditCommand'...
  payload JSONB NOT NULL,
  status VARCHAR(20) DEFAULT 'PENDING',   -- PENDING, PUBLISHED
  created_at TIMESTAMP DEFAULT NOW(),
  published_at TIMESTAMP
)

-- CQRS: Read model riêng cho transaction history
transaction_history (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  transaction_id UUID NOT NULL,
  user_id UUID NOT NULL,
  type VARCHAR(20),                        -- SENT, RECEIVED, TOPUP
  amount DECIMAL(20,8),
  status VARCHAR(20),
  description VARCHAR(255),
  created_at TIMESTAMP DEFAULT NOW()
)
```

**Kafka Events Consumed:**
- `DebitCompleted` ← topic: `wallet.events` → gửi CreditCommand
- `DebitFailed` ← topic: `wallet.events` → cập nhật FAILED
- `CreditCompleted` ← topic: `wallet.events` → cập nhật COMPLETED
- `CreditFailed` ← topic: `wallet.events` → gửi DebitReverseCommand (compensate)
- `DebitReversed` ← topic: `wallet.events` → cập nhật REVERSED

**Kafka Events Published:**
- `TransferInitiated` → topic: `payment.events`
- `TransferCompleted` → topic: `payment.events`
- `TransferFailed` → topic: `payment.events`
- `DebitCommand` → topic: `wallet.commands`
- `CreditCommand` → topic: `wallet.commands`
- `DebitReverseCommand` → topic: `wallet.commands`

---

### 3.4 notification-service (port 8084)
**Trách nhiệm:** Lắng nghe events và gửi thông báo (log ra console trong demo)

**Database:** Không cần

**Dependencies:**
- Spring Web, Spring for Apache Kafka, Lombok

**Kafka Events Consumed:**
- `UserRegistered` ← topic: `user.events` → thông báo "Chào mừng!"
- `TransferCompleted` ← topic: `payment.events` → thông báo cả sender và receiver
- `TransferFailed` ← topic: `payment.events` → thông báo giao dịch thất bại

---

### 3.5 audit-service (port 8085)
**Trách nhiệm:** Trace log tập trung — consume **tất cả** event của mọi service và ghi vào một `event_store` duy nhất để trace toàn bộ hành trình 1 giao dịch xuyên suốt hệ thống.

**Database:** `audit_db`

**Dependencies:**
- Spring for Apache Kafka, Spring Data JPA, PostgreSQL Driver, Lombok

**Vì sao cần service riêng, không cho 4 service ghi chung 1 bảng?**
- Cho 4 service cùng ghi 1 bảng chung sẽ phá **Database-per-Service** (shared-DB coupling, single point of contention, cross-service transaction).
- Vì mọi giao tiếp đã đi qua Kafka, chỉ cần **1 service consume mọi topic** rồi ghi vào **DB của riêng nó** → có bảng tổng hợp mong muốn mà vẫn loosely coupled. audit-service chết không ảnh hưởng luồng nghiệp vụ.
- Đây là mô hình **audit trail / Event Sourcing nghiệp vụ**; observability hạ tầng (ELK, OpenTelemetry/Jaeger) là lớp bổ trợ.

**Kafka Events Consumed:** tất cả topic — `user.events`, `wallet.commands`, `wallet.events`, `payment.events`, `wallet.dlq` (group-id riêng `audit-service-group`, không giành message với consumer nghiệp vụ).

**Kafka Events Published:** không.

**Table:** `event_store` — trace theo `correlation_id` (xuyên suốt 1 giao dịch), `causation_id` (cây nhân quả), idempotent theo `event_id` (UNIQUE). Xem `06-audit-db.sql`.

---

## 4. Kafka Topics

| Topic | Publisher | Consumers |
|---|---|---|
| `user.events` | user-service | wallet-service, notification-service, audit-service |
| `wallet.commands` | payment-service | wallet-service, audit-service |
| `wallet.events` | wallet-service | payment-service, audit-service |
| `payment.events` | payment-service | notification-service, audit-service |
| `wallet.dlq` | payment-service | audit-service, (vận hành / alerting) |

> `wallet.dlq` là Dead Letter Queue: khi Compensating Transaction (`DebitReverseCommand`) retry hết số lần vẫn thất bại, payment-service đẩy event vào đây kèm `saga_state=COMPENSATION_FAILED` để con người can thiệp. Xem UC3 mục 6 trong `use-cases.md`.

> `audit-service` consume mọi topic bằng **group-id riêng** (`audit-service-group`) — Kafka giao bản sao event cho từng consumer group độc lập, nên audit không "cướp" message của consumer nghiệp vụ.

---

## 5. Kịch bản nghiệp vụ (3 flows chính)

### Kịch bản 1: Đăng ký & Khởi tạo tài khoản

```
1. User POST /api/users/register
2. user-service lưu DB → ghi outbox_event (UserRegistered)
3. Outbox cronjob publish UserRegistered → topic: user.events
4. wallet-service consume UserRegistered → tạo wallet mới balance=0
5. notification-service consume UserRegistered → log "Chào mừng [username]!"
```

**Điểm kỹ thuật cần implement:**
- Password hash với BCrypt
- JWT token trả về sau đăng ký
- wallet-service tự động tạo ví (event-driven, không gọi REST)

---

### Kịch bản 2: Nạp tiền (Top-up)

```
1. User POST /api/payments/topup { amount: 1000, currency: "USDT" }
2. payment-service gọi mock external bank API (luôn trả về success)
3. Ghi outbox_event (TopUpCommand)
4. Outbox publish CreditCommand → topic: wallet.commands
5. wallet-service consume CreditCommand → cộng balance
6. wallet-service publish CreditCompleted → topic: wallet.events
7. payment-service consume CreditCompleted → cập nhật COMPLETED
8. notification-service consume → log "Nạp tiền thành công"
```

---

### Kịch bản 3: Chuyển tiền P2P (Saga Orchestration — trọng tâm)

#### Happy Path
```
1. User A POST /api/payments/transfer { receiverId, amount: 500 }
2. payment-service:
   - Tạo transaction record (status=INITIATED, sagaState=DEBIT_PENDING)
   - Ghi outbox_event (DebitCommand)
3. Outbox publish DebitCommand → topic: wallet.commands
4. wallet-service consume DebitCommand:
   - Check idempotency key (Redis SETNX: "idempotency:txn:{userId}:{transactionId}")
   - Check balance đủ không
   - Trừ balance với @Version (Optimistic Locking)
   - Publish DebitCompleted → topic: wallet.events
5. payment-service consume DebitCompleted:
   - Update sagaState=CREDIT_PENDING
   - Ghi outbox_event (CreditCommand)
6. Outbox publish CreditCommand → topic: wallet.commands
7. wallet-service consume CreditCommand:
   - Check idempotency key
   - Cộng balance user B
   - Publish CreditCompleted → topic: wallet.events
8. payment-service consume CreditCompleted:
   - Update transaction status=COMPLETED, sagaState=COMPLETED
   - Publish TransferCompleted → topic: payment.events
   - Ghi vào transaction_history (CQRS read model) cho cả A và B
9. notification-service consume TransferCompleted → log thông báo
```

#### Sad Path 1: User A không đủ tiền
```
1. → 4. (giống happy path)
4. wallet-service: balance không đủ → Publish DebitFailed
5. payment-service consume DebitFailed:
   - Update transaction status=FAILED, sagaState=FAILED
   - Publish TransferFailed → topic: payment.events
6. notification-service → log "Giao dịch thất bại: không đủ số dư"
```

#### Sad Path 2: Partial Failure — Debit thành công nhưng Credit thất bại (quan trọng nhất!)
```
1. → 7. wallet-service consume CreditCommand:
   - Tài khoản B bị khóa (status=SUSPENDED) hoặc DB lỗi
   - Publish CreditFailed → topic: wallet.events
8. payment-service consume CreditFailed:
   - Update sagaState=COMPENSATING
   - Ghi outbox_event (DebitReverseCommand) ← Compensating Transaction
9. Outbox publish DebitReverseCommand → topic: wallet.commands
10. wallet-service consume DebitReverseCommand:
    - Hoàn tiền lại cho user A (cộng ngược balance)
    - Publish DebitReversed → topic: wallet.events
11. payment-service consume DebitReversed:
    - Update transaction status=FAILED, sagaState=REVERSED
    - Publish TransferFailed
12. notification-service → log "Giao dịch thất bại: đã hoàn tiền"
```

> **Đây là Compensating Transaction — trái tim của Saga Pattern**

---

## 6. Patterns Implementation

### 6.1 Outbox Pattern
**Vấn đề giải quyết:** Đảm bảo event không bị mất khi server crash giữa chừng

**Cách implement:**
```java
// WRONG — có thể crash sau khi save DB nhưng trước khi publish Kafka
transactionRepository.save(transaction);
kafkaTemplate.send("wallet.commands", debitCommand); // ← crash ở đây → mất event

// CORRECT — Outbox Pattern
@Transactional
public void initiateTransfer(TransferRequest request) {
    Transaction tx = transactionRepository.save(transaction);
    // Ghi event vào cùng transaction DB → ACID đảm bảo
    outboxRepository.save(OutboxEvent.builder()
        .aggregateId(tx.getId())
        .eventType("DebitCommand")
        .payload(toJson(debitCommand))
        .status("PENDING")
        .build());
}

// Cronjob riêng đọc outbox và publish Kafka
@Scheduled(fixedDelay = 1000)
public void publishOutboxEvents() {
    List<OutboxEvent> events = outboxRepository.findByStatus("PENDING");
    events.forEach(event -> {
        kafkaTemplate.send(event.getEventType(), event.getPayload());
        event.setStatus("PUBLISHED");
        outboxRepository.save(event);
    });
}
```

---

### 6.2 Idempotency
**Vấn đề giải quyết:** Tránh xử lý event 2 lần khi Kafka re-deliver

**Cách implement:**
```java
// Composite key = userId + transactionId
String idempotencyKey = String.format("idempotency:txn:%s:%s", userId, transactionId);

// Redis SETNX — set if not exists
Boolean isNew = redisTemplate.opsForValue()
    .setIfAbsent(idempotencyKey, "PROCESSING", 24, TimeUnit.HOURS);

if (Boolean.FALSE.equals(isNew)) {
    log.info("Duplicate event, skipping: {}", idempotencyKey);
    return; // Bỏ qua, không xử lý
}
// Xử lý bình thường...
```

**Tại sao dùng composite key `userId:transactionId`?**
- `transactionId` đảm bảo uniqueness
- `userId` là security guard — ngăn user giả mạo transactionId của người khác

---

### 6.3 Optimistic Locking
**Vấn đề giải quyết:** Tránh race condition khi 2 request cùng trừ tiền 1 ví

**Cách implement:**
```java
@Entity
public class Wallet {
    @Id
    private UUID id;
    private UUID userId;
    private BigDecimal balance;

    @Version               // ← Spring JPA tự động check version
    private Long version;
}

// Khi update, nếu version không khớp → throw OptimisticLockException
// Retry logic xử lý exception này
@Retryable(value = OptimisticLockException.class, maxAttempts = 3)
public void debitWallet(UUID userId, BigDecimal amount) {
    Wallet wallet = walletRepository.findByUserId(userId);
    if (wallet.getBalance().compareTo(amount) < 0) {
        throw new InsufficientBalanceException();
    }
    wallet.setBalance(wallet.getBalance().subtract(amount));
    walletRepository.save(wallet); // ← tự động check @Version
}
```

---

### 6.4 Saga Orchestration
**payment-service đóng vai Saga Manager (nhạc trưởng):**

```java
@KafkaListener(topics = "wallet.events")
public void handleWalletEvent(WalletEvent event) {
    Transaction tx = transactionRepository.findById(event.getTransactionId());

    switch (event.getType()) {
        case "DebitCompleted":
            tx.setSagaState(SagaState.CREDIT_PENDING);
            transactionRepository.save(tx);
            publishCreditCommand(tx);      // ← bước tiếp theo
            break;

        case "DebitFailed":
            tx.setStatus(TransactionStatus.FAILED);
            tx.setSagaState(SagaState.FAILED);
            transactionRepository.save(tx);
            publishTransferFailed(tx);
            break;

        case "CreditFailed":
            tx.setSagaState(SagaState.COMPENSATING);
            transactionRepository.save(tx);
            publishDebitReverseCommand(tx); // ← Compensating Transaction
            break;

        case "CreditCompleted":
            tx.setStatus(TransactionStatus.COMPLETED);
            tx.setSagaState(SagaState.COMPLETED);
            transactionRepository.save(tx);
            publishTransferCompleted(tx);
            break;

        case "DebitReversed":
            tx.setStatus(TransactionStatus.FAILED);
            tx.setSagaState(SagaState.REVERSED);
            transactionRepository.save(tx);
            publishTransferFailed(tx);
            break;
    }
}
```

---

### 6.5 CQRS
**Tách read model riêng cho transaction history:**

```java
// Command side: ghi vào transactions table
transactionRepository.save(transaction);

// Query side: ghi vào transaction_history (read model)
// Triggered khi TransferCompleted event
transactionHistoryRepository.save(TransactionHistory.builder()
    .transactionId(tx.getId())
    .userId(tx.getSenderId())
    .type("SENT")
    .amount(tx.getAmount().negate())
    .build());

transactionHistoryRepository.save(TransactionHistory.builder()
    .transactionId(tx.getId())
    .userId(tx.getReceiverId())
    .type("RECEIVED")
    .amount(tx.getAmount())
    .build());

// Read API: query trực tiếp từ read model, không join bảng
GET /api/transactions/history?userId={userId}
→ query transaction_history WHERE user_id = ?
```

---
### 6.6 Distributed Lock

**Vấn đề giải quyết:** Race condition cross-instance — khi chạy nhiều instance
của cùng 1 service, cronjob Outbox Publisher có thể chạy đồng thời trên tất cả
instance → publish Kafka 2 lần.

**Use case 1 — Outbox Publisher (bắt buộc):**
```java
@Scheduled(fixedDelay = 1000)
public void publishOutboxEvents() {
    Boolean acquired = redisTemplate.opsForValue()
        .setIfAbsent("lock:outbox-publisher", "locked", 30, TimeUnit.SECONDS);

    if (Boolean.FALSE.equals(acquired)) {
        return; // Instance khác đang chạy
    }

    try {
        List<OutboxEvent> events = outboxRepository.findByStatus("PENDING");
        events.forEach(event -> {
            kafkaTemplate.send(event.getTopic(), event.getPayload());
            event.setStatus("PUBLISHED");
            outboxRepository.save(event);
        });
    } finally {
        redisTemplate.delete("lock:outbox-publisher");
    }
}
```

**Use case 2 — Prevent duplicate transfer request:**
```java
// User bấm submit 2 lần liên tiếp trước khi Idempotency key được set
String lockKey = "lock:transfer:" + userId;
Boolean acquired = redisTemplate.opsForValue()
    .setIfAbsent(lockKey, "locked", 5, TimeUnit.SECONDS);

if (Boolean.FALSE.equals(acquired)) {
    throw new DuplicateRequestException("Request đang được xử lý");
}
```

**Production upgrade — dùng Redisson thay vì tự implement:**
```java
// Vấn đề với TTL thủ công: job chạy 60s nhưng TTL chỉ 30s → lock expire sớm
// Redisson Watchdog tự động gia hạn TTL khi job vẫn đang chạy
RLock lock = redissonClient.getLock("lock:outbox-publisher");
lock.lock();
try {
    // xử lý...
} finally {
    lock.unlock();
}
```

**Distributed Lock vs Optimistic Locking:**

| | Optimistic Locking | Distributed Lock |
|---|---|---|
| Scope | Single DB row | Cross-instance/process |
| Cơ chế | @Version check | Redis SETNX |
| Dùng khi | Concurrent update 1 record | Chỉ 1 process được chạy |
| Trong project | Wallet balance update | Outbox Publisher cronjob |

---

#### Phân biệt 3 cơ chế hay bị nhầm (câu hỏi bẫy)

Cùng dùng Redis `SETNX` nhưng **mục đích khác hẳn** — đây là điểm interviewer hay xoáy:

| Cơ chế | Bài toán | Ý nghĩa |
|---|---|---|
| **Optimistic Locking** (`@Version`) | Lost update trên 1 row | "Nếu ai đó vừa sửa, tôi retry" |
| **Idempotency** (SETNX key nghiệp vụ) | Xử lý *lặp lại* cùng 1 event | "Việc này làm rồi → bỏ qua" |
| **Distributed Lock** (SETNX + TTL) | 2 process vào critical section | "Đợi tôi xong đã → anh hãy chờ" |

> Idempotency = *bỏ qua* lần thứ 2. Distributed Lock = *chặn/đợi* lần thứ 2. Trông giống code nhưng ngữ nghĩa ngược nhau.

---

### Case thực hành (Lab)

Mỗi lab gồm: cách **tái hiện bug** → **fix** → **cách verify**. Làm tuần tự để hiểu sâu.

#### Lab 1 — Tái hiện double-publish ở Outbox (cốt lõi)

**Mục tiêu:** Chứng minh tại sao cần distributed lock cho scheduler.

1. Chạy **2 instance** payment-service trên 2 port:
   ```bash
   java -jar payment-service.jar --server.port=8083
   java -jar payment-service.jar --server.port=8093
   ```
2. Tắt distributed lock (comment đoạn `setIfAbsent`), tạo 1 giao dịch transfer.
3. **Quan sát:** cùng 1 `DebitCommand` bị publish 2 lần lên `wallet.commands` (xem log consumer của wallet-service nhận 2 message trùng `transactionId`).
4. **Fix:** bật lại distributed lock → chỉ 1 instance publish.
5. **Verify:** log wallet-service chỉ nhận 1 lần. (Nếu vẫn nhận 2 lần là do Idempotency cứu — đó là defense-in-depth, không phải lock hoạt động.)

> Bài học: lock ở producer + idempotency ở consumer là **2 lớp phòng thủ độc lập**, không thay thế nhau.

#### Lab 2 — Lock expire sớm (vì sao cần Redisson Watchdog)

**Mục tiêu:** Thấy hậu quả khi TTL ngắn hơn thời gian xử lý.

1. Set TTL lock = `5s` nhưng làm job chạy lâu hơn (thêm `Thread.sleep(8000)` hoặc xử lý batch lớn).
2. Chạy 2 instance.
3. **Quan sát:** instance A đang chạy thì lock hết hạn ở giây thứ 5 → instance B chiếm lock và chạy song song → **double-publish dù đã có lock**.
4. **Fix:** chuyển sang `RLock` của Redisson (`lock.lock()`) — watchdog tự gia hạn TTL mỗi 10s khi job còn sống.
5. **Verify:** không còn double-publish dù job chạy 30s+.

> Đây là lý do "TTL thủ công nguy hiểm". Câu trả lời senior: TTL cố định luôn sai trong ít nhất 1 trong 2 trường hợp (quá ngắn → mất an toàn; quá dài → instance chết làm kẹt lock).

#### Lab 3 — Lock không an toàn khi unlock nhầm (Lua atomic release)

**Mục tiêu:** Tái hiện lỗi "xóa nhầm lock của người khác".

1. Instance A acquire lock (TTL 5s), nhưng GC pause / sleep 6s.
2. Sau 5s lock hết hạn → instance B acquire lock mới.
3. Instance A tỉnh dậy, chạy `redisTemplate.delete(lockKey)` ở `finally` → **xóa mất lock của B**.
4. **Fix:** release có điều kiện — chỉ xóa nếu value là token của chính mình, bằng Lua script atomic:
   ```java
   String script =
     "if redis.call('get', KEYS[1]) == ARGV[1] " +
     "then return redis.call('del', KEYS[1]) else return 0 end";
   redisTemplate.execute(new DefaultRedisScript<>(script, Long.class),
       List.of(lockKey), myToken);
   ```
5. **Verify:** instance A không xóa được lock của B (script trả về 0). Redisson làm sẵn việc này.

#### Lab 4 — Lock vs Optimistic Locking trên ví (so sánh hiệu năng)

**Mục tiêu:** Trả lời "tại sao spec chọn Optimistic chứ không Distributed Lock cho ví".

1. Bắn 100 request đồng thời debit **cùng 1 ví** (dùng JMeter / `ab` / script).
2. **Phương án A — Optimistic Lock** (`@Version` + `@Retryable`): đo số lần retry, throughput.
3. **Phương án B — Distributed Lock** (`RLock` per `userId`): đo thời gian chờ lock, throughput.
4. **Quan sát:**
   - Contention thấp (nhiều ví khác nhau): A nhanh hơn hẳn, gần như không retry.
   - Contention cao (dồn 1 ví): A retry nhiều → có thể nên dùng B cho *hot account*.
5. **Kết luận để nói khi phỏng vấn:** ví cá nhân contention thấp → Optimistic thắng. Chỉ ví hệ thống/ví sàn (hot account) mới cân nhắc Distributed Lock.

#### Lab 5 — Chống double-submit từ phía user (Use case 2 ở trên)

1. Dùng Postman/script bắn 2 request `POST /api/payments/transfer` y hệt nhau cách nhau 10ms (trước khi transaction record kịp tạo).
2. **Không có lock:** tạo ra 2 transaction trùng nội dung.
3. **Fix:** `lock:transfer:{userId}` TTL ngắn (5s) ở đầu controller/service.
4. **Verify:** request thứ 2 nhận `DuplicateRequestException`.

---

### Phương án thay thế tốt hơn cho Outbox: `SKIP LOCKED`

Distributed lock toàn cục cho Outbox publisher làm **serialize** toàn bộ — chỉ 1 instance làm việc, các instance khác ngồi không. Cách "đẹp" hơn là cho **nhiều instance chạy song song mà không giẫm chân nhau**, dùng tính năng của chính PostgreSQL:

```sql
SELECT * FROM outbox_events
WHERE status = 'PENDING'
ORDER BY created_at
LIMIT 100
FOR UPDATE SKIP LOCKED;   -- row đã bị instance khác lock → bỏ qua, lấy row khác
```

- Mỗi instance lấy một batch row **khác nhau** → tận dụng được nhiều instance.
- Không cần Redis cho việc này → bớt một critical dependency.
- Đây thường là câu trả lời "ăn điểm" hơn cả ShedLock cho bài toán outbox.

> Khi nào vẫn cần lock toàn cục (ShedLock)? Khi job **không thể chia nhỏ** (vd: gọi 1 external API tổng hợp, rebuild cache toàn cục). Outbox thì chia nhỏ được nên `SKIP LOCKED` hợp lý hơn.

---

### Bẫy phỏng vấn nâng cao

- **"Redis lock an toàn tuyệt đối chưa?"** → Chưa. Tham khảo phản biện Redlock của Martin Kleppmann: GC pause / network delay khiến process tưởng còn giữ lock nhưng lease đã hết → 2 process cùng vào critical section (chính là Lab 2 & 3).
- **Fencing Token (câu trả lời senior):** mỗi lần cấp lock kèm 1 số tăng dần (monotonic). Resource phía sau (DB) **từ chối** request mang token cũ hơn token đã thấy → dù lock bị "tranh chấp" vẫn an toàn ở tầng ghi dữ liệu.
- **"Sao không SETNX thuần mà cần Redisson?"** → Redisson lo: watchdog auto-renew lease, reentrant lock, release atomic bằng Lua (Lab 3), hỗ trợ Redlock multi-node.
- **"Distributed lock có phá vỡ tinh thần microservice không?"** → Có, nó tạo coupling + bottleneck + single point (Redis). Nguyên tắc: ưu tiên thiết kế **lock-free** (Optimistic + Idempotency + Saga), chỉ dùng distributed lock khi thật sự cần.

## 7. Infrastructure

### docker-compose.yml
```yaml
version: '3.8'

services:
  postgres:
    image: postgres:15
    container_name: crypto-postgres
    environment:
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports:
      - "5432:5432"
    volumes:
      - postgres-data:/var/lib/postgresql/data
      - ./init-db/init.sql:/docker-entrypoint-initdb.d/init.sql
    networks:
      - crypto-network

  redis:
    image: redis:7-alpine
    container_name: crypto-redis
    ports:
      - "6379:6379"
    networks:
      - crypto-network

  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    container_name: crypto-zookeeper
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
    networks:
      - crypto-network

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    container_name: crypto-kafka
    depends_on:
      - zookeeper
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
    networks:
      - crypto-network

volumes:
  postgres-data:

networks:
  crypto-network:
    driver: bridge
```

### init-db/init.sql
```sql
CREATE DATABASE user_db;
CREATE DATABASE wallet_db;
CREATE DATABASE payment_db;
CREATE DATABASE notification_db;
```

---

## 8. application.yml cho từng service

### user-service
```yaml
server:
  port: 8081

spring:
  application:
    name: user-service
  datasource:
    url: jdbc:postgresql://localhost:5432/user_db
    username: postgres
    password: postgres
    driver-class-name: org.postgresql.Driver
  jpa:
    database-platform: org.hibernate.dialect.PostgreSQLDialect
    hibernate:
      ddl-auto: update
    show-sql: true
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
```

### wallet-service
```yaml
server:
  port: 8082

spring:
  application:
    name: wallet-service
  datasource:
    url: jdbc:postgresql://localhost:5432/wallet_db
    username: postgres
    password: postgres
    driver-class-name: org.postgresql.Driver
  jpa:
    database-platform: org.hibernate.dialect.PostgreSQLDialect
    hibernate:
      ddl-auto: update
  data:
    redis:
      host: localhost
      port: 6379
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: wallet-service-group
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
```

### payment-service
```yaml
server:
  port: 8083

spring:
  application:
    name: payment-service
  datasource:
    url: jdbc:postgresql://localhost:5432/payment_db
    username: postgres
    password: postgres
    driver-class-name: org.postgresql.Driver
  jpa:
    database-platform: org.hibernate.dialect.PostgreSQLDialect
    hibernate:
      ddl-auto: update
  data:
    redis:
      host: localhost
      port: 6379
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: payment-service-group
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
```

### notification-service
```yaml
server:
  port: 8084

spring:
  application:
    name: notification-service
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: notification-service-group
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
```

---

## 9. API Endpoints

### user-service
```
POST   /api/users/register       ← Đăng ký tài khoản
POST   /api/users/login          ← Đăng nhập, trả về JWT
GET    /api/users/{id}           ← Lấy thông tin user
```

### wallet-service
```
GET    /api/wallets/balance      ← Xem số dư (Redis cache)
```

### payment-service
```
POST   /api/payments/topup       ← Nạp tiền
POST   /api/payments/transfer    ← Chuyển tiền P2P
GET    /api/payments/history     ← Lịch sử giao dịch (CQRS read model)
GET    /api/payments/{id}        ← Chi tiết giao dịch
```

---

## 10. Câu hỏi phỏng vấn và câu trả lời mẫu

| Câu hỏi | Điểm trả lời |
|---|---|
| Tại sao dùng Saga thay vì 2PC? | 2PC blocking, không scale được. Saga async, loosely coupled, phù hợp microservice |
| Choreography vs Orchestration? | Orchestration cho financial transaction: State Machine rõ ràng, dễ trace, dễ thêm bước (fee, risk check) |
| Làm sao đảm bảo event không mất? | Outbox Pattern: ghi event vào DB cùng transaction nghiệp vụ, cronjob publish sau |
| Làm sao tránh double-charge? | Idempotency key = userId:transactionId, Redis SETNX TTL 24h |
| Concurrent debit xử lý thế nào? | Optimistic Locking @Version + Retry, không dùng Pessimistic Lock vì giảm throughput |
| Tại sao dùng PostgreSQL? | ACID mạnh hơn MySQL, MVCC ít deadlock, JSONB cho outbox payload, chuẩn Fintech |
| Database per Service có nhược điểm gì? | Không join được cross-service, nhưng đổi lại độc lập deploy và scale |
| Distributed Lock khác Optimistic Lock thế nào? | Optimistic Lock: single DB row, dùng @Version. Distributed Lock: cross-instance, dùng Redis SETNX. Project dùng cả 2: Optimistic cho wallet balance, Distributed Lock cho Outbox cronjob |
| Nếu instance crash sau khi acquire lock thì sao? | TTL tự động expire lock sau 30 giây. Production dùng Redisson Watchdog để gia hạn TTL khi job vẫn chạy |
| Tại sao Outbox cần Distributed Lock? | Nhiều instance cùng đọc outbox_events PENDING → publish Kafka 2 lần → duplicate events dù đã có Idempotency. Lock đảm bảo chỉ 1 instance publish tại một thời điểm |
---

## 11. Thứ tự implement gợi ý

1. **Infrastructure**: Docker Compose, verify Kafka + PostgreSQL + Redis chạy được
2. **user-service**: Register, Login, JWT, publish UserRegistered
3. **wallet-service**: Consume UserRegistered tạo ví, Optimistic Locking, Idempotency
4. **payment-service**: Top-up flow trước (đơn giản hơn)
5. **payment-service**: P2P Transfer — Saga Orchestration (Happy Path)
6. **payment-service**: Sad Path + Compensating Transaction
7. **payment-service**: Outbox Pattern thay thế kafkaTemplate.send() trực tiếp
8. **payment-service**: CQRS — transaction history read model
9. **wallet-service**: Redis cache cho balance query
10. **notification-service**: Consume events và log
11. **README.md**: Architecture diagram, hướng dẫn chạy local
