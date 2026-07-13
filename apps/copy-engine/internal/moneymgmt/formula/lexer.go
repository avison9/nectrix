package formula

import (
	"fmt"
	"strings"
	"unicode"
)

type tokenKind int

const (
	tokEOF tokenKind = iota
	tokNumber
	tokIdent
	tokPlus
	tokMinus
	tokStar
	tokSlash
	tokPercent
	tokLParen
	tokRParen
	tokComma
)

type token struct {
	kind tokenKind
	text string
	num  float64
}

// lex tokenizes expr in one pass. maxExprLen is enforced by the caller
// (formula.go) before this ever runs -- lex itself does not re-check length,
// it only ever sees an already-bounded input.
func lex(expr string) ([]token, error) {
	var tokens []token
	runes := []rune(expr)
	i := 0
	for i < len(runes) {
		r := runes[i]
		switch {
		case unicode.IsSpace(r):
			i++
		case r == '+':
			tokens = append(tokens, token{kind: tokPlus})
			i++
		case r == '-':
			tokens = append(tokens, token{kind: tokMinus})
			i++
		case r == '*':
			tokens = append(tokens, token{kind: tokStar})
			i++
		case r == '/':
			tokens = append(tokens, token{kind: tokSlash})
			i++
		case r == '%':
			tokens = append(tokens, token{kind: tokPercent})
			i++
		case r == '(':
			tokens = append(tokens, token{kind: tokLParen})
			i++
		case r == ')':
			tokens = append(tokens, token{kind: tokRParen})
			i++
		case r == ',':
			tokens = append(tokens, token{kind: tokComma})
			i++
		case unicode.IsDigit(r):
			start := i
			seenDot := false
			for i < len(runes) && (unicode.IsDigit(runes[i]) || (runes[i] == '.' && !seenDot)) {
				if runes[i] == '.' {
					seenDot = true
				}
				i++
			}
			text := string(runes[start:i])
			var num float64
			if _, err := fmt.Sscanf(text, "%g", &num); err != nil {
				return nil, fmt.Errorf("formula: invalid number %q", text)
			}
			tokens = append(tokens, token{kind: tokNumber, text: text, num: num})
		case unicode.IsLetter(r) || r == '_':
			start := i
			for i < len(runes) && (unicode.IsLetter(runes[i]) || unicode.IsDigit(runes[i]) || runes[i] == '_') {
				i++
			}
			tokens = append(tokens, token{kind: tokIdent, text: strings.ToLower(string(runes[start:i]))})
		default:
			return nil, fmt.Errorf("formula: unexpected character %q", r)
		}
	}
	tokens = append(tokens, token{kind: tokEOF})
	return tokens, nil
}
