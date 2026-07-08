package com.nectrix.coreapp.bootstrap;

import static net.logstash.logback.argument.StructuredArguments.kv;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {
  private static final Logger log = LoggerFactory.getLogger(HelloController.class);

  @GetMapping("/hello")
  public String hello() {
    // TICKET-010 AC3 — a deliberately-logged fake "secret" field, proving
    // logback-spring.xml's MaskingJsonGeneratorDecorator redacts allow-listed
    // sensitive field names at the logging-library level rather than
    // relying on every call site to remember not to log this kind of value.
    log.info("hello endpoint hit", kv("secret", "fake-secret-should-be-redacted"));
    return "nectrix core-app: hello";
  }
}
