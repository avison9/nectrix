# Fronts the gateway/admin-portal Ingresses (docs/16-deployment-architecture.md
# §16.1). The Global HTTPS Load Balancer itself is GKE-Ingress-managed (created
# from a Kubernetes Ingress object with `kubernetes.io/ingress.class: gce` —
# see deploy/components/cloud-gcp), not created directly here. Cloud Armor
# attaches to that LB via a BackendConfig CRD referencing this policy's name,
# not via a Terraform-side association resource (GCP has no equivalent of AWS's
# wafv2_web_acl_association for this).
resource "google_compute_security_policy" "this" {
  name        = "${var.name_prefix}-armor-policy"
  description = "Cloud Armor policy in front of the gateway (API/BFF) and admin-portal ingresses"

  rule {
    action   = "allow"
    priority = "2147483647"

    match {
      versioned_expr = "SRC_IPS_V1"
      config {
        src_ip_ranges = ["*"]
      }
    }

    description = "Default allow rule"
  }

  rule {
    action   = "deny(403)"
    priority = "1000"

    match {
      expr {
        expression = "evaluatePreconfiguredExpr('sqli-stable')"
      }
    }

    description = "Block SQL injection (OWASP preconfigured rule)"
  }

  rule {
    action   = "deny(403)"
    priority = "1001"

    match {
      expr {
        expression = "evaluatePreconfiguredExpr('xss-stable')"
      }
    }

    description = "Block cross-site scripting (OWASP preconfigured rule)"
  }

  rule {
    action   = "deny(403)"
    priority = "1002"

    match {
      expr {
        expression = "evaluatePreconfiguredExpr('cve-canary')"
      }
    }

    description = "Block CVE-2021-44228 (Log4Shell) probes (preconfigured rule)"
  }
}
