package com.nectrix.coreapp.admin.service;

/**
 * Engine Control page's restart/stop/start capability — {@link DockerServiceControlClient} is the
 * only implementation today (local dev, core-app running directly on the host); a
 * KubernetesServiceControlClient implementing this same interface is a deferred follow-up ticket
 * once a real, persistent cluster exists to test one against (see this feature's own plan doc).
 */
public interface ServiceControlClient {

  /**
   * The fixed set of engines this page can act on — {@code configKey} matches
   * nectrix.admin.service-control.containers' own map keys in application.yml. A fixed enum (rather
   * than accepting a raw serviceId string from the request) means there is no path from an HTTP
   * request body to an unvalidated container name ever reaching a shell-out call.
   */
  enum ServiceId {
    BROKER_ADAPTERS("broker-adapters"),
    COPY_ENGINE("copy-engine"),
    MT5_BRIDGE_GATEWAY("mt5-bridge-gateway"),
    MT_TERMINAL_HOST("mt-terminal-host");

    private final String configKey;

    ServiceId(String configKey) {
      this.configKey = configKey;
    }

    public String configKey() {
      return configKey;
    }

    /**
     * Used by AdminController to turn the {@code {serviceId}} path variable into a fixed enum
     * value.
     */
    public static ServiceId fromConfigKey(String configKey) {
      for (ServiceId id : values()) {
        if (id.configKey.equals(configKey)) {
          return id;
        }
      }
      throw new IllegalArgumentException("unknown serviceId " + configKey);
    }
  }

  void restart(ServiceId serviceId);

  void stop(ServiceId serviceId);

  void start(ServiceId serviceId);

  /** Thrown when the underlying control action couldn't be carried out at all. */
  class ServiceControlException extends RuntimeException {
    public ServiceControlException(String message) {
      super(message);
    }

    public ServiceControlException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
