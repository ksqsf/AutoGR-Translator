package AppSemantics

import AbstractValue
import AggregateKind
import com.github.javaparser.ast.expr.Expression
import knownSemantics
import Interpreter
import java.lang.IllegalArgumentException
import com.github.h0tk3y.betterParse.combinators.*
import com.github.h0tk3y.betterParse.grammar.Grammar
import com.github.h0tk3y.betterParse.grammar.parseToEnd
import com.github.h0tk3y.betterParse.lexer.literalToken
import com.github.h0tk3y.betterParse.lexer.regexToken
import com.github.h0tk3y.betterParse.parser.Parser

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
// 1. We adopt a conservative analysis strategy, i.e. we think column_data of
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

fun registerHealthPlusSemantics() {
    knownSemantics["com.hms.hms_test_2.DatabaseOperator.customInsertion"] = ::customInsertionSemantics
    knownSemantics["com.hms.hms_test_2.DatabaseOperator.customSelection"] = ::customSelectionSemantics
}

// NOTE: Some UPDATE queries are actually handled by customInsertion.
fun customInsertionSemantics(self: Expression, env: Interpreter, receiver: AbstractValue?, args: List<AbstractValue>): AbstractValue {
    val approxSql = approximateSQL(args[0])
    println("Insertion Approx = $approxSql")
    return AbstractValue.Unknown(self, self.calculateResolvedType())
}

fun customSelectionSemantics(self: Expression, env: Interpreter, receiver: AbstractValue?, args: List<AbstractValue>): AbstractValue {
    val approxSql = approximateSQL(args[0])
    println("Selection Approx = $approxSql")
    return AbstractValue.Unknown(self, self.calculateResolvedType())
}

var cnt = 0

/**
 * Return an approximation of the SQL string building expression, for further parsing.
 */
fun approximateSQL(av: AbstractValue): String {
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
                is String -> av.data
                is Long -> av.data.toString()
                is Double -> av.data.toString()
                else -> throw IllegalArgumentException("Unknown data type ${av.data::class}")
            }
        }
        is AbstractValue.Unknown -> {
            cnt += 1
            return "[[v$cnt|${av.e}]]"
        }
        is AbstractValue.Call -> {
            cnt += 1
            return "[[v$cnt]]"
        }
        is AbstractValue.Free -> {
            cnt += 1
            return "[[${av.e}$cnt]]"
        }
        is AbstractValue.Null -> {
            cnt += 1
            return "[[${av.e}$cnt]]"
        }
        else -> {
            throw IllegalArgumentException("Unknown $av")
        }
    }
}

sealed class SqlAst
data class SqlTable(val name: String)
data class SqlColumn(val name: String, val table: SqlTable?, val aggregateKind: AggregateKind): SqlAst()
data class SqlTemplate(val tag: String, val data: Any?): SqlAst()
data class SqlInsert(val table: SqlTable, val columns: List<SqlColumn>?, val values: List<SqlExpr>): SqlAst()
data class SqlUpdate(val table: SqlTable): SqlAst()
data class SqlSelect(val table: SqlTable): SqlAst()

sealed class SqlExpr : SqlAst()
data class SqlInt(val value: Long): SqlExpr()
data class SqlBool(val value: Boolean): SqlExpr()
data class SqlFunc(val funcName: String, val args: List<SqlExpr>): SqlExpr()
data class SqlInterpol(val value: String): SqlExpr()
data class SqlSingleton(val query: SqlSelect): SqlExpr()

object SqlGrammar : Grammar<SqlAst>() {
    // Lex
    private val ws by regexToken("\\s+", ignore = true)
    private val eq by literalToken("=")
    private val lpar by literalToken("(")
    private val rpar by literalToken(")")
    private val comma by literalToken(",")
    private val semicol by literalToken(";")
    private val dot by literalToken(".")
    private val tliteral by regexToken("\\[\\[[a-zA-Z0-9_]*\\]\\]")
    private val tru by literalToken("true")
    private val fal by literalToken("false")
    private val kwUpdate by literalToken("UPDATE")
    private val kwSelect by literalToken("SELECT")
    private val kwInsert by literalToken("INSERT")
    private val kwInto by literalToken("INTO")
    private val kwValues by literalToken("VALUES")
    private val kwValue by literalToken("VALUE")
    private val kwSet by literalToken("SET")
    private val kwWhere by literalToken("WHERE")
    private val kwMax by literalToken("MAX")
    private val kwFrom by literalToken("FROM")
    private val kwNow by literalToken("NOW")
    private val num by regexToken("-?\\d+")
    private val ident by regexToken("\\w+")
    private val string by regexToken("'[^']*'")

    // Expression
    private val number: Parser<SqlExpr> by num map { SqlInt(it.text.toLong()) }
    private val bool: Parser<SqlExpr> by
            tru map { SqlBool(true) } or
            fal map { SqlBool(false) }
    private val op: Parser<Char> by eq map { it.text[0] }
    private val interpolation: Parser<SqlExpr> by string map { SqlInterpol(it.text) }
    private val func by kwNow * -lpar * -rpar map { SqlFunc("NOW", emptyList()) }
    // private val singleton by select map { SqlSingleton(it) }
    private val expr: Parser<SqlExpr> by bool or number or interpolation or func // or singleton
    private val exprList: Parser<List<SqlExpr>> by separated(expr, comma) use { this.terms }

    // Syntax
    private val values by kwValues or kwValue
    private val tableName by ident map { SqlTable(it.text) }
    private val columnName by optional(tableName and dot) and ident map {
        SqlColumn(it.t2.text, it.t1?.t1, AggregateKind.ID)
    }
    private val aggregate by -kwMax * -lpar * columnName * -rpar map {
        SqlColumn(it.name, it.table, AggregateKind.MAX)
    }
    private val column by columnName or aggregate
    private val columnList by separated(column, comma) use { this.terms }
    private val template by tliteral map { SqlTemplate(it.text, null) }
    private val assignment by columnName * -eq * expr map { Pair(it.t1, it.t2) }
    private val assignmentList by separated(assignment, comma) use { this.terms }

    private val locator by columnName * op * expr
    private val locators by separated(locator, comma) use { this.terms }

    // Statements
    private val insert by -kwInsert * -kwInto * // INSERT INTO
            tableName * optional(-lpar * columnList * -rpar) * // table(col1, col2, ...)
            -values * -lpar * exprList * -rpar map {
        SqlInsert(it.t1, it.t2, it.t3)
    }
    private val select by -kwSelect * columnList * -kwFrom * tableName * optional(-kwWhere * locators) map {
        println(it)
        SqlSelect(it.t2)
    }
    private val update by -kwUpdate * tableName * -kwSet * (template or assignmentList) * kwWhere * locators map {
        println(it)
        SqlUpdate(it.t1)
    }

    override val rootParser by (insert or select or update) * -optional(semicol)
}

fun main() {
    val texts = listOf(
        "INSERT INTO table VALUES (1,2);",
        "INSERT INTO lab_appointment (lab_appointment_id,test_id,patient_id,doctor_id,date,cancelled) VALUES ('lapp[[v236]]' , '[[testID237]]' , '[[patienID238]]' , '[[doctorID239]]' , '[[v240]] [[v241]]:00' , false );",
        "UPDATE doctor SET [[v1]] WHERE slmc_reg_no = '[[v4|this.slmcRegNo]]'",
        "SELECT MAX(lab_appointment_id) FROM lab_appointment",
        "SELECT tmp_bill_id FROM tmp_bill WHERE patient_id = '[[patientID49]]';",
        // "SELECT appointment_id FROM appointment WHERE appointment_id = (SELECT MAX(appointment_id) FROM appointment);"
    )
    for (text in texts) {
        println(SqlGrammar.parseToEnd(text))
    }
}



// Still can't figure out how to make it work.
// https://github.com/h0tk3y/better-parse/issues/40
//
//var _cmd: Parser<Int>? = null
//
//object NestedGrammar : Grammar<Int>() {
//    val ws by regexToken("\\w+", ignore = true)
//    val num by regexToken("[0-9]+")
//    val lbrace by literalToken("{")
//    val rbrace by literalToken("}")
//    val semicol by literalToken(";")
//
//    val number: Parser<Int> by num use { text.toInt() }
//    val singleton: Parser<Int> by -lbrace * _cmd!! * -rbrace
//    val expr: Parser<Int> by number or singleton
//    val cmd: Parser<Int> by expr * -semicol
//    init {
//        _cmd = cmd
//    }
//    override val rootParser by cmd
//}
//
//fun main() {
//    val text = "{ 1; };"
//    NestedGrammar.parseToEnd(text)
//}
