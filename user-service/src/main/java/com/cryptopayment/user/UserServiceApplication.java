package com.cryptopayment.user;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling} đặt ở đây (tầng ứng dụng) chứ không nằm trong
 * {@code OutboxConfig}: bật scheduler là quyết định của cả ứng dụng. Gắn vào config
 * của một cơ chế cụ thể thì khi bỏ cơ chế đó đi, mọi job @Scheduled khác sẽ âm thầm
 * ngừng chạy — lỗi không có log, rất khó truy.
 */
@EnableScheduling
@SpringBootApplication
public class UserServiceApplication {

    public static void main(String[] args) {
        // Ép timezone JVM về UTC — tránh lỗi PostgreSQL không nhận alias "Asia/Saigon"
        // của Windows. Phải set TRƯỚC khi Spring tạo datasource connection.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(UserServiceApplication.class, args);
    }

}
