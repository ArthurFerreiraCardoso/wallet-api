package com.example.wallet_api.api.dto;

import java.util.List;
import java.util.function.Function;

import com.example.wallet_api.domain.PageResult;

public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <D, T> PageResponse<T> from(PageResult<D> result, Function<D, T> mapper) {
        return new PageResponse<>(result.content().stream().map(mapper).toList(),
                result.page(), result.size(), result.totalElements(), result.totalPages());
    }
}
