package com.datn.grading.exception;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.*;
@RestControllerAdvice public class GlobalExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class) public ProblemDetail business(ResponseStatusException e){return ProblemDetail.forStatusAndDetail(e.getStatusCode(),e.getReason());}
    @ExceptionHandler(feign.FeignException.class) public ProblemDetail upstream(feign.FeignException e){int s=e.status();return ProblemDetail.forStatusAndDetail(s>=400 && s<500?HttpStatus.valueOf(s):HttpStatus.SERVICE_UNAVAILABLE,"Required service rejected request or unavailable");}
}
