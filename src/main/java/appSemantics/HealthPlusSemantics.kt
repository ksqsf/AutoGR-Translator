@file:Suppress("KDocUnresolvedReference", "UNUSED_PARAMETER")

package appSemantics

import AbstractValue
import AggregateKind
import Atom
import Column
import Interpreter
import Operator
import Schema
import Table
import Type
import com.github.h0tk3y.betterParse.combinators.*
import com.github.h0tk3y.betterParse.grammar.Grammar
import com.github.h0tk3y.betterParse.grammar.parseToEnd
import com.github.h0tk3y.betterParse.grammar.parser
import com.github.h0tk3y.betterParse.lexer.literalToken
import com.github.h0tk3y.betterParse.lexer.regexToken
import com.github.h0tk3y.betterParse.parser.Parser
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import knownSemantics
import java.lang.Exception
import java.util.*

// This file deals with the original HealthPlus code, not the synthetic one.
//
// There are some subtleties:
//
// HealthPlus builds SQL queries by constructing query strings, not a good practice, but anyway we stick to the
// original semantics.
//     "UPDATE sys_user SET " + column_data + " WHERE user_id=" +
//     "(SELECT user_id FROM doctor WHERE slmc_reg_no='" + this.slmcRegNo + "');"
// The pluses totally mess up the AST structure, and column_data is entirely opaque.
//
// 1. We adopt a conservative analysis strategy, i.e. we consider column_data
//        field1 = argv[field1], field2 = argv[field2], ...
//    Since argv are opaque to Z3, this is safe.
//
// 2. To parse it, we resort to a hand-made one. We collected all approximate SQL string templates, and the complex patterns are
//    1) INSERT INTO medical_history VALUES ('[[v7]]','[[patientID8]]','[[v9|this.slmcRegNo]]','[[v10]]','[[diagnostic11]]')
//    2) UPDATE person SET [...] WHERE person_id = (SELECT person_id FROM sys_user WHERE user_id = (SELECT user_id FROM doctor WHERE slmc_reg_no = '[...]'))
//    3) INSERT INTO tmp_bill ([...]) VALUES ([...])
//    4) SELECT stock_id FROM pharmacy_stock WHERE stock_id = (SELECT MAX(stock_id) FROM pharmacy_stock)
//
// Anyway, this is a pretty ad hoc strategy, and the relevant code is only put here.

@Suppress("unused")
class HealthPlusSemantics {
    private fun register() {
        knownSemantics["com.hms.hms_test_2.DatabaseOperator.customInsertion"] = ::customInsertionSemantics
        knownSemantics["com.hms.hms_test_2.DatabaseOperator.customDeletion"] = ::customInsertionSemantics
        knownSemantics["com.hms.hms_test_2.DatabaseOperator.customSelection"] = ::customSelectionSemantics
        knownSemantics["com.hms.hms_test_2.DatabaseOperator.addTableRow"] = ::addTableRowSemantics
        knownSemantics["com.hms.hms_test_2.DatabaseOperator.deleteTableRow"] = ::deleteTableRowSemantics
        knownSemantics["java.util.ArrayList.get"] = ::arrayListGetSemantics

        // HACK
        knownSemantics["Receptionist.Receptionist.refund"] = ::refundSemanticsHack

        // Calendar
        knownSemantics["java.util.Calendar.getInstance"] = ::calendarGetInstanceSemantics
        knownSemantics["java.util.Calendar.getTime"] = ::calendarGetTimeSemantics
        knownSemantics["java.util.Calendar.setTime"] = ::calendarSetTimeSemantics
        knownSemantics["java.util.Calendar.get"] = ::calendarGetSemantics
        knownSemantics["java.util.Calendar.add"] = ::calendarAddSemantics
        knownSemantics["java.text.SimpleDateFormat.format"] = ::dateFormatSemantics
    }
}

fun refundSemanticsHack(self: Expression, env: Interpreter, receiver: AbstractValue?, args: List<AbstractValue>): AbstractValue {
    // Some methods, Receptionist.Receptionist.cancelLabAppointment, calls refund.
    // Toposort guarantees refund() has been analyzed.
    val refundES = env.effect.analyzer.effectMap["Receptionist.Receptionist.refund(java.lang.String)"]!!
    assert(refundES.size == 1)
    env.effect.add(refundES.first())
    return AbstractValue.Unknown(null)
}

/**
 * Prototype: `boolean customInsertion(String sql)`. Returns `true` when update is successful.
 *
 * This function actually used for all kinds of updates, not only insertion.
 */
fun customInsertionSemantics(self: Expression, env: Interpreter, receiver: AbstractValue?, args: List<AbstractValue>): AbstractValue {
    try {
        val approxSql = approximateSQL(args[0])
        println("Insertion Approx = $approxSql")
        val atom = when (val sql = SqlGrammar.parseToEnd(approxSql.template)) {
            is SqlInsert -> atomizeInsert(sql, env, approxSql.values)
            is SqlDelete -> atomizeDelete(sql, env, approxSql.values)
            is SqlUpdate -> atomizeUpdate(sql, env, approxSql.values)
            else -> throw RuntimeException("Invalid SQL AST class for customInsertion: ${sql::class} of $sql")
        }
        env.effect.addAtom(atom)
        // Always assume the update is successful.
        return AbstractValue.Data(null, true)
    } catch (e: Exception) {
        println("[WARN] Cannot handle $self due to $e, ignoring")
        return AbstractValue.Unknown(null)
    }
}

fun convertColumns(sqlCols: List<SqlColumn>, defaultTable: Table, schema: Schema, interpreter: Interpreter): List<Pair<Column, AggregateKind>> {
    val res = mutableListOf<Pair<Column, AggregateKind>>()
    for (sqlCol in sqlCols) {
        when (sqlCol) {
            is SqlAllColumn -> {
                // Currently, we don't support `table.*`, and * always refers to defaultTable.
                res += defaultTable.columns.map { Pair(it, AggregateKind.ID) }
            }
            is SqlSingleColumn -> {
                val table = if (sqlCol.table == null) {
                    defaultTable
                } else {
                    schema[sqlCol.table.name]!!
                }
                res.add(Pair(table[sqlCol.name]!!, sqlCol.aggregateKind))
            }
        }
    }
    return res
}

fun evalSqlSelect(sql: SqlSelect, interpreter: Interpreter, tvalues: Map<Int, AbstractValue>): AbstractValue {
    val schema = interpreter.schema
    val defaultTable = schema[sql.table.name]!!
    val locators = convertLocators(sql.locators, defaultTable, interpreter, tvalues)
    // If sql.columns does not exist, assume it to be *
    val columns = convertColumns(sql.columns ?: listOf(SqlAllColumn), defaultTable, schema, interpreter)
    // Now, for each column create a DbState.
    return AbstractValue.DbStateList(null,
        sql,
        defaultTable,
        locators,
        columns.map { AbstractValue.DbState(null, it.first, it.second, locators) }
    )
}

/**
 * Prototype: `ArrayList<ArrayList<String>> customSelection(String sql)`.
 */
fun customSelectionSemantics(self: Expression, env: Interpreter, receiver: AbstractValue?, args: List<AbstractValue>): AbstractValue {
    val approxSql = approximateSQL(args[0])
    println("Selection Approx = $approxSql")
    val res = when (val sql = SqlGrammar.parseToEnd(approxSql.template)) {
        is SqlSelect -> evalSqlSelect(sql, env, approxSql.values)
        is SqlJoinSelect -> {
            // TODO: implement precise JOIN analysis
            AbstractValue.Unknown(null)
        }
        else -> throw RuntimeException("Invalid SQL AST class for customSelection: ${sql::class} of $sql")
    }
    println("customSelection: $res")
    return res
}

fun arrayListGetSemantics(self: Expression, env: Interpreter, receiver: AbstractValue?, args: List<AbstractValue>): AbstractValue {
    if (receiver == null || receiver !is AbstractValue.DbStateList) {
        return AbstractValue.Unknown(self)
    }

    // customSelection(sql).get(i) -> the i-th record in the resultset
    if (receiver.knownExisting == null) {
        fun locatorOk(v: AbstractValue): Boolean {
            when (v) {
                is AbstractValue.DbState -> {
                    if (v.aggregateKind == AggregateKind.MAX)
                        return false
                    if (v.locators?.isEmpty() == true)
                        return false
                    return true
                }
                else -> return true
            }
        }
        val locators = receiver.locators.filter { locatorOk(it.value) }
        val cond = AbstractValue.DbNotNil(self, receiver.table, locators)
        return AbstractValue.DbStateList(receiver.e, receiver.query, receiver.table, receiver.locators, receiver.result,
            cond)
    }

    // customSelection(sql).get(i).get(0) -> the first column
    // assert this row exists
    if (receiver.knownExisting.locators.isNotEmpty()) {
        env.effect.addCondition(receiver.knownExisting)
    }
    // return the dbstate
    val idx = (args[0] as AbstractValue.Data).data as Long
    val value = receiver.result[idx.toInt()]
    return if (value.type() == Type.Int) {
        AbstractValue.Unary(null, Operator.I2S, value)
    } else {
        value
    }
}

/**
 * Prototype: `boolean DatabaseOperator.addTableRow(String table, String tableData)`. Returns `true` when the new record
 * inserted successfully.
 *
 * This operator simply inserts a new record into the specified table. All we can know is that a new record is inserted,
 * it's quite hard to extract anything meaningful from tableData, so we resort to a conservative strategy.
 */
fun addTableRowSemantics(self: Expression, env: Interpreter, receiver: AbstractValue?, args: List<AbstractValue>): AbstractValue {
    val table = env.schema[(args[0] as AbstractValue.Data).data as String]!!
    val assns = mutableMapOf<Column, AbstractValue>()
    for (column in table.columns) {
        assns[column] = env.freshArg("addTableRow", column.type)
    }
    val insert = Atom.Insert(table, assns)
    env.effect.addAtom(insert)
    // Always assume the insertion is successful.
    return AbstractValue.Data(null, true)
}

/**
 * Prototype: `void DatabaseOperator.deleteTableRow(String table, String columnName, String fieldValue)`.
 *
 * This is equivalent to `DELETE FROM [[table]] WHERE [[columnName]] = [[fieldValue]]`.
 */
fun deleteTableRowSemantics(self: Expression, env: Interpreter, receiver: AbstractValue?, args: List<AbstractValue>): AbstractValue {
    val table = env.schema[(args[0] as AbstractValue.Data).data as String]!!
    val column = table[(args[1] as AbstractValue.Data).data as String]!!
    val value = args[2]
    val delete = Atom.Delete(table, mapOf(column to value))
    env.effect.addAtom(delete)
    return AbstractValue.Unknown(self)
}

/**
 * A template is a string containing `[[JavaExpr]]`, which indicates the evaluation result should be inserted here.
 * `'[[Expr]]'` indicates the result should be cast to [String].
 *
 * If a template is naked, then the template can stand for values of any type. A contextual type determines what type it
 * should be. E.g., a context like `[[x]]-10` implies x must be an integer.
 *
 * If the expression is unable to be evaluated, it's considered `Unknown` and an argument will be added for it.
 *
 * E.g., `SELECT person_id FROM doctor WHERE slmc_reg_no = [[this.slmcRegNo]]` relies on a local state, so it's considered `Unknown`.
 * It will be translated as `doctor[slmc_reg_no=arg].person_id`.
 *
 * @param template the template string, either a naked one (`[[x]]`) or a string interpolation (`prefix[[x]]suffix`)
 * @param interpreter
 * @param contextualType the type expected by the context
 */
fun evalTemplate(template: String, interpreter: Interpreter, contextualType: Type, tvalues: Map<Int, AbstractValue>): AbstractValue {
    // 'str'
    if (!template.contains("[[")) {
        return AbstractValue.Data(null, template.removeSurrounding("'"))
    }
    val format = template.removeSurrounding("'").substringAfter("[[").substringBefore("]]")
    // [[?n]]
    if (format.startsWith("?")) {
        return tvalues[format.substringAfter("?").toInt()]!!
    }
    // [[exp]] or [[tag|exp]]
    val sep = format.indexOf("|")
    val exprStr = format.substring(sep+1)
    return interpreter.lookup(exprStr)?.getKnown()?.cast(contextualType) ?: interpreter.freshArg(exprStr, contextualType)
}

/**
 * Evaluate a [SqlExpr] into an [AbstractValue].
 *
 * @param table the default table referred to by a naked column name
 * @param interpreter used for evaluating a template, see [evalTemplate]
 * @param contextualType used for determining the type of a template, see [evalTemplate]
 */
fun evalSQLExpr(expr: SqlExpr, table: Table, interpreter: Interpreter, contextualType: Type, tvalues: Map<Int, AbstractValue>): AbstractValue {
    fun singletonToDbState(select: SqlSelect, schema: Schema): AbstractValue.DbState {
        val selectTbl = schema[select.table.name]!!
        val locators = mutableMapOf<Column, AbstractValue>()
        for (locator in select.locators) {
            locators[selectTbl[locator.column.name]!!] = evalSQLExpr(locator.value, selectTbl, interpreter, contextualType, tvalues)
        }
        val col = select.columns!![0] as SqlSingleColumn
        // Workaround some bugs in the original code.
        return try {
            AbstractValue.DbState(null, selectTbl[col.name]!!, col.aggregateKind, locators)
        } catch (e: NullPointerException) {
            println("[ERR] select table doesn't have ${col.name}, this is likely to be a bug in the original project; expr=$expr")
            AbstractValue.DbState(null, table[col.name]!!, col.aggregateKind, locators)
        }
    }

    when (expr) {
        // Template-related reflection
        is SqlInterpol -> return evalTemplate(expr.value, interpreter, contextualType, tvalues)
        is SqlTemplateValue -> return evalTemplate(expr.tag, interpreter, contextualType, tvalues)
        // Conventional SQL
        is SqlInt -> return AbstractValue.Data(null, expr.value)
        is SqlBool -> return AbstractValue.Data(null, expr.value)
        is SqlSingleton -> return singletonToDbState(expr.query, interpreter.schema)
        is SqlColRef -> {
            val col = table[expr.column.name]!!
            return AbstractValue.DbState(null, col, expr.column.aggregateKind)
        }
        is SqlBinary -> {
            val left = evalSQLExpr(expr.left, table, interpreter, Type.Int, tvalues)
            val right = evalSQLExpr(expr.right, table, interpreter, Type.Int, tvalues)
            if (left is AbstractValue.Unknown || right is AbstractValue.Unknown) {
                return AbstractValue.Unknown(null)
            }
            return when (expr.op) {
                SqlOperator.ADD -> left.add(null, right)
                SqlOperator.SUB -> left.sub(null, right)
                SqlOperator.MUL -> left.mul(null, right)
                SqlOperator.DIV -> left.div(null, right)
                SqlOperator.EQ -> left.eq(null, right)
                SqlOperator.LT -> left.lt(null, right)
                SqlOperator.LE -> left.le(null, right)
                SqlOperator.GT -> left.gt(null, right)
                SqlOperator.GE -> left.ge(null, right)
            }
        }
        is SqlFunc -> return dispatchSQLFunc(expr.funcName, expr.args, interpreter)
    }
}

/**
 * Evaluate a [SqlFunc].
 */
fun dispatchSQLFunc(funcName: String, args: List<SqlExpr>, env: Interpreter): AbstractValue {
    if (funcName.equals("now", ignoreCase = true)) {
        env.effect.addArgv("now", Type.Int)
        return AbstractValue.Free(null, "now", Type.Int)
    } else {
        throw IllegalArgumentException("Unknown SQL function $funcName")
    }
}

/**
 * Convert a [SqlUpdate] object into an Atom object. Nested `SELECT`s are correctly translated to [AbstractValue.DbState] by [evalSQLExpr].
 *
 * Unknown templates are translated into a list of `Free` arguments.
 */
fun atomizeUpdate(update: SqlUpdate, interpreter: Interpreter, tvalues: Map<Int, AbstractValue>): Atom.Update {
    val table = interpreter.schema[update.table.name]!!
    val locators = convertLocators(update.locators, table, interpreter, tvalues)
    val values = mutableMapOf<Column, AbstractValue>()
    if (update.columns == null) {
        for (tableCol in table.columns) {
            // Assume the update doesn't update the indexing key and the primary key itself.
            // This assumption is true for HealthPlus, but is not universally true.
            if (tableCol !in locators.keys && !tableCol.name.endsWith("id", ignoreCase = true) &&
                    !tableCol.name.endsWith("no", ignoreCase = true)) {
                values[tableCol] = interpreter.freshArg(tableCol.name, tableCol.type)
            }
        }
    } else {
        for (assn in update.columns) {
            val column = table[assn.column.name]!!
            val value = evalSQLExpr(assn.value, table, interpreter, column.type, tvalues)
            values[column] = value
        }
    }
    return Atom.Update(table, locators, values)
}

/**
 * Convert a [SqlDelete] object into an Atom object. Nested `SELECT`s are correctly translated to [AbstractValue.DbState] by [evalSQLExpr].
 *
 * Unknown templates are translated into a list of `Free` arguments.
 */
fun atomizeDelete(delete: SqlDelete, interpreter: Interpreter, tvalues: Map<Int, AbstractValue>): Atom.Delete {
    val table = interpreter.schema[delete.table.name]!!
    val locators = convertLocators(delete.locators, table, interpreter, tvalues)
    return Atom.Delete(table, locators)
}

/**
 * Convert a [SqlInsert] object into an Atom object.
 *
 * Unknown templates are translated into a list of `Free` arguments.
 */
fun atomizeInsert(insert: SqlInsert, interpreter: Interpreter, tvalues: Map<Int, AbstractValue>): Atom.Insert {
    val table = interpreter.schema[insert.table.name]!!
    val values = mutableMapOf<Column, AbstractValue>()
    if (insert.values == null) {
        // NULL values correspond to `INSERT INTO table VALUES ([[...]])` or `INSERT INTO table([[...]]) VALUES ([[...]])`.
        for (column in table.columns) {
            values[column] = interpreter.freshArg("insertValue", column.type)
        }
    } else {
        // NULL columns correspond to either `INSERT INTO table([[...]])` or `INSERT INTO table`. However, the first case
        // is already handled above.
        val columns = insert.columns?.map { table[it.name]!! } ?: table.columns
        for ((column, value) in columns zip insert.values) {
            values[column] = evalSQLExpr(value, table, interpreter, column.type, tvalues)
        }
    }
    return Atom.Insert(table, values)
}

/**
 * Convert a list of [SqlLocator]s into the Effect-style locator set.
 *
 * @param locators
 * @param table the default table to which the 'naked' column refers
 * @param interpreter
 */
fun convertLocators(locators: List<SqlLocator>, table: Table, interpreter: Interpreter, tvalues: Map<Int, AbstractValue>): Map<Column, AbstractValue> {
    val schema = interpreter.schema
    val res = mutableMapOf<Column, AbstractValue>()
    val locatorCols = mutableSetOf<Column>()
    for (locator in locators) {
        val columnName = locator.column.name
        val columnTable = locator.column.table
        val column = if (columnTable == null) {
            table[columnName]!!
        } else {
            schema[columnTable.name]!![columnName]!!
        }
        locatorCols.add(column)
        res[column] = evalSQLExpr(locator.value, table, interpreter, column.type, tvalues)
    }
    // Declare locator cols as pkey
    table.addPKey(locatorCols.filter { it.table == table }.toSet())
    return res
}

data class SqlApprox(val template: String, val values: Map<Int, AbstractValue> = emptyMap()) {
    operator fun plus(rhs: SqlApprox): SqlApprox {
        return SqlApprox(template + rhs.template, values + rhs.values)
    }
}

var cnt = 0
/**
 * Return an approximation of the SQL string building expression, for further parsing.
 */
fun approximateSQL(av: AbstractValue): SqlApprox {
    when (av) {
        is AbstractValue.Binary -> {
            when (av.op) {
                Operator.ADD -> {
                    return approximateSQL(av.left) + approximateSQL(av.right)
                }
                else -> {
                    throw IllegalArgumentException("Unknown binary operator ${av.op}")
                }
            }
        }
        is AbstractValue.Data -> {
            return when (av.data) {
                is String -> SqlApprox(av.data)
                is Long -> SqlApprox(av.data.toString())
                is Double -> SqlApprox(av.data.toString())
                else -> throw IllegalArgumentException("Unknown data type ${av.data::class}")
            }
        }
        is AbstractValue.Unknown -> {
            return if (av.tag != null && av.tag is String) {
                SqlApprox("[[${av.tag}]]")
            } else {
                cnt += 1
                SqlApprox("[[v$cnt|${av.e}]]")
            }
        }
        is AbstractValue.Call -> {
            cnt += 1
            return SqlApprox("[[v$cnt]]")
        }
        is AbstractValue.Free -> {
            return SqlApprox("[[${av.name}]]")
        }
        is AbstractValue.Null -> {
            return SqlApprox("[[${av.e}]]")
        }
        is AbstractValue.DbState -> {
            cnt += 1
            return SqlApprox("[[?$cnt]]", mapOf(cnt to av))
        }
        is AbstractValue.Unary -> {
            cnt += 1
            return SqlApprox("[[?$cnt]]", mapOf(cnt to av))
        }
        else -> {
            throw IllegalArgumentException("Unknown $av")
        }
    }
}

//
// Custom SQL parser.
//
enum class SqlOperator {
    EQ, LT, GT, LE, GE, ADD, SUB, MUL, DIV;

    override fun toString(): String {
        return when (this) {
            EQ -> "="
            LT -> "<"
            GT -> ">"
            LE -> "<="
            GE -> ">="
            ADD -> "+"
            SUB -> "-"
            MUL -> "*"
            DIV -> "/"
        }
    }
}

data class SqlAssign(val column: SqlSingleColumn, val value: SqlExpr) {
    override fun toString(): String {
        return "$column = $value"
    }
}
data class SqlLocator(val column: SqlSingleColumn, val op: SqlOperator, val value: SqlExpr) {
    override fun toString(): String {
        return "$column $op $value"
    }
}

sealed class SqlAst
data class SqlTable(val name: String) {
    override fun toString(): String {
        return name
    }
}
sealed class SqlColumn : SqlAst()
object SqlAllColumn : SqlColumn() {
    override fun toString(): String {
        return "*"
    }
}
data class SqlSingleColumn(val name: String, val table: SqlTable?, val aggregateKind: AggregateKind): SqlColumn() {
    override fun toString(): String {
        return if (table != null) {
            "${table.name}.$name"
        } else {
            name
        }
    }
}
data class SqlTemplate(val tag: String, val data: Any?): SqlAst()
data class SqlInsert(val table: SqlTable, val columns: List<SqlSingleColumn>?, val values: List<SqlExpr>?): SqlAst() {
    override fun toString(): String {
        return "INSERT INTO $table (${columns?.joinToString(", ")}) VALUES (${values?.joinToString(", ")})"
    }
}
data class SqlUpdate(val table: SqlTable, val columns: List<SqlAssign>?, val locators: List<SqlLocator>): SqlAst() {
    override fun toString(): String {
        return "UPDATE $table SET $columns WHERE ${locators.joinToString(", ")}"
    }
}
data class SqlSelect(val table: SqlTable, val columns: List<SqlColumn>?, val locators: List<SqlLocator>): SqlAst() {
    override fun toString(): String {
        return if (locators.isNotEmpty())
            "SELECT ${columns?.joinToString(", ")} FROM $table WHERE ${locators.joinToString(", ")}"
        else
            "SELECT ${columns?.joinToString(", ")} FROM $table"
    }
}
data class SqlJoinSelect(val tableLeft: SqlTable, val tableRight: SqlTable,
                         val joinLeftAttr: SqlSingleColumn, val joinRightAttr: SqlSingleColumn,
                         val selectAttrs: List<SqlColumn>?, val locators: List<SqlLocator>) : SqlAst()
{
    override fun toString(): String {
        return "SELECT ${selectAttrs?.joinToString(", ")} FROM $tableLeft JOIN $tableRight ON $joinLeftAttr = $joinRightAttr WHERE ${locators.joinToString(", ")}"
    }
}
data class SqlDelete(val table: SqlTable, val locators: List<SqlLocator>): SqlAst() {
    override fun toString(): String {
        return "DELETE FROM $table WHERE $locators"
    }
}

sealed class SqlExpr : SqlAst()
data class SqlInt(val value: Long): SqlExpr() {
    override fun toString(): String {
        return value.toString()
    }
}
data class SqlBool(val value: Boolean): SqlExpr() {
    override fun toString(): String {
        return value.toString()
    }
}
data class SqlFunc(val funcName: String, val args: List<SqlExpr>): SqlExpr() {
    override fun toString(): String {
        return "$funcName(${args.joinToString(",") { it.toString() }})"
    }
}
data class SqlInterpol(val value: String): SqlExpr() {
    override fun toString(): String {
        return value
    }
}
data class SqlSingleton(val query: SqlSelect): SqlExpr() {
    override fun toString(): String {
        return "($query)"
    }
}
data class SqlColRef(val column: SqlSingleColumn): SqlExpr() {
    override fun toString(): String {
        return column.toString()
    }
}
data class SqlTemplateValue(val tag: String): SqlExpr() {
    override fun toString(): String {
        return tag
    }
}
data class SqlBinary(val left: SqlExpr, val right: SqlExpr, val op: SqlOperator): SqlExpr() {
    override fun toString(): String {
        return "$left $op $right"
    }
}

object SqlGrammar : Grammar<SqlAst>() {
    // Lex
    @Suppress("unused")
    private val ws by regexToken("\\s+", ignore = true)
    private val eq by literalToken("=")
    private val lpar by literalToken("(")
    private val rpar by literalToken(")")
    private val comma by literalToken(",")
    private val semicol by literalToken(";")
    private val dot by literalToken(".")
    private val asterisk by literalToken("*")
    private val tliteral by regexToken("\\[\\[[a-zA-Z0-9_]*]]")
    private val tru by literalToken("true")
    private val fal by literalToken("false")
    private val kwUpdate by literalToken("UPDATE")
    private val kwSelect by literalToken("SELECT")
    private val kwInsert by literalToken("INSERT")
    private val kwDelete by literalToken("DELETE")
    private val kwInto by literalToken("INTO")
    private val kwValues by literalToken("VALUES")
    private val kwValue by literalToken("VALUE")
    private val kwSet by literalToken("SET")
    private val kwWhere by literalToken("WHERE")
    private val kwMax by literalToken("MAX")
    private val kwFrom by literalToken("FROM")
    private val kwNow by literalToken("NOW")
    private val kwAnd by literalToken("AND")
    private val kwInner by literalToken("INNER")
    private val kwJoin by literalToken("JOIN")
    private val kwOn by literalToken("ON")
    private val kwLimit by literalToken("LIMIT")
    private val num by regexToken("-?\\d+")
    private val ident by regexToken("\\w+")
    private val string by regexToken("'[^']*'")
    private val add by literalToken("+")
    private val sub by literalToken("-")
    private val mul by literalToken("*")
    private val div by literalToken("/")

    // Expression
    private val number: Parser<SqlExpr> by num map { SqlInt(it.text.toLong()) }
    private val bool: Parser<SqlExpr> by
            tru map { SqlBool(true) } or
            fal map { SqlBool(false) }
    private val op: Parser<SqlOperator> by eq map { when (it.text) {
        "=" -> SqlOperator.EQ
        ">" -> SqlOperator.GT
        ">=" -> SqlOperator.GE
        "<" -> SqlOperator.LT
        "<=" -> SqlOperator.LE
        else -> throw RuntimeException("Unknown comparison operator ${it.text}")
    } }
    private val interpolation: Parser<SqlExpr> by string map { SqlInterpol(it.text) }
    private val func by kwNow * -lpar * -rpar map { SqlFunc("NOW", emptyList()) }
    private val colRef by parser { singleColumn map { SqlColRef(it) } }
    private val singleton by parser { select map { SqlSingleton(it) } }
    private val exprParens by parser { -lpar * expr * -rpar }
    private val templateValue by tliteral map { SqlTemplateValue(it.text) }
    private val term by parser { exprParens or bool or number or interpolation or func or colRef or singleton or templateValue }
    private val mulDiv: Parser<SqlOperator> by mul or div map {
        when (it.text) {
            "*" -> SqlOperator.MUL
            "/" -> SqlOperator.DIV
            else -> throw RuntimeException("unreachable")
        }
    }
    private val mulDivExpr by leftAssociative(term, mulDiv) {
        l, op, r -> SqlBinary(l, r, op)
    }
    private val addSub: Parser<SqlOperator> by add or sub map {
        when (it.text) {
            "+" -> SqlOperator.ADD
            "-" -> SqlOperator.SUB
            else -> throw RuntimeException("unreachable")
        }
    }
    private val addSubExpr by leftAssociative(mulDivExpr, addSub) {
        l, op, r -> SqlBinary(l, r, op)
    }
    private val expr: Parser<SqlExpr> by addSubExpr
    private val exprList by separated(expr, comma) use { this.terms }

    // Syntax
    private val values by kwValues or kwValue
    private val tableName by ident map { SqlTable(it.text) }
    private val columnName by optional(tableName and dot) and ident map {
        SqlSingleColumn(it.t2.text, it.t1?.t1, AggregateKind.ID)
    }
    private val aggregate by -kwMax * -lpar * columnName * -rpar map {
        SqlSingleColumn(it.name, it.table, AggregateKind.MAX)
    }
    private val singleColumn by columnName or aggregate
    private val column by (asterisk asJust SqlAllColumn) or singleColumn
    private val singleColumnList by separated(singleColumn, comma) use { this.terms }
    private val columnList by separated(column, comma) use { this.terms }
    private val template by tliteral map { SqlTemplate(it.text, null) }
    private val assignment by columnName * -eq * expr map { SqlAssign(it.t1, it.t2) }
    private val assignmentList by separated(assignment, comma) use { this.terms }
    private val locator by columnName * op * expr use { SqlLocator(t1, t2, t3) }
    private val locators by separated(locator, kwAnd) use { this.terms }
    private val limit by kwLimit * expr

    // Workaround for Admin.Admin.updateAccountInfo
    private val commandEnd by optional(rpar) and semicol

    // Statements
    @Suppress("UNCHECKED_CAST")
    private val insert by -kwInsert * -kwInto * // INSERT INTO
            tableName * optional(-lpar * optional(template or singleColumnList) * -rpar) * // table(col1, col2, ...)
            -values * -lpar * optional(template or exprList) * -rpar map {
        val values = if (it.t3 == null || it.t3 is SqlTemplate) {
            null
        } else {
            it.t3 as List<SqlExpr>
        }
        val columns = if (it.t2 == null || it.t2 is SqlTemplate) {
            null
        } else {
            it.t2 as List<SqlSingleColumn>?
        }
        SqlInsert(it.t1, columns, values)
    }
    private val select by -kwSelect * columnList * -kwFrom * tableName * optional(-kwWhere * locators) map {
        SqlSelect(it.t2, it.t1, it.t3 ?: emptyList())
    }
    private val joinSelect by -kwSelect * columnList * -kwFrom * tableName * -kwInner * -kwJoin * tableName * -kwOn *
            singleColumn * -eq * singleColumn * -kwWhere * locators use {
        SqlJoinSelect(t2, t3, t4, t5, t1, t6)
    }
    @Suppress("UNCHECKED_CAST")
    private val update by -kwUpdate * tableName * -kwSet * (template or assignmentList) * -kwWhere * locators map {
        val columns = if (it.t2 is SqlTemplate) {
            null
        } else {
            it.t2 as List<SqlAssign>
        }
        SqlUpdate(it.t1, columns, it.t3)
    }
    private val delete by -kwDelete * -kwFrom * tableName * -kwWhere * locators * optional(limit) map {
        SqlDelete(it.t1, it.t2)
    }

    override val rootParser by (insert or joinSelect or select or update or delete) * -optional(commandEnd)
}


//////////////
// Calendar //
//////////////
data class CalendarState(val e: Expression?) : AbstractValue(e)
data class DateState(val e: Expression?, var date: AbstractValue) : AbstractValue(e)

fun calendarGetInstanceSemantics(self: Expression, env: Interpreter, receiver: AbstractValue?, args: List<AbstractValue>): AbstractValue {
    return CalendarState(self)
}

fun calendarGetSemantics(self: Expression, env: Interpreter, receiver: AbstractValue?, args: List<AbstractValue>): AbstractValue {
    val field = args[0]
    assert(field is AbstractValue.Unknown && field.e != null && field.e is FieldAccessExpr && field.e.toString() == "Calendar.DAY_OF_WEEK")
    env.effect.addArgv("__today", Type.Datetime)
    return AbstractValue.Free(self, "__today", Type.Datetime)
}

fun calendarGetTimeSemantics(self: Expression, env: Interpreter, receiver: AbstractValue?, args: List<AbstractValue>): AbstractValue {
    env.effect.addArgv("now", Type.Datetime)
    return AbstractValue.Free(self, "now", Type.Datetime)
}

fun calendarSetTimeSemantics(self: Expression, env: Interpreter, receiver: AbstractValue?, args: List<AbstractValue>): AbstractValue {
    val arg = args[0]
    if (receiver == null || receiver !is DateState || arg !is DateState) {
        return AbstractValue.Unknown(self)
    }
    return DateState(self, arg.date)
}

fun calendarAddSemantics(self: Expression, env: Interpreter, receiver: AbstractValue?, args: List<AbstractValue>): AbstractValue {
    val field = args[0]
    val delta = args[1]
    if (receiver == null || receiver !is DateState)
        return AbstractValue.Unknown(self)
    assert(field is AbstractValue.Unknown && field.e.toString() == "Calendar.DATE")
    receiver.date = receiver.date.add(self, delta)
    return AbstractValue.Unknown(self)
}

fun dateFormatSemantics(self: Expression, env: Interpreter, receiver: AbstractValue?, args: List<AbstractValue>): AbstractValue {
    val date = args[0]
    if (date !is DateState) {
        return AbstractValue.Unknown(self)
    }
    // Pass down the datetime information.
    return date.cast(Type.String)
}
