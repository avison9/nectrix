variable "domain" {
  description = "Apex domain, e.g. nectrix.dev"
  type        = string
}

variable "dev_subdomain" {
  description = "Delegated subtree this zone owns, e.g. \"dev\""
  type        = string
}

variable "static_ip" {
  type = string
}

variable "subdomain_hosts" {
  description = "Short hostnames (no domain suffix) to A-record at static_ip, e.g. [\"app\", \"portal\", \"api\", \"kafka-ui\", \"minio\"]"
  type        = list(string)
}
