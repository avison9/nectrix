package com.nectrix.events.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.util.JsonFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Parses the fixture shared with the Go round-trip test
 * (packages/event-contracts/testdata/sample_trade_event.json) and asserts
 * the decoded fields, proving the Java-generated type can consume the
 * canonical wire format. The Go test in packages/event-contracts/go parses
 * the identical file and asserts the same values, proving both generated
 * languages agree on structure without needing a live cross-process round
 * trip.
 */
class RoundTripTest {

  @Test
  void parsesSharedFixture() throws Exception {
    String json =
        Files.readString(Path.of("../testdata/sample_trade_event.json"));

    NormalizedTradeEvent.Builder builder = NormalizedTradeEvent.newBuilder();
    JsonFormat.parser().merge(json, builder);
    NormalizedTradeEvent evt = builder.build();

    assertEquals("evt_01HXAMPLE0000000000000001", evt.getEventId());
    assertEquals("bacc_master_0001", evt.getMasterBrokerAccountId());
    assertEquals(TradeEventType.TRADE_EVENT_TYPE_POSITION_OPENED, evt.getEventType());

    NormalizedPosition pos = evt.getPosition();
    assertEquals("pos_0001", pos.getBrokerPositionId());
    assertEquals("EURUSD", pos.getSymbol().getCanonicalCode());
    assertEquals(AssetClass.ASSET_CLASS_FX, pos.getSymbol().getAssetClass());
    assertEquals(TradeDirection.TRADE_DIRECTION_BUY, pos.getDirection());
    assertEquals(1.5, pos.getVolumeLots());
    assertTrue(pos.hasCurrentSlPrice());
    assertEquals(1.08, pos.getCurrentSlPrice());

    // Fields absent from the fixture must decode as "not present", not zero-as-present.
    assertFalse(evt.hasClosedVolumeLots());
  }

  /**
   * TICKET-007 AC3 — same cross-language fixture-parsing proof as {@link #parsesSharedFixture()},
   * for one of the new topic message types (not just NormalizedTradeEvent), confirming the new
   * proto schemas generate correctly in Java too.
   */
  @Test
  void parsesSharedBrokerConnectionEventFixture() throws Exception {
    String json =
        Files.readString(Path.of("../testdata/sample_broker_connection_event.json"));

    BrokerConnectionEvent.Builder builder = BrokerConnectionEvent.newBuilder();
    JsonFormat.parser().merge(json, builder);
    BrokerConnectionEvent event = builder.build();

    assertEquals("evt_01HXAMPLE0000000000000002", event.getEnvelope().getEventId());
    assertEquals("v1", event.getEnvelope().getSchemaVersion());
    assertEquals("bacc_master_0001", event.getBrokerAccountId());
    assertEquals(BrokerConnectionEventType.BROKER_CONNECTION_EVENT_TYPE_DEGRADED, event.getEventType());
    assertTrue(event.hasDetail());
    assertEquals("3 consecutive heartbeat timeouts", event.getDetail());
  }
}
