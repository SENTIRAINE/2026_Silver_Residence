package org.example.xqy1._026_silver_residence.api;

import org.example.xqy1._026_silver_residence.controller.UserController;
import org.example.xqy1._026_silver_residence.service.UserAuthenticationException;
import org.example.xqy1._026_silver_residence.service.UserRegistrationConflictException;
import org.example.xqy1._026_silver_residence.util.Result;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = UserController.class)
public class UserApiExceptionHandler {

    @ExceptionHandler(UserAuthenticationException.class)
    ResponseEntity<Result<Void>> handleAuthenticationFailure(UserAuthenticationException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.error(exception.getMessage()));
    }

    @ExceptionHandler(UserRegistrationConflictException.class)
    ResponseEntity<Result<Void>> handleRegistrationConflict(UserRegistrationConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Result.error(exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Result<Void>> handleValidationFailure(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("请求参数不正确");
        return ResponseEntity.badRequest().body(Result.error(message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Result<Void>> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(Result.error("请求体不是合法 JSON"));
    }
}
