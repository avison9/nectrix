package com.nectrix.coreapp.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// TICKET-112 — @EnableScheduling for analytics' LeaderboardScheduler (@Scheduled), the first
// scheduled job in this codebase.
@SpringBootApplication(scanBasePackages = "com.nectrix.coreapp")
@EnableScheduling
public class CoreAppApplication {
  public static void main(String[] args) {
    SpringApplication.run(CoreAppApplication.class, args);
  }
}
