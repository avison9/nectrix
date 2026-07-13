package formula

// node is the sealed AST node interface -- deliberately unexported (no way
// for a caller outside this package to construct or extend the tree), one
// more layer keeping this a closed, auditable grammar rather than an open
// one a caller could smuggle extra behavior into.
type node interface {
	isNode()
}

type numberLit struct{ value float64 }

type ident struct{ name string }

type unaryExpr struct {
	op string // "-"
	x  node
}

type binaryExpr struct {
	op   string // "+" "-" "*" "/" "%"
	x, y node
}

type callExpr struct {
	name string
	args []node
}

func (numberLit) isNode()  {}
func (ident) isNode()      {}
func (unaryExpr) isNode()  {}
func (binaryExpr) isNode() {}
func (callExpr) isNode()   {}
