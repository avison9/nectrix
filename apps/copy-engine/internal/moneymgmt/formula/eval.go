package formula

import (
	"context"
	"fmt"
	"math"
	"time"
)

// maxEvalSteps is a defense-in-depth guard: the grammar has no loops, so
// parse-time depth bounds already make eval-time cost O(bounded) -- this
// exists mainly to future-proof the design if the grammar ever grows
// loop-like constructs, and to satisfy docs/17 §17.4's literal
// "step-count/time budget" wording with an actual step count, not just time.
const maxEvalSteps = 10000

type evaluator struct {
	parent   context.Context
	ctx      Context
	steps    int
	deadline time.Time
}

func (e *evaluator) checkBudget() error {
	e.steps++
	if e.steps > maxEvalSteps {
		return fmt.Errorf("formula: exceeded evaluation step budget (%d)", maxEvalSteps)
	}
	if time.Now().After(e.deadline) {
		return fmt.Errorf("formula: evaluation exceeded time budget")
	}
	if e.parent != nil && e.parent.Err() != nil {
		return fmt.Errorf("formula: evaluation cancelled: %w", e.parent.Err())
	}
	return nil
}

func (e *evaluator) eval(n node) (float64, error) {
	if err := e.checkBudget(); err != nil {
		return 0, err
	}
	switch v := n.(type) {
	case numberLit:
		return v.value, nil
	case ident:
		val, ok := e.ctx[v.name]
		if !ok {
			return 0, fmt.Errorf("formula: unknown identifier %q", v.name)
		}
		return val, nil
	case unaryExpr:
		x, err := e.eval(v.x)
		if err != nil {
			return 0, err
		}
		return -x, nil
	case binaryExpr:
		x, err := e.eval(v.x)
		if err != nil {
			return 0, err
		}
		y, err := e.eval(v.y)
		if err != nil {
			return 0, err
		}
		switch v.op {
		case "+":
			return x + y, nil
		case "-":
			return x - y, nil
		case "*":
			return x * y, nil
		case "/":
			if y == 0 {
				return 0, fmt.Errorf("formula: division by zero")
			}
			return x / y, nil
		case "%":
			if y == 0 {
				return 0, fmt.Errorf("formula: modulo by zero")
			}
			return math.Mod(x, y), nil
		default:
			return 0, fmt.Errorf("formula: unknown operator %q", v.op)
		}
	case callExpr:
		args := make([]float64, len(v.args))
		for i, a := range v.args {
			val, err := e.eval(a)
			if err != nil {
				return 0, err
			}
			args[i] = val
		}
		return callFunc(v.name, args)
	default:
		return 0, fmt.Errorf("formula: unknown node type %T", n)
	}
}

// callFunc implements exactly the allow-listed function set (see
// parser.go's allowedFuncArity, which already rejects anything else at
// parse time -- this is the second, defense-in-depth gate at eval time).
func callFunc(name string, args []float64) (float64, error) {
	switch name {
	case "min":
		return math.Min(args[0], args[1]), nil
	case "max":
		return math.Max(args[0], args[1]), nil
	case "round":
		return math.Round(args[0]), nil
	case "clamp":
		v, lo, hi := args[0], args[1], args[2]
		if v < lo {
			return lo, nil
		}
		if v > hi {
			return hi, nil
		}
		return v, nil
	case "abs":
		return math.Abs(args[0]), nil
	default:
		return 0, fmt.Errorf("formula: %q is not an allowed function", name)
	}
}
