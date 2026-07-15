# Fill in project_id with the real GCP project ID (the one with the
# $300/3-month credit attached) before running terraform apply.
project_id = "REPLACE_WITH_REAL_PROJECT_ID"
region     = "us-central1"
zone       = "us-central1-a"

machine_type      = "e2-standard-4" # 4 vCPU / 16GB — ~$120-130/mo all-in, see infra/terraform/gcp-dev/README.md
boot_disk_size_gb = 100

domain        = "nectrix.dev"
dev_subdomain = "dev"
