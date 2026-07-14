package pipeline

import (
	"testing"

	domain "github.com/avison9/nectrix/go-domain"
)

func TestApplyCopyDirection_SAME_PreservesMasterDirection(t *testing.T) {
	if got := applyCopyDirection(domain.TradeDirectionBuy, "SAME"); got != domain.TradeDirectionBuy {
		t.Fatalf("applyCopyDirection(BUY, SAME) = %v, want BUY", got)
	}
	if got := applyCopyDirection(domain.TradeDirectionSell, "SAME"); got != domain.TradeDirectionSell {
		t.Fatalf("applyCopyDirection(SELL, SAME) = %v, want SELL", got)
	}
}

func TestApplyCopyDirection_REVERSE_FlipsMasterDirection(t *testing.T) {
	if got := applyCopyDirection(domain.TradeDirectionBuy, "REVERSE"); got != domain.TradeDirectionSell {
		t.Fatalf("applyCopyDirection(BUY, REVERSE) = %v, want SELL", got)
	}
	if got := applyCopyDirection(domain.TradeDirectionSell, "REVERSE"); got != domain.TradeDirectionBuy {
		t.Fatalf("applyCopyDirection(SELL, REVERSE) = %v, want BUY", got)
	}
}

func TestSignOf(t *testing.T) {
	if signOf(domain.TradeDirectionBuy) != 1 {
		t.Fatalf("signOf(BUY) != 1")
	}
	if signOf(domain.TradeDirectionSell) != -1 {
		t.Fatalf("signOf(SELL) != -1")
	}
}

func TestTranslateSlTp_NilMasterPrice_ReturnsNil(t *testing.T) {
	got := translateSlTp(nil, 1.1000, 1.1000, domain.TradeDirectionBuy, domain.TradeDirectionBuy, 0.0001, 0.0001, "SL")
	if got != nil {
		t.Fatalf("expected nil for nil masterPrice, got %v", *got)
	}
}

func TestTranslateSlTp_ZeroPipSize_ReturnsNil(t *testing.T) {
	price := 1.0950
	if got := translateSlTp(&price, 1.1000, 1.1000, domain.TradeDirectionBuy, domain.TradeDirectionBuy, 0, 0.0001, "SL"); got != nil {
		t.Fatalf("expected nil for zero masterPipSize, got %v", *got)
	}
	if got := translateSlTp(&price, 1.1000, 1.1000, domain.TradeDirectionBuy, domain.TradeDirectionBuy, 0.0001, 0, "SL"); got != nil {
		t.Fatalf("expected nil for zero followerPipSize, got %v", *got)
	}
}

// docs/09 §9.6: SAME-direction copy, identical pip size on both sides -- the
// follower's translated SL/TP must land at the exact same price as the
// master's, since the pip distance and direction are unchanged.
func TestTranslateSlTp_SAME_IdenticalPipSize_BuyPosition(t *testing.T) {
	masterOpen := 1.1000
	masterSL := 1.0950 // 50 pips below open, correct for a BUY stop-loss
	masterTP := 1.1050 // 50 pips above open, correct for a BUY take-profit

	gotSL := translateSlTp(&masterSL, masterOpen, masterOpen, domain.TradeDirectionBuy, domain.TradeDirectionBuy, 0.0001, 0.0001, "SL")
	gotTP := translateSlTp(&masterTP, masterOpen, masterOpen, domain.TradeDirectionBuy, domain.TradeDirectionBuy, 0.0001, 0.0001, "TP")

	if gotSL == nil || !floatsClose(*gotSL, 1.0950) {
		t.Fatalf("SL = %v, want 1.0950", gotSL)
	}
	if gotTP == nil || !floatsClose(*gotTP, 1.1050) {
		t.Fatalf("TP = %v, want 1.1050", gotTP)
	}
}

// SAME-direction copy but the follower trades at a different pip size (e.g.
// a cross-broker relationship) -- the pip DISTANCE (50 pips) must be
// preserved, translated into the follower's own pip size, not the master's
// raw price distance.
func TestTranslateSlTp_SAME_DifferentPipSize_ScalesDistance(t *testing.T) {
	masterOpen := 1.1000
	masterSL := 1.0950 // 50 pips at masterPipSize=0.0001

	got := translateSlTp(&masterSL, masterOpen, masterOpen, domain.TradeDirectionBuy, domain.TradeDirectionBuy, 0.0001, 0.01, "SL")
	// followerOpenPrice (masterOpen, the OPEN-path approximation) - followerSign*(50 pips * 0.01) = 1.1000 - 0.5 = 0.6000
	want := 0.6000
	if got == nil || !floatsClose(*got, want) {
		t.Fatalf("SL = %v, want %v", got, want)
	}
}

// REVERSE relationship, master BUY -> follower SELL: the follower's SL must
// land ABOVE the (approximated) open price and its TP BELOW it, the correct
// sides for a short position -- not simply mirroring the master's own
// above/below placement.
func TestTranslateSlTp_REVERSE_BuyMasterSellFollower_FlipsSide(t *testing.T) {
	masterOpen := 1.1000
	masterSL := 1.0950 // 50 pips below open (correct for master's BUY)
	masterTP := 1.1050 // 50 pips above open (correct for master's BUY)

	followerDirection := applyCopyDirection(domain.TradeDirectionBuy, "REVERSE")
	if followerDirection != domain.TradeDirectionSell {
		t.Fatalf("test setup: followerDirection = %v, want SELL", followerDirection)
	}

	gotSL := translateSlTp(&masterSL, masterOpen, masterOpen, domain.TradeDirectionBuy, followerDirection, 0.0001, 0.0001, "SL")
	gotTP := translateSlTp(&masterTP, masterOpen, masterOpen, domain.TradeDirectionBuy, followerDirection, 0.0001, 0.0001, "TP")

	// Follower is SELL: SL must be ABOVE its open price, TP must be BELOW it.
	if gotSL == nil || !floatsClose(*gotSL, 1.1050) {
		t.Fatalf("REVERSE SL = %v, want 1.1050 (above open, correct for a SELL stop-loss)", gotSL)
	}
	if gotTP == nil || !floatsClose(*gotTP, 1.0950) {
		t.Fatalf("REVERSE TP = %v, want 1.0950 (below open, correct for a SELL take-profit)", gotTP)
	}
}

// REVERSE relationship, master SELL -> follower BUY: the mirror image of the
// case above, confirming the flip isn't hardcoded to one direction.
func TestTranslateSlTp_REVERSE_SellMasterBuyFollower_FlipsSide(t *testing.T) {
	masterOpen := 1.1000
	masterSL := 1.1050 // 50 pips above open, correct for master's SELL
	masterTP := 1.0950 // 50 pips below open, correct for master's SELL

	followerDirection := applyCopyDirection(domain.TradeDirectionSell, "REVERSE")
	if followerDirection != domain.TradeDirectionBuy {
		t.Fatalf("test setup: followerDirection = %v, want BUY", followerDirection)
	}

	gotSL := translateSlTp(&masterSL, masterOpen, masterOpen, domain.TradeDirectionSell, followerDirection, 0.0001, 0.0001, "SL")
	gotTP := translateSlTp(&masterTP, masterOpen, masterOpen, domain.TradeDirectionSell, followerDirection, 0.0001, 0.0001, "TP")

	// Follower is BUY: SL must be BELOW its open price, TP must be ABOVE it.
	if gotSL == nil || !floatsClose(*gotSL, 1.0950) {
		t.Fatalf("REVERSE SL = %v, want 1.0950 (below open, correct for a BUY stop-loss)", gotSL)
	}
	if gotTP == nil || !floatsClose(*gotTP, 1.1050) {
		t.Fatalf("REVERSE TP = %v, want 1.1050 (above open, correct for a BUY take-profit)", gotTP)
	}
}

// TICKET-107: handleModify passes the follower's REAL fill price (known
// once the position is open), which generally differs from the master's own
// open price -- this is the scenario translateSlTp's explicit
// followerOpenPrice parameter exists for.
func TestTranslateSlTp_DifferingFollowerOpenPrice_AnchorsOnFollowerPrice(t *testing.T) {
	masterOpen := 1.1000
	masterSL := 1.0950     // 50 pips below master's own open price
	followerOpen := 1.1010 // follower's real fill price, 10 pips of slippage from master's open

	got := translateSlTp(&masterSL, masterOpen, followerOpen, domain.TradeDirectionBuy, domain.TradeDirectionBuy, 0.0001, 0.0001, "SL")
	// 50 pips below the FOLLOWER's own open price (1.1010), not the master's (1.1000).
	want := 1.0960
	if got == nil || !floatsClose(*got, want) {
		t.Fatalf("SL = %v, want %v (anchored on followerOpenPrice, not masterOpenPrice)", got, want)
	}
}

func floatsClose(a, b float64) bool {
	const epsilon = 1e-9
	d := a - b
	if d < 0 {
		d = -d
	}
	return d < epsilon
}
