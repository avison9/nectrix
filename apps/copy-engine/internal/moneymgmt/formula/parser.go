package formula

import "fmt"

// maxParseDepth guards against a Go stack overflow from pathological input
// nesting (e.g. 100k nested parens). A Go stack overflow is NOT recoverable
// via defer/recover -- it crashes the whole process, not just one goroutine,
// taking down every in-flight computation across the whole copy-engine. 64
// is well below Go's own default goroutine stack limit, with wide margin.
const maxParseDepth = 64

// allowedFuncArity is the fixed, allow-listed function set docs/09 §9.2.6 /
// docs/17 §17.4 both name: min, max, round, clamp, abs. Anything else is a
// parse-time error -- never a runtime lookup against anything ambient.
var allowedFuncArity = map[string]int{
	"min":   2,
	"max":   2,
	"round": 1,
	"clamp": 3,
	"abs":   1,
}

type parser struct {
	tokens []token
	pos    int
}

func parse(tokens []token) (node, error) {
	p := &parser{tokens: tokens}
	n, err := p.parseExpr(0)
	if err != nil {
		return nil, err
	}
	if p.peek().kind != tokEOF {
		return nil, fmt.Errorf("formula: unexpected trailing input at token %d", p.pos)
	}
	return n, nil
}

func (p *parser) peek() token { return p.tokens[p.pos] }

func (p *parser) advance() token {
	t := p.tokens[p.pos]
	if t.kind != tokEOF {
		p.pos++
	}
	return t
}

func (p *parser) checkDepth(depth int) error {
	if depth > maxParseDepth {
		return fmt.Errorf("formula: expression nested too deeply (max depth %d)", maxParseDepth)
	}
	return nil
}

// expr := term (("+"|"-") term)*
func (p *parser) parseExpr(depth int) (node, error) {
	if err := p.checkDepth(depth); err != nil {
		return nil, err
	}
	left, err := p.parseTerm(depth + 1)
	if err != nil {
		return nil, err
	}
	for {
		switch p.peek().kind {
		case tokPlus:
			p.advance()
			right, err := p.parseTerm(depth + 1)
			if err != nil {
				return nil, err
			}
			left = binaryExpr{op: "+", x: left, y: right}
		case tokMinus:
			p.advance()
			right, err := p.parseTerm(depth + 1)
			if err != nil {
				return nil, err
			}
			left = binaryExpr{op: "-", x: left, y: right}
		default:
			return left, nil
		}
	}
}

// term := unary (("*"|"/"|"%") unary)*
func (p *parser) parseTerm(depth int) (node, error) {
	if err := p.checkDepth(depth); err != nil {
		return nil, err
	}
	left, err := p.parseUnary(depth + 1)
	if err != nil {
		return nil, err
	}
	for {
		var op string
		switch p.peek().kind {
		case tokStar:
			op = "*"
		case tokSlash:
			op = "/"
		case tokPercent:
			op = "%"
		default:
			return left, nil
		}
		p.advance()
		right, err := p.parseUnary(depth + 1)
		if err != nil {
			return nil, err
		}
		left = binaryExpr{op: op, x: left, y: right}
	}
}

// unary := "-" unary | primary
func (p *parser) parseUnary(depth int) (node, error) {
	if err := p.checkDepth(depth); err != nil {
		return nil, err
	}
	if p.peek().kind == tokMinus {
		p.advance()
		x, err := p.parseUnary(depth + 1)
		if err != nil {
			return nil, err
		}
		return unaryExpr{op: "-", x: x}, nil
	}
	return p.parsePrimary(depth + 1)
}

// primary := NUMBER | IDENT | IDENT "(" (expr ("," expr)*)? ")" | "(" expr ")"
func (p *parser) parsePrimary(depth int) (node, error) {
	if err := p.checkDepth(depth); err != nil {
		return nil, err
	}
	t := p.peek()
	switch t.kind {
	case tokNumber:
		p.advance()
		return numberLit{value: t.num}, nil
	case tokIdent:
		p.advance()
		if p.peek().kind == tokLParen {
			return p.parseCall(t.text, depth+1)
		}
		return ident{name: t.text}, nil
	case tokLParen:
		p.advance()
		inner, err := p.parseExpr(depth + 1)
		if err != nil {
			return nil, err
		}
		if p.peek().kind != tokRParen {
			return nil, fmt.Errorf("formula: expected ')' at token %d", p.pos)
		}
		p.advance()
		return inner, nil
	default:
		return nil, fmt.Errorf("formula: unexpected token at position %d", p.pos)
	}
}

func (p *parser) parseCall(name string, depth int) (node, error) {
	if err := p.checkDepth(depth); err != nil {
		return nil, err
	}
	arity, ok := allowedFuncArity[name]
	if !ok {
		return nil, fmt.Errorf("formula: %q is not an allowed function", name)
	}

	p.advance() // consume '('
	var args []node
	if p.peek().kind != tokRParen {
		for {
			arg, err := p.parseExpr(depth + 1)
			if err != nil {
				return nil, err
			}
			args = append(args, arg)
			if p.peek().kind == tokComma {
				p.advance()
				continue
			}
			break
		}
	}
	if p.peek().kind != tokRParen {
		return nil, fmt.Errorf("formula: expected ')' closing call to %q", name)
	}
	p.advance()

	if len(args) != arity {
		return nil, fmt.Errorf("formula: %q expects %d argument(s), got %d", name, arity, len(args))
	}
	return callExpr{name: name, args: args}, nil
}
