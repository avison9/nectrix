project_id = "nectrix-dev"
region     = "europe-west1"
zone       = "europe-west1-b"

machine_type      = "e2-standard-4" # 4 vCPU / 16GB — ~$120-130/mo all-in, see infra/terraform/gcp-dev/README.md
boot_disk_size_gb = 100

domain        = "nectrix.app"
dev_subdomain = "dev"
