package com.emporia.authorisation.admin;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(basePackageClasses = AdminUserController.class)
class AdminProblemHandler {

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail handleResponseStatus(ResponseStatusException exception) {
        String detail = exception.getReason() != null
                ? exception.getReason()
                : "The admin user request could not be completed";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(exception.getStatusCode(), detail);
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        if (status != null) {
            problem.setTitle(status.getReasonPhrase());
        }
        return problem;
    }
}
