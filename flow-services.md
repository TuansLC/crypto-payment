# Crypto Payment Platform — Service Flows

> Sơ đồ luồng xử lý bên trong từng service (sequence + kiến trúc phân tầng). Mỗi block `mermaid` dán được vào [mermaid.live](https://mermaid.live).

## Mục lục
1. [user-service](#1-user-service)
   - [Register](#11-register--đăng-ký--phát-userregistered-outbox)
   - [Login](#12-login--xác-thực--cấp-jwt)
   - [Kiến trúc phân tầng](#13-kiến-trúc-phân-tầng)
2. wallet-service _(sẽ bổ sung)_
3. payment-service _(sẽ bổ sung)_
4. notification-service _(sẽ bổ sung)_
5. audit-service _(sẽ bổ sung)_

---

## 1. user-service

Trách nhiệm: đăng ký, đăng nhập (JWT), phát `UserRegistered` qua Outbox. Port 8081, DB `user_db`.

### 1.1 Register — Đăng ký & phát `UserRegistered` (Outbox)

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant C as UserController
    participant S as UserService
    participant R as UserRepository
    participant O as OutboxRepository
    participant DB as (user_db)
    participant J as JwtService
    participant P as OutboxPublisher<br/>(@Scheduled)
    participant K as Kafka (user.events)

    Client->>C: POST /api/users/register<br/>{username, email, password}
    C->>S: register(request)
    S->>R: existsByUsername / existsByEmail
    R->>DB: SELECT
    alt Trùng username/email
        S-->>Client: 409 Conflict (không ghi outbox)
    else Hợp lệ
        Note over S,DB: @Transactional (atomic)
        S->>S: passwordEncoder.encode(password) — BCrypt
        S->>R: save(user status=ACTIVE)
        R->>DB: INSERT users
        S->>O: save(UserRegistered)
        O->>DB: INSERT user_outbox_events (PENDING)
        Note over S,DB: commit — users + outbox cùng sống/chết
        S->>J: generateToken(user)
        J-->>S: JWT
        S-->>Client: 201 Created + JWT
    end

    Note over P,K: Bất đồng bộ, độc lập request
    P->>DB: SELECT outbox WHERE PENDING (FOR UPDATE SKIP LOCKED)
    P->>K: publish UserRegistered (key=userId)
    P->>DB: UPDATE outbox SET PUBLISHED
    Note over K: wallet-service → tạo ví<br/>notification-service → email chào mừng<br/>audit-service → ghi event_store
```

### 1.2 Login — Xác thực & cấp JWT

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant C as UserController
    participant S as UserService
    participant R as UserRepository
    participant DB as (user_db)
    participant J as JwtService

    Client->>C: POST /api/users/login {username, password}
    C->>S: login(request)
    S->>R: findByUsername(username)
    R->>DB: SELECT users
    alt Không tồn tại / sai mật khẩu
        S->>S: passwordEncoder.matches(...) = false
        S-->>Client: 401 Unauthorized
    else Đúng mật khẩu & status=ACTIVE
        S->>S: passwordEncoder.matches(...) = true
        S->>J: generateToken(user)
        J-->>S: JWT (claims: userId, username)
        S-->>Client: 200 OK + JWT
    end
```

### 1.3 Kiến trúc phân tầng

```mermaid
flowchart TB
    Client([Client])

    subgraph UserService["user-service :8081"]
        direction TB
        CTRL["UserController<br/>REST endpoints"]
        SEC["SecurityFilter<br/>(JWT validate)"]
        SVC["UserService<br/>business logic"]
        JWT["JwtService<br/>tạo/validate token"]
        ENC["PasswordEncoder<br/>BCrypt"]
        UREPO["UserRepository (JPA)"]
        OREPO["OutboxRepository (JPA)"]
        PUB["OutboxPublisher<br/>@Scheduled"]
    end

    DB[("user_db<br/>users + user_outbox_events")]
    K{{"Kafka: user.events"}}

    Client -->|register / login| CTRL
    Client -.->|request kèm Bearer JWT| SEC
    SEC --> CTRL
    CTRL --> SVC
    SVC --> ENC
    SVC --> JWT
    SVC --> UREPO
    SVC --> OREPO
    UREPO --> DB
    OREPO --> DB
    PUB -->|đọc PENDING| DB
    PUB ==>|publish UserRegistered| K

    classDef comp fill:#1f6feb,stroke:#0b3d91,color:#fff;
    classDef db fill:#238636,stroke:#0f5323,color:#fff;
    classDef topic fill:#8957e5,stroke:#4b2a8a,color:#fff;
    class CTRL,SEC,SVC,JWT,ENC,UREPO,OREPO,PUB comp;
    class DB db;
    class K topic;
```

**Điểm nhấn:**
- **Register dùng Outbox**: ghi `users` + `user_outbox_events` trong 1 transaction atomic → `UserRegistered` không mất kể cả khi Kafka down.
- **JWT trả về ngay sau đăng ký** → client dùng token gọi API tiếp theo, không cần login lại.
- **BCrypt** cho password — không lưu plaintext.
- Downstream (wallet/notification/audit) hoàn toàn bất đồng bộ — user-service chỉ phát event, không gọi REST trực tiếp.
