package com.nectrix.coreapp.admin.api;

import com.nectrix.coreapp.admin.repository.AdminRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AdminEventIngestApiImpl implements AdminEventIngestApi {

  private final AdminRepository adminRepository;

  public AdminEventIngestApiImpl(AdminRepository adminRepository) {
    this.adminRepository = adminRepository;
  }

  @Override
  public void recordReconciliationDrift(
      UUID brokerAccountId, String driftType, Instant detectedAt) {
    adminRepository.insertReconciliationDrift(brokerAccountId, driftType, detectedAt);
  }
}
