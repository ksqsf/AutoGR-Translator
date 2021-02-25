import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.visitor.GenericVisitorAdapter
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration

val basicUpdates = setOf(
    "java.sql.Statement.executeUpdate",
    "java.sql.PreparedStatement.executeUpdate"
)

fun containsCall(nameSet: Set<QualifiedName>, s: Expression): Boolean {
    return s.accept(object : GenericVisitorAdapter<Boolean, Void?>() {
        override fun visit(m: MethodCallExpr, arg_: Void?): Boolean? {
            if (nameSet.contains(m.resolve().qualifiedName)) {
                return true
            }
            return null
        }
    }, null) ?: return false
}

fun containsCall(nameSet: Set<QualifiedName>, s: Statement): Boolean {
    return s.accept(object : GenericVisitorAdapter<Boolean, Void?>() {
        override fun visit(m: MethodCallExpr, arg_: Void?): Boolean? {
            if (containsCall(nameSet, m))
                return true
            return null
        }
    }, null) ?: return false
}

fun containsUpdate(s: Expression): Boolean {
    return containsCall(basicUpdates, s)
}

fun containsUpdate(s: Statement): Boolean {
    return containsCall(basicUpdates, s)
}

val basicCommits = setOf(
    "java.sql.Connection.commit",
    "edu.rice.rubis.servlets.Database.commit", // FIXME: we haven't implemented precise analysis yet
)

fun containsCommit(s: Expression): Boolean {
    return containsCall(basicCommits, s)
}

fun containsCommit(s: Statement): Boolean {
    return containsCall(basicCommits, s)
}

val basicRollback = setOf(
    "java.sql.Connection.rollback",
    "edu.rice.rubis.servlets.Database.rollback"
)

fun containsRollback(s: Expression): Boolean {
    return containsCall(basicRollback, s)
}

fun containsRollback(s: Statement): Boolean {
    return containsCall(basicRollback, s)
}

val knownSemantics = mapOf(
    "java.sql.Connection.prepareStatement" to ::prepareStatementSemantics,
    "java.sql.PreparedStatement.executeQuery" to ::executeQuerySemantics,
    "java.sql.PreparedStatement.setInt" to ::setParameterSemantics,
    "java.sql.PreparedStatement.setLong" to ::setParameterSemantics,
    "java.sql.PreparedStatement.setDate" to ::setParameterSemantics,
    "java.sql.PreparedStatement.setDouble" to ::setParameterSemantics,
    "java.sql.PreparedStatement.setFloat" to ::setParameterSemantics,
    "java.sql.ResultSet.getInt" to ::getColumnSemantics,
    "java.sql.ResultSet.getLong" to ::getColumnSemantics,
    "java.sql.ResultSet.getDate" to ::getColumnSemantics,
    "java.sql.ResultSet.getDouble" to ::getColumnSemantics,
    "java.sql.ResultSet.getFloat" to ::getColumnSemantics,
)

fun hasSemantics(methodDecl: ResolvedMethodDeclaration): Boolean {
    return knownSemantics.containsKey(methodDecl.qualifiedName)
}

fun dispatchSemantics(self: Expression, env: Interpreter, receiver: AbstractValue?, args: List<AbstractValue>): AbstractValue {
    return knownSemantics[self.asMethodCallExpr().resolve().qualifiedName]!!(self, env, receiver, args)
}

fun prepareStatementSemantics(self: Expression, env: Interpreter, receiver: AbstractValue?, args: List<AbstractValue>): AbstractValue {
    // Receiver is a Connection object, ignore it.
    // The first argument is the SQL string.
    val sqlStrVal = args[0]

    val sqlStr = if (sqlStrVal is AbstractValue.Data && sqlStrVal.data is String) {
        sqlStrVal.data
    } else {
        sqlStrVal.guessSql()
    }

    return AbstractValue.SqlStmt(self, self.calculateResolvedType(), sqlStr)
}

fun executeQuerySemantics(self: Expression, env: Interpreter, receiver: AbstractValue?, args: List<AbstractValue>): AbstractValue {
    // Receiver is a prepared statement.
    assert(receiver is AbstractValue.SqlStmt)
    val receiver = receiver as AbstractValue.SqlStmt
    val sqlStr = receiver.sql
    TODO()
}

fun setParameterSemantics(self: Expression, env: Interpreter, receiver: AbstractValue?, args: List<AbstractValue>): AbstractValue {
    val receiver = receiver!! as AbstractValue.SqlStmt
    val idx = (args[0] as AbstractValue.Data).data as Long
    val param = args[1]
    receiver.setParameter(idx.toInt(), param)
    return AbstractValue.Unknown(self, self.calculateResolvedType())
}

fun getColumnSemantics(self: Expression, env: Interpreter, receiver: AbstractValue?, args: List<AbstractValue>): AbstractValue {
    TODO()
}
