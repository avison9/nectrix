package com.nectrix.coreapp.bootstrap.archival;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ArchivalStorageProperties.class, ArchivalJobProperties.class})
public class ArchivalConfig {}
