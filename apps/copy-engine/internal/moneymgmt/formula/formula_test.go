package formula

import (
	"context"
	"math"
	"testing"
)

func TestEvaluate_ArithmeticAndPrecedence(t *testing.T) {
	tests := []struct {
		expr string
		want float64
	}{
		{"1 + 2", 3},
		{"2 + 3 * 4", 14},          // precedence: * before +
		{"(2 + 3) * 4", 20},        // parens override precedence
		{"10 - 2 - 3", 5},          // left-associative
		{"10 / 2 / 5", 1},          // left-associative
		{"-5 + 3", -2},             // unary minus
		{"7 % 3", 1},
		{"-(2 + 3)", -5},
	}
	for _, tt := range tests {
		t.Run(tt.expr, func(t *testing.T) {
			got, err := Evaluate(context.Background(), tt.expr, Context{})
			if err != nil {
				t.Fatalf("Evaluate(%q) returned error: %v", tt.expr, err)
			}
			if math.Abs(got-tt.want) > 1e-9 {
				t.Fatalf("Evaluate(%q) = %v, want %v", tt.expr, got, tt.want)
			}
		})
	}
}

func TestEvaluate_ContextVariables(t *testing.T) {
	ctx := Context{
		"master_open_volume_lots": 2.0,
		"follower_account_equity": 5000,
		"master_account_equity":   10000,
	}
	got, err := Evaluate(context.Background(), "master_open_volume_lots * (follower_account_equity / master_account_equity)", ctx)
	if err != nil {
		t.Fatalf("Evaluate returned error: %v", err)
	}
	want := 2.0 * (5000.0 / 10000.0)
	if math.Abs(got-want) > 1e-9 {
		t.Fatalf("got %v, want %v", got, want)
	}
}

func TestEvaluate_AllowedFunctions(t *testing.T) {
	tests := []struct {
		expr string
		want float64
	}{
		{"min(3, 5)", 3},
		{"max(3, 5)", 5},
		{"round(2.6)", 3},
		{"clamp(0.5, 1, 5)", 1},
		{"clamp(10, 1, 5)", 5},
		{"clamp(3, 1, 5)", 3},
		{"abs(-4)", 4},
	}
	for _, tt := range tests {
		t.Run(tt.expr, func(t *testing.T) {
			got, err := Evaluate(context.Background(), tt.expr, Context{})
			if err != nil {
				t.Fatalf("Evaluate(%q) returned error: %v", tt.expr, err)
			}
			if math.Abs(got-tt.want) > 1e-9 {
				t.Fatalf("Evaluate(%q) = %v, want %v", tt.expr, got, tt.want)
			}
		})
	}
}

func TestEvaluate_WrongArity_Rejected(t *testing.T) {
	_, err := Evaluate(context.Background(), "min(1, 2, 3)", Context{})
	if err == nil {
		t.Fatal("expected an error for min() called with the wrong arity")
	}
}

func TestEvaluate_DivisionByZero_Rejected(t *testing.T) {
	_, err := Evaluate(context.Background(), "1 / 0", Context{})
	if err == nil {
		t.Fatal("expected an error for division by zero")
	}
}

func TestEvaluate_MalformedExpression_Rejected(t *testing.T) {
	exprs := []string{"1 +", "(1 + 2", "1 2", "* 5"}
	for _, expr := range exprs {
		t.Run(expr, func(t *testing.T) {
			if _, err := Evaluate(context.Background(), expr, Context{}); err == nil {
				t.Fatalf("expected a parse error for malformed expression %q", expr)
			}
		})
	}
}
