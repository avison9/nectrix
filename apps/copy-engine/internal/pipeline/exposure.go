package pipeline

import (
	"context"
	"fmt"

	"github.com/google/uuid"
)

// "Open" for exposure purposes means FILLED or PARTIALLY_CLOSED -- the two
// copied_trades.status values representing a still-live broker position.
// PENDING/SUBMITTED are deliberately excluded: no code path produces them
// yet (dispatch.go's doDispatchOrder only ever writes FILLED/REJECTED/
// FAILED today), and an unconfirmed order hasn't consumed real broker-side
// exposure. TICKET-106/107 must revisit if/when those statuses become
// reachable with genuine broker-side commitment.
const openStatusesSQL = `('FILLED','PARTIALLY_CLOSED')`

// SumOpenVolumeForSymbol is docs/09 §9.4's current_symbol_exposure: total
// open volume for THIS copy relationship on one canonical symbol. Exported
// (unlike hasConfirmedSymbolMapping) because this ticket's own integration
// test calls it directly from package main to reuse pipeline_integration_test.go's
// seedFixture verbatim; TICKET-106 will call it from dispatch.go regardless.
//
// copied_trades has no symbol column of its own -- the join to
// trade_signals is required to filter by canonical_symbol.
func (p *Pipeline) SumOpenVolumeForSymbol(ctx context.Context, copyRelationshipID uuid.UUID, canonicalSymbol string) (float64, error) {
	var sum float64
	err := p.pool.QueryRow(ctx, `
		SELECT COALESCE(SUM(ct.computed_volume_lots), 0)::float8
		FROM copied_trades ct
		JOIN trade_signals ts ON ts.id = ct.trade_signal_id
		WHERE ct.copy_relationship_id = $1
		  AND ts.canonical_symbol = $2
		  AND ct.status IN `+openStatusesSQL,
		copyRelationshipID, canonicalSymbol).Scan(&sum)
	if err != nil {
		return 0, fmt.Errorf("sum open volume for symbol: %w", err)
	}
	return sum, nil
}

// SumOpenVolumeAllSymbols is docs/09 §9.4's current_total_exposure: total
// open volume for this copy relationship across all symbols.
func (p *Pipeline) SumOpenVolumeAllSymbols(ctx context.Context, copyRelationshipID uuid.UUID) (float64, error) {
	var sum float64
	err := p.pool.QueryRow(ctx, `
		SELECT COALESCE(SUM(computed_volume_lots), 0)::float8
		FROM copied_trades
		WHERE copy_relationship_id = $1 AND status IN `+openStatusesSQL,
		copyRelationshipID).Scan(&sum)
	if err != nil {
		return 0, fmt.Errorf("sum open volume all symbols: %w", err)
	}
	return sum, nil
}

// CountOpenPositions is docs/09 §9.4's open_position_count.
func (p *Pipeline) CountOpenPositions(ctx context.Context, copyRelationshipID uuid.UUID) (int, error) {
	var count int
	err := p.pool.QueryRow(ctx, `
		SELECT COUNT(*) FROM copied_trades
		WHERE copy_relationship_id = $1 AND status IN `+openStatusesSQL,
		copyRelationshipID).Scan(&count)
	if err != nil {
		return 0, fmt.Errorf("count open positions: %w", err)
	}
	return count, nil
}
