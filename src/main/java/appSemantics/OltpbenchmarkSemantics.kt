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
    }
}

/**
 * PreparedStatement getPreparedStatement(Connection conn, SQLStmt stmt, Object...params) throws SQLException
 */
fun getPreparedStatementSemantics(self: Expression, env: Interpreter, receiver: AbstractValue?, args: List<AbstractValue>): AbstractValue {
    println(args[1])
    val stmt = AbstractValue.SqlStmt(self, (args[1] as SqlStmtState).sql)
    for (i in 2 until args.size) {
        stmt.setParameter(i-1, args[i])
    }
    return stmt
}

data class SqlStmtState(val e: Expression?, val sql: String): AbstractValue(e)

fun newSqlStmtSemantics(self: Expression, env: Interpreter, receiver: AbstractValue?, args: List<AbstractValue>): AbstractValue {
    return SqlStmtState(self, (args[0] as AbstractValue.Data).data as String)
}
