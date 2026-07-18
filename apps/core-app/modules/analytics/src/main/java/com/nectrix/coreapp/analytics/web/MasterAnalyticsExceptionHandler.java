package com.nectrix.coreapp.analytics.web;

import com.nectrix.coreapp.analytics.service.MasterProfileNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = MasterAnalyticsController.class)
public class MasterAnalyticsExceptionHandler {

  @ExceptionHandler(MasterProfileNotFoundException.class)
  public ResponseEntity<ErrorBody> handleNotFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ErrorBody("master_profile_not_found"));
  }

  public record ErrorBody(String error) {}
}
