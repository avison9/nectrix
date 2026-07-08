package redisclient

import (
	"fmt"
	"strconv"

	"github.com/redis/go-redis/v9"
)

// New builds the one client type both Deduper/RateLimiter implementations are written against —
// redis.Cmdable, satisfied by both *redis.Client (standalone) and *redis.ClusterClient (cluster
// mode). This is the one place standalone-vs-cluster branches; every caller downstream is
// topology-agnostic.
func New(cfg Config) (redis.Cmdable, error) {
	if cfg.Mode == ModeCluster {
		if len(cfg.ClusterNodes) == 0 {
			return nil, fmt.Errorf("redisclient: REDIS_CLUSTER_NODES must be set when REDIS_MODE=cluster")
		}
		return redis.NewClusterClient(&redis.ClusterOptions{
			Addrs:    cfg.ClusterNodes,
			Password: cfg.Password,
		}), nil
	}
	return redis.NewClient(&redis.Options{
		Addr:     cfg.Host + ":" + strconv.Itoa(cfg.Port),
		Password: cfg.Password,
	}), nil
}
