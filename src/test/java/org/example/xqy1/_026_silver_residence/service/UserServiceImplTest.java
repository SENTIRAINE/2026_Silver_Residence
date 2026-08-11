package org.example.xqy1._026_silver_residence.service;

import org.example.xqy1._026_silver_residence.dao.UserRepository;
import org.example.xqy1._026_silver_residence.pojo.User;
import org.example.xqy1._026_silver_residence.service.Impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {
    private static final String RAW_PASSWORD = "Test2026!";

    @Mock
    private UserRepository userRepository;

    private PasswordEncoder passwordEncoder;
    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder(4);
        userService = new UserServiceImpl(userRepository, passwordEncoder);
    }

    @Test
    void registrationStoresBcryptInsteadOfRawPasswordOrMd5() {
        User user = user("new-user", RAW_PASSWORD);

        userService.register(user);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        String stored = captor.getValue().getPassword();
        assertTrue(stored.startsWith("$2"));
        assertTrue(passwordEncoder.matches(RAW_PASSWORD, stored));
    }

    @Test
    void wrongPasswordUsesControlledAuthenticationFailure() {
        User stored = user("existing-user", passwordEncoder.encode(RAW_PASSWORD));
        when(userRepository.findByUsername("existing-user")).thenReturn(stored);

        assertThrows(UserAuthenticationException.class,
                () -> userService.login(user("existing-user", "wrong-password")));
        verify(userRepository, never()).save(any());
    }

    @Test
    void legacyMd5PasswordIsUpgradedAfterSuccessfulLogin() {
        String legacyHash = DigestUtils.md5DigestAsHex(RAW_PASSWORD.getBytes(StandardCharsets.UTF_8));
        User stored = user("legacy-user", legacyHash);
        when(userRepository.findByUsername("legacy-user")).thenReturn(stored);

        User result = userService.login(user("legacy-user", RAW_PASSWORD));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertTrue(passwordEncoder.matches(RAW_PASSWORD, captor.getValue().getPassword()));
        assertNull(result.getPassword());
    }

    @Test
    void malformedStoredPasswordUsesControlledAuthenticationFailure() {
        User stored = user("broken-user", "not-a-supported-password-hash");
        when(userRepository.findByUsername("broken-user")).thenReturn(stored);

        assertThrows(UserAuthenticationException.class,
                () -> userService.login(user("broken-user", RAW_PASSWORD)));
        verify(userRepository, never()).save(any());
    }

    private User user(String username, String password) {
        User user = new User();
        user.setId("user-id");
        user.setUsername(username);
        user.setPassword(password);
        user.setEmail(username + "@example.test");
        user.setPhone("13900000000");
        return user;
    }
}
