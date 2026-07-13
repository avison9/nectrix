// Package formula is TICKET-104's sandboxed expression evaluator for
// docs/09-money-management-risk-formulas.md §9.2.6's Custom Formula sizing
// method: a restricted recursive-descent parser + tree-walking interpreter,
// never eval/dynamic code execution, per
// docs/17-security-architecture.md §17.4. Hand-rolled rather than a
// third-party expression library -- confirmed via repo-wide search that no
// such library or hand-rolled evaluator exists anywhere in this repo yet,
// and a hand-rolled interpreter is fully auditable for a server-side sandbox
// executing untrusted, user-authored input (no need to audit a dependency's
// full reflection/builtin surface).
package formula

import (
	"context"
	"fmt"
	"time"
)

// Context is the fixed variable set docs/09 §9.2.6 names -- no ambient
// access to anything else (docs/17 §17.4's "no ambient access to other
// users' data").
type Context map[string]float64

const (
	// maxExprLen is the cheapest possible guard against a giant flat token
	// stream, checked before tokenizing/parsing even begins.
	maxExprLen = 2000
	// evalBudget is the wall-clock deadline for one evaluation -- matches
	// docs/17 §17.4's literal "time budget" wording, checked periodically
	// during eval (see evaluator.checkBudget), not just at the end.
	evalBudget = 50 * time.Millisecond
)

// Evaluate parses and evaluates expr against evalCtx. Layered defenses
// against pathological/malicious input:
//  1. An O(1) length pre-check before tokenizing at all.
//  2. A depth-bounded recursive-descent parser (parser.go's maxParseDepth)
//     -- guards stack exhaustion from deep nesting. A Go stack overflow is
//     NOT recoverable via defer/recover; it crashes the whole process, not
//     just one goroutine, so this bound is load-bearing, not cosmetic.
//  3. An eval-time step counter (eval.go's maxEvalSteps).
//  4. A wall-clock deadline, checked throughout evaluation, plus honoring
//     the caller's own context.Context cancellation.
//
// A parse or eval failure is always a plain error -- never a partial
// numeric result, never a panic, never a hang.
func Evaluate(ctx context.Context, expr string, evalCtx Context) (float64, error) {
	if len(expr) > maxExprLen {
		return 0, fmt.Errorf("formula: expression exceeds max length (%d characters)", maxExprLen)
	}

	tokens, err := lex(expr)
	if err != nil {
		return 0, err
	}
	tree, err := parse(tokens)
	if err != nil {
		return 0, err
	}

	e := &evaluator{parent: ctx, ctx: evalCtx, deadline: time.Now().Add(evalBudget)}
	return e.eval(tree)
}
