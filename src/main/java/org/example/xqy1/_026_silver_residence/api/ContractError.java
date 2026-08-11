package org.example.xqy1._026_silver_residence.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContractError(
        String code,
        String message,
        boolean retryable,
        Map<String, Object> details
) {
}
