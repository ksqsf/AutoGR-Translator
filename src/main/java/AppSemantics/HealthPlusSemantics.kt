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
import com.github.h0tk3y.betterParse.grammar.parser
import com.github.h0tk3y.betterParse.lexer.literalToken
import com.github.h0tk3y.betterParse.lexer.regexToken
import com.github.h0tk3y.betterParse.parser.Parser
import java.lang.RuntimeException

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
        return "(${query.toString()})"
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
    private val ws by regexToken("\\s+", ignore = true)
    private val eq by literalToken("=")
    private val lpar by literalToken("(")
    private val rpar by literalToken(")")
    private val comma by literalToken(",")
    private val semicol by literalToken(";")
    private val dot by literalToken(".")
    private val asterisk by literalToken("*")
    private val tliteral by regexToken("\\[\\[[a-zA-Z0-9_]*\\]\\]")
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

    // Statements
    private val insert by -kwInsert * -kwInto * // INSERT INTO
            tableName * optional(-lpar * (template or singleColumnList) * -rpar) * // table(col1, col2, ...)
            -values * -lpar * (template or exprList) * -rpar map {
        val values = if (it.t3 is SqlTemplate) {
            null
        } else {
            it.t3 as List<SqlExpr>
        }
        val columns = if (it.t2 is SqlTemplate) {
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
    private val update by -kwUpdate * tableName * -kwSet * (template or assignmentList) * -kwWhere * locators map {
        val columns = if (it.t2 is SqlTemplate) {
            null
        } else {
            it.t2 as List<SqlAssign>
        }
        SqlUpdate(it.t1, columns, it.t3)
    }
    private val delete by -kwDelete * -kwFrom * tableName * -kwWhere * locators map {
        SqlDelete(it.t1, it.t2)
    }

    override val rootParser by (insert or joinSelect or select or update or delete) * -optional(semicol)
}

fun main() {
    val texts = listOf(
        "UPDATE person SET [[v1]] WHERE person_id = (SELECT person_id FROM sys_user WHERE user_id = (SELECT user_id FROM doctor WHERE slmc_reg_no = '[[v2|this.slmcRegNo]]'));",
        "UPDATE doctor SET [[v3]] WHERE slmc_reg_no = '[[v4|this.slmcRegNo]]';",
        "UPDATE sys_user SET [[v5]] WHERE user_id = (SELECT user_id FROM doctor WHERE slmc_reg_no = '[[v6|this.slmcRegNo]]');",
        "SELECT history_id FROM medical_history WHERE history_id = (SELECT MAX(history_id) FROM medical_history);",
        "INSERT INTO medical_history VALUES ('his[[v7]]','[[patientID8]]','[[v9|this.slmcRegNo]]','[[v10]]','[[diagnostic11]]')",
        "SELECT tmp_bill_id FROM tmp_bill WHERE patient_id = '[[patientID12]]';",
        "UPDATE tmp_bill SET laboratory_fee = '[[labFee13]]' WHERE tmp_bill_id = '[[v14]]';",
        "SELECT tmp_bill_id FROM tmp_bill WHERE patient_id = '[[patientID15]]';",
        "UPDATE tmp_bill SET laboratory_fee = '[[labFee16]]' WHERE tmp_bill_id = '[[v17]]';",
        "SELECT tmp_bill_id FROM tmp_bill WHERE tmp_bill_id = (SELECT MAX(tmp_bill_id) FROM tmp_bill);",
        "INSERT INTO tmp_bill([[column]]) VALUES([[column]]);",
        "SELECT drug_allergies_and_reactions FROM patient WHERE patient_id = '[[patientID18]]';",
        "SELECT drug_allergies_and_reactions FROM patient WHERE patient_id = '[[patientID19]]';",
        "UPDATE patient SET drug_allergies_and_reactions = '[[v20]]' WHERE patient_id = '[[patientID21]]';",
        "SELECT drug_id FROM drug WHERE drug_id = (SELECT MAX(drug_id) FROM drug);",
        "INSERT INTO drug([[column]]) VALUES([[column]]);",
        "SELECT stock_id FROM pharmacy_stock WHERE stock_id = (SELECT MAX(stock_id) FROM pharmacy_stock);",
        "INSERT INTO pharmacy_stock([[column]]) VALUES([[column]]);",
        "UPDATE pharmacy_stock SET remaining_quantity = remaining_quantity -[[qt22]] WHERE stock_id = '[[stkID23]]';",
        "SELECT drug_id FROM drug WHERE drug_id = (SELECT MAX(drug_id) FROM drug);",
        "INSERT INTO drug VALUES ('d[[v24]]','[[genName25]]',0);",
        "SELECT brand_id FROM drug_brand_names WHERE brand_id = (SELECT MAX(brand_id) FROM drug_brand_names);",
        "INSERT INTO drug_brand_names VALUES ('br[[v26]]','[[brandName27]]','[[genName28]]','[[type29]]','[[unit30]]','[[price31]]');",
        "SELECT supplier_id FROM suppliers WHERE supplier_id = (SELECT MAX(supplier_id) FROM suppliers);",
        "INSERT INTO suppliers VALUES ('sup[[v32]]','[[suppName33]]');",
        "SELECT stock_id FROM pharmacy_stock WHERE stock_id = (SELECT MAX(stock_id) FROM pharmacy_stock);",
        "INSERT INTO pharmacy_stock VALUES ('stk[[v34]]','[[drugID35]]','[[brandID36]]','[[stock37]]','[[stock38]]','[[manuDate39]]','[[expDate40]]','[[suppID41]]','[[date42]]');",
        "UPDATE person SET [[v43]] WHERE person_id = (SELECT person_id FROM sys_user WHERE user_name = '[[v44|super.username]]');",
        "UPDATE pharmacist SET [[v45]] WHERE pharmacist_id = '[[v46|this.pharmacistID]]';",
        "UPDATE sys_user SET [[v47]] WHERE user_id = '[[v48|this.userID]]';",
        "SELECT tmp_bill_id FROM tmp_bill WHERE patient_id = '[[patientID49]]';",
        "UPDATE tmp_bill SET pharmacy_fee = '[[pharmacyFee50]]' WHERE tmp_bill_id = '[[v51]]';",
        "SELECT tmp_bill_id FROM tmp_bill WHERE patient_id = '[[patientID52]]';",
        "UPDATE tmp_bill SET pharmacy_fee = '[[pharmacyFee53]]' WHERE tmp_bill_id = '[[v54]]';",
        "SELECT tmp_bill_id FROM tmp_bill WHERE tmp_bill_id = (SELECT MAX(tmp_bill_id) FROM tmp_bill);",
        "INSERT INTO tmp_bill([[column]]) VALUES([[column]]);",
        "UPDATE person SET [[v55]] WHERE person_id = (SELECT person_id FROM sys_user WHERE user_name = '[[v56|super.username]]');",
        "UPDATE lab_assistant SET [[v57]] WHERE lab_assistant_id = '[[v58|this.labAssistantID]]';",
        "UPDATE sys_user SET [[v59]] WHERE user_id = '[[v60|this.userID]]';",
        "SELECT tst_ur_id FROM UrineFullReport WHERE tst_ur_id = (SELECT MAX(tst_ur_id) FROM UrineFullReport);",
        "INSERT INTO UrineFullReport(tst_ur_id, appointment_id, appearance,sgRefractometer,ph,protein,glucose,ketoneBodies,bilirubin,urobilirubin,contrifugedDepositsphaseContrastMicroscopy,pusCells,redCells,epithelialCells,casts,cristals,date) VALUES('ur[[v61]]','[[appointment_id62]]','[[appearance63]]','[[sgRefractometer64]]','[[ph65]]','[[protein66]]','[[glucose67]]','[[ketoneBodies68]]','[[bilirubin69]]','[[urobilirubin70]]','[[contrifugedDepositsphaseContrastMicroscopy71]]','[[pusCells72]]','[[redCells73]]','[[epithelialCells74]]','[[casts75]]','[[cristals76]]',NOW())",
        "SELECT tst_li_id FROM LipidTest WHERE tst_li_id = (SELECT MAX(tst_li_id) FROM LipidTest);",
        "INSERT INTO LipidTest(tst_li_id , appointment_id, cholestrolHDL,cholestrolLDL,triglycerides,totalCholestrolLDLHDLratio,date) VALUE('li[[v77]]','[[appointment_id78]]','[[cholestrolHDL79]]','[[cholestrolLDL80]]','[[triglycerides81]]','[[totalCholestrolLDLHDLratio82]]',NOW())",
        "SELECT tst_bloodG_id FROM BloodGroupingRh WHERE tst_bloodG_id = (SELECT MAX(tst_bloodG_id) FROM BloodGroupingRh);",
        "INSERT INTO BloodGroupingRh(tst_bloodG_id, appointment_id, BloodGroup, rhesusD,date) VALUE('bg[[v83]]','[[app_id84]]','[[bloodG85]]','[[rhD86]]',NOW())",
        "SELECT tst_CBC_id FROM completeBloodCount WHERE tst_CBC_id = (SELECT MAX(tst_CBC_id) FROM completeBloodCount);",
        "INSERT INTO completeBloodCount(tst_CBC_id , appointment_id, totalWhiteCellCount,differentialCount,neutrophils,lymphocytes,monocytes,eosonophils,basophils,haemoglobin,redBloodCells,meanCellVolume,haematocrit,meanCellHaemoglobin, mchConcentration,redCellsDistributionWidth,plateletCount,date) VALUE('cbc[[v87]]','[[appointment_id88]]','[[totalWhiteCellCount89]]','[[differentialCount90]]','[[neutrophils91]]','[[lymphocytes92]]','[[monocytes93]]','[[eosonophils94]]','[[basophils95]]','[[haemoglobin96]]','[[redBloodCells97]]','[[meanCellVolume98]]','[[haematocrit99]]','[[meanCellHaemoglobin100]]','[[mchConcentration101]]','[[redCellsDistributionWidth102]]','[[plateletCount103]]',NOW())",
        "SELECT tst_renal_id FROM RenalFunctionTest WHERE tst_renal_id = (SELECT MAX(tst_renal_id) FROM RenalFunctionTest);",
        "INSERT INTO RenalFunctionTest(tst_renal_id, appointment_id, creatinine,urea,totalBilirubin,directBilirubin,sgotast,sgptalt,alkalinePhospates,date) VALUE('re[[v104]]','[[appointment_id105]]','[[creatinine106]]','[[urea107]]','[[totalBilirubin108]]','[[directBilirubin109]]','[[sgotast110]]','[[sgptalt111]]','[[alkalinePhospates112]]',NOW())",
        "SELECT tst_SCPT_id FROM SeriumCreatinePhosphokinaseTotal WHERE tst_SCPT_id = (SELECT MAX(tst_SCPT_id) FROM SeriumCreatinePhosphokinaseTotal);",
        "INSERT INTO SeriumCreatinePhosphokinaseTotal(tst_SCPT_id, appointment_id, cpkTotal,date) VALUE('scpt[[v113]]','[[appointment_id114]]','[[cpkTotal115]]',NOW())",
        "SELECT tst_SCP_id FROM SeriumCreatinePhosphokinase WHERE tst_SCP_id = (SELECT MAX(tst_SCP_id) FROM SeriumCreatinePhosphokinase);",
        "INSERT INTO SeriumCreatinePhosphokinase(tst_SCP_id, appointment_id, hiv12ELISA,date) VALUE('scp[[v116]]','[[appointment_id117]]','[[hiv12ELISA118]]',NOW())",
        "SELECT tst_liver_id FROM LiverFunctionTest WHERE tst_liver_id = (SELECT MAX(tst_liver_id) FROM LiverFunctionTest);",
        "INSERT INTO LiverFunctionTest(tst_liver_id, appointment_id, totalProtein,albumin,globulin,totalBilirubin,directBilirubin,sgotast,sgptalt,alkalinePhospates,date) VALUE('lv[[v119]]','[[appointment_id120]]','[[totalProtein121]]','[[albumin122]]','[[globulin123]]','[[totalBilirubin124]]','[[directBilirubin125]]','[[sgotast126]]','[[sgptalt127]]','[[alkalinePhospates128]]',NOW())",
        "UPDATE person SET [[v129]] WHERE person_id = (SELECT person_id FROM sys_user WHERE user_id = '[[v130|this.userID]]');",
        "UPDATE sys_user SET [[v131]] WHERE user_id = '[[v132|this.userID]]';",
        "SELECT person_id FROM person WHERE person_id = (SELECT MAX(person_id) FROM person);",
        "SELECT user_id FROM sys_user WHERE user_id = (SELECT MAX(user_id) FROM sys_user);",
        "SELECT user_name FROM sys_user WHERE user_name = (SELECT MAX(user_name) FROM sys_user);",
        "INSERT INTO person(person_id,first_name,last_name,nic,mobile) VALUES ('hms[[v133]]','[[firstName134]]','[[lastName135]]','[[nic136]]','[[mobile137]]');",
        "SELECT person_id FROM person WHERE person_id = (SELECT MAX(person_id) FROM person);",
        "SELECT user_id FROM sys_user WHERE user_id = (SELECT MAX(user_id) FROM sys_user);",
        "SELECT user_name FROM sys_user WHERE user_name = (SELECT MAX(user_name) FROM sys_user);",
        "INSERT INTO person(person_id,first_name,last_name,nic,mobile) VALUES ('hms[[v138]]','[[firstName139]]','[[lastName140]]','[[nic141]]','[[mobile142]]');",
        "INSERT INTO sys_user(person_id,user_id,user_name,user_type,password) VALUES ('hms[[v143]]','hms[[v144]]u','user[[v145]]','[[userType146]]', '1234' );",
        "SELECT person_id FROM person WHERE person_id = (SELECT MAX(person_id) FROM person);",
        "SELECT user_id FROM sys_user WHERE user_id = (SELECT MAX(user_id) FROM sys_user);",
        "SELECT user_name FROM sys_user WHERE user_name = (SELECT MAX(user_name) FROM sys_user);",
        "INSERT INTO person(person_id,first_name,last_name,nic,mobile) VALUES ('hms[[v147]]','[[firstName148]]','[[lastName149]]','[[nic150]]','[[mobile151]]');",
        "INSERT INTO sys_user(person_id,user_id,user_name,user_type,password) VALUES ('hms[[v152]]','hms[[v153]]u','user[[v154]]','[[userType155]]', '1234' );",
        "UPDATE person SET user_id = 'hms[[v156]]u' WHERE person_id = 'hms[[v157]]';",
        "SELECT person_id FROM person WHERE person_id = (SELECT MAX(person_id) FROM person);",
        "SELECT user_id FROM sys_user WHERE user_id = (SELECT MAX(user_id) FROM sys_user);",
        "SELECT user_name FROM sys_user WHERE user_name = (SELECT MAX(user_name) FROM sys_user);",
        "INSERT INTO person(person_id,first_name,last_name,nic,mobile) VALUES ('hms[[v158]]','[[firstName159]]','[[lastName160]]','[[nic161]]','[[mobile162]]');",
        "INSERT INTO sys_user(person_id,user_id,user_name,user_type,password) VALUES ('hms[[v163]]','hms[[v164]]u','user[[v165]]','[[userType166]]', '1234' );",
        "UPDATE person SET user_id = 'hms[[v167]]u' WHERE person_id = 'hms[[v168]]';",
        "INSERT INTO doctor(slmc_reg_no,user_id) VALUES ('[[slmcReg169]]','hms[[v170]]u');",
        "UPDATE sys_user SET suspend = 1 WHERE user_id = '[[userid171]]';",
        "UPDATE sys_user SET suspend = 0 WHERE user_id = '[[userid172]]';",
        "UPDATE sys_user SET password='123456' WHERE user_id = '[[userid173]]';",
        "UPDATE sys_user SET online=1,login=NOW() WHERE user_name ='[[username174]]';",
        "UPDATE sys_user SET online=0,logout=NOW() WHERE user_name ='[[username175]]';",
        "SELECT message_id FROM user_message WHERE message_id = (SELECT MAX(message_id) FROM user_message);",
        "INSERT INTO user_message (message_id,reciver,sender,subject,message,date) VALUES ('msg[[v176]]','[[receiver177]]','[[sender178]]','[[subject179]]','[[message180]]','[[v181]]');",
        "DELETE FROM user_message WHERE message_id ='[[msgID182]]';",
        "UPDATE sys_user SET profile_pic = '[[name183]]'WHERE sys_user.user_name = '[[v184|this.username]]';",
        "UPDATE user_message SET rd = '1'WHERE user_message.message_id = '[[msgID185]]';",
        "UPDATE person SET [[v186]] WHERE person_id = (SELECT person_id FROM sys_user WHERE user_id = '[[v187|this.userID]]');",
        "UPDATE sys_user SET [[v188]] WHERE user_id = '[[v189|this.userID]]';",
        "SELECT patient_id FROM patient WHERE patient_id = (SELECT MAX(patient_id) FROM patient);",
        "SELECT person_id FROM person WHERE person_id = (SELECT MAX(person_id) FROM person);",
        "INSERT INTO person([[column]]) VALUES([[column]]);",
        "SELECT patient_id FROM patient WHERE patient_id = (SELECT MAX(patient_id) FROM patient);",
        "SELECT person_id FROM person WHERE person_id = (SELECT MAX(person_id) FROM person);",
        "INSERT INTO person([[column]]) VALUES([[column]]);",
        "INSERT INTO patient([[column]]) VALUES([[column]]);",
        "UPDATE person SET [[info190]] WHERE person_id = (SELECT person_id FROM patient WHERE patient_id = '[[patientID191]]');",
        "SELECT appointment_id FROM appointment WHERE appointment_id = (SELECT MAX(appointment_id) FROM appointment);",
        "SELECT tmp_bill_id FROM tmp_bill WHERE patient_id = '[[patienID192]]';",
        "UPDATE tmp_bill SET appointment_fee = ' 500 ' WHERE tmp_bill_id = '[[v193]]';",
        "SELECT appointment_id FROM appointment WHERE appointment_id = (SELECT MAX(appointment_id) FROM appointment);",
        "SELECT tmp_bill_id FROM tmp_bill WHERE patient_id = '[[patienID194]]';",
        "UPDATE tmp_bill SET appointment_fee = ' 500 ' WHERE tmp_bill_id = '[[v195]]';",
        "SELECT tmp_bill_id FROM tmp_bill WHERE tmp_bill_id = (SELECT MAX(tmp_bill_id) FROM tmp_bill);",
        "INSERT INTO tmp_bill([[column]]) VALUES([[column]]);",
        "SELECT appointment_id FROM appointment WHERE appointment_id = (SELECT MAX(appointment_id) FROM appointment);",
        "SELECT tmp_bill_id FROM tmp_bill WHERE patient_id = '[[patienID196]]';",
        "UPDATE tmp_bill SET appointment_fee = ' 500 ' WHERE tmp_bill_id = '[[v197]]';",
        "INSERT INTO appointment (appointment_id,patient_id,slmc_reg_no,date,cancelled) VALUES ('app[[v198]]' , '[[patienID199]]' , '[[doctorID200]]' , '[[v201]] [[v202]]:00' , false );",
        "SELECT appointment_id FROM appointment WHERE appointment_id = (SELECT MAX(appointment_id) FROM appointment);",
        "SELECT tmp_bill_id FROM tmp_bill WHERE patient_id = '[[patienID203]]';",
        "UPDATE tmp_bill SET appointment_fee = ' 500 ' WHERE tmp_bill_id = '[[v204]]';",
        "INSERT INTO appointment (appointment_id,patient_id,slmc_reg_no,date,cancelled) VALUES ('app[[v205]]' , '[[patienID206]]' , '[[doctorID207]]' , '[[v208]] [[v209]]:00' , false );",
        "UPDATE doctor_availability SET next_week_appointments = next_week_appointments + 1 WHERE time_slot = '[[timeSlot210]]' AND slmc_reg_no = '[[doctorID211]]' AND day = '[[v212]]';",
        "SELECT lab_appointment_id FROM lab_appointment WHERE lab_appointment_id = (SELECT MAX(lab_appointment_id) FROM lab_appointment);",
        "SELECT test_fee FROM lab_test WHERE test_id = '[[testID213]]';",
        "SELECT lab_appointment_id FROM lab_appointment WHERE lab_appointment_id = (SELECT MAX(lab_appointment_id) FROM lab_appointment);",
        "SELECT test_fee FROM lab_test WHERE test_id = '[[testID214]]';",
        "SELECT tmp_bill_id FROM tmp_bill WHERE patient_id = '[[patienID215]]';",
        "UPDATE tmp_bill SET laboratory_fee = ' [[v216]] ' WHERE tmp_bill_id = '[[v217]]';",
        "SELECT lab_appointment_id FROM lab_appointment WHERE lab_appointment_id = (SELECT MAX(lab_appointment_id) FROM lab_appointment);",
        "SELECT test_fee FROM lab_test WHERE test_id = '[[testID218]]';",
        "SELECT tmp_bill_id FROM tmp_bill WHERE patient_id = '[[patienID219]]';",
        "UPDATE tmp_bill SET laboratory_fee = ' [[v220]] ' WHERE tmp_bill_id = '[[v221]]';",
        "SELECT tmp_bill_id FROM tmp_bill WHERE tmp_bill_id = (SELECT MAX(tmp_bill_id) FROM tmp_bill);",
        "INSERT INTO tmp_bill([[column]]) VALUES([[column]]);",
        "SELECT lab_appointment_id FROM lab_appointment WHERE lab_appointment_id = (SELECT MAX(lab_appointment_id) FROM lab_appointment);",
        "SELECT test_fee FROM lab_test WHERE test_id = '[[testID222]]';",
        "SELECT tmp_bill_id FROM tmp_bill WHERE patient_id = '[[patienID223]]';",
        "UPDATE tmp_bill SET laboratory_fee = ' [[v224]] ' WHERE tmp_bill_id = '[[v225]]';",
        "INSERT INTO lab_appointment (lab_appointment_id,test_id,patient_id,doctor_id,date,cancelled) VALUES ('lapp[[v226]]' , '[[testID227]]' , '[[patienID228]]' , '[[doctorID229]]' , '[[v230]] [[v231]]:00' , false );",
        "SELECT lab_appointment_id FROM lab_appointment WHERE lab_appointment_id = (SELECT MAX(lab_appointment_id) FROM lab_appointment);",
        "SELECT test_fee FROM lab_test WHERE test_id = '[[testID232]]';",
        "SELECT tmp_bill_id FROM tmp_bill WHERE patient_id = '[[patienID233]]';",
        "UPDATE tmp_bill SET laboratory_fee = ' [[v234]] ' WHERE tmp_bill_id = '[[v235]]';",
        "INSERT INTO lab_appointment (lab_appointment_id,test_id,patient_id,doctor_id,date,cancelled) VALUES ('lapp[[v236]]' , '[[testID237]]' , '[[patienID238]]' , '[[doctorID239]]' , '[[v240]] [[v241]]:00' , false );",
        "UPDATE lab_appointment_timetable SET current_week_appointments = current_week_appointments + 1 WHERE time_slot = '[[timeSlot242]]' AND app_test_id = '[[testID243]]' AND app_day = '[[day244]]';",
        "UPDATE appointment SET cancelled = true WHERE appointment.appointment_id = '[[appointmentID245]]';",
        "UPDATE appointment SET cancelled = true WHERE appointment.appointment_id = '[[appointmentID246]]';",
        "SELECT appointment.bill_id, bill.total FROM appointment INNER JOIN bill ON appointment.bill_id = bill.bill_id WHERE appointment_id = '[[appointmentID247]]'",
        "UPDATE appointment SET cancelled = true WHERE appointment.appointment_id = '[[appointmentID248]]';",
        "SELECT appointment.bill_id, bill.total FROM appointment INNER JOIN bill ON appointment.bill_id = bill.bill_id WHERE appointment_id = '[[appointmentID249]]'",
        "UPDATE bill SET refund = 1 WHERE bill_id = '[[v250]]'",
        "SELECT refund_id FROM refund WHERE refund_id = (SELECT MAX(refund_id) FROM bill);",
        "INSERT INTO refund([[column]]) VALUES([[column]]);",
        "UPDATE lab_appointment SET cancelled = true WHERE lab_appointment.lab_appointment_id = '[[appointmentID251]]';",
        "UPDATE lab_appointment SET cancelled = true WHERE lab_appointment.lab_appointment_id = '[[appointmentID252]]';",
        "SELECT lab_appointment.bill_id, bill.total FROM lab_appointment INNER JOIN bill ON lab_appointment.bill_id = bill.bill_id WHERE lab_appointment_id = '[[appointmentID253]]'",
        "UPDATE lab_appointment SET cancelled = true WHERE lab_appointment.lab_appointment_id = '[[appointmentID254]]';",
        "SELECT lab_appointment.bill_id, bill.total FROM lab_appointment INNER JOIN bill ON lab_appointment.bill_id = bill.bill_id WHERE lab_appointment_id = '[[appointmentID255]]'",
        "UPDATE bill SET refund = 1 WHERE bill_id = '[[v256]]'",
        "SELECT bill_id FROM bill WHERE bill_id = (SELECT MAX(bill_id) FROM bill);",
        "INSERT INTO bill([[column]]) VALUES([[column]]);",
        "DELETE FROM tmp_bill WHERE patient_id = '[[patientID257]]';",
        "SELECT refund_id FROM refund WHERE refund_id = (SELECT MAX(refund_id) FROM bill);",
        "INSERT INTO refund([[column]]) VALUES([[column]]);",
        "DELETE FROM refund WHERE refund_id = '[[id258]]'",
        "UPDATE person SET [[v259]] WHERE person_id = (SELECT person_id FROM sys_user WHERE user_id = '[[v260|this.userID]]');",
        "UPDATE sys_user SET [[v261]] WHERE user_id = '[[v262|this.userID]]';",
    )
    for (text in texts) {
        println(text)
        println(SqlGrammar.parseToEnd(text))
    }
}
