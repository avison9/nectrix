package com.nectrix.coreapp.admin.service;

import com.nectrix.coreapp.admin.config.AdminProperties;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Shells out to the local {@code docker} CLI ({@code docker restart/stop/start <container>}) —
 * only ever valid because core-app itself runs directly on the dev host in local dev (not
 * containerized), so it can see and control sibling containers via the host's own Docker daemon. A
 * real k8s deployment would need a k8s client + RBAC instead (deferred follow-up ticket, see
 * ServiceControlClient's own Javadoc) — this exact mechanism would NOT work if core-app itself ran
 * inside a container/pod.
 *
 * <p>{@code @ConditionalOnProperty} (default false, same convention as {@code TokenRefreshJob}/
 * {@code AccountSnapshotSchedulerJob}): this bean doesn't even exist unless explicitly opted into,
 * so no environment accidentally exposes real container-restart capability just by being up.
 *
 * <p>Array-form {@link ProcessBuilder} exec throughout — never a shell string built by
 * concatenation — and the container name is always looked up from {@code
 * nectrix.admin.service-control.containers} via a fixed {@link ServiceId} enum value, never a raw
 * caller-supplied string, so there is no injection surface regardless.
 */
@Service
@ConditionalOnProperty(
    prefix = "nectrix.admin.service-control",
    name = "enabled",
    havingValue = "true")
public class DockerServiceControlClient implements ServiceControlClient {

  private static final int COMMAND_TIMEOUT_SECONDS = 30;

  private final Map<String, String> containers;

  public DockerServiceControlClient(AdminProperties props) {
    this.containers = props.serviceControl().containers();
  }

  @Override
  public void restart(ServiceId serviceId) {
    runDocker(serviceId, "restart");
  }

  @Override
  public void stop(ServiceId serviceId) {
    runDocker(serviceId, "stop");
  }

  @Override
  public void start(ServiceId serviceId) {
    runDocker(serviceId, "start");
  }

  private void runDocker(ServiceId serviceId, String action) {
    String containerName = containers.get(serviceId.configKey());
    if (containerName == null || containerName.isBlank()) {
      throw new ServiceControlException(
          "no container configured for serviceId " + serviceId.configKey());
    }

    try {
      Process process =
          new ProcessBuilder("docker", action, containerName)
              .redirectErrorStream(true)
              .start();
      boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        throw new ServiceControlException(
            "docker " + action + " " + containerName + " timed out after "
                + COMMAND_TIMEOUT_SECONDS + "s");
      }
      if (process.exitValue() != 0) {
        String output = new String(process.getInputStream().readAllBytes());
        throw new ServiceControlException(
            "docker " + action + " " + containerName + " exited " + process.exitValue() + ": "
                + output.strip());
      }
    } catch (IOException e) {
      throw new ServiceControlException("docker " + action + " " + containerName + " failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ServiceControlException(
          "docker " + action + " " + containerName + " interrupted", e);
    }
  }
}
