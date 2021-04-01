package AppSemantics

import AbstractValue
import com.github.javaparser.ast.expr.Expression
import knownSemantics
import Interpreter

// This file deals with original HealthPlus code.

fun registerHealthPlusSemantics() {
    knownSemantics["com.hms.hms_test_2.DatabaseOperator.customInsertion"] = ::customInsertionSemantics
}

fun customInsertionSemantics(self: Expression, env: Interpreter, receiver: AbstractValue?, args: List<AbstractValue>): AbstractValue {
    println("Found expression $self")
    return AbstractValue.Unknown(self, self.calculateResolvedType())
}
