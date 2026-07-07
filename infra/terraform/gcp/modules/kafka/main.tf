# TICKET-007 — Google Cloud's Managed Service for Apache Kafka (GA since late
# 2024). Same asymmetry vs. AWS's module as ../memorystore-redis has vs.
# ../../aws/modules/elasticache-redis: no security-group-equivalent resource
# here — access is scoped by the subnet/VPC firewall rules, not a resource
# referencing the GKE cluster's identity directly.

# Topics are deliberately NOT managed here (no google_managed_kafka_topic
# resources), even though that resource exists — AWS's MSK has no
# Terraform-level topic-management resource at all (topics are managed via
# the standard Kafka protocol against the real bootstrap brokers, same as
# any Kafka cluster), so creating topics here would mean two different
# topic-creation mechanisms across clouds, drifting from each other and from
# infra/kafka/create-topics.sh (today's local/CI mechanism). One mechanism,
# not three: a real deployed cluster's topics get created by pointing that
# same script's kafka-topics.sh invocation at the real bootstrap-broker
# endpoint instead of the local docker-compose broker.

resource "google_managed_kafka_cluster" "this" {
  cluster_id = "${var.name_prefix}-kafka"
  location   = var.region

  capacity_config {
    vcpu_count   = var.vcpu_count
    memory_bytes = var.memory_bytes
  }

  gcp_config {
    access_config {
      network_configs {
        subnet = var.subnetwork_self_link
      }
    }
  }
}
