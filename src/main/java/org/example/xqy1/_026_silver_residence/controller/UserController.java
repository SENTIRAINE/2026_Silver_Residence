package org.example.xqy1._026_silver_residence.controller;


import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.example.xqy1._026_silver_residence.agent.AssistantSessionIdentity;
import org.example.xqy1._026_silver_residence.api.UserLoginRequest;
import org.example.xqy1._026_silver_residence.api.UserRegistrationRequest;
import org.example.xqy1._026_silver_residence.pojo.User;
import org.example.xqy1._026_silver_residence.service.UserService;
import org.example.xqy1._026_silver_residence.util.Result;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/user")
@CrossOrigin(origins = "http://localhost:63343")
public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 注册
     */
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody UserRegistrationRequest request) {
        log.info("用户注册 username={}", request.username());
        User user = new User();
        user.setUsername(request.username().trim());
        user.setPassword(request.password());
        user.setEmail(request.email().trim());
        user.setPhone(request.phone().trim());
        userService.register(user);
        return Result.success();
    }



    /**
     * 登录
     * @return
     */
    @PostMapping("/login")
    public Result<User> login(@Valid @RequestBody UserLoginRequest loginRequest, HttpServletRequest request) {
        log.info("用户登录 username={}", loginRequest.username());

        User user = new User();
        user.setUsername(loginRequest.username().trim());
        user.setPassword(loginRequest.password());
        User users = userService.login(user);
        HttpSession session = request.getSession(true);
        String userId = users.getId() == null || users.getId().isBlank() ? users.getUsername() : users.getId();
        session.setAttribute(AssistantSessionIdentity.USER_ID, userId);
        session.setAttribute(AssistantSessionIdentity.TENANT_ID, AssistantSessionIdentity.DEFAULT_TENANT);
        session.setAttribute(AssistantSessionIdentity.ROLES, List.of("USER"));
        users.setPassword(null);

        return Result.success(users);
    }

    /**
     * 登出
     * @return
     */
    @PostMapping("/logout")
    public Result<String> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return Result.success();
    }


}
