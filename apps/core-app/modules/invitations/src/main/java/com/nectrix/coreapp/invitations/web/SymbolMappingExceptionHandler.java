package com.nectrix.coreapp.invitations.web;

import com.nectrix.coreapp.invitations.service.SymbolMappingNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = SymbolMappingController.class)
public class SymbolMappingExceptionHandler {

  @ExceptionHandler(SymbolMappingNotFoundException.class)
  public ResponseEntity<ErrorBody> handleNotFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ErrorBody("symbol_mapping_not_found"));
  }

  public record ErrorBody(String error) {}
}
