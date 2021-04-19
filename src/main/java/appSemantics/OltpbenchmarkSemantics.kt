@file:Suppress("KDocUnresolvedReference", "UNUSED_PARAMETER")

package appSemantics

import AbstractValue
import com.github.javaparser.ast.expr.Expression
import knownSemantics
import Interpreter

@Suppress("unused")
class OltpbenchmarkSemantics {
    fun register() {
        knownSemantics["com.oltpbenchmark.api.Procedure.getPreparedStatement"] = ::getPreparedStatementSemantics
        knownSemantics["com.oltpbenchmark.api.SQLStmt"] = ::newSqlStmtSemantics
        knownSemantics["java.lang.String.format"] = ::stringFormatSemantics
    }
}

/**
 * PreparedStatement getPreparedStatement(Connection conn, SQLStmt stmt, Object...params) throws SQLException
 */
fun getPreparedStatementSemantics(self: Expression, env: Interpreter, receiver: AbstractValue?, args: List<AbstractValue>): AbstractValue {
    val stmt = AbstractValue.SqlStmt(self, (args[1] as SqlStmtState).sql)
    for (i in 2 until args.size) {
        stmt.setParameter(i-1, args[i])
    }
    return stmt
}

data class SqlStmtState(val e: Expression?, val sql: String): AbstractValue(e) {
    override fun toString(): String {
        return "SqlStmtState($sql; $e)"
    }
}

fun newSqlStmtSemantics(self: Expression, env: Interpreter, receiver: AbstractValue?, args: List<AbstractValue>): AbstractValue {
    return SqlStmtState(self, (args[0] as AbstractValue.Data).data as String)
}

fun stringFormatSemantics(self: Expression, env: Interpreter, receiver: AbstractValue?, args: List<AbstractValue>): AbstractValue {
    val format = args[0]
    val fields = args.slice(1 until args.size)
    // Check argument types
    if (format !is AbstractValue.Data || format.data !is String || fields.any { it !is AbstractValue.Data })
        return AbstractValue.Call(self, receiver, "format", args)
    // Format is string and fields are Data
    val res = String.format(format.data, *fields.map { (it as AbstractValue.Data).data }.toTypedArray())
    return AbstractValue.Data(self, res)
}
