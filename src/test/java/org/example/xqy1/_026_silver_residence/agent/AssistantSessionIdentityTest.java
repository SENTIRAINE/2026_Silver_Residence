package org.example.xqy1._026_silver_residence.agent;

import org.example.xqy1._026_silver_residence.api.MapContractException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssistantSessionIdentityTest {
    private final AssistantSessionIdentity resolver = new AssistantSessionIdentity();

    @Test
    void rejectsAnonymousRequests() {
        MapContractException exception = assertThrows(
                MapContractException.class,
                () -> resolver.require(new MockHttpServletRequest())
        );

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
        assertEquals("AUTHENTICATION_REQUIRED", exception.getCode());
    }

    @Test
    void readsIdentityOnlyFromServerSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute(AssistantSessionIdentity.USER_ID, "user-1001");
        request.getSession().setAttribute(AssistantSessionIdentity.TENANT_ID, "tenant-a");
        request.getSession().setAttribute(AssistantSessionIdentity.ROLES, List.of("USER", "ANALYST"));

        AssistantSessionIdentity.Identity identity = resolver.require(request);

        assertEquals("user-1001", identity.userId());
        assertEquals("tenant-a", identity.tenantId());
        assertEquals(List.of("USER", "ANALYST"), identity.roles());
    }
}
