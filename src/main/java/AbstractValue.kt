import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.resolution.types.ResolvedType

sealed class AbstractValue(val expr : Expression, val staticType: ResolvedType) {

    data class Null(
        val e: Expression,
        val t: ResolvedType
    ): AbstractValue(e, t)

    data class Data(
        val e: Expression,
        val t: ResolvedType,
        val data: Any
    ): AbstractValue(e, t)

    data class Unknown(
        val e: Expression,
        val t: ResolvedType
    ): AbstractValue(e, t)

    data class Query(
        val e: Expression,
        val t: ResolvedType,
        val table: List<String>,
        val attrs: List<String>
    ): AbstractValue(e, t)

    // Method call or object construction
    data class Call(
        val e: Expression,
        val t: ResolvedType
    ): AbstractValue(e, t)

    data class Unary(
        val e: Expression,
        val t: ResolvedType,
        val value: AbstractValue
    ): AbstractValue(e, t)

    data class Binary(
        val e: Expression,
        val t: ResolvedType,
        val left: AbstractValue,
        val right: AbstractValue
    ): AbstractValue(e, t)
}
