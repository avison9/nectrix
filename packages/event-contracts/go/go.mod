module github.com/avison9/nectrix/event-contracts/go

go 1.26

require (
	github.com/avison9/nectrix/go-domain v0.0.0
	github.com/avison9/nectrix/redis-client/go v0.0.0
	github.com/google/uuid v1.6.0
	github.com/redis/go-redis/v9 v9.21.0
	github.com/segmentio/kafka-go v0.4.51
	google.golang.org/protobuf v1.36.11
)

require (
	github.com/cespare/xxhash/v2 v2.3.0 // indirect
	github.com/klauspost/compress v1.15.9 // indirect
	github.com/pierrec/lz4/v4 v4.1.15 // indirect
	go.uber.org/atomic v1.11.0 // indirect
)

// Resolved locally via go.work in workspace mode (matches apps/copy-engine's
// identical go-domain usage, which likewise carries no explicit require) —
// this line documents the dependency for readers/tools that don't evaluate
// go.work, but the actual resolution never hits the network/module cache.
replace github.com/avison9/nectrix/go-domain => ../../go-domain

replace github.com/avison9/nectrix/redis-client/go => ../../redis-client/go
