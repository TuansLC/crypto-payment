# Crypto Payment Platform — Diagrams

> Tổng hợp toàn bộ sơ đồ trực quan của hệ thống. Mỗi block `mermaid` dán được trực tiếp vào [mermaid.live](https://mermaid.live).

## Mục lục
1. [Kiến trúc tổng quan (services ↔ DB ↔ Kafka)](#1-kiến-trúc-tổng-quan)
2. [ERD từng service](#2-erd-từng-service)
   - [user-service](#21-user-service--user_db)
   - [wallet-service](#22-wallet-service--wallet_db)
   - [payment-service](#23-payment-service--payment_db)
   - [notification-service](#24-notification-service--notification_db)
   - [audit-service](#25-audit-service--audit_db)
3. [Sequence — P2P Transfer Happy Path](#3-sequence--p2p-transfer-happy-path)
4. [Sequence — Compensating Transaction (Sad Path)](#4-sequence--compensating-transaction-sad-path)

---

## 1. Kiến trúc tổng quan

- Mũi tên đậm `==>` = **publish** event lên Kafka.
- Mũi tên đứt `-.->` = **consume** event từ Kafka.
- Mỗi service có **database riêng** (database-per-service) — không truy cập chéo.
- `audit-service` hút **mọi topic** → ghi `event_store` để trace toàn hệ thống.

```mermaid
flowchart TB
    Client([" Client / API Gateway "])

    %% ---------- SERVICES + DB ----------
    subgraph US["user-service :8081"]
        U["REST: register / login<br/>JWT + Outbox"]
        UDB[("user_db")]
        U --- UDB
    end

    subgraph WS["wallet-service :8082"]
        W["Consumer: debit/credit<br/>Optimistic Lock + Idempotency"]
        WDB[("wallet_db")]
        W --- WDB
    end

    subgraph PS["payment-service :8083"]
        P["Saga Orchestrator<br/>Outbox + CQRS"]
        PDB[("payment_db")]
        P --- PDB
    end

    subgraph NS["notification-service :8084"]
        N["Consumer: gửi thông báo"]
        NDB[("notification_db")]
        N --- NDB
    end

    subgraph AS["audit-service :8085"]
        A["Consumer: MỌI topic<br/>Event Store / Trace"]
        ADB[("audit_db")]
        A --- ADB
    end

    %% ---------- KAFKA ----------
    subgraph KAFKA["Apache Kafka"]
        T1{{"user.events"}}
        T2{{"wallet.commands"}}
        T3{{"wallet.events"}}
        T4{{"payment.events"}}
        T5{{"wallet.dlq"}}
    end

    %% ---------- HTTP ----------
    Client -->|"POST /register"| U
    Client -->|"POST /transfer, /topup"| P
    Client -.->|"GET /audit/trace"| A

    %% ---------- PUBLISH (nét liền) ----------
    U ==>|publish| T1
    P ==>|"DebitCommand / CreditCommand / DebitReverseCommand"| T2
    W ==>|"Debit/Credit Completed/Failed"| T3
    P ==>|"TransferCompleted / TransferFailed"| T4
    P ==>|"compensation fail"| T5

    %% ---------- CONSUME (nét đứt) ----------
    T1 -.->|consume| W
    T1 -.->|consume| N
    T2 -.->|consume| W
    T3 -.->|consume| P

    T1 -.-> A
    T2 -.-> A
    T3 -.-> A
    T4 -.->|consume| N
    T4 -.-> A
    T5 -.-> A
    T5 -.->|alert| Ops([" Ops / Alerting "])

    %% ---------- STYLE ----------
    classDef svc fill:#1f6feb,stroke:#0b3d91,color:#fff;
    classDef db fill:#238636,stroke:#0f5323,color:#fff;
    classDef topic fill:#8957e5,stroke:#4b2a8a,color:#fff;
    class U,W,P,N,A svc;
    class UDB,WDB,PDB,NDB,ADB db;
    class T1,T2,T3,T4,T5 topic;
```

---

## 2. ERD từng service

> `||--o{` (nét liền) = physical FK thật (cùng 1 DB). `||..o{` (nét đứt) = quan hệ logical qua id, KHÔNG có FK (database-per-service).

### 2.1 user-service — `user_db`

```mermaid
---
title: user-service (user_db)
---
erDiagram
    users {
        uuid id PK
        varchar username UK
        varchar email UK
        varchar password_hash
        varchar full_name
        varchar status "ACTIVE|SUSPENDED|DELETED"
        timestamp created_at
        timestamp updated_at
        timestamp deleted_at "soft delete"
    }
    user_outbox_events {
        bigserial id PK "FIFO"
        uuid event_id
        uuid aggregate_id "= user id"
        varchar event_type
        varchar topic
        jsonb payload
        varchar status "PENDING|PUBLISHED|FAILED"
        int retry_count
        text error_message
        timestamp created_at
        timestamp published_at
    }
    users ||..o{ user_outbox_events : "aggregate_id (logical)"
```

### 2.2 wallet-service — `wallet_db`

```mermaid
---
title: wallet-service (wallet_db)
---
erDiagram
    wallets {
        uuid id PK
        uuid user_id "UK(user_id,currency)"
        decimal balance "CHECK >= 0"
        varchar currency
        varchar status "ACTIVE|FROZEN|CLOSED"
        bigint version "optimistic lock"
        timestamp created_at
        timestamp updated_at
    }
    idempotency_keys {
        varchar idempotency_key PK "debit/credit/topup:..."
        varchar status "PROCESSING|COMPLETED|FAILED"
        jsonb result
        timestamp created_at
        timestamp expires_at "TTL 24h"
    }
    wallet_transaction_logs {
        uuid id PK
        uuid wallet_id FK
        varchar type "DEBIT|CREDIT|DEBIT_REVERSED|TOP_UP"
        decimal amount
        decimal balance_before
        decimal balance_after
        varchar reference_id "= transactionId"
        varchar description
        timestamp created_at
    }
    wallet_outbox_events {
        bigserial id PK
        uuid event_id
        uuid aggregate_id
        varchar event_type
        varchar topic
        jsonb payload
        varchar status
        int retry_count
        text error_message
        timestamp created_at
        timestamp published_at
    }
    wallets ||--o{ wallet_transaction_logs : "wallet_id (FK thật)"
```

### 2.3 payment-service — `payment_db`

```mermaid
---
title: payment-service (payment_db) — Saga Orchestrator
---
erDiagram
    transactions {
        uuid id PK
        uuid sender_id "null nếu TOP_UP"
        uuid receiver_id
        decimal amount "CHECK > 0"
        varchar currency
        varchar type "TRANSFER|TOP_UP"
        varchar status "INITIATED|PROCESSING|COMPLETED|FAILED|REVERSED"
        varchar saga_state "DEBIT_PENDING|CREDIT_PENDING|COMPLETED|COMPENSATING|REVERSED|FAILED|COMPENSATION_FAILED"
        varchar failure_reason
        varchar external_reference_id "TOP_UP only"
        timestamp created_at
        timestamp updated_at
    }
    payment_idempotency_keys {
        varchar idempotency_key PK "client Idempotency-Key"
        uuid transaction_id
        varchar status
        jsonb result
        timestamp created_at
        timestamp expires_at
    }
    outbox_events {
        bigserial id PK
        uuid event_id
        uuid aggregate_id "= transactionId"
        varchar event_type
        varchar topic
        jsonb payload
        varchar status
        int retry_count
        text error_message
        timestamp created_at
        timestamp published_at
    }
    transaction_history {
        uuid id PK
        uuid transaction_id
        uuid user_id
        uuid counterpart_id
        varchar type "SENT|RECEIVED|TOP_UP"
        decimal amount
        varchar currency
        varchar status
        varchar description
        timestamp created_at
    }
    saga_logs {
        uuid id PK
        uuid transaction_id
        varchar from_state
        varchar to_state
        varchar event_type
        jsonb event_payload
        timestamp created_at
    }
    dead_letter_events {
        uuid id PK
        uuid transaction_id
        varchar topic
        varchar event_type
        jsonb payload
        varchar failed_step
        varchar error_class
        text error_message
        int retry_count
        varchar status "NEW|INVESTIGATING|RESOLVED"
        timestamp created_at
        timestamp resolved_at
    }
    transactions ||..o| payment_idempotency_keys : "transaction_id (logical)"
    transactions ||..o{ outbox_events : "aggregate_id (logical)"
    transactions ||..o{ saga_logs : "transaction_id (logical)"
    transactions ||..o{ dead_letter_events : "transaction_id (logical)"
    transactions ||..o{ transaction_history : "transaction_id (logical)"
```

### 2.4 notification-service — `notification_db`

```mermaid
---
title: notification-service (notification_db)
---
erDiagram
    notification_logs {
        uuid id PK
        uuid user_id
        uuid transaction_id
        varchar event_type
        varchar channel "EMAIL|PUSH|SMS|LOG"
        varchar title
        text message
        varchar status "PENDING|SENT|FAILED"
        text error_message
        timestamp created_at
    }
```

### 2.5 audit-service — `audit_db`

```mermaid
---
title: audit-service (audit_db) — Centralized Event Trace
---
erDiagram
    event_store {
        bigserial id PK
        uuid event_id UK "idempotency"
        uuid correlation_id "trace 1 giao dịch xuyên service"
        uuid causation_id "cây nhân quả"
        varchar source_service
        varchar topic
        varchar event_type
        uuid aggregate_id
        jsonb payload
        timestamp occurred_at "lúc event sinh ra"
        timestamp recorded_at "lúc audit ghi"
    }
```

---

## 3. Sequence — P2P Transfer Happy Path

A chuyển $500 cho B thành công. Mỗi bước của Orchestrator là **1 transaction atomic** ghi kèm outbox (Hướng A — không có state trung gian `DEBIT_COMPLETED`).

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant P as payment-service<br/>(Saga Orchestrator)
    participant K as Kafka
    participant W as wallet-service
    participant N as notification-service

    Note over Client,P: correlationId sinh 1 lần, lan truyền suốt luồng

    Client->>P: POST /transfer {sender, receiver, amount, currency}<br/>Header: Idempotency-Key
    Note over P: @Transactional (atomic)<br/>INSERT transactions (INITIATED, saga=DEBIT_PENDING)<br/>+ outbox(DebitCommand) + idempotency_key
    P-->>Client: 202 Accepted (transactionId)

    Note over P,K: OutboxPublisher (SKIP LOCKED) publish
    P->>K: DebitCommand → wallet.commands (key=txnId)
    K->>W: DebitCommand
    Note over W: @Transactional (atomic)<br/>idempotency debit:txn:sender<br/>check balance ≥ amount<br/>balance -= amount (@Version)<br/>+ log + outbox(DebitCompleted)
    W->>K: DebitCompleted → wallet.events

    K->>P: DebitCompleted
    Note over P: @Transactional (atomic)<br/>saga=CREDIT_PENDING + outbox(CreditCommand)
    P->>K: CreditCommand → wallet.commands

    K->>W: CreditCommand
    Note over W: @Transactional (atomic)<br/>idempotency credit:txn:receiver<br/>tìm/tự tạo ví B (insert-or-get)<br/>balance += amount + outbox(CreditCompleted)
    W->>K: CreditCompleted → wallet.events

    K->>P: CreditCompleted
    Note over P: @Transactional (atomic)<br/>status=COMPLETED, saga=COMPLETED<br/>+ 2 rows transaction_history (CQRS)<br/>+ outbox(TransferCompleted)
    P->>K: TransferCompleted → payment.events
    K->>N: TransferCompleted
    N-->>N: Thông báo cho A (SENT) và B (RECEIVED)
```

---

## 4. Sequence — Compensating Transaction (Sad Path)

Debit tiền A xong nhưng Credit cho B thất bại (ví B `FROZEN`) → phải hoàn tiền A. Khối đỏ cuối là nhánh "rollback cũng lỗi".

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant P as payment-service<br/>(Saga Orchestrator)
    participant K as Kafka
    participant W as wallet-service
    participant N as notification-service

    Client->>P: POST /transfer
    Note over P: transactions (INITIATED, saga=DEBIT_PENDING)<br/>+ outbox(DebitCommand)
    P-->>Client: 202 Accepted

    P->>K: DebitCommand → wallet.commands
    K->>W: DebitCommand
    Note over W: Trừ tiền A thành công ✅
    W->>K: DebitCompleted → wallet.events

    K->>P: DebitCompleted
    Note over P: saga=CREDIT_PENDING + outbox(CreditCommand)
    P->>K: CreditCommand → wallet.commands

    K->>W: CreditCommand
    Note over W: ❌ Ví B đang FROZEN → từ chối
    W->>K: CreditFailed → wallet.events

    K->>P: CreditFailed
    Note over P: saga=COMPENSATING<br/>+ outbox(DebitReverseCommand)
    P->>K: DebitReverseCommand → wallet.commands

    K->>W: DebitReverseCommand
    Note over W: idempotency debit-reverse:txn:sender<br/>Hoàn $500 lại ví A ✅ (retriable)
    W->>K: DebitReversed → wallet.events

    K->>P: DebitReversed
    Note over P: status=REVERSED, saga=REVERSED<br/>+ outbox(TransferFailed)
    P->>K: TransferFailed → payment.events
    K->>N: TransferFailed
    N-->>N: Báo A: "Thất bại — đã hoàn tiền"

    rect rgb(60, 20, 20)
    Note over P,W: ⚠️ Nếu DebitReverseCommand retry hết vẫn fail:<br/>saga=COMPENSATION_FAILED (status giữ PROCESSING)<br/>→ đẩy wallet.dlq + INSERT dead_letter_events + alert Ops
    end
```
