import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.visitor.GenericVisitorAdapter

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
