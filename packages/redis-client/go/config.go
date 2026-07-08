// Package redisclient is TICKET-008's shared, cluster-aware Redis client library — the Go
// counterpart to packages/redis-client/java, used by both core-app (Java side) and copy-engine
// (this side).
package redisclient

import (
	"os"
	"strconv"
	"strings"
)

// Mode is an explicit config switch, not auto-detection — go-redis's ClusterClient doesn't work
// transparently against a plain non-cluster-enabled node (CLUSTER SLOTS returns an empty slot
// table there, and routing then fails hard for every subsequent command). Local/CI Redis
// (docker-compose.yml) is always ModeStandalone; real cloud deployments use ModeCluster.
type Mode string

const (
	ModeStandalone Mode = "standalone"
	ModeCluster    Mode = "cluster"
)

// Config configures New. ClusterNodes is only consulted when Mode == ModeCluster.
type Config struct {
	Mode         Mode
	Host         string
	Port         int
	ClusterNodes []string // "host:port" pairs
	Password     string   // empty = no AUTH attempted (matches local/CI Redis, no requirepass)
}

// ConfigFromEnv reads REDIS_MODE (standalone|cluster, default standalone), REDIS_HOST/REDIS_PORT
// (defaults localhost/6379 — same convention as every other service's env-var-driven host/port),
// REDIS_CLUSTER_NODES (comma-separated host:port pairs, cluster mode only), and REDIS_PASSWORD.
func ConfigFromEnv() Config {
	mode := ModeStandalone
	if strings.EqualFold(envOr("REDIS_MODE", "standalone"), "cluster") {
		mode = ModeCluster
	}
	port, _ := strconv.Atoi(envOr("REDIS_PORT", "6379"))
	nodesRaw := envOr("REDIS_CLUSTER_NODES", "")
	var nodes []string
	if nodesRaw != "" {
		nodes = strings.Split(nodesRaw, ",")
	}
	return Config{
		Mode:         mode,
		Host:         envOr("REDIS_HOST", "localhost"),
		Port:         port,
		ClusterNodes: nodes,
		Password:     os.Getenv("REDIS_PASSWORD"),
	}
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
