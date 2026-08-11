package org.example.xqy1._026_silver_residence.controller;

import org.example.xqy1._026_silver_residence.agent.AssistantSessionIdentity;
import org.example.xqy1._026_silver_residence.api.UserApiExceptionHandler;
import org.example.xqy1._026_silver_residence.pojo.User;
import org.example.xqy1._026_silver_residence.service.UserAuthenticationException;
import org.example.xqy1._026_silver_residence.service.UserRegistrationConflictException;
import org.example.xqy1._026_silver_residence.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import(UserApiExceptionHandler.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @Test
    void wrongPasswordReturnsUnauthorizedWithoutCreatingSession() throws Exception {
        when(userService.login(any())).thenThrow(new UserAuthenticationException());

        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"existing-user","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("用户名或密码不正确"))
                .andExpect(request().sessionAttribute(AssistantSessionIdentity.USER_ID, nullValue()));
    }

    @Test
    void emptyLoginBodyReturnsBadRequestBeforeServiceCall() throws Exception {
        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").isNotEmpty());

        verifyNoInteractions(userService);
    }

    @Test
    void successfulLoginCreatesAssistantSessionAndNeverReturnsPassword() throws Exception {
        User user = new User();
        user.setId("user-1001");
        user.setUsername("valid-user");
        user.setPassword(null);
        user.setEmail("valid-user@example.test");
        when(userService.login(any())).thenReturn(user);

        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"valid-user","password":"Test2026!"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data.password").value(nullValue()))
                .andExpect(request().sessionAttribute(AssistantSessionIdentity.USER_ID, "user-1001"))
                .andExpect(request().sessionAttribute(AssistantSessionIdentity.TENANT_ID,
                        AssistantSessionIdentity.DEFAULT_TENANT));
    }

    @Test
    void duplicateRegistrationReturnsConflictWithActionableMessage() throws Exception {
        doThrow(new UserRegistrationConflictException("用户名已存在，请更换！"))
                .when(userService).register(any());

        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username":"existing-user",
                                  "password":"Test2026!",
                                  "email":"existing@example.test",
                                  "phone":"13900000000"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("用户名已存在，请更换！"));
    }

    @Test
    void malformedRegistrationFieldsReturnBadRequest() throws Exception {
        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"x","password":"123","email":"bad","phone":"1"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").isNotEmpty());

        verifyNoInteractions(userService);
    }
}
