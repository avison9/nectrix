package com.nectrix.coreapp.crypto.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Registered via {@code @EnableConfigurationProperties(EnvelopeEncryptionProperties.class)} on
 * {@link CryptoConfig} (Boot's documented preference over {@code @Component
 * + @ConfigurationProperties}) — see application.yml's {@code nectrix.envelope-encryption.kms.*}
 * block.
 *
 * @param region standard AWS region, e.g. "us-east-1" — required even against LocalStack (the SDK
 *     validates it's non-null, though LocalStack itself ignores the value).
 * @param endpointOverride nullable — set only for LocalStack (local dev/CI, docker-compose.yml);
 *     real AWS KMS resolves its endpoint from {@code region} alone. When set, credentials also
 *     switch to LocalStack's dummy static "test"/"test" pair instead of the SDK's normal
 *     credential-provider chain (see AwsEnvelopeKmsClient).
 */
@ConfigurationProperties(prefix = "nectrix.envelope-encryption.kms")
public record EnvelopeEncryptionProperties(String region, String endpointOverride) {}
