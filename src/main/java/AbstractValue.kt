import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.resolution.types.ResolvedType
import net.sf.jsqlparser.statement.select.Join
import net.sf.jsqlparser.statement.select.Limit
import net.sf.jsqlparser.statement.select.PlainSelect
import java.lang.StringBuilder

sealed class AbstractValue(val expr : Expression, val staticType: ResolvedType) {
    override fun toString(): String {
        return "(Value ${this::class})"
    }

    open fun guessSql(): String {
        TODO("shouldn't work")
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
    open fun add(expr: Expression, rhs: AbstractValue): AbstractValue {
        return Binary(expr, expr.calculateResolvedType(), Operator.ADD, this, rhs)
    }
    open fun sub(expr: Expression, rhs: AbstractValue): AbstractValue {
        return Binary(expr, expr.calculateResolvedType(), Operator.SUB, this, rhs)
    }
    open fun mul(expr: Expression, rhs: AbstractValue): AbstractValue {
        return Binary(expr, expr.calculateResolvedType(), Operator.MUL, this, rhs)
    }
    open fun div(expr: Expression, rhs: AbstractValue): AbstractValue {
        return Binary(expr, expr.calculateResolvedType(), Operator.DIV, this, rhs)
    }
    open fun eq(expr: Expression, rhs: AbstractValue): AbstractValue {
        return Binary(expr, expr.calculateResolvedType(), Operator.EQ, this, rhs)
    }
    open fun ne(expr: Expression, rhs: AbstractValue): AbstractValue {
        return Binary(expr, expr.calculateResolvedType(), Operator.NE, this, rhs)
    }
    open fun ge(expr: Expression, rhs: AbstractValue): AbstractValue {
        return Binary(expr, expr.calculateResolvedType(), Operator.GE, this, rhs)
    }
    open fun gt(expr: Expression, rhs: AbstractValue): AbstractValue {
        return Binary(expr, expr.calculateResolvedType(), Operator.GT, this, rhs)
    }
    open fun le(expr: Expression, rhs: AbstractValue): AbstractValue {
        return Binary(expr, expr.calculateResolvedType(), Operator.LE, this, rhs)
    }
    open fun lt(expr: Expression, rhs: AbstractValue): AbstractValue {
        return Binary(expr, expr.calculateResolvedType(), Operator.LT, this, rhs)
    }


    //
    // Variants
    //

    data class Unknown(
        val e: Expression,
        val t: ResolvedType
    ): AbstractValue(e, t){
        override fun toString(): String {
            return "(unknown from $expr)"
        }
        override fun negate(expr: Expression): AbstractValue { return Unknown(expr, expr.calculateResolvedType()) }
        override fun xor(expr: Expression, rhs: AbstractValue): AbstractValue { return Unknown(expr, expr.calculateResolvedType()) }
        override fun not(expr: Expression): AbstractValue { return Unknown(expr, expr.calculateResolvedType()) }
        override fun and(expr: Expression, rhs: AbstractValue): AbstractValue { return Unknown(expr, expr.calculateResolvedType()) }
        override fun or(expr: Expression, rhs: AbstractValue): AbstractValue { return Unknown(expr, expr.calculateResolvedType()) }
        override fun add(expr: Expression, rhs: AbstractValue): AbstractValue { return Unknown(expr, expr.calculateResolvedType()) }
        override fun sub(expr: Expression, rhs: AbstractValue): AbstractValue { return Unknown(expr, expr.calculateResolvedType()) }
        override fun mul(expr: Expression, rhs: AbstractValue): AbstractValue { return Unknown(expr, expr.calculateResolvedType()) }
        override fun div(expr: Expression, rhs: AbstractValue): AbstractValue { return Unknown(expr, expr.calculateResolvedType()) }
        override fun eq(expr: Expression, rhs: AbstractValue): AbstractValue { return Unknown(expr, expr.calculateResolvedType()) }
        override fun ne(expr: Expression, rhs: AbstractValue): AbstractValue { return Unknown(expr, expr.calculateResolvedType()) }
        override fun ge(expr: Expression, rhs: AbstractValue): AbstractValue { return Unknown(expr, expr.calculateResolvedType()) }
        override fun gt(expr: Expression, rhs: AbstractValue): AbstractValue { return Unknown(expr, expr.calculateResolvedType()) }
        override fun le(expr: Expression, rhs: AbstractValue): AbstractValue { return Unknown(expr, expr.calculateResolvedType()) }
        override fun lt(expr: Expression, rhs: AbstractValue): AbstractValue { return Unknown(expr, expr.calculateResolvedType()) }
    }

    data class Null(
        val e: Expression,
        val t: ResolvedType
    ): AbstractValue(e, t) {
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

        override fun add(expr: Expression, rhs: AbstractValue): AbstractValue {
            println("[WARN] NullPointerException!")
            return Unknown(expr, expr.calculateResolvedType())
        }
        override fun sub(expr: Expression, rhs: AbstractValue): AbstractValue {
            println("[WARN] NullPointerException!")
            return Unknown(expr, expr.calculateResolvedType())
        }
        override fun mul(expr: Expression, rhs: AbstractValue): AbstractValue {
            println("[WARN] NullPointerException!")
            return Unknown(expr, expr.calculateResolvedType())
        }
        override fun div(expr: Expression, rhs: AbstractValue): AbstractValue {
            println("[WARN] NullPointerException!")
            return Unknown(expr, expr.calculateResolvedType())
        }
    }

    data class Data(
        val e: Expression,
        val t: ResolvedType,
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

        override fun add(expr: Expression, rhs: AbstractValue): AbstractValue {
            return if (data is Long && rhs is Data && rhs.data is Long) {
                Data(expr, expr.calculateResolvedType(), data + rhs.data)
            } else if (data is Double && rhs is Data && rhs.data is Double) {
                Data(expr, expr.calculateResolvedType(), data + rhs.data)
            } else if (data is Double && rhs is Data && rhs.data is Long) {
                Data(expr, expr.calculateResolvedType(), data + rhs.data)
            } else if (data is Long && rhs is Data && rhs.data is Double) {
                Data(expr, expr.calculateResolvedType(), data + rhs.data)
            } else if (data is String && rhs is Data && rhs.data is String) {
                Data(expr, expr.calculateResolvedType(), data + rhs.data)
            } else {
                super.add(expr, rhs)
            }
        }

        override fun sub(expr: Expression, rhs: AbstractValue): AbstractValue {
            return if (data is Long && rhs is Data && rhs.data is Long) {
                Data(expr, expr.calculateResolvedType(), data + rhs.data)
            } else if (data is Double && rhs is Data && rhs.data is Double) {
                Data(expr, expr.calculateResolvedType(), data + rhs.data)
            } else if (data is Double && rhs is Data && rhs.data is Long) {
                Data(expr, expr.calculateResolvedType(), data + rhs.data)
            } else if (data is Long && rhs is Data && rhs.data is Double) {
                Data(expr, expr.calculateResolvedType(), data + rhs.data)
            } else {
                super.sub(expr, rhs)
            }
        }

        override fun mul(expr: Expression, rhs: AbstractValue): AbstractValue {
            return if (data is Long && rhs is Data && rhs.data is Long) {
                Data(expr, expr.calculateResolvedType(), data + rhs.data)
            } else if (data is Double && rhs is Data && rhs.data is Double) {
                Data(expr, expr.calculateResolvedType(), data + rhs.data)
            } else if (data is Double && rhs is Data && rhs.data is Long) {
                Data(expr, expr.calculateResolvedType(), data + rhs.data)
            } else if (data is Long && rhs is Data && rhs.data is Double) {
                Data(expr, expr.calculateResolvedType(), data + rhs.data)
            } else {
                super.mul(expr, rhs)
            }
        }

        override fun div(expr: Expression, rhs: AbstractValue): AbstractValue {
            return if (data is Long && rhs is Data && rhs.data is Long) {
                Data(expr, expr.calculateResolvedType(), data + rhs.data)
            } else if (data is Double && rhs is Data && rhs.data is Double) {
                Data(expr, expr.calculateResolvedType(), data + rhs.data)
            } else if (data is Double && rhs is Data && rhs.data is Long) {
                Data(expr, expr.calculateResolvedType(), data + rhs.data)
            } else if (data is Long && rhs is Data && rhs.data is Double) {
                Data(expr, expr.calculateResolvedType(), data + rhs.data)
            } else {
                super.div(expr, rhs)
            }
        }

        override fun toString(): String {
            return "(data $staticType $data)"
        }

        override fun guessSql(): String {
            if (data is String) {
                return data
            } else {
                return "?"
            }
        }
    }

    // Variable names that occur free
    data class Free(
        val e: Expression,
        val t: ResolvedType,
        val name: String
    ): AbstractValue(e, t) {
        override fun toString(): String {
            return "(free $name)"
        }
    }

    data class SqlStmt(
        val e: Expression,
        val t: ResolvedType,
        val sql: String
    ): AbstractValue(e, t) {
        val params = mutableMapOf<Int, Any>()

        fun setParameter(i: Int, data: Any) {
            params[i] = data
        }
    }

    data class ResultSet(
        val e: Expression,
        val t: ResolvedType,
        val select: PlainSelect,
    ): AbstractValue(e, t) {
        val columns = mutableListOf<Pair<Column, AggregateKind>>()
        val joins: List<Join>? = select.joins
        val limit: Limit? = select.limit
        val hasJoin = joins?.isNotEmpty() ?: false
        val hasLimit = limit != null

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
            sb.append(")")
            return sb.toString()
        }
    }

    data class DbState(
        val e: Expression,
        val t: ResolvedType,
        val query: ResultSet,
        val column: Column,
        val aggregateKind: AggregateKind
    ): AbstractValue(e, t) {
        override fun toString(): String {
            return "(db ${column.table.name}.${column.name})"
        }
    }

    data class DbNotNil(
        val e: Expression,
        val t: ResolvedType,
        val query: ResultSet,
    ): AbstractValue(e, t) {
        var reversed = false
        fun reverse() {
            reversed = !reversed
        }
        override fun toString(): String {
            if (reversed) {
                return "(Nil $query)"
            } else {
                return "(notNil $query)"
            }
        }
    }

    // Method call or object construction
    data class Call(
        val e: Expression,
        val t: ResolvedType,
        val receiver: AbstractValue,
        val args: List<AbstractValue>
    ): AbstractValue(e, t) {
        override fun toString(): String {
            return "(invoke $receiver $args)"
        }
    }

    data class Unary(
        val e: Expression,
        val t: ResolvedType,
        val op: Operator,
        val value: AbstractValue
    ): AbstractValue(e, t) {
        override fun toString(): String {
            return "($op $value)"
        }
    }

    data class Binary(
        val e: Expression,
        val t: ResolvedType,
        val op: Operator,
        val left: AbstractValue,
        val right: AbstractValue
    ): AbstractValue(e, t) {
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
    }
}

enum class AggregateKind {
    ID,
    MAX,
}

enum class Operator {
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
            else -> {
                super.toString()
            }
        }
    }
}
