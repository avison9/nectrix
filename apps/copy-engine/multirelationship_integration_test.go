//go:build integration

// Bugfix regression test — a real bug caught live during a Master↔multi-Follower
// dispatch investigation: processSignalForAllRelationships used to return on the
// FIRST relationship's dispatch error, aborting the whole batch and starving every
// OTHER relationship of that same Master's signal. matchRelationships' own query has
// no ORDER BY, so which relationship goes "first" is arbitrary — meaning a completely
// unrelated Follower's disconnected broker account could silently block copies to
// every other Follower of the same Master.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/avison9/nectrix/copy-engine/internal/stubadapter"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
)

func TestDispatch_OneRelationshipFailure_DoesNotBlockOtherRelationships(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	deduper := newTestDeduper(t)
	topic := createDedicatedTopic(t)
	writer := newWriter(topic)
	t.Cleanup(func() { writer.Close() })

	masterAccountID, _, brokenRelationshipID := seedDispatchFixture(t, ctx, pool, "CTRADER", "CTRADER", "SAME")
	healthyFollowerAccountID, healthyRelationshipID := seedSecondFollowerForSameMaster(t, ctx, pool, masterAccountID)

	fake := newFakeBrokerService()
	fake.setSnapshot(masterAccountID, testAccountSnapshot(masterAccountID))
	// Deliberately never registered: the broken relationship's own follower account
	// snapshot 404s, exactly like a real "no connected handle for broker account" response.
	fake.setSnapshot(healthyFollowerAccountID, testAccountSnapshot(healthyFollowerAccountID))
	router := buildDispatchRouter(t, fake)
	server := buildDispatchTestServer(t, ctx, pool, deduper, writer, router, masterAccountID)

	brokerPositionID := "multi-rel-" + uuid.NewString()
	serverTimestamp := time.Now().UTC().Format(time.RFC3339)
	// Deliberately not using postInjectWithOpenPrice: HandleEvent now returns a
	// real (joined) error whenever ANY relationship fails, which the inject
	// endpoint surfaces as a 500 -- expected here, since the broken
	// relationship's own snapshot call genuinely 404s. That must not be
	// confused with "nothing was dispatched", which is what this test disproves.
	injectAllowingError(t, server, brokerPositionID, serverTimestamp, 1.0, 1.2000)

	assertTradeSignalRowCount(t, ctx, pool, masterAccountID, brokerPositionID, 1)

	if got := fake.placeOrderCallCount(healthyFollowerAccountID); got != 1 {
		t.Fatalf("PlaceOrder called %d times for the healthy relationship's follower, want exactly 1 -- "+
			"the broken relationship's failure must not have blocked this one", got)
	}

	var healthyStatus string
	err := pool.QueryRow(ctx, `SELECT status FROM copied_trades WHERE copy_relationship_id = $1`, healthyRelationshipID).Scan(&healthyStatus)
	if err != nil {
		t.Fatalf("query copied_trades for healthy relationship: %v", err)
	}
	if healthyStatus != "FILLED" {
		t.Fatalf("healthy relationship's copied_trades.status = %q, want FILLED", healthyStatus)
	}

	var brokenCount int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM copied_trades WHERE copy_relationship_id = $1`, brokenRelationshipID).Scan(&brokenCount); err != nil {
		t.Fatalf("query copied_trades for broken relationship: %v", err)
	}
	if brokenCount != 0 {
		t.Fatalf("broken relationship's copied_trades count = %d, want 0 (its own snapshot call genuinely failed, no row should exist)", brokenCount)
	}
}

// injectAllowingError is postInjectWithOpenPrice minus the 200-or-fatal assertion --
// this test deliberately provokes a real (partial) failure, which the inject
// endpoint correctly surfaces as a 500; the test's own assertions are what actually
// prove the fix, not the HTTP status of this call.
func injectAllowingError(t *testing.T, server *httptest.Server, brokerPositionID, serverTimestamp string, volumeLots, openPrice float64) {
	t.Helper()
	body, err := json.Marshal(stubadapter.InjectEventParams{
		BrokerPositionID: brokerPositionID,
		ServerTimestamp:  serverTimestamp,
		VolumeLots:       volumeLots,
		OpenPrice:        openPrice,
	})
	if err != nil {
		t.Fatalf("marshal inject params: %v", err)
	}
	resp, err := http.Post(server.URL+"/test/inject-trade-event", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatalf("POST inject: %v", err)
	}
	defer resp.Body.Close()
}

// seedSecondFollowerForSameMaster adds a SECOND, independent Follower/relationship
// pointing at the SAME already-seeded Master account -- proving dispatch fans out
// across every relationship for a Master, not just the first one matched. Mirrors
// seedDispatchFixture's own follower-side rows exactly, minus re-seeding the Master.
func seedSecondFollowerForSameMaster(t *testing.T, ctx context.Context, pool *pgxpool.Pool, masterAccountID string) (followerAccountID, relationshipID string) {
	t.Helper()
	suffix := uuid.NewString()[:8]

	var masterProfileID string
	if err := pool.QueryRow(ctx, `SELECT id FROM master_profiles WHERE primary_broker_account_id = $1`, masterAccountID).Scan(&masterProfileID); err != nil {
		t.Fatalf("look up master_profiles for %s: %v", masterAccountID, err)
	}

	adminUserID := uuid.NewString()
	followerUserID := uuid.NewString()
	mustExec(t, ctx, pool, `INSERT INTO users (id, email, display_name, status) VALUES ($1,$2,$3,'ACTIVE')`,
		adminUserID, "multi-rel-admin-"+suffix+"@test.nectrix.dev", "Test Admin")
	mustExec(t, ctx, pool, `INSERT INTO users (id, email, display_name, status, created_by_user_id) VALUES ($1,$2,$3,'ACTIVE',$4)`,
		followerUserID, "multi-rel-follower-"+suffix+"@test.nectrix.dev", "Test Follower", adminUserID)

	followerAccountID = uuid.NewString()
	mustExec(t, ctx, pool, `
		INSERT INTO broker_accounts (id, user_id, broker_type, broker_account_login, is_demo, currency, connection_role, credentials_ciphertext, credentials_key_version, connection_status)
		VALUES ($1,$2,'CTRADER',$3,TRUE,'USD','FOLLOWER_ONLY',$4,1,'CONNECTED')`,
		followerAccountID, followerUserID, "multi-rel-follower-"+suffix, []byte{0})

	mmProfileID := uuid.NewString()
	riskProfileID := uuid.NewString()
	mustExec(t, ctx, pool, `INSERT INTO money_management_profiles (id, method, multiplier, rounding_mode) VALUES ($1,'MULTIPLIER',1.0,'DOWN')`, mmProfileID)
	mustExec(t, ctx, pool, `INSERT INTO risk_profiles (id, max_lot_per_trade, max_open_positions, max_slippage_pips) VALUES ($1,5.0,20,5)`, riskProfileID)

	followRequestID := uuid.NewString()
	mustExec(t, ctx, pool, `
		INSERT INTO follow_requests (id, follower_user_id, master_profile_id, follower_broker_account_id, proposed_money_management_profile_id, proposed_risk_profile_id, status, decided_by_user_id, decided_at)
		VALUES ($1,$2,$3,$4,$5,$6,'APPROVED',$7,now())`,
		followRequestID, followerUserID, masterProfileID, followerAccountID, mmProfileID, riskProfileID, adminUserID)

	relationshipID = uuid.NewString()
	mustExec(t, ctx, pool, `
		INSERT INTO copy_relationships (id, master_profile_id, master_broker_account_id, follower_user_id, follower_broker_account_id, money_management_profile_id, risk_profile_id, status, copy_direction, performance_fee_percent, fee_collection_method, originating_follow_request_id)
		VALUES ($1,$2,$3,$4,$5,$6,$7,'ACTIVE','SAME',20.00,'BROKER_PARTNERSHIP',$8)`,
		relationshipID, masterProfileID, masterAccountID, followerUserID, followerAccountID, mmProfileID, riskProfileID, followRequestID)

	mustExec(t, ctx, pool, `
		INSERT INTO symbol_mappings (broker_account_id, canonical_symbol, broker_symbol_name, contract_size, lot_step, min_lot, max_lot, pip_size, digits, margin_currency, is_confirmed)
		VALUES ($1,'EURUSD','EURUSD',100000,0.01,0.01,100,0.0001,5,'USD',TRUE)`,
		followerAccountID)

	return followerAccountID, relationshipID
}
