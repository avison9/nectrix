module github.com/avison9/nectrix/redis-client/go

go 1.26

require (
	github.com/avison9/nectrix/go-domain v0.0.0
	github.com/redis/go-redis/v9 v9.21.0
)

require (
	github.com/cespare/xxhash/v2 v2.3.0 // indirect
	go.uber.org/atomic v1.11.0 // indirect
)

// Resolved locally via go.work in workspace mode (matches
// packages/event-contracts/go's identical go-domain usage).
replace github.com/avison9/nectrix/go-domain => ../../go-domain
