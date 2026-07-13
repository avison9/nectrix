package formula

import (
	"context"
	"strings"
	"testing"
	"time"
)

// TestEvaluate_DeeplyNestedExpression_SafelyRejected_NotHang is TICKET-104's
// AC4 "infinite loop attempt" case for a pure-expression grammar: the real
// attack surface isn't a literal loop (the grammar has none), it's
// pathological nesting attempting to exhaust the recursive-descent parser's
// own Go call stack. Run in a goroutine racing a timeout: the test asserts
// not just that an error comes back, but that it comes back AT ALL within a
// bounded time -- a process-crashing stack overflow would kill the whole
// test binary, not just fail an assertion, so "the test process is still
// alive to report a result" is itself part of what this proves.
func TestEvaluate_DeeplyNestedExpression_SafelyRejected_NotHang(t *testing.T) {
	expr := strings.Repeat("(", 50000) + "1" + strings.Repeat(")", 50000)

	type outcome struct {
		val float64
		err error
	}
	done := make(chan outcome, 1)
	go func() {
		val, err := Evaluate(context.Background(), expr, Context{})
		done <- outcome{val: val, err: err}
	}()

	select {
	case o := <-done:
		if o.err == nil {
			t.Fatalf("expected a rejection error for a pathologically nested expression, got value %v", o.val)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("Evaluate did not return within 2s for a pathologically nested expression -- hang or crash")
	}
}

// TestEvaluate_DisallowedFunctionAccess_Rejected is TICKET-104's AC4
// "attempt to access disallowed functions" case. None of these have any
// path to succeed: the interpreter has no reflection/os/network access
// points at all (see eval.go's callFunc, a fixed switch over exactly 5
// names) -- this proves the boundary is enforced, not merely undocumented.
func TestEvaluate_DisallowedFunctionAccess_Rejected(t *testing.T) {
	exprs := []string{
		`exec(1)`,
		`os_getenv("SECRET")`,
		`sqrt(4)`,       // plausible-looking math function, not allow-listed
		`eval("1+1")`,
		`system("ls")`,
	}
	for _, expr := range exprs {
		t.Run(expr, func(t *testing.T) {
			val, err := Evaluate(context.Background(), expr, Context{})
			if err == nil {
				t.Fatalf("expected a rejection error for disallowed function call %q, got value %v", expr, val)
			}
		})
	}
}

// TestEvaluate_UnknownIdentifier_Rejected proves there is no ambient access
// to anything outside the explicit Context passed in (docs/17 §17.4's "no
// ambient access to other users' data").
func TestEvaluate_UnknownIdentifier_Rejected(t *testing.T) {
	_, err := Evaluate(context.Background(), "some_other_users_balance", Context{"master_account_equity": 1000})
	if err == nil {
		t.Fatal("expected a rejection error for an identifier outside the supplied Context")
	}
}

// TestEvaluate_ExpressionTooLong_Rejected proves the O(1) length pre-check
// runs before tokenizing/parsing even begins.
func TestEvaluate_ExpressionTooLong_Rejected(t *testing.T) {
	expr := strings.Repeat("1+", 2000) + "1"
	_, err := Evaluate(context.Background(), expr, Context{})
	if err == nil {
		t.Fatal("expected a rejection error for an over-length expression")
	}
}
