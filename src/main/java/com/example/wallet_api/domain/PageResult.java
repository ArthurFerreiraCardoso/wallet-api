package com.example.wallet_api.domain;

import java.util.List;

public record PageResult<T>(List<T> content, int page, int size, long totalElements) {

    public PageResult {
        if (size < 1) {
            throw new IllegalArgumentException("size deve ser >= 1");
        }
        content = List.copyOf(content);
    }

    public int totalPages() {
        return (int) ((totalElements + size - 1) / size);
    }
}
