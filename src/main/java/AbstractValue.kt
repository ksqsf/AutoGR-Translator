import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.resolution.types.ResolvedPrimitiveType
import com.github.javaparser.resolution.types.ResolvedReferenceType
import com.github.javaparser.resolution.types.ResolvedType
import net.sf.jsqlparser.statement.select.Join
import net.sf.jsqlparser.statement.select.Limit
import net.sf.jsqlparser.statement.select.PlainSelect

sealed class AbstractValue(val expr : Expression?, val staticType: ResolvedType?) {
    override fun toString(): String {
        return "(Value ${this::class})"
    }

    /**
     * Is local-dependent? Local-dependent <=> it could be referring to local states. Default = true.
     *
     * When emitting code, local-dependent values can be replaced by something else.
     */
    open fun local(): Boolean {
        return true
    }

    /**
     * Try to determine the type of an immediate value, only using information about this value itself.
     *
     * Immediate typed values include: Data, DbState, and Free.
     */
    open fun type(): Type? {
        return null
    }

    /**
     * Try to cast this value to another type. Returns the same value if it cannot be cast.
     */
    fun cast(toType: Type): AbstractValue {
        val thisType = this.type() ?: return this
        return if (thisType == Type.String && toType.isIntLike()) {
            Unary(null, null, Operator.S2I, this)
        } else if (thisType.isIntLike() && toType == Type.String) {
            Unary(null, null, Operator.I2S, this)
        } else {
            this
        }
    }

    /**
     * Returns a Python reference (variable name), while the actual computation is emitted to the emitter.
     */
    open fun toRigi(emitter: Emitter): String {
        throw IllegalArgumentException("This type ${this::class} can't be converted to Rigi")
    }

    open fun guessSql(): String {
        throw IllegalArgumentException("AbstractValue of type ${this::class} doesn't have SQL guesses")
    }

    /**
     * whether this value is unknown. a free variable is not considered unknown.
     */
    open fun unknown(): Boolean {
        return false
    }

    //
    // Interfaces
    //
    open fun negate(expr: Expression): AbstractValue {
        return Unary(expr, expr.calculateResolvedType(), Operator.NEG, this)
    }
    open fun xor(expr: Expression, rhs: AbstractValue): AbstractValue {
        return Binary(expr, expr.calculateResolvedType(), Operator.XOR, this, rhs)
    }
    open fun not(expr: Expression): AbstractValue {
        return Unary(expr, expr.calculateResolvedType(), Operator.NOT, this)
    }
    open fun and(expr: Expression, rhs: AbstractValue): AbstractValue {
        return Binary(expr, expr.calculateResolvedType(), Operator.AND, this, rhs)
    }
    open fun or(expr: Expression, rhs: AbstractValue): AbstractValue {
        return Binary(expr, expr.calculateResolvedType(), Operator.OR, this, rhs)
    }
    open fun add(expr: Expression?, rhs: AbstractValue): AbstractValue {
        return Binary(expr, expr?.calculateResolvedType(), Operator.ADD, this, rhs)
    }
    open fun sub(expr: Expression?, rhs: AbstractValue): AbstractValue {
        return Binary(expr, expr?.calculateResolvedType(), Operator.SUB, this, rhs)
    }
    open fun mul(expr: Expression?, rhs: AbstractValue): AbstractValue {
        return Binary(expr, expr?.calculateResolvedType(), Operator.MUL, this, rhs)
    }
    open fun div(expr: Expression?, rhs: AbstractValue): AbstractValue {
        return Binary(expr, expr?.calculateResolvedType(), Operator.DIV, this, rhs)
    }
    open fun eq(expr: Expression?, rhs: AbstractValue): AbstractValue {
        return Binary(expr, expr?.calculateResolvedType(), Operator.EQ, this, rhs)
    }
    open fun ne(expr: Expression?, rhs: AbstractValue): AbstractValue {
        return Binary(expr, expr?.calculateResolvedType(), Operator.NE, this, rhs)
    }
    open fun ge(expr: Expression?, rhs: AbstractValue): AbstractValue {
        return Binary(expr, expr?.calculateResolvedType(), Operator.GE, this, rhs)
    }
    open fun gt(expr: Expression?, rhs: AbstractValue): AbstractValue {
        return Binary(expr, expr?.calculateResolvedType(), Operator.GT, this, rhs)
    }
    open fun le(expr: Expression?, rhs: AbstractValue): AbstractValue {
        return Binary(expr, expr?.calculateResolvedType(), Operator.LE, this, rhs)
    }
    open fun lt(expr: Expression?, rhs: AbstractValue): AbstractValue {
        return Binary(expr, expr?.calculateResolvedType(), Operator.LT, this, rhs)
    }


    //
    // Variants
    //

    data class Unknown(
        val e: Expression?,
        val t: ResolvedType?,
        val tag: Any? = null,
    ): AbstractValue(e, t){
        override fun toString(): String {
            return "(unknown from $expr)"
        }

        override fun unknown(): Boolean {
            return true
        }
        override fun negate(expr: Expression): AbstractValue { return Unknown(expr, expr.calculateResolvedType()) }
        override fun xor(expr: Expression, rhs: AbstractValue): AbstractValue { return Unknown(expr, expr.calculateResolvedType()) }
        override fun not(expr: Expression): AbstractValue { return Unknown(expr, expr.calculateResolvedType()) }
        override fun and(expr: Expression, rhs: AbstractValue): AbstractValue { return Unknown(expr, expr.calculateResolvedType()) }
        override fun or(expr: Expression, rhs: AbstractValue): AbstractValue { return Unknown(expr, expr.calculateResolvedType()) }
        override fun add(expr: Expression?, rhs: AbstractValue): AbstractValue { return Unknown(expr, expr?.calculateResolvedType()) }
        override fun sub(expr: Expression?, rhs: AbstractValue): AbstractValue { return Unknown(expr, expr?.calculateResolvedType()) }
        override fun mul(expr: Expression?, rhs: AbstractValue): AbstractValue { return Unknown(expr, expr?.calculateResolvedType()) }
        override fun div(expr: Expression?, rhs: AbstractValue): AbstractValue { return Unknown(expr, expr?.calculateResolvedType()) }
        override fun eq(expr: Expression?, rhs: AbstractValue): AbstractValue { return Unknown(expr, expr?.calculateResolvedType()) }
        override fun ne(expr: Expression?, rhs: AbstractValue): AbstractValue { return Unknown(expr, expr?.calculateResolvedType()) }
        override fun ge(expr: Expression?, rhs: AbstractValue): AbstractValue { return Unknown(expr, expr?.calculateResolvedType()) }
        override fun gt(expr: Expression?, rhs: AbstractValue): AbstractValue { return Unknown(expr, expr?.calculateResolvedType()) }
        override fun le(expr: Expression?, rhs: AbstractValue): AbstractValue { return Unknown(expr, expr?.calculateResolvedType()) }
        override fun lt(expr: Expression?, rhs: AbstractValue): AbstractValue { return Unknown(expr, expr?.calculateResolvedType()) }
    }

    data class Null(
        val e: Expression?,
        val t: ResolvedType?
    ): AbstractValue(e, t) {
        override fun local(): Boolean {
            return false
        }

        override fun toString(): String {
            return "(null from $expr)"
        }

        override fun negate(expr: Expression): AbstractValue {
            println("[WARN] NullPointerException!")
            return Unknown(expr, expr.calculateResolvedType())
        }

        override fun xor(expr: Expression, rhs: AbstractValue): AbstractValue {
            println("[WARN] NullPointerException!")
            return Unknown(expr, expr.calculateResolvedType())
        }

        override fun not(expr: Expression): AbstractValue {
            println("[WARN] NullPointerException!")
            return Unknown(expr, expr.calculateResolvedType())
        }

        override fun and(expr: Expression, rhs: AbstractValue): AbstractValue {
            println("[WARN] NullPointerException!")
            return Unknown(expr, expr.calculateResolvedType())
        }

        override fun or(expr: Expression, rhs: AbstractValue): AbstractValue {
            println("[WARN] NullPointerException!")
            return Unknown(expr, expr.calculateResolvedType())
        }

        override fun add(expr: Expression?, rhs: AbstractValue): AbstractValue {
            println("[WARN] NullPointerException!")
            return Unknown(expr, expr?.calculateResolvedType())
        }
        override fun sub(expr: Expression?, rhs: AbstractValue): AbstractValue {
            println("[WARN] NullPointerException!")
            return Unknown(expr, expr?.calculateResolvedType())
        }
        override fun mul(expr: Expression?, rhs: AbstractValue): AbstractValue {
            println("[WARN] NullPointerException!")
            return Unknown(expr, expr?.calculateResolvedType())
        }
        override fun div(expr: Expression?, rhs: AbstractValue): AbstractValue {
            println("[WARN] NullPointerException!")
            return Unknown(expr, expr?.calculateResolvedType())
        }

        override fun toRigi(emitter: Emitter): String {
            println("[WARN] Null toRigi = None")
            return "None"
        }
    }

    data class Data(
        val e: Expression?,
        val t: ResolvedType?,
        val data: Any
    ): AbstractValue(e, t) {
        override fun negate(expr: Expression): AbstractValue {
            val ty = expr.calculateResolvedType()
            return when (data) {
                is Double -> {
                    Data(expr, ty, -data)
                }
                is Int -> {
                    Data(expr, ty, -data)
                }
                is Long -> {
                    Data(expr, ty, -data)
                }
                else -> {
                    super.negate(expr)
                }
            }
        }

        override fun not(expr: Expression): AbstractValue {
            return if (data is Boolean) {
                Data(expr, expr.calculateResolvedType(), !data)
            } else {
                super.not(expr)
            }
        }

        override fun and(expr: Expression, rhs: AbstractValue): AbstractValue {
            return if (data is Boolean && rhs is Data && rhs.data is Boolean) {
                Data(expr, expr.calculateResolvedType(), data && rhs.data)
            } else {
                super.and(expr, rhs)
            }
        }

        override fun or(expr: Expression, rhs: AbstractValue): AbstractValue {
            return if (data is Boolean && rhs is Data && rhs.data is Boolean) {
                Data(expr, expr.calculateResolvedType(), data || rhs.data)
            } else {
                super.or(expr, rhs)
            }
        }

        override fun add(expr: Expression?, rhs: AbstractValue): AbstractValue {
            return if (data is Long && rhs is Data && rhs.data is Long) {
                Data(expr, expr?.calculateResolvedType(), data + rhs.data)
            } else if (data is Double && rhs is Data && rhs.data is Double) {
                Data(expr, expr?.calculateResolvedType(), data + rhs.data)
            } else if (data is Double && rhs is Data && rhs.data is Long) {
                Data(expr, expr?.calculateResolvedType(), data + rhs.data)
            } else if (data is Long && rhs is Data && rhs.data is Double) {
                Data(expr, expr?.calculateResolvedType(), data + rhs.data)
            } else if (data is String && rhs is Data && rhs.data is String) {
                Data(expr, expr?.calculateResolvedType(), data + rhs.data)
            } else {
                super.add(expr, rhs)
            }
        }

        override fun sub(expr: Expression?, rhs: AbstractValue): AbstractValue {
            return if (data is Long && rhs is Data && rhs.data is Long) {
                Data(expr, expr?.calculateResolvedType(), data + rhs.data)
            } else if (data is Double && rhs is Data && rhs.data is Double) {
                Data(expr, expr?.calculateResolvedType(), data + rhs.data)
            } else if (data is Double && rhs is Data && rhs.data is Long) {
                Data(expr, expr?.calculateResolvedType(), data + rhs.data)
            } else if (data is Long && rhs is Data && rhs.data is Double) {
                Data(expr, expr?.calculateResolvedType(), data + rhs.data)
            } else {
                super.sub(expr, rhs)
            }
        }

        override fun mul(expr: Expression?, rhs: AbstractValue): AbstractValue {
            return if (data is Long && rhs is Data && rhs.data is Long) {
                Data(expr, expr?.calculateResolvedType(), data + rhs.data)
            } else if (data is Double && rhs is Data && rhs.data is Double) {
                Data(expr, expr?.calculateResolvedType(), data + rhs.data)
            } else if (data is Double && rhs is Data && rhs.data is Long) {
                Data(expr, expr?.calculateResolvedType(), data + rhs.data)
            } else if (data is Long && rhs is Data && rhs.data is Double) {
                Data(expr, expr?.calculateResolvedType(), data + rhs.data)
            } else {
                super.mul(expr, rhs)
            }
        }

        override fun div(expr: Expression?, rhs: AbstractValue): AbstractValue {
            return if (data is Long && rhs is Data && rhs.data is Long) {
                Data(expr, expr?.calculateResolvedType(), data + rhs.data)
            } else if (data is Double && rhs is Data && rhs.data is Double) {
                Data(expr, expr?.calculateResolvedType(), data + rhs.data)
            } else if (data is Double && rhs is Data && rhs.data is Long) {
                Data(expr, expr?.calculateResolvedType(), data + rhs.data)
            } else if (data is Long && rhs is Data && rhs.data is Double) {
                Data(expr, expr?.calculateResolvedType(), data + rhs.data)
            } else {
                super.div(expr, rhs)
            }
        }

        override fun toString(): String {
            val shortType = when (staticType) {
                is ResolvedPrimitiveType -> staticType.name
                is ResolvedReferenceType -> staticType.qualifiedName
                else -> staticType.toString()
            }
            return "(data $shortType $data)"
        }

        override fun guessSql(): String {
            if (data is String) {
                return data
            } else {
                return "?"
            }
        }

        override fun toRigi(emitter: Emitter): String {
            return when (data) {
                is String -> "StringVal('${quote(data)}')"
                is Int -> "$data"
                is Long -> "$data"
                is Double -> "$data"
                is Float -> "$data"
                is Boolean -> if (data) { "True" } else { "False" }
                else -> super.toRigi(emitter)
            }
        }

        override fun type(): Type? {
            return when (data) {
                is String -> Type.String
                is Int -> Type.Int
                is Long -> Type.Int
                is Short -> Type.Int
                is Double -> Type.Real
                is Float -> Type.Real
                is Boolean -> Type.Bool
                else -> null
            }
        }

        override fun local(): Boolean {
            return false
        }
    }

    // Variable names that occur free
    data class Free(
        val e: Expression?,
        val t: ResolvedType?,
        val name: String,
        val type: Type?
    ): AbstractValue(e, t) {
        override fun toString(): String {
            return "(free $name)"
        }

        override fun guessSql(): String {
            return "?"
        }

        override fun toRigi(emitter: Emitter): String {
            return emitter.emitAssign(name, "argv['@OP@']['$name']")
        }

        override fun type(): Type? {
            return type
        }

        override fun local(): Boolean {
            return false
        }
    }

    data class SqlStmt(
        val e: Expression,
        val t: ResolvedType,
        val sql: String
    ): AbstractValue(e, t) {
        val params = mutableMapOf<Int, AbstractValue>()

        fun setParameter(i: Int, data: AbstractValue) {
            params[i] = data
        }
    }

    data class ResultSet(
        val e: Expression,
        val t: ResolvedType,
        val stmt: SqlStmt,
        val select: PlainSelect,
        val locators: Locators
    ): AbstractValue(e, t) {
        val columns = mutableListOf<Pair<Column, AggregateKind>>()
        val joins: List<Join>? = select.joins
        val limit: Limit? = select.limit
        val hasJoin = joins?.isNotEmpty() ?: false
        val hasLimit = limit != null
        var tables = listOf<Table>()

        fun addColumn(column: Column) {
            columns.add(Pair(column, AggregateKind.ID))
        }

        fun addAggregate(column: Column, kind: AggregateKind) {
            columns.add(Pair(column, kind))
        }

        override fun toString(): String {
            val sb = StringBuilder()
            sb.append("(resultset")
            for (pair in columns) {
                sb.append(' ')
                when (pair.second) {
                    AggregateKind.ID->{
                        sb.append("${pair.first.table.name}.${pair.first.name}")
                    }
                    AggregateKind.MAX->{
                        sb.append("MAX(${pair.first.table.name}.${pair.first.name})")
                    }
                }
            }
            sb.append("; ${select.where})")
            return sb.toString()
        }
    }

    /**
     * A [DbState] represent a single column from a SELECT query.
     *
     * SELECT a, b, c FROM table WHERE ... is equivalent to (DbState(table.a), DbState(table.b), DbState(table.c)).
     */
    data class DbState(
        val e: Expression?,
        val t: ResolvedType?,
        val column: Column,
        val aggregateKind: AggregateKind,
        val locators: Map<Column, AbstractValue>? = null
    ): AbstractValue(e, t) {
        override fun toString(): String {
            return "(db ${column.table.name}.${column.name} $locators)"
        }

        override fun toRigi(emitter: Emitter): String {
            val name = "${column.table.name}_${column.name}"
            // If locators == null, then this DbState refers to the table itself.
            // UPDATE tbl SET a = a + 1 WHERE ...
            //                    ^
            // Assert in this case, the WHERE clause exists.
            val locatorStr = locatorsToRigi((locators ?: emitter.context.currentLocators)!!, emitter)
            val value = "state['TABLE_${column.table.name}'].get(${locatorStr}, '${column.name}')"
            return emitter.emitAssign(name, value)
        }

        override fun type(): Type {
            return column.type
        }

        override fun local(): Boolean {
            return false
        }
    }

    data class DbStateList(
        val e: Expression?,
        val t: ResolvedType?,
        val query: Any,
        val table: Table,
        val locators: Locators,
        val result: List<DbState>,
        val knownExisting: DbNotNil? = null, // If not nil, the DbStateList has been checked to contain a row.
    ): AbstractValue(e, t) {
        override fun toString(): String {
            return if (knownExisting != null) {
                "(resultset! $query)"
            } else {
                "(resultset $query)"
            }
        }
    }

    data class DbNotNil(
        val e: Expression,
        val t: ResolvedType,
        val table: Table,
        val locators: Locators,
    ): AbstractValue(e, t) {
        var reversed = false

        fun reverse() {
            reversed = !reversed
        }

        override fun toString(): String {
            if (reversed) {
                return "(Nil $locators)"
            } else {
                return "(notNil $locators)"
            }
        }

        override fun not(expr: Expression): AbstractValue {
            val clone = DbNotNil(e, t, table, locators)
            clone.reverse()
            return clone
        }

        override fun toRigi(emitter: Emitter): String {
            val expect = if (reversed) { "False" } else { "True" }
            val parts = mutableListOf<String>()
            for (locator in locators) {
                parts.add("'${locator.key.name}': ${locator.value.toRigi(emitter)}")
            }
            return "(state['TABLE_${table.name}'].notNil({${parts.joinToString(", ")}}) == $expect)"
        }

        override fun local(): Boolean {
            return false
        }
    }

    // Method call or object construction
    data class Call(
        val e: Expression,
        val t: ResolvedType,
        val receiver: AbstractValue?,
        val methodName: String,
        val args: List<AbstractValue>
    ): AbstractValue(e, t) {
        override fun unknown(): Boolean {
            // Already handled by interpreter.
            return false
        }
        override fun toString(): String {
            return "(invoke $receiver $methodName $args)"
        }

        override fun toRigi(emitter: Emitter): String {
            // TODO: actually implement it
            println("[ERR] Call.toRigi: $this")
            return "None"
        }

        override fun local(): Boolean {
            val receiverL = receiver?.local() ?: false
            if (receiverL)
                return true
            for (arg in args) {
                if (arg.local())
                    return true
            }
            return false
        }
    }

    data class Unary(
        val e: Expression?,
        val t: ResolvedType?,
        val op: Operator,
        val value: AbstractValue
    ): AbstractValue(e, t) {
        override fun unknown(): Boolean {
            return value.unknown()
        }

        override fun toString(): String {
            return "($op $value)"
        }

        override fun toRigi(emitter: Emitter): String {
            return "$op(${value.toRigi(emitter)})"
        }

        override fun type(): Type? {
            val innerType = value.type() ?: return null
            return if (innerType.isIntLike() && op == Operator.I2S)
                Type.String
            else if (innerType == Type.String && op == Operator.S2I)
                Type.Int
            else
                super.type()
        }

        override fun local(): Boolean {
            return value.local()
        }
    }

    data class Binary(
        val e: Expression?,
        val t: ResolvedType?,
        val op: Operator,
        val left: AbstractValue,
        val right: AbstractValue
    ): AbstractValue(e, t) {
        override fun unknown(): Boolean {
            return left.unknown() || right.unknown()
        }

        override fun toString(): String {
            return "($op $left $right)"
        }

        override fun guessSql(): String {
            if (op != Operator.ADD) {
                return super.guessSql()
            }

            val leftSql = left.guessSql()
            val rightSql = right.guessSql()
            return when {
                leftSql == "?" -> {
                    rightSql
                }
                rightSql == "?" -> {
                    leftSql
                }
                else -> {
                    leftSql + rightSql
                }
            }
        }

        override fun toRigi(emitter: Emitter): String {
            val leftStr = left.toRigi(emitter)
            val rightStr = right.toRigi(emitter)
            return when (op) {
                Operator.AND -> "($leftStr) and ($rightStr)"
                Operator.OR -> "($leftStr) or ($rightStr)"
                else -> "($leftStr)$op($rightStr)"
            }
        }

        override fun local(): Boolean {
            return left.local() || right.local()
        }
    }
}

enum class AggregateKind {
    ID,
    MAX,
}

enum class Operator {
    S2I, // String to int
    I2S, // Int to string

    NEG, // -a, where a is integer
    XOR, // a^b
    ADD, // a+b
    SUB, // a-b
    MUL, // a*b
    DIV, // a/b

    NOT, // !a
    AND, // a&b
    OR,  // a|b

    EQ, // a==b
    NE, // a!=b
    GE, // a>=b
    LE, // a<=b
    GT, // a>b
    LT; // a<b

    override fun toString(): String {
        return when (this) {
            S2I -> "StrToInt"
            I2S -> "IntToStr"
            NEG -> "-"
            XOR -> "^"
            ADD -> "+"
            SUB -> "-"
            MUL -> "*"
            DIV -> "/"
            NOT -> "!"
            AND -> "&"
            OR -> "|"
            EQ -> "=="
            NE -> "!="
            GE -> ">="
            GT -> ">"
            LE -> "<="
            LT -> "<"
        }
    }
}
