//go:build integration

// TICKET-009's real, hands-on verification of AC2/AC3/AC4 against a live
// local Postgres/Redis/Kafka (docker-compose.yml). Each test seeds its own
// fixture rows (fresh random UUIDs, mirroring apps/core-app/db's
// 014-seed-dev-data.sql shape) rather than depending on `make db-seed-dev`'s
// context:dev changesets — CI's `:db:update` step deliberately does not
// apply those, so these tests must be self-contained/hermetic.
package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/avison9/nectrix/copy-engine/internal/httpapi"
	"github.com/avison9/nectrix/copy-engine/internal/moneymgmt"
	"github.com/avison9/nectrix/copy-engine/internal/pipeline"
	"github.com/avison9/nectrix/copy-engine/internal/remoteadapter"
	"github.com/avison9/nectrix/copy-engine/internal/stubadapter"
	eventsv1 "github.com/avison9/nectrix/event-contracts/go/gen/nectrix/events/v1"
	domain "github.com/avison9/nectrix/go-domain"
	redisclient "github.com/avison9/nectrix/redis-client/go"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/segmentio/kafka-go"
	"google.golang.org/protobuf/proto"
)

// AC2: "Triggering a synthetic event via the stub adapter's test endpoint
// results in an observable trade_signals row in Postgres and a published
// copied-trades.opened-shaped event on the message bus."
func TestAC2_InjectSyntheticEvent_ObservableTradeSignalAndPublishedEvent(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	deduper := newTestDeduper(t)
	topic := createDedicatedTopic(t)
	writer := newWriter(topic)
	t.Cleanup(func() { writer.Close() })

	masterAccountID, followerAccountID, relationshipID := seedFixture(t, ctx, pool)
	server := buildTestServer(t, ctx, pool, deduper, writer, stubadapter.New(), masterAccountID, followerAccountID)

	brokerPositionID := "ac2-pos-" + uuid.NewString()
	serverTimestamp := time.Now().UTC().Format(time.RFC3339)
	postInject(t, server, brokerPositionID, serverTimestamp, 2.5)

	assertTradeSignalRowCount(t, ctx, pool, masterAccountID, brokerPositionID, 1)

	reader := newReader(topic, "test-group-"+uuid.NewString())
	defer reader.Close()
	event := readCopiedTradeEvent(t, reader)

	if event.GetCopyRelationshipId() != relationshipID {
		t.Fatalf("copy_relationship_id = %q, want %q", event.GetCopyRelationshipId(), relationshipID)
	}
	if event.GetEventType() != eventsv1.CopiedTradeEventType_COPIED_TRADE_EVENT_TYPE_OPENED {
		t.Fatalf("event_type = %v, want OPENED", event.GetEventType())
	}
	if event.GetVolumeLots() != 2.5 {
		t.Fatalf("volume_lots = %v, want 2.5", event.GetVolumeLots())
	}
}

// AC3: "Submitting the identical synthetic event twice in rapid succession
// results in exactly one trade_signals row" — proven here under genuine
// concurrency (N goroutines racing the identical event), not just
// sequentially, mirroring TICKET-008's own race-condition test discipline.
func TestAC3_ConcurrentIdenticalInjection_ExactlyOneTradeSignalRow(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	deduper := newTestDeduper(t)
	topic := createDedicatedTopic(t)
	writer := newWriter(topic)
	t.Cleanup(func() { writer.Close() })

	masterAccountID, followerAccountID, _ := seedFixture(t, ctx, pool)
	server := buildTestServer(t, ctx, pool, deduper, writer, stubadapter.New(), masterAccountID, followerAccountID)

	brokerPositionID := "ac3-pos-" + uuid.NewString()
	serverTimestamp := time.Now().UTC().Format(time.RFC3339)
	body := injectBody(t, brokerPositionID, serverTimestamp, 1.0)

	const concurrency = 20
	var ready sync.WaitGroup
	ready.Add(concurrency)
	start := make(chan struct{})
	var wg sync.WaitGroup
	wg.Add(concurrency)
	for i := 0; i < concurrency; i++ {
		go func() {
			defer wg.Done()
			ready.Done()
			<-start
			resp, err := http.Post(server.URL+"/test/inject-trade-event", "application/json", strings.NewReader(string(body)))
			if err != nil {
				t.Errorf("POST inject: %v", err)
				return
			}
			resp.Body.Close()
		}()
	}
	ready.Wait()
	close(start)
	wg.Wait()

	assertTradeSignalRowCount(t, ctx, pool, masterAccountID, brokerPositionID, 1)
}

// AC4: "A second stub adapter instance (e.g., StubBrokerAdapterVariant) can
// be swapped in without changing any Copy Engine pipeline code." Both
// subtests call the exact same buildTestServer/pipeline.New/httpapi.NewMux
// code path, differing only in which concrete domain.BrokerAdapter is
// passed in.
func TestAC4_SwappingStubAdapterVariant_PipelineCodeUnchanged(t *testing.T) {
	t.Run("StubBrokerAdapter", func(t *testing.T) {
		runAC4Case(t, stubadapter.New())
	})
	t.Run("StubBrokerAdapterVariant", func(t *testing.T) {
		runAC4Case(t, stubadapter.NewVariant())
	})
}

func runAC4Case(t *testing.T, adapter domain.BrokerAdapter) {
	ctx := context.Background()
	pool := newTestPool(t)
	deduper := newTestDeduper(t)
	topic := createDedicatedTopic(t)
	writer := newWriter(topic)
	t.Cleanup(func() { writer.Close() })

	masterAccountID, followerAccountID, relationshipID := seedFixture(t, ctx, pool)
	// TICKET-106: dispatch.go now reads broker_accounts.broker_type to pick
	// which remoteadapter.RemoteAdapter to route through -- seedFixture
	// always seeds 'CTRADER' (matching every OTHER test's actual adapter),
	// so the StubBrokerAdapterVariant sub-test (BrokerType MT5) needs its
	// fixture rows updated to match the adapter it's actually exercising,
	// or routing would look for MT5 in a router keyed by whatever
	// buildTestServer registers for adapter.BrokerType() while Postgres
	// still says CTRADER.
	mustExec(t, ctx, pool, `UPDATE broker_accounts SET broker_type = $1 WHERE id IN ($2, $3)`,
		string(adapter.BrokerType()), masterAccountID, followerAccountID)
	server := buildTestServer(t, ctx, pool, deduper, writer, adapter, masterAccountID, followerAccountID)

	brokerPositionID := "ac4-pos-" + uuid.NewString()
	serverTimestamp := time.Now().UTC().Format(time.RFC3339)
	postInject(t, server, brokerPositionID, serverTimestamp, 1.5)

	assertTradeSignalRowCount(t, ctx, pool, masterAccountID, brokerPositionID, 1)

	reader := newReader(topic, "test-group-"+uuid.NewString())
	defer reader.Close()
	event := readCopiedTradeEvent(t, reader)
	if event.GetCopyRelationshipId() != relationshipID {
		t.Fatalf("copy_relationship_id = %q, want %q", event.GetCopyRelationshipId(), relationshipID)
	}
}

// TICKET-103: "A trade signal for a symbol with no confirmed mapping on the
// follower account results in a copied_trades row with status=FAILED,
// reject_reason='UNMAPPED_SYMBOL' and a corresponding follower
// notification" (the CopiedTradeEvent FAILED publish -- see
// dispatch.go's publishCopiedTradeFailed doc comment for why that publish
// itself is the real, testable deliverable here). Deletes seedFixture's
// own auto-inserted confirmed EURUSD mapping (required by every other test
// in this file) to reconstruct the genuinely-unmapped case.
func TestAC_UnmappedSymbol_SkipsPlaceOrderAndRecordsFailedCopiedTrade(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	deduper := newTestDeduper(t)
	topic := createDedicatedTopic(t)
	writer := newWriter(topic)
	t.Cleanup(func() { writer.Close() })

	masterAccountID, followerAccountID, relationshipID := seedFixture(t, ctx, pool)
	mustExec(t, ctx, pool, `DELETE FROM symbol_mappings WHERE broker_account_id = $1 AND canonical_symbol = 'EURUSD'`, followerAccountID)

	adapter := stubadapter.New()
	server := buildTestServer(t, ctx, pool, deduper, writer, adapter, masterAccountID, followerAccountID)

	brokerPositionID := "ac-unmapped-pos-" + uuid.NewString()
	serverTimestamp := time.Now().UTC().Format(time.RFC3339)
	postInject(t, server, brokerPositionID, serverTimestamp, 1.0)

	assertTradeSignalRowCount(t, ctx, pool, masterAccountID, brokerPositionID, 1)

	// The real, authoritative proof: no PlaceOrder call ever reached the
	// adapter -- if the unmapped-symbol check were missing/broken, the
	// stub adapter would have recorded a real (wrongly) filled order.
	if got := adapter.PlaceOrderCallCount(); got != 0 {
		t.Fatalf("PlaceOrder was called %d times, want 0 (symbol is unmapped, must never be attempted)", got)
	}

	var status, rejectReason string
	err := pool.QueryRow(ctx,
		`SELECT status, reject_reason FROM copied_trades WHERE copy_relationship_id = $1`,
		relationshipID).Scan(&status, &rejectReason)
	if err != nil {
		t.Fatalf("query copied_trades: %v", err)
	}
	if status != "FAILED" {
		t.Fatalf("copied_trades.status = %q, want FAILED", status)
	}
	if rejectReason != "UNMAPPED_SYMBOL" {
		t.Fatalf("copied_trades.reject_reason = %q, want UNMAPPED_SYMBOL", rejectReason)
	}

	reader := newReader(topic, "test-group-"+uuid.NewString())
	defer reader.Close()
	event := readCopiedTradeEvent(t, reader)
	if event.GetCopyRelationshipId() != relationshipID {
		t.Fatalf("copy_relationship_id = %q, want %q", event.GetCopyRelationshipId(), relationshipID)
	}
	if event.GetEventType() != eventsv1.CopiedTradeEventType_COPIED_TRADE_EVENT_TYPE_FAILED {
		t.Fatalf("event_type = %v, want FAILED", event.GetEventType())
	}
	if event.GetRejectReason() != "UNMAPPED_SYMBOL" {
		t.Fatalf("reject_reason = %q, want UNMAPPED_SYMBOL", event.GetRejectReason())
	}
}

// ---- shared test wiring ----

func buildTestServer(t *testing.T, ctx context.Context, pool *pgxpool.Pool, deduper domain.Deduper, kafkaWriter *kafka.Writer, adapter domain.BrokerAdapter, masterAccountID, followerAccountID string) *httptest.Server {
	t.Helper()

	masterHandle, err := adapter.Connect(ctx, domain.BrokerCredentials{BrokerType: adapter.BrokerType(), AccountID: masterAccountID})
	if err != nil {
		t.Fatalf("connect master handle: %v", err)
	}
	followerHandle, err := adapter.Connect(ctx, domain.BrokerCredentials{BrokerType: adapter.BrokerType(), AccountID: followerAccountID})
	if err != nil {
		t.Fatalf("connect follower handle: %v", err)
	}

	// TICKET-106: dispatch.go now routes every GetAccountSnapshot/PlaceOrder
	// call through a remoteadapter.Router -- LocalAdapter wraps this exact
	// adapter/handle pair as a RemoteAdapter, so these tests keep running
	// the EXACT SAME dispatch.go code the real HTTP path runs, no network
	// hop involved (both master and follower are on the same stub adapter/
	// BrokerType in every fixture here).
	local := remoteadapter.NewLocalAdapter(adapter, map[string]domain.ConnectionHandle{
		masterAccountID:   masterHandle,
		followerAccountID: followerHandle,
	})
	router := remoteadapter.NewRouter(map[domain.BrokerType]remoteadapter.RemoteAdapter{
		adapter.BrokerType(): local,
	})
	pl := pipeline.New(pool, deduper, router, moneymgmt.NewFrankfurterClient(nil, nil), kafkaWriter, nil, nil)

	sub, err := adapter.StreamTradeEvents(ctx, masterHandle, pl.HandleEvent)
	if err != nil {
		t.Fatalf("stream trade events: %v", err)
	}
	t.Cleanup(func() { sub.Close() })

	mux := httpapi.NewMux("copy-engine-test", adapter, masterHandle)
	server := httptest.NewServer(mux)
	t.Cleanup(server.Close)
	return server
}

func newTestPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	pool, err := pgxpool.New(context.Background(), postgresDSN())
	if err != nil {
		t.Fatalf("postgres pool: %v", err)
	}
	t.Cleanup(pool.Close)
	return pool
}

func newTestDeduper(t *testing.T) domain.Deduper {
	t.Helper()
	client, err := redisclient.New(redisclient.ConfigFromEnv())
	if err != nil {
		t.Fatalf("redis client: %v", err)
	}
	return redisclient.NewDeduper(client, dedupTTL)
}

// seedFixture inserts one FK-satisfying, ACTIVE copy_relationships fixture
// (user/broker_accounts/master_profiles/money_management_profiles/
// risk_profiles/follow_requests/copy_relationships), mirroring
// 014-seed-dev-data.sql's shape but with fresh random UUIDs so tests never
// depend on (or collide with) dev-seed data or each other.
func seedFixture(t *testing.T, ctx context.Context, pool *pgxpool.Pool) (masterAccountID, followerAccountID, relationshipID string) {
	t.Helper()
	suffix := uuid.NewString()[:8]

	adminUserID := uuid.NewString()
	masterUserID := uuid.NewString()
	followerUserID := uuid.NewString()
	mustExec(t, ctx, pool, `INSERT INTO users (id, email, display_name, status) VALUES ($1,$2,$3,'ACTIVE')`,
		adminUserID, "admin-"+suffix+"@test.nectrix.dev", "Test Admin")
	mustExec(t, ctx, pool, `INSERT INTO users (id, email, display_name, status, created_by_user_id) VALUES ($1,$2,$3,'ACTIVE',$4)`,
		masterUserID, "master-"+suffix+"@test.nectrix.dev", "Test Master", adminUserID)
	mustExec(t, ctx, pool, `INSERT INTO users (id, email, display_name, status, created_by_user_id) VALUES ($1,$2,$3,'ACTIVE',$4)`,
		followerUserID, "follower-"+suffix+"@test.nectrix.dev", "Test Follower", adminUserID)

	masterAccountID = uuid.NewString()
	followerAccountID = uuid.NewString()
	mustExec(t, ctx, pool, `
		INSERT INTO broker_accounts (id, user_id, broker_type, broker_account_login, is_demo, currency, connection_role, credentials_ciphertext, credentials_key_version, connection_status)
		VALUES ($1,$2,'CTRADER',$3,TRUE,'USD','MASTER_ONLY',$4,1,'CONNECTED')`,
		masterAccountID, masterUserID, "test-master-"+suffix, []byte{0})
	mustExec(t, ctx, pool, `
		INSERT INTO broker_accounts (id, user_id, broker_type, broker_account_login, is_demo, currency, connection_role, credentials_ciphertext, credentials_key_version, connection_status)
		VALUES ($1,$2,'CTRADER',$3,TRUE,'USD','FOLLOWER_ONLY',$4,1,'CONNECTED')`,
		followerAccountID, followerUserID, "test-follower-"+suffix, []byte{0})

	masterProfileID := uuid.NewString()
	mustExec(t, ctx, pool, `
		INSERT INTO master_profiles (id, user_id, primary_broker_account_id, display_name, is_public)
		VALUES ($1,$2,$3,'Test Master',TRUE)`,
		masterProfileID, masterUserID, masterAccountID)

	mmProfileID := uuid.NewString()
	riskProfileID := uuid.NewString()
	mustExec(t, ctx, pool, `INSERT INTO money_management_profiles (id, method, multiplier, rounding_mode) VALUES ($1,'MULTIPLIER',1.0,'DOWN')`, mmProfileID)
	mustExec(t, ctx, pool, `INSERT INTO risk_profiles (id, max_lot_per_trade, max_open_positions, max_slippage_pips) VALUES ($1,5.0,20,5)`, riskProfileID)

	followRequestID := uuid.NewString()
	mustExec(t, ctx, pool, `
		INSERT INTO follow_requests (id, follower_user_id, master_profile_id, follower_broker_account_id, proposed_money_management_profile_id, proposed_risk_profile_id, status, decided_by_user_id, decided_at)
		VALUES ($1,$2,$3,$4,$5,$6,'APPROVED',$7,now())`,
		followRequestID, followerUserID, masterProfileID, followerAccountID, mmProfileID, riskProfileID, masterUserID)

	relationshipID = uuid.NewString()
	mustExec(t, ctx, pool, `
		INSERT INTO copy_relationships (id, master_profile_id, master_broker_account_id, follower_user_id, follower_broker_account_id, money_management_profile_id, risk_profile_id, status, performance_fee_percent, fee_collection_method, originating_follow_request_id)
		VALUES ($1,$2,$3,$4,$5,$6,$7,'ACTIVE',20.00,'BROKER_PARTNERSHIP',$8)`,
		relationshipID, masterProfileID, masterAccountID, followerUserID, followerAccountID, mmProfileID, riskProfileID, followRequestID)

	// TICKET-103: the dispatcher gates every dispatch on a CONFIRMED
	// symbol_mappings row for the follower account — required, not just
	// additive, since every existing test here injects a synthetic event
	// via stubadapter.InjectEventParams, whose Symbol defaults to "EURUSD"
	// (see internal/stubadapter/inject.go's buildEvent) when unset, as it
	// always is here. Without this row, every AC test above would now dead-
	// end at the new UNMAPPED_SYMBOL skip instead of reaching PlaceOrder.
	//
	// TICKET-106: the master's own account now needs an equally CONFIRMED
	// mapping too — its pip_size/contract_size are direct inputs to §9.6's
	// SL/TP translation and to RISK_PERCENT sizing, and an unconfirmed
	// auto-suggestion is exactly the "confidently wrong guess" TICKET-103's
	// confirmation gate exists to prevent, on whichever side reads it. This
	// is a genuine, real onboarding gap this ticket surfaces: masters were
	// never previously required to confirm their own mappings.
	mustExec(t, ctx, pool, `
		INSERT INTO symbol_mappings (broker_account_id, canonical_symbol, broker_symbol_name, contract_size, lot_step, min_lot, max_lot, pip_size, digits, margin_currency, is_confirmed)
		VALUES ($1,'EURUSD','EURUSD',100000,0.01,0.01,100,0.0001,5,'USD',TRUE)`,
		followerAccountID)
	mustExec(t, ctx, pool, `
		INSERT INTO symbol_mappings (broker_account_id, canonical_symbol, broker_symbol_name, contract_size, lot_step, min_lot, max_lot, pip_size, digits, margin_currency, is_confirmed)
		VALUES ($1,'EURUSD','EURUSD',100000,0.01,0.01,100,0.0001,5,'USD',TRUE)`,
		masterAccountID)

	return masterAccountID, followerAccountID, relationshipID
}

func mustExec(t *testing.T, ctx context.Context, pool *pgxpool.Pool, sql string, args ...any) {
	t.Helper()
	if _, err := pool.Exec(ctx, sql, args...); err != nil {
		t.Fatalf("exec %q: %v", sql, err)
	}
}

func assertTradeSignalRowCount(t *testing.T, ctx context.Context, pool *pgxpool.Pool, masterAccountID, brokerPositionID string, want int) {
	t.Helper()
	var count int
	err := pool.QueryRow(ctx,
		`SELECT count(*) FROM trade_signals WHERE master_broker_account_id = $1 AND broker_position_id = $2`,
		masterAccountID, brokerPositionID).Scan(&count)
	if err != nil {
		t.Fatalf("query trade_signals: %v", err)
	}
	if count != want {
		t.Fatalf("trade_signals row count = %d, want %d", count, want)
	}
}

func injectBody(t *testing.T, brokerPositionID, serverTimestamp string, volumeLots float64) []byte {
	t.Helper()
	b, err := json.Marshal(stubadapter.InjectEventParams{
		BrokerPositionID: brokerPositionID,
		ServerTimestamp:  serverTimestamp,
		VolumeLots:       volumeLots,
	})
	if err != nil {
		t.Fatalf("marshal inject params: %v", err)
	}
	return b
}

func postInject(t *testing.T, server *httptest.Server, brokerPositionID, serverTimestamp string, volumeLots float64) {
	t.Helper()
	body := injectBody(t, brokerPositionID, serverTimestamp, volumeLots)
	resp, err := http.Post(server.URL+"/test/inject-trade-event", "application/json", strings.NewReader(string(body)))
	if err != nil {
		t.Fatalf("POST inject: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(resp.Body)
		t.Fatalf("POST inject: status %d body %s", resp.StatusCode, b)
	}
}

// readCopiedTradeEvent skips any message that doesn't unmarshal into a
// CopiedTradeEvent with a real copy_relationship_id — specifically the
// readiness-probe sentinel waitForProduceReady writes to the same topic
// before the test proper starts (see there for why that probe exists).
func readCopiedTradeEvent(t *testing.T, reader *kafka.Reader) *eventsv1.CopiedTradeEvent {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	for {
		msg, err := reader.ReadMessage(ctx)
		if err != nil {
			t.Fatalf("read copied-trades topic: %v", err)
		}
		var event eventsv1.CopiedTradeEvent
		if err := proto.Unmarshal(msg.Value, &event); err != nil || event.GetCopyRelationshipId() == "" {
			continue
		}
		return &event
	}
}

// ---- Kafka test-topic helpers (mirror packages/event-contracts/go/eventconsumer's own) ----

func kafkaAddr() string {
	return envOr("KAFKA_HOST", "localhost") + ":" + envOr("KAFKA_PORT", "9092")
}

func newWriter(topic string) *kafka.Writer {
	return &kafka.Writer{
		Addr:     kafka.TCP(kafkaAddr()),
		Topic:    topic,
		Balancer: &kafka.Hash{},
		// waitForTopic only proves the topic's leader is dialable — the
		// Writer's own first metadata fetch for a just-created topic can
		// still race that propagation and see "Unknown Topic Or Partition"
		// (observed directly: intermittent under back-to-back subtests each
		// creating their own dedicated topic). More attempts/backoff than
		// the kafka-go default (3 attempts, 1s max) rides out that race
		// instead of failing the test on a topic-creation timing fluke.
		MaxAttempts:     10,
		WriteBackoffMin: 100 * time.Millisecond,
		WriteBackoffMax: 1 * time.Second,
	}
}

func newReader(topic, groupID string) *kafka.Reader {
	return kafka.NewReader(kafka.ReaderConfig{
		Brokers:     []string{kafkaAddr()},
		Topic:       topic,
		GroupID:     groupID,
		StartOffset: kafka.FirstOffset,
	})
}

func createDedicatedTopic(t *testing.T) string {
	t.Helper()
	topic := "test-copy-engine-pipeline-" + uuid.NewString()
	conn, err := kafka.Dial("tcp", kafkaAddr())
	if err != nil {
		t.Fatalf("dial kafka: %v", err)
	}
	defer conn.Close()

	if err := conn.CreateTopics(kafka.TopicConfig{Topic: topic, NumPartitions: 1, ReplicationFactor: 1}); err != nil {
		t.Fatalf("create topic: %v", err)
	}
	waitForTopic(t, topic)
	waitForProduceReady(t, topic)

	t.Cleanup(func() {
		conn, err := kafka.Dial("tcp", kafkaAddr())
		if err != nil {
			return
		}
		defer conn.Close()
		_ = conn.DeleteTopics(topic)
	})

	return topic
}

func waitForTopic(t *testing.T, topic string) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		conn, err := kafka.DialLeader(context.Background(), "tcp", kafkaAddr(), topic, 0)
		if err == nil {
			conn.Close()
			return
		}
		time.Sleep(200 * time.Millisecond)
	}
	t.Fatalf("topic %q did not become available within 10s", topic)
}

// waitForProduceReady confirms a real produce actually succeeds before
// returning. waitForTopic (DialLeader) alone isn't sufficient in practice --
// observed directly, intermittently, a fresh *kafka.Writer's own first
// metadata fetch for a just-created topic can still race that propagation
// and fail with "Unknown Topic Or Partition" even after DialLeader
// succeeds. A fresh *kafka.Writer per attempt sidesteps any internal
// metadata-cache staleness left over from a prior failed attempt. The
// throwaway probe message this writes is why readCopiedTradeEvent skips
// messages that don't parse as a real CopiedTradeEvent.
func waitForProduceReady(t *testing.T, topic string) {
	t.Helper()
	deadline := time.Now().Add(15 * time.Second)
	var lastErr error
	for time.Now().Before(deadline) {
		w := &kafka.Writer{Addr: kafka.TCP(kafkaAddr()), Topic: topic, Balancer: &kafka.Hash{}}
		lastErr = w.WriteMessages(context.Background(), kafka.Message{Key: []byte("readiness-probe"), Value: []byte("ok")})
		w.Close()
		if lastErr == nil {
			return
		}
		time.Sleep(300 * time.Millisecond)
	}
	t.Fatalf("topic %q not produce-ready within 15s: %v", topic, lastErr)
}
