package com.nectrix.coreapp.trading.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TradingProperties.class)
public class TradingConfig {}
