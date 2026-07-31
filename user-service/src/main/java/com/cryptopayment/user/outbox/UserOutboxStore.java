package com.cryptopayment.user.outbox;

import java.util.List;

import org.springframework.stereotype.Component;

import com.cryptopayment.common.messaging.outbox.OutboxStore;

import lombok.RequiredArgsConstructor;

/**
 * Adapter giữa {@link OutboxStore} (abstraction mà {@code OutboxPublisherJob} generic
 * phụ thuộc) và {@link UserOutboxRepository} của Spring Data.
 * <p>
 * Vì sao cần class riêng mà không để {@code UserOutboxRepository} implement luôn
 * {@code OutboxStore}? Vì chữ ký xung đột: {@code OutboxStore} khai
 * {@code void save(E)} còn {@code JpaRepository} khai {@code <S extends T> S save(S)}
 * — cùng tên, khác kiểu trả về nên không compile được. Adapter là cách xử lý đúng.
 */
@Component
@RequiredArgsConstructor
public class UserOutboxStore implements OutboxStore<UserOutboxEvent> {

    private final UserOutboxRepository repository;

    @Override
    public List<UserOutboxEvent> fetchPending(int limit) {
        return repository.fetchPending(limit);
    }

    @Override
    public void save(UserOutboxEvent event) {
        repository.save(event);
    }
}
