package org.example.xqy1._026_silver_residence.api;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContractResponse<T>(
        boolean success,
        T data,
        ContractError error,
        String traceId
) {
    public static <T> ContractResponse<T> success(T data, String traceId) {
        return new ContractResponse<>(true, data, null, traceId);
    }

    public static <T> ContractResponse<T> failure(ContractError error, String traceId) {
        return new ContractResponse<>(false, null, error, traceId);
    }
}
