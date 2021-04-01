import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.visitor.GenericVisitorAdapter
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration
import net.sf.jsqlparser.expression.JdbcParameter
import net.sf.jsqlparser.expression.operators.arithmetic.Addition
import net.sf.jsqlparser.expression.operators.arithmetic.Subtraction
import net.sf.jsqlparser.expression.operators.conditional.AndExpression
import net.sf.jsqlparser.expression.operators.relational.EqualsTo
import net.sf.jsqlparser.expression.operators.relational.ExpressionList
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.delete.Delete
import net.sf.jsqlparser.statement.insert.Insert
import net.sf.jsqlparser.statement.select.PlainSelect
import net.sf.jsqlparser.statement.select.Select
import net.sf.jsqlparser.statement.update.Update
import net.sf.jsqlparser.util.TablesNamesFinder
import java.lang.IllegalArgumentException

val basicUpdates = mutableSetOf(
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

val knownSemantics = mutableMapOf(
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
    "java.sql.ResultSet.first" to ::notNilSemantics,
    "java.sql.ResultSet.next" to ::notNilSemantics,
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

            val rs = AbstractValue.ResultSet(self, self.calculateResolvedType(), receiver, selectBody)
            rs.tables = tables.map { env.schema.get(it)!! }.toList()

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
            val table = env.schema.get(selectBody.fromItem.toString())!!
            val indexers = collectColumnsFromExpr(where).map {
                if (it.contains(".")) {
                    assert(it.substringBefore(".") == table.name)
                }
                it.substringAfter(".")
            }.map { table.get(it)!! }
            table.addPKey(indexers.toSet())

            if (rs.hasJoin) {
                println("[WARN] this sql query has joins!!")
            }


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

    if (sqlStr.contains("now(", ignoreCase = true)) {
        // FIXME: This is a datetime
        env.effect.addArgv("now", Type.Int)
    }

    val sql = CCJSqlParserUtil.parse(sqlStr)!!
    when (sql) {
        is Update -> {
            println("[update] Update $sql, cols=${sql.columns}, exprs=${sql.expressions}, from=${sql.fromItem}, tbl=${sql.table}")
            val table = env.schema.get(sql.table.name)!!
            val valueList = sql.expressions.map { evalSqlExpr(it, receiver, table) }
            val columnList = sql.columns.map { table.get(it.columnName)!! }
            val locators = whereToLocators(receiver, table, sql.where)
            val atom = Atom.Update(table, locators, castValues(columnList, valueList))
            env.effect.addAtom(atom)
            table.addPKey(locators.keys)

            // UPDATE asserts WHERE selects something.
            env.effect.addCondition(AbstractValue.DbNotNil(self, self.calculateResolvedType(), receiver, table, locators))
            //Consider these locators arguments too.
            for (locator in locators) {
                env.effect.addArgv(locator.key)
            }

            env.effect.addUpdatedTable(table)
        }
        is Insert -> {
            println("[update] Insert $sql, cols=${sql.columns}, table=${sql.table}, itemL=${sql.itemsList}, exprL=${sql.setExpressionList}")
            val table = env.schema.get(sql.table.name)!!
            val exprs = sql.itemsList as ExpressionList
            val valueMap = mutableMapOf<Column, AbstractValue>()
            if (sql.columns == null) {
                for ((col, expr) in table.columns.zip(exprs.expressions)) {
                    valueMap[col] = castValue(col, evalSqlExpr(expr, receiver, table))
                }
            } else {
                for ((col, expr) in sql.columns.zip(exprs.expressions)) {
                    val col = table.get(col.columnName)!!
                    valueMap[table.get(col.name)!!] = castValue(col, evalSqlExpr(expr, receiver, table))
                }
            }
            val atom = Atom.Insert(table, valueMap)
            env.effect.addAtom(atom)
            env.effect.addUpdatedTable(table)
        }
        is Delete -> {
            println("[update] Delete $sql, tbl=${sql.table}, tbls=${sql.tables}, where=${sql.where}")
            val table = env.schema.get(sql.table.name)!!
            val locators = whereToLocators(receiver, table, sql.where)
            val atom = Atom.Delete(table, locators)
            env.effect.addAtom(atom)
            table.addPKey(locators.keys)
        }
        else -> {
            println("[ERR] Unknown type of update $sql")
        }
    }

    return AbstractValue.Unknown(self, self.calculateResolvedType())
}

fun castValue(col: Column, value: AbstractValue): AbstractValue {
    if (col.type == Type.Datetime && value is AbstractValue.Data && value.data is String) {
        return AbstractValue.Data(null, null, parseDateTimeString(value.data))
    } else if (col.type == Type.Real && value is AbstractValue.Data && value.data is Long) {
        return AbstractValue.Data(null, null, value.data.toDouble())
    } else {
        return value
    }
}

fun castValues(columnList: List<Column>, valueList: List<AbstractValue>): Map<Column, AbstractValue?> {
    val result = mutableMapOf<Column, AbstractValue?>()
    for ((col, value) in columnList.zip(valueList)) {
        result[col] = castValue(col, value)
    }
    return result
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
    val (column, aggKind) = receiver.columns[idx.toInt() - 1]
    val value = AbstractValue.DbState(self, self.calculateResolvedType(), receiver, column, aggKind)
    val argName = "${column.table.name}_${column.name}"
    env.effect.addArgv(argName, column.type)
    return value
}

fun notNilSemantics(self: Expression, env: Interpreter, receiver: AbstractValue?, args: List<AbstractValue>): AbstractValue {
    if (receiver !is AbstractValue.ResultSet) {
        println("[ERR] resultset was not successfully analyzed")
        return AbstractValue.Unknown(self, self.calculateResolvedType())
    }

    val receiver = receiver!! as AbstractValue.ResultSet
    assert(receiver.tables.size == 1)
    return AbstractValue.DbNotNil(self, self.calculateResolvedType(), receiver.stmt, receiver.tables[0]!!,
        whereToLocators(receiver.stmt, receiver.tables[0]!!, receiver.select.where))
}

fun whereToLocators(sql: AbstractValue.SqlStmt, table: Table, where: net.sf.jsqlparser.expression.Expression): Map<Column, AbstractValue> {
    when (where) {
        is EqualsTo -> {
            val locators = mutableMapOf<Column, AbstractValue>()

            // FIXME: only WHERE xx_id = ? is supported here
            val left = where.leftExpression.toString().substringAfter(".")
            if (left.contains("."))
                assert(left.substringBefore(".") == table.name)
            val col = table.get(left)!!
            val right = evalSqlExpr(where.rightExpression, sql, table)
            locators[col] = right

            return locators
        }
        is AndExpression -> {
            val leftLocators = whereToLocators(sql, table, where.leftExpression)
            val rightLocators = whereToLocators(sql, table, where.rightExpression)
            return leftLocators + rightLocators
        }
        else -> {
            throw IllegalArgumentException("where clause is not supported $where")
        }
    }
}

fun collectColumnsFromExpr(expr: net.sf.jsqlparser.expression.Expression?): Set<String> {
    when (expr) {
        null -> {
            return emptySet()
        }
        is net.sf.jsqlparser.schema.Column -> {
            return setOf(expr.fullyQualifiedName)
        }
        is JdbcParameter -> {
            return emptySet()
        }
        is EqualsTo -> {
            return collectColumnsFromExpr(expr.leftExpression) + collectColumnsFromExpr(expr.rightExpression)
        }
        is AndExpression -> {
            return collectColumnsFromExpr(expr.leftExpression) + collectColumnsFromExpr(expr.rightExpression)
        }
        else -> {
            throw IllegalArgumentException("cannot collect columns from $expr")
        }
    }
}

fun evalSqlExpr(expr: net.sf.jsqlparser.expression.Expression, sql: AbstractValue.SqlStmt, table: Table): AbstractValue {
    val exprStr = expr.toString()
    if (exprStr.startsWith("'")) {
        return AbstractValue.Data(null, null, exprStr.substringAfter("'").substringBefore("'"))
    } else if (Regex("\\d+").matches(exprStr)) {
        return AbstractValue.Data(null, null, exprStr.toInt())
    } else if (Regex("[nN][oO][wW] *\\( *\\)").matches(exprStr)) {
        return AbstractValue.Free(null, null, "now")
    } else if (exprStr.contains("null", ignoreCase = true)) {
        return AbstractValue.Null(null, null)
    } else if (exprStr == "?") {
        return sql.params[(expr as JdbcParameter).index]!!
    } else if (exprStr.equals("true", ignoreCase=true)) {
        return AbstractValue.Data(null, null, true)
    } else if (exprStr.equals("false", ignoreCase=true)) {
        return AbstractValue.Data(null, null, false)
    } else if (expr is net.sf.jsqlparser.schema.Column) {
        val colName = expr.columnName
        return AbstractValue.DbState(null, null, null, table.get(colName)!!, AggregateKind.ID)
    } else if (expr is Subtraction) {
        val left = evalSqlExpr(expr.leftExpression, sql, table)
        val right = evalSqlExpr(expr.rightExpression, sql, table)
        return left.sub(null, right)
    } else if (expr is Addition) {
        val left = evalSqlExpr(expr.leftExpression, sql, table)
        val right = evalSqlExpr(expr.rightExpression, sql, table)
        return left.add(null, right)
    } else {
        throw IllegalArgumentException("Cannot evaluate SQL expression $expr")
    }
}