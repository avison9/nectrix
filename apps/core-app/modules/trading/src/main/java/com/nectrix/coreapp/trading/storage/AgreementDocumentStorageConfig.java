package com.nectrix.coreapp.trading.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AgreementDocumentStorageProperties.class)
public class AgreementDocumentStorageConfig {}
