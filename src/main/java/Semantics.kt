import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.visitor.GenericVisitorAdapter
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration
import net.sf.jsqlparser.expression.operators.relational.EqualsTo
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.select.PlainSelect
import net.sf.jsqlparser.statement.select.Select
import net.sf.jsqlparser.util.TablesNamesFinder

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
    "java.sql.PreparedStatement.executeUpdate" to ::executeUpdateSemantics,
    "java.sql.PreparedStatement.setNull" to ::setNullSemantics,
    "java.sql.PreparedStatement.setInt" to ::setParameterSemantics,
    "java.sql.PreparedStatement.setLong" to ::setParameterSemantics,
    "java.sql.PreparedStatement.setDate" to ::setParameterSemantics,
    "java.sql.PreparedStatement.setDouble" to ::setParameterSemantics,
    "java.sql.PreparedStatement.setFloat" to ::setParameterSemantics,
    "java.sql.PreparedStatement.setString" to ::setParameterSemantics,
    "java.sql.ResultSet.getInt" to ::getColumnSemantics,
    "java.sql.ResultSet.getLong" to ::getColumnSemantics,
    "java.sql.ResultSet.getDate" to ::getColumnSemantics,
    "java.sql.ResultSet.getDouble" to ::getColumnSemantics,
    "java.sql.ResultSet.getFloat" to ::getColumnSemantics,
    "java.sql.ResultSet.getString" to ::getColumnSemantics,
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
    val sql = CCJSqlParserUtil.parse(sqlStr)
    when (sql) {
        is Select -> {
            if (sql.selectBody !is PlainSelect) {
                println("[WARN] don't know $sql")
                return AbstractValue.Unknown(self, self.calculateResolvedType())
            }

            val selectBody = sql.selectBody as PlainSelect
            val where = selectBody.where
            val items = selectBody.selectItems
            val tables = TablesNamesFinder().getTableList(sql)

            val rs = AbstractValue.ResultSet(self, self.calculateResolvedType(), selectBody)

            // Collect columns
            if (items.size == 1) {
                assert(tables.size == 1)
                if (items[0].toString() == "*") {
                    for (item in env.schema.get(tables[0]!!)!!.columns) {
                        rs.addColumn(item)
                    }
                } else if (items[0].toString().contains("(")) {
                    // FIXME: only case in HealthPlus is SELECT MAX(bill_id) FROM bill
                    assert(items[0].toString().substringBefore('(') == "MAX")
                    val colName = items[0].toString().substringAfter('(').substringBefore(')')
                    assert(colName == "bill_id")
                    val col = env.schema.get(tables[0]!!)!!.get(colName)!!
                    rs.addAggregate(col, AggregateKind.MAX)
                } else {
                    val col = env.schema.get(tables[0])!!.get(items[0].toString())!!
                    rs.addColumn(col)
                }
            } else {
                for (item in items) {
                    val (tableName, colName) = item.toString().split(".")
                    val column = env.schema.get(tableName)!!.get(colName)!!
                    rs.addColumn(column)
                }
            }

            // Collect indexers
            when (where) {
                is EqualsTo -> {
                    val left = where.leftExpression.toString()
                    if (!left.contains(".")) {
                        val tblName = selectBody.fromItem.toString()
                        assert(!tblName.contains(","))
                        env.schema.get(tblName)!!.get(left)!!.setPKey()
                    } else {
                        val (tblName, colName) = left.split(".")
                        env.schema.get(tables[0]!!)!!.get(left)!!.setPKey()
                    }
                }
                null -> {
                    // no where clause
                }
                else -> {
                    println("[ERR-SQL] cannot handle this where clause $where of ${where::class}")
                }
            }

            println("")
            return rs
        }
        else -> {
            println("[ERR] Query string is not SELECT")
            return AbstractValue.Unknown(self, self.calculateResolvedType())
        }
    }
}

fun executeUpdateSemantics(self: Expression, env: Interpreter, receiver: AbstractValue?, args: List<AbstractValue>): AbstractValue {
    // Receiver is a prepared statement.
    assert(receiver is AbstractValue.SqlStmt)
    val receiver = receiver as AbstractValue.SqlStmt
    val sqlStr = receiver.sql
    println("[DBG] execute update $sqlStr, #params=${receiver.params.size}")
    for (param in receiver.params) {
        println("- $param")
    }
    // TODO: record SOP
    return AbstractValue.Unknown(self, self.calculateResolvedType())
}

fun setParameterSemantics(self: Expression, env: Interpreter, receiver: AbstractValue?, args: List<AbstractValue>): AbstractValue {
    val receiver = receiver!! as AbstractValue.SqlStmt
    val idx = (args[0] as AbstractValue.Data).data as Long
    val param = args[1]
    receiver.setParameter(idx.toInt(), param)
    return AbstractValue.Unknown(self, self.calculateResolvedType())
}

fun setNullSemantics(self: Expression, env: Interpreter, receiver: AbstractValue?, args: List<AbstractValue>): AbstractValue {
    val receiver = receiver!! as AbstractValue.SqlStmt
    val idx = (args[0] as AbstractValue.Data).data as Long
    receiver.setParameter(idx.toInt(), AbstractValue.Null(self, self.calculateResolvedType())) // FIXME
    return AbstractValue.Unknown(self, self.calculateResolvedType())
}

fun getColumnSemantics(self: Expression, env: Interpreter, receiver: AbstractValue?, args: List<AbstractValue>): AbstractValue {
    if (receiver !is AbstractValue.ResultSet) {
        println("[ERR] resultset was not successfully analyzed")
        return AbstractValue.Unknown(self, self.calculateResolvedType())
    }

    val receiver = receiver!! as AbstractValue.ResultSet
    val idx = (args[0] as AbstractValue.Data).data as Long
    val pair = receiver.columns[idx.toInt() - 1]
    val value = AbstractValue.DbState(self, self.calculateResolvedType(), receiver, pair.first, pair.second)
    return value
}
