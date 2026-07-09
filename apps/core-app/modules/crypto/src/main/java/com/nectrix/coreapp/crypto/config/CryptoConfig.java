package com.nectrix.coreapp.crypto.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(EnvelopeEncryptionProperties.class)
public class CryptoConfig {}
