package org.example.xqy1._026_silver_residence.agent;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.example.xqy1._026_silver_residence.api.MapContractException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AssistantSessionIdentity {
    public static final String USER_ID = "assistant.userId";
    public static final String TENANT_ID = "assistant.tenantId";
    public static final String ROLES = "assistant.roles";
    public static final String DEFAULT_TENANT = "tenant-default";

    public Identity require(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(USER_ID) instanceof String userId) || userId.isBlank()) {
            throw new MapContractException(
                    HttpStatus.UNAUTHORIZED,
                    "AUTHENTICATION_REQUIRED",
                    "请登录后使用智能助手"
            );
        }
        String tenantId = session.getAttribute(TENANT_ID) instanceof String value && !value.isBlank()
                ? value
                : DEFAULT_TENANT;
        List<String> roles = readRoles(session.getAttribute(ROLES));
        return new Identity(userId, tenantId, roles);
    }

    private List<String> readRoles(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of("USER");
        }
        List<String> roles = values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(role -> !role.isBlank())
                .distinct()
                .toList();
        return roles.isEmpty() ? List.of("USER") : roles;
    }

    public record Identity(String userId, String tenantId, List<String> roles) {
        public Identity {
            roles = List.copyOf(roles);
        }
    }
}
