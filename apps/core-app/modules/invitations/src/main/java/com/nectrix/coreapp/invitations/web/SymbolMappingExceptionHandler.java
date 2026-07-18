package com.nectrix.coreapp.invitations.web;

import com.nectrix.coreapp.invitations.service.SymbolMappingNotFoundException;
import com.nectrix.coreapp.invitations.service.UnresolvedBrokerSymbolException;
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

  @ExceptionHandler(UnresolvedBrokerSymbolException.class)
  public ResponseEntity<ErrorBody> handleUnresolvedBrokerSymbol() {
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .body(new ErrorBody("unresolved_broker_symbol"));
  }

  public record ErrorBody(String error) {}
}
