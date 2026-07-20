package com.nectrix.coreapp.bootstrap.archival;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * See application.yml's {@code nectrix.archival.*} block — separate record from {@link
 * ArchivalStorageProperties} (both bind to the same {@code nectrix.archival} prefix) since {@code
 * BrokerAccountArchivalJob} needs its own scheduling knobs but not the blob client's
 * bucket/region/endpoint fields, same "one properties record per real consumer" shape {@code
 * InvitationsProperties.TokenRefresh} already establishes.
 *
 * @param staleAfterSeconds how long a broker account must have sat {@code DISCONNECTED} before the
 *     scheduled sweep archives and deletes it (90 days by default).
 */
@ConfigurationProperties(prefix = "nectrix.archival")
public record ArchivalJobProperties(long staleAfterSeconds) {}
