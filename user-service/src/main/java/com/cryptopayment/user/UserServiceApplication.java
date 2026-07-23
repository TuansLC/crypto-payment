package com.cryptopayment.user;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class UserServiceApplication {

    public static void main(String[] args) {
        // Ép timezone JVM về UTC — tránh lỗi PostgreSQL không nhận alias "Asia/Saigon"
        // của Windows. Phải set TRƯỚC khi Spring tạo datasource connection.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(UserServiceApplication.class, args);
    }

}
