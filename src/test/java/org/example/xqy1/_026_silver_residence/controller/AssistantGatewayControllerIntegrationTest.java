package org.example.xqy1._026_silver_residence.controller;

import org.example.xqy1._026_silver_residence.agent.AssistantSessionIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "agent.langgraph.enabled=false",
        "housing.search.snapshot-prewarm-enabled=false"
})
@AutoConfigureMockMvc
class AssistantGatewayControllerIntegrationTest {
    private static final String REQUEST = """
            {
              "conversationId": "d00f61d6-1713-4458-af0b-86027a58b032",
              "messageId": "7ec1cff4-6705-401c-b36a-692b5f9173a7",
              "query": "筛选中山区道路",
              "context": {
                "locale": "zh-CN",
                "map": {"visibleLayerIds": [3], "zoom": 13, "extent": null},
                "businessObjectIds": []
              }
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsAnonymousAgentRequests() throws Exception {
        mockMvc.perform(post("/api/assistant/runs/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(REQUEST))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: preflight.failed")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("AUTHENTICATION_REQUIRED")));
    }

    @Test
    void authenticatedRequestFailsClosedWhenLangGraphIsDisabled() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AssistantSessionIdentity.USER_ID, "user-1001");
        session.setAttribute(AssistantSessionIdentity.TENANT_ID, "tenant-default");
        session.setAttribute(AssistantSessionIdentity.ROLES, List.of("USER"));

        mockMvc.perform(post("/api/assistant/runs/stream")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(REQUEST))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: preflight.failed")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("AGENT_NOT_CONFIGURED")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"retryable\":true")));
    }

    @Test
    void replayEndpointUsesSsePreflightFailureForAnonymousUsers() throws Exception {
        mockMvc.perform(get("/api/assistant/runs/13546154-1f31-4091-b7ce-a890b39d16bf/events")
                        .queryParam("afterSequence", "2")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: preflight.failed")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("AUTHENTICATION_REQUIRED")));
    }
}
