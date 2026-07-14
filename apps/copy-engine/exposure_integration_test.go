//go:build integration

// TICKET-105's real, hands-on verification of AC3/AC4 against a live local
// Postgres (docker-compose.yml) -- mirrors pipeline_integration_test.go's
// own seedFixture/newTestPool/mustExec conventions exactly, reusing
// seedFixture verbatim rather than duplicating fixture setup.
package main

import (
	"context"
	"testing"

	"github.com/avison9/nectrix/copy-engine/internal/moneymgmt"
	"github.com/avison9/nectrix/copy-engine/internal/pipeline"
	domain "github.com/avison9/nectrix/go-domain"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
)

// seedCopiedTrade inserts a copied_trades row (and its required
// trade_signals parent, since trade_signal_id is a NOT NULL FK) directly,
// at a chosen status/volume -- lets these tests construct exactly the
// exposure state they need without going through the real dispatch path
// (which doesn't yet call ComputeLotSize/ApplyRiskGuard -- that's
// TICKET-106's job).
func seedCopiedTrade(t *testing.T, ctx context.Context, pool *pgxpool.Pool, masterAccountID, relationshipID, canonicalSymbol, status string, volumeLots float64) {
	t.Helper()
	signalID := uuid.NewString()
	mustExec(t, ctx, pool, `
		INSERT INTO trade_signals (id, master_broker_account_id, broker_position_id, event_type, canonical_symbol, direction, volume_lots, server_timestamp, raw_payload)
		VALUES ($1,$2,$3,'POSITION_OPENED',$4,'BUY',$5,now(),'{}')`,
		signalID, masterAccountID, "pos-"+uuid.NewString(), canonicalSymbol, volumeLots)
	mustExec(t, ctx, pool, `
		INSERT INTO copied_trades (copy_relationship_id, trade_signal_id, idempotency_key, status, computed_volume_lots, sizing_method_snapshot)
		VALUES ($1,$2,$3,$4,$5,'{}')`,
		relationshipID, signalID, "idem-"+uuid.NewString(), status, volumeLots)
}

// newExposurePipeline builds a *pipeline.Pipeline whose ONLY real dependency
// these tests exercise is its Postgres pool -- deduper/router/fx/kafkaWriter
// are nil since SumOpenVolumeForSymbol/SumOpenVolumeAllSymbols/
// CountOpenPositions touch only p.pool internally.
func newExposurePipeline(pool *pgxpool.Pool) *pipeline.Pipeline {
	return pipeline.New(pool, nil, nil, nil, nil, nil, nil)
}

// TICKET-105 AC4: "A close/partial-close signal is never blocked by any
// risk cap (explicit regression test)." The real, testable mechanism this
// depends on: exposure sums/counts must exclude CLOSED/REJECTED/FAILED
// rows, so a close works by shrinking future exposure via a status
// transition, never by being routed through ApplyRiskGuard's cap-shrinking
// logic at all (see moneymgmt.ApplyRiskGuard's own doc comment).
func TestSumOpenVolumeForSymbol_ExcludesNonOpenStatuses(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	masterAccountID, _, relationshipID := seedFixture(t, ctx, pool)
	relID := uuid.MustParse(relationshipID)

	seedCopiedTrade(t, ctx, pool, masterAccountID, relationshipID, "GBPUSD", "FILLED", 1.0)
	seedCopiedTrade(t, ctx, pool, masterAccountID, relationshipID, "GBPUSD", "PARTIALLY_CLOSED", 0.5)
	seedCopiedTrade(t, ctx, pool, masterAccountID, relationshipID, "GBPUSD", "CLOSED", 2.0)
	seedCopiedTrade(t, ctx, pool, masterAccountID, relationshipID, "GBPUSD", "REJECTED", 3.0)
	seedCopiedTrade(t, ctx, pool, masterAccountID, relationshipID, "GBPUSD", "FAILED", 4.0)

	pl := newExposurePipeline(pool)
	sum, err := pl.SumOpenVolumeForSymbol(ctx, relID, "GBPUSD")
	if err != nil {
		t.Fatalf("SumOpenVolumeForSymbol: %v", err)
	}
	if sum != 1.5 {
		t.Fatalf("SumOpenVolumeForSymbol = %v, want 1.5 (only FILLED+PARTIALLY_CLOSED counted)", sum)
	}
}

func TestSumOpenVolumeAllSymbols_ExcludesNonOpenStatuses(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	masterAccountID, _, relationshipID := seedFixture(t, ctx, pool)
	relID := uuid.MustParse(relationshipID)

	seedCopiedTrade(t, ctx, pool, masterAccountID, relationshipID, "GBPUSD", "FILLED", 1.0)
	seedCopiedTrade(t, ctx, pool, masterAccountID, relationshipID, "USDJPY", "PARTIALLY_CLOSED", 0.5)
	seedCopiedTrade(t, ctx, pool, masterAccountID, relationshipID, "AUDUSD", "CLOSED", 2.0)
	seedCopiedTrade(t, ctx, pool, masterAccountID, relationshipID, "NZDUSD", "REJECTED", 3.0)
	seedCopiedTrade(t, ctx, pool, masterAccountID, relationshipID, "USDCAD", "FAILED", 4.0)

	pl := newExposurePipeline(pool)
	sum, err := pl.SumOpenVolumeAllSymbols(ctx, relID)
	if err != nil {
		t.Fatalf("SumOpenVolumeAllSymbols: %v", err)
	}
	if sum != 1.5 {
		t.Fatalf("SumOpenVolumeAllSymbols = %v, want 1.5 (only FILLED+PARTIALLY_CLOSED counted, across symbols)", sum)
	}
}

func TestCountOpenPositions_ExcludesNonOpenStatuses(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	masterAccountID, _, relationshipID := seedFixture(t, ctx, pool)
	relID := uuid.MustParse(relationshipID)

	seedCopiedTrade(t, ctx, pool, masterAccountID, relationshipID, "GBPUSD", "FILLED", 1.0)
	seedCopiedTrade(t, ctx, pool, masterAccountID, relationshipID, "USDJPY", "PARTIALLY_CLOSED", 0.5)
	seedCopiedTrade(t, ctx, pool, masterAccountID, relationshipID, "AUDUSD", "CLOSED", 2.0)
	seedCopiedTrade(t, ctx, pool, masterAccountID, relationshipID, "NZDUSD", "REJECTED", 3.0)
	seedCopiedTrade(t, ctx, pool, masterAccountID, relationshipID, "USDCAD", "FAILED", 4.0)

	pl := newExposurePipeline(pool)
	count, err := pl.CountOpenPositions(ctx, relID)
	if err != nil {
		t.Fatalf("CountOpenPositions: %v", err)
	}
	if count != 2 {
		t.Fatalf("CountOpenPositions = %d, want 2 (only FILLED+PARTIALLY_CLOSED counted)", count)
	}
}

// TICKET-105 AC3: "max_open_positions cap correctly rejects a new open when
// at the limit, verified against real copied_trades state, not a stale
// cache."
func TestCountOpenPositions_ReflectsRealStateImmediately(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	masterAccountID, _, relationshipID := seedFixture(t, ctx, pool)
	relID := uuid.MustParse(relationshipID)
	pl := newExposurePipeline(pool)

	for i := 1; i <= 3; i++ {
		seedCopiedTrade(t, ctx, pool, masterAccountID, relationshipID, "GBPUSD", "FILLED", 1.0)
		count, err := pl.CountOpenPositions(ctx, relID)
		if err != nil {
			t.Fatalf("CountOpenPositions: %v", err)
		}
		if count != i {
			t.Fatalf("after inserting row %d, CountOpenPositions = %d, want %d (must reflect real state immediately, no stale cache)", i, count, i)
		}
	}
}

func TestApplyRiskGuard_MaxOpenPositions_RejectsAtLimit_AgainstRealCopiedTradesState(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	masterAccountID, _, relationshipID := seedFixture(t, ctx, pool)
	relID := uuid.MustParse(relationshipID)
	pl := newExposurePipeline(pool)

	const maxOpenPositions = 3
	for i := 0; i < maxOpenPositions; i++ {
		seedCopiedTrade(t, ctx, pool, masterAccountID, relationshipID, "GBPUSD", "FILLED", 1.0)
	}

	realCount, err := pl.CountOpenPositions(ctx, relID)
	if err != nil {
		t.Fatalf("CountOpenPositions: %v", err)
	}
	if realCount != maxOpenPositions {
		t.Fatalf("seeded %d open positions, CountOpenPositions returned %d", maxOpenPositions, realCount)
	}

	maxOpen := maxOpenPositions
	profile := moneymgmt.RiskProfile{MaxOpenPositions: &maxOpen}
	result := moneymgmt.ApplyRiskGuard(1.0, profile, domain.SymbolSpec{LotStep: 0.01, MinLot: 0.01, MaxLot: 50}, moneymgmt.Exposure{
		OpenPositionCount: realCount,
	})
	if !result.Rejected {
		t.Fatalf("expected rejection at max_open_positions limit (real count=%d), got Volume=%v", realCount, result.Volume)
	}
	if result.RejectReason != moneymgmt.RejectMaxOpenPositionsReached {
		t.Fatalf("RejectReason = %q, want %q", result.RejectReason, moneymgmt.RejectMaxOpenPositionsReached)
	}
}
