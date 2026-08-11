package org.example.xqy1._026_silver_residence.service.Impl;

import org.example.xqy1._026_silver_residence.dao.UserRepository;
import org.example.xqy1._026_silver_residence.pojo.User;
import org.example.xqy1._026_silver_residence.service.UserAuthenticationException;
import org.example.xqy1._026_silver_residence.service.UserRegistrationConflictException;
import org.example.xqy1._026_silver_residence.service.UserService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.regex.Pattern;

@Service
public class UserServiceImpl implements UserService {
    private static final Pattern LEGACY_MD5 = Pattern.compile("^[0-9a-fA-F]{32}$");
    private static final Pattern BCRYPT = Pattern.compile("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void register(User user) {
        User existUser = userRepository.findByUsername(user.getUsername());
        if (existUser != null) {
            throw new UserRegistrationConflictException("用户名已存在，请更换！");
        }

        existUser = userRepository.findByEmail(user.getEmail());
        if (existUser != null) {
            throw new UserRegistrationConflictException("邮箱已被注册，请更换！");
        }

        try {
            user.setPassword(passwordEncoder.encode(user.getPassword()));
            user.setCreateTime(new Date());
            user.setUpdateTime(new Date());
            userRepository.save(user);
        } catch (DuplicateKeyException exception) {
            throw new UserRegistrationConflictException("用户名或邮箱已被使用，请更换！");
        } catch (Exception e) {
            throw new RuntimeException("未知错误");
        }
    }

    @Override
    public User login(User user) {
        String username = user.getUsername();
        String password = user.getPassword();

        User existUser = userRepository.findByUsername(username);
        if (existUser == null) {
            throw new UserAuthenticationException();
        }

        String storedPassword = existUser.getPassword();
        boolean legacyPassword = storedPassword != null && LEGACY_MD5.matcher(storedPassword).matches();
        boolean matches = passwordMatches(password, storedPassword, legacyPassword);
        if (!matches) {
            throw new UserAuthenticationException();
        }

        if (legacyPassword) {
            existUser.setPassword(passwordEncoder.encode(password));
            existUser.setUpdateTime(new Date());
            userRepository.save(existUser);
        }

        return withoutPassword(existUser);
    }

    private boolean passwordMatches(String rawPassword, String storedPassword, boolean legacyPassword) {
        if (storedPassword == null) {
            return false;
        }
        if (legacyPassword) {
            return DigestUtils.md5DigestAsHex(rawPassword.getBytes(StandardCharsets.UTF_8))
                    .equalsIgnoreCase(storedPassword);
        }
        if (!BCRYPT.matcher(storedPassword).matches()) {
            return false;
        }
        try {
            return passwordEncoder.matches(rawPassword, storedPassword);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private User withoutPassword(User source) {
        return new User(
                source.getId(),
                source.getUsername(),
                null,
                source.getEmail(),
                source.getPhone(),
                source.getAddress(),
                source.getCreateTime(),
                source.getUpdateTime()
        );
    }
}
