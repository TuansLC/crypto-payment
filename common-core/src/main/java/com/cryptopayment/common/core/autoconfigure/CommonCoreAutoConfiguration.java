package com.cryptopayment.common.core.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

import com.cryptopayment.common.core.exception.GlobalExceptionHandler;

/**
 * Auto-configuration cho common-core: service chỉ cần add dependency là các bean
 * dùng chung tự đăng ký, KHÔNG cần @ComponentScan vào package của lib.
 * <p>
 * Được kích hoạt qua file:
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
 */
@AutoConfiguration
public class CommonCoreAutoConfiguration {

    /**
     * Đăng ký GlobalExceptionHandler cho ứng dụng web servlet.
     * @ConditionalOnMissingBean để service có thể override bằng handler riêng nếu muốn.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}
