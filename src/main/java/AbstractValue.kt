import com.github.javaparser.ast.expr.Expression

sealed class AbstractValue(val expr : Expression) {

    // A known Java constant
    data class Java(
            val e: Expression,
            val data : Object
            ): AbstractValue(e)

    // Unknown value
    data class Unknown(
            val e: Expression,

            ): AbstractValue(e)

    // QueryValue
    data class QueryValue(
            val e: Expression,
            val table: String,
            val attrs: Array<String>
            ): AbstractValue(e) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as QueryValue

            if (e != other.e) return false
            if (table != other.table) return false
            if (!attrs.contentEquals(other.attrs)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = e.hashCode()
            result = 31 * result + table.hashCode()
            result = 31 * result + attrs.contentHashCode()
            return result
        }
    }

    data class Composite(
            val e: Expression
            ): AbstractValue(e)
}