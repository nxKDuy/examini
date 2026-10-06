package com.datn.notification.exception;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.*;
@RestControllerAdvice public class GlobalExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class) public ProblemDetail business(ResponseStatusException e){return ProblemDetail.forStatusAndDetail(e.getStatusCode(),e.getReason());}
}
