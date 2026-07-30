package com.cryptopayment.common.core.dto;

import java.util.List;

import org.springframework.data.domain.Page;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Vỏ phân trang thống nhất — dùng cho các API lịch sử (transaction history...).
 * <p>
 * QUY ƯỚC: {@code page} là 0-based (page=0 là trang đầu tiên), khớp với
 * quy ước mặc định của Spring Data {@code Pageable}/{@code Page}. Toàn bộ
 * Controller trong mọi service PHẢI tuân theo quy ước này khi nhận query
 * param từ client, tránh lệch 1 trang giữa các service.
 *
 * @param <T> kiểu phần tử
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> {

    private List<T> items;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalElements) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be > 0");
        }
        int totalPages = (int) Math.ceil((double) totalElements / size);
        return PageResponse.<T>builder()
                .items(items)
                .page(page)
                .size(size)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .build();
    }

    /**
     * Chuyển trực tiếp từ {@link Page} của Spring Data JPA — cách dùng chính
     * trong các service, tránh phải map thủ công từng field.
     */
    public static <T> PageResponse<T> from(Page<T> page) {
        return PageResponse.<T>builder()
                .items(page.getContent())
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .build();
    }

    public boolean isHasNext() {
        return page < totalPages - 1;
    }

    public boolean isHasPrevious() {
        return page > 0;
    }
}
