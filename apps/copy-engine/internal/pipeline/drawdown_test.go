package pipeline

import "testing"

func TestEvaluateDrawdown_ComputesCorrectPercentage(t *testing.T) {
	pausePct := 10.0
	drawdownPct, _, _ := evaluateDrawdown(10000, 9000, &pausePct, nil)
	if !floatsCloseDrawdown(drawdownPct, 10.0) {
		t.Fatalf("drawdownPct = %v, want 10.0", drawdownPct)
	}
}

func TestEvaluateDrawdown_NeitherConfigured_NeverTriggers(t *testing.T) {
	_, pause, forceClose := evaluateDrawdown(10000, 5000, nil, nil)
	if pause || forceClose {
		t.Fatalf("pause=%v forceClose=%v, want both false when neither threshold is configured", pause, forceClose)
	}
}

func TestEvaluateDrawdown_PauseOnly_BelowThreshold_NoTrigger(t *testing.T) {
	pausePct := 10.0
	_, pause, forceClose := evaluateDrawdown(10000, 9500, &pausePct, nil) // 5% drawdown
	if pause {
		t.Fatalf("pause = true, want false (5%% drawdown < 10%% threshold)")
	}
	if forceClose {
		t.Fatalf("forceClose = true, want false (not configured)")
	}
}

func TestEvaluateDrawdown_PauseOnly_AboveThreshold_Triggers(t *testing.T) {
	pausePct := 10.0
	_, pause, forceClose := evaluateDrawdown(10000, 8900, &pausePct, nil) // 11% drawdown
	if !pause {
		t.Fatalf("pause = false, want true (11%% drawdown >= 10%% threshold)")
	}
	if forceClose {
		t.Fatalf("forceClose = true, want false (not configured)")
	}
}

func TestEvaluateDrawdown_ExactlyAtThreshold_Triggers(t *testing.T) {
	pausePct := 10.0
	_, pause, _ := evaluateDrawdown(10000, 9000, &pausePct, nil) // exactly 10% drawdown
	if !pause {
		t.Fatalf("pause = false, want true (exactly at threshold, >= is inclusive)")
	}
}

func TestEvaluateDrawdown_CloseAllOnly_Triggers(t *testing.T) {
	closeAllPct := 20.0
	_, pause, forceClose := evaluateDrawdown(10000, 7900, nil, &closeAllPct) // 21% drawdown
	if pause {
		t.Fatalf("pause = true, want false (not configured)")
	}
	if !forceClose {
		t.Fatalf("forceClose = false, want true (21%% drawdown >= 20%% threshold)")
	}
}

// docs/09 §9.7: crossing the stricter drawdown_close_all_pct also crosses
// the looser drawdown_pause_pct in any sane configuration -- both booleans
// must independently report true in the same evaluation.
func TestEvaluateDrawdown_BothConfigured_CrossingStricterThreshold_BothTrigger(t *testing.T) {
	pausePct := 10.0
	closeAllPct := 20.0
	_, pause, forceClose := evaluateDrawdown(10000, 7500, &pausePct, &closeAllPct) // 25% drawdown
	if !pause {
		t.Fatalf("pause = false, want true (25%% drawdown crosses the 10%% pause threshold too)")
	}
	if !forceClose {
		t.Fatalf("forceClose = false, want true (25%% drawdown >= 20%% threshold)")
	}
}

func TestEvaluateDrawdown_BothConfigured_OnlyPauseCrossed(t *testing.T) {
	pausePct := 10.0
	closeAllPct := 20.0
	_, pause, forceClose := evaluateDrawdown(10000, 8500, &pausePct, &closeAllPct) // 15% drawdown
	if !pause {
		t.Fatalf("pause = false, want true (15%% drawdown >= 10%% threshold)")
	}
	if forceClose {
		t.Fatalf("forceClose = true, want false (15%% drawdown < 20%% threshold)")
	}
}

func floatsCloseDrawdown(a, b float64) bool {
	const epsilon = 1e-9
	d := a - b
	if d < 0 {
		d = -d
	}
	return d < epsilon
}
