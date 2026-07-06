//go:build integration

// Proves the CI integration-test stage's ephemeral Postgres/Redis/Kafka
// (started via `docker compose up -d --wait`) are actually reachable.
// Deliberately stdlib-only — no pgx/redis/kafka client dependency exists yet
// (those land with TICKET-004/007/008), so this only asserts TCP
// reachability, not protocol-level correctness. Run explicitly via:
//
//	go test -tags=integration ./...
package main

import (
	"net"
	"testing"
	"time"
)

const dialTimeout = 5 * time.Second

func TestPostgresIsReachable(t *testing.T) {
	dial(t, "localhost:5432")
}

func TestRedisIsReachable(t *testing.T) {
	dial(t, "localhost:6379")
}

func TestKafkaIsReachable(t *testing.T) {
	dial(t, "localhost:9092")
}

func dial(t *testing.T, addr string) {
	t.Helper()
	conn, err := net.DialTimeout("tcp", addr, dialTimeout)
	if err != nil {
		t.Fatalf("dialing %s: %v", addr, err)
	}
	defer conn.Close()
}
