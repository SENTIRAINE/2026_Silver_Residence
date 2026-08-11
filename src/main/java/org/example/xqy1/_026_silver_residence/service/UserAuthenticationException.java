package org.example.xqy1._026_silver_residence.service;

public class UserAuthenticationException extends RuntimeException {

    public UserAuthenticationException() {
        super("用户名或密码不正确");
    }
}
