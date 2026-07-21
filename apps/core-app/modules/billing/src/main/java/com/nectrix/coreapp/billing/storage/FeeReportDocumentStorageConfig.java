package com.nectrix.coreapp.billing.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FeeReportDocumentStorageProperties.class)
public class FeeReportDocumentStorageConfig {}
