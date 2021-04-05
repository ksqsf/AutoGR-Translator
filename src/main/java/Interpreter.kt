import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.stmt.*
import com.github.javaparser.ast.visitor.GenericVisitorAdapter
import com.github.javaparser.ast.visitor.VoidVisitorAdapter
import java.lang.IllegalArgumentException

/**
 * A variable corresponds to a variable definition (statically), or the storage (dynamically).
 */
data class Variable(var value: AbstractValue? = null) {
    fun get(): AbstractValue {
        return value!!
    }

    fun set(v: AbstractValue) {
        value = v
    }
}

typealias Scope = MutableMap<String, Variable>

fun emptyScope(): Scope {
    return mutableMapOf()
}

/**
 * Interpret an intra-graph with supplied environments.
 *
 * Only interpret the graph on all paths leading to an effectual Database.commit.
 */
class Interpreter(val g: IntraGraph, val schema: Schema, val effect: Effect) {
    var depth = 0
    var returnValue: AbstractValue? = null

    // Current method scopes.
    val scope = mutableListOf(emptyScope())

    fun pushScope() {
        scope.add(mutableMapOf())
        depth++
    }

    fun popScope() {
        scope.removeLast()
        depth--
    }

    fun adjustScope(dest: Int) {
        while (dest > depth) {
            pushScope()
        }
        while (dest < depth) {
            popScope()
        }
    }

    fun lookup(varName: String): Variable? {
        scope.reversed().forEach {
            if (it.containsKey(varName))
                return it[varName]
        }
        return null
    }

    fun lookupOrCreate(varName: String): Variable {
        val existing: Variable? = lookup(varName)
        if (existing != null)
            return existing
        val new = Variable()
        scope.last()[varName] = new
        return new
    }

    fun putVariable(varName: String, value: AbstractValue) {
        lookupOrCreate(varName).set(value)
    }

    // null is returned when it's a variable declaration expression.
    // If any expr is of unknown kind, AbstractValue.Null is returned.
    // Therefore, it's generally safe to unwrap the return value.
    private fun evalExpr(expr: Expression): AbstractValue? {
        val result = expr.accept(object : GenericVisitorAdapter<AbstractValue?, Interpreter>() {
            // Null
            override fun visit(expr: NullLiteralExpr, arg: Interpreter): AbstractValue {
                return AbstractValue.Null(expr, expr.calculateResolvedType())
            }

            // Literals
            override fun visit(expr: StringLiteralExpr, arg: Interpreter): AbstractValue {
                return AbstractValue.Data(expr, expr.calculateResolvedType(), expr.asString())
            }
            override fun visit(expr: BooleanLiteralExpr, arg: Interpreter): AbstractValue {
                return AbstractValue.Data(expr, expr.calculateResolvedType(), expr.value)
            }
            override fun visit(expr: CharLiteralExpr, arg: Interpreter): AbstractValue {
                return AbstractValue.Data(expr, expr.calculateResolvedType(), expr.asChar())
            }
            override fun visit(expr: DoubleLiteralExpr, arg: Interpreter): AbstractValue {
                return AbstractValue.Data(expr, expr.calculateResolvedType(), expr.asDouble())
            }
            override fun visit(expr: IntegerLiteralExpr, arg: Interpreter): AbstractValue {
                return AbstractValue.Data(expr, expr.calculateResolvedType(), expr.asNumber().toLong())
            }
            override fun visit(expr: LongLiteralExpr, arg: Interpreter): AbstractValue {
                return AbstractValue.Data(expr, expr.calculateResolvedType(), expr.asNumber().toLong())
            }
            override fun visit(expr: TextBlockLiteralExpr, arg: Interpreter): AbstractValue {
                return AbstractValue.Data(expr, expr.calculateResolvedType(), expr.asString())
            }

            // Variable
            override fun visit(expr: NameExpr, arg: Interpreter): AbstractValue {
                val varName = expr.nameAsString
                val variable = lookup(varName)
                // varName is undefined.
                if (variable == null) {
                    val javaType = expr.calculateResolvedType()
                    // Free variables are arguments.
                    if (!effect.argv.contains(varName)) {
                        val typeStr = javaType.toString()
                        if (typeStr.contains("String", ignoreCase = true)) {
                            effect.addArgv(varName, Type.String)
                        } else if (typeStr.contains("Double", ignoreCase = true) || typeStr.contains("Float", ignoreCase = true)) {
                            effect.addArgv(varName, Type.Real)
                        } else if (typeStr.contains("Date", ignoreCase = true)) {
                            // effect.addArgv(varName, Type.Int)
                            // FIXME: Use int for date
                        } else if (typeStr.contains("Int", ignoreCase = true)) {
                            effect.addArgv(varName, Type.Int)
                        } else if (!typeStr.contains("Connection", ignoreCase = true)) {
                            println("[WARN-INT] unknown arg type $typeStr")
                        }
                    }
                    return AbstractValue.Free(expr, javaType, varName)
                } else {
                    return variable.get()
                }
            }
            override fun visit(expr: VariableDeclarator, arg: Interpreter): AbstractValue? {
                val name = expr.nameAsString
                val ty = expr.type.resolve()
                val value = if (expr.initializer.isEmpty) {
                    AbstractValue.Null(expr.nameAsExpression, ty)
                } else {
                    evalExpr(expr.initializer.get())
                }
                putVariable(name, value!!)
                println("[DBG] Var $name = $value")
                return null
            }
            override fun visit(expr: AssignExpr, arg: Interpreter): AbstractValue {
                // If expr is not `a = expr`, but `a += expr`, change it to `a = a + expr` first.
                if (expr.operator != AssignExpr.Operator.ASSIGN) {
                    val target = expr.target.clone()
                    val a = target.clone()
                    val b = expr.value.clone()
                    val newExpr = when (expr.operator) {
                        AssignExpr.Operator.PLUS -> BinaryExpr(a, b, BinaryExpr.Operator.PLUS)
                        AssignExpr.Operator.MINUS -> BinaryExpr(a, b, BinaryExpr.Operator.MINUS)
                        AssignExpr.Operator.MULTIPLY -> BinaryExpr(a, b, BinaryExpr.Operator.MULTIPLY)
                        AssignExpr.Operator.DIVIDE -> BinaryExpr(a, b, BinaryExpr.Operator.DIVIDE)
                        AssignExpr.Operator.LEFT_SHIFT -> BinaryExpr(a, b, BinaryExpr.Operator.LEFT_SHIFT)
                        AssignExpr.Operator.SIGNED_RIGHT_SHIFT -> BinaryExpr(a, b, BinaryExpr.Operator.SIGNED_RIGHT_SHIFT)
                        AssignExpr.Operator.UNSIGNED_RIGHT_SHIFT -> BinaryExpr(a, b, BinaryExpr.Operator.UNSIGNED_RIGHT_SHIFT)
                        AssignExpr.Operator.BINARY_AND -> BinaryExpr(a, b, BinaryExpr.Operator.BINARY_AND)
                        AssignExpr.Operator.BINARY_OR -> BinaryExpr(a, b, BinaryExpr.Operator.BINARY_OR)
                        AssignExpr.Operator.XOR -> BinaryExpr(a, b, BinaryExpr.Operator.XOR)
                        AssignExpr.Operator.REMAINDER -> BinaryExpr(a, b, BinaryExpr.Operator.REMAINDER)
                        AssignExpr.Operator.ASSIGN -> error("unreachable")
                        null -> error("assignment operator is null")
                    }
                    expr.replace(newExpr)
                    return visit(newExpr, arg)
                }

                // expr must be `a = expr` now.
                assert(expr.target::class.toString().contains("NameExpr"))
                val value = evalExpr(expr.value)!!
                putVariable(expr.target.toString(), value)
                return value
            }

            // Arithmetics
            override fun visit(expr: UnaryExpr, arg: Interpreter): AbstractValue {
                val inner = evalExpr(expr.expression)!!
                return when (expr.operator) {
                    UnaryExpr.Operator.PLUS -> inner
                    UnaryExpr.Operator.MINUS -> inner.negate(expr)
                    UnaryExpr.Operator.LOGICAL_COMPLEMENT -> inner.not(expr)
                    else -> {
                        println("[WARN] unary operator ${expr.operator} unsupported")
                        AbstractValue.Unknown(expr, expr.calculateResolvedType())
                    }
                }
            }
            override fun visit(expr: BinaryExpr, arg: Interpreter): AbstractValue {
                val left = evalExpr(expr.left)!!
                val right = evalExpr(expr.right)!!

                if (left.staticType.toString().toLowerCase().contains("bool")) {
                    println("[WARN] Short-circuit semantics may be ignored")
                }

                return when (expr.operator) {
                    BinaryExpr.Operator.PLUS -> left.add(expr, right)
                    BinaryExpr.Operator.MINUS -> left.sub(expr, right)
                    BinaryExpr.Operator.MULTIPLY -> left.mul(expr, right)
                    BinaryExpr.Operator.DIVIDE -> left.div(expr, right)
                    BinaryExpr.Operator.XOR -> left.xor(expr, right)
                    BinaryExpr.Operator.EQUALS -> left.eq(expr, right)
                    BinaryExpr.Operator.NOT_EQUALS -> left.ne(expr, right)
                    BinaryExpr.Operator.GREATER -> left.gt(expr, right)
                    BinaryExpr.Operator.GREATER_EQUALS -> left.ge(expr, right)
                    BinaryExpr.Operator.LESS -> left.lt(expr, right)
                    BinaryExpr.Operator.LESS_EQUALS -> left.le(expr, right)
                    else -> {
                        println("[WARN] binary operator ${expr.operator} unsupported")
                        AbstractValue.Unknown(expr, expr.calculateResolvedType())
                    }
                }
            }
            override fun visit(methodCallExpr: MethodCallExpr, arg: Interpreter): AbstractValue {
                if (methodCallExpr.scope.isEmpty) {
                    println("[WARN] don't know how to handle empty scope for $methodCallExpr")
                    return AbstractValue.Unknown(methodCallExpr, methodCallExpr.calculateResolvedType())
                }

                val scope = methodCallExpr.scope.get()
                if (scope !is NameExpr) {
                    println("[WARN] scope is complex: $methodCallExpr")
                }

                val receiver = evalExpr(scope)!!
                val args = methodCallExpr.arguments.map { evalExpr(it)!! }
                val methodDecl = methodCallExpr.resolve()
                return if (hasSemantics(methodDecl)) {
                    dispatchSemantics(methodCallExpr, arg, receiver, args)
                } else {
                    AbstractValue.Call(methodCallExpr, methodCallExpr.calculateResolvedType(), receiver, args)
                }
            }

            override fun visit(conditionalExpr: ConditionalExpr, arg: Interpreter): AbstractValue {
                val cond = evalExpr(conditionalExpr.condition)!!
                if (cond is AbstractValue.Data && cond.data is Boolean) {
                    return if (cond.data) {
                        evalExpr(conditionalExpr.thenExpr)!!
                    } else {
                        evalExpr(conditionalExpr.elseExpr)!!
                    }
                } else {
                    return AbstractValue.Unknown(conditionalExpr, conditionalExpr.calculateResolvedType())
                }
            }

            //
            // The following are expressions currently not supported.
            //
            override fun visit(expr: ObjectCreationExpr, arg: Interpreter): AbstractValue {
                println("[WARN] unknown ${expr::class}: $expr")
                return AbstractValue.Unknown(expr, expr.calculateResolvedType())
            }
            override fun visit(expr: ArrayAccessExpr, arg: Interpreter): AbstractValue {
                println("[WARN] unknown ${expr::class}: $expr")
                return AbstractValue.Unknown(expr, expr.calculateResolvedType())
            }
            override fun visit(expr: ArrayInitializerExpr, arg: Interpreter): AbstractValue {
                println("[WARN] unknown ${expr::class}: $expr")
                // Java Symbol Solver is incapable of inferring the array type.
                return AbstractValue.Unknown(expr, null)
            }
            override fun visit(expr: ArrayCreationExpr, arg: Interpreter): AbstractValue {
                println("[WARN] unknown ${expr::class}: $expr")
                return AbstractValue.Unknown(expr, expr.calculateResolvedType())
            }
            override fun visit(expr: LambdaExpr, arg: Interpreter): AbstractValue {
                println("[WARN] unknown ${expr::class}: $expr")
                return AbstractValue.Unknown(expr, expr.calculateResolvedType())
            }
            override fun visit(expr: CastExpr, arg: Interpreter): AbstractValue {
                println("[WARN] unknown ${expr::class}: $expr")
                return AbstractValue.Unknown(expr, expr.calculateResolvedType())
            }
            override fun visit(expr: ThisExpr, arg: Interpreter): AbstractValue {
                println("[WARN] unknown ${expr::class}: $expr")
                return AbstractValue.Unknown(expr, expr.calculateResolvedType())
            }
            override fun visit(expr: TypeExpr, arg: Interpreter): AbstractValue {
                println("[WARN] unknown ${expr::class}: $expr")
                return AbstractValue.Unknown(expr, expr.calculateResolvedType())
            }
            override fun visit(expr: ClassExpr, arg: Interpreter): AbstractValue {
                println("[WARN] unknown ${expr::class}: $expr")
                return AbstractValue.Unknown(expr, expr.calculateResolvedType())
            }
            override fun visit(expr: SuperExpr, arg: Interpreter): AbstractValue {
                println("[WARN] unknown ${expr::class}: $expr")
                return AbstractValue.Unknown(expr, expr.calculateResolvedType())
            }
            override fun visit(expr: PatternExpr, arg: Interpreter): AbstractValue {
                println("[WARN] unknown ${expr::class}: $expr")
                return AbstractValue.Unknown(expr, expr.calculateResolvedType())
            }
            override fun visit(expr: EnclosedExpr, arg: Interpreter): AbstractValue {
                println("[WARN] unknown ${expr::class}: $expr")
                return AbstractValue.Unknown(expr, expr.calculateResolvedType())
            }
            override fun visit(expr: InstanceOfExpr, arg: Interpreter): AbstractValue {
                println("[WARN] unknown ${expr::class}: $expr")
                return AbstractValue.Unknown(expr, expr.calculateResolvedType())
            }
            override fun visit(fieldAccessExpr: FieldAccessExpr, arg: Interpreter): AbstractValue {
                println("[WARN] unknown ${expr::class}: $expr")
                return AbstractValue.Unknown(expr, expr.calculateResolvedType())
            }
        }, this)
        // println("[DBG] Eval $expr = $result")
        return result
    }

    private fun evalStatement(stmt: Statement) {
        // We have unfolded all control-flow-related statements in the construction of the intra-graph.
        return stmt.accept(object : VoidVisitorAdapter<Void>() {
            override fun visit(stmt: EmptyStmt, arg: Void?) {}
            override fun visit(stmt: ExpressionStmt, arg: Void?) {
                evalExpr(stmt.expression)
            }
            override fun visit(stmt: AssertStmt, arg: Void?) {
                // Assume it always succeeds.
                // Do nothing.
            }
            override fun visit(stmt: ReturnStmt, arg: Void?) {
                if (stmt.expression.isPresent) {
                    returnValue = evalExpr(stmt.expression.get())
                }
            }
            override fun visit(stmt: ExplicitConstructorInvocationStmt, arg: Void?) {
                println("[WARN] can't handle ${stmt::class}: $stmt")
            }
            override fun visit(stmt: ForEachStmt, arg: Void?) {
                println("[WARN] can't handle ${stmt::class}: $stmt")
            }
            override fun visit(stmt: LabeledStmt, arg: Void?) {
                println("[WARN] can't handle ${stmt::class}: $stmt")
            }
            override fun visit(stmt: SynchronizedStmt, arg: Void?) {
                println("[WARN] can't handle ${stmt::class}: $stmt")
            }
            override fun visit(stmt: LocalClassDeclarationStmt, arg: Void?) {
                println("[WARN] can't handle ${stmt::class}: $stmt")
            }
            override fun visit(stmt: SwitchStmt, arg: Void?) {
                println("[WARN] can't handle ${stmt::class}: $stmt")
            }
            override fun visit(stmt: YieldStmt, arg: Void?) {
                println("[WARN] can't handle ${stmt::class}: $stmt")
            }
            override fun visit(stmt: UnparsableStmt, arg: Void?) {
                println("[WARN] encounter unparsable code: $stmt")
            }
        }, null)
    }

    private fun evalNode(node: Node) {
        adjustScope(node.scopingDepth)
        evalStatement(node.statement)
    }

    private fun evalCond(cond: Expression, not: Boolean = false) {
        val value = evalExpr(cond)
        when (value) {
            null -> {
                throw IllegalArgumentException("Condition `$cond` is not a boolean expression")
            }
            is AbstractValue.DbNotNil -> {
                if (not)
                    value.reverse()
                println("[DB] condition: $value")
                effect.addCondition(value)
            }
            else -> {
                println("[COND] condition: $cond")
                effect.addCondition(value)
            }
        }
    }

    /**
     * Run class-wise code like field definitions.
     *
     * This should be called before running any code, or scopes will be broken.
     */
    fun runClass(classDef: ClassOrInterfaceDeclaration) {
        println("[DBG] Run class ${classDef.resolve().qualifiedName}")

        assert(depth == 0)

        classDef.accept(object : VoidVisitorAdapter<Interpreter>() {
            override fun visit(methodDeclaration: MethodDeclaration, arg: Interpreter) {
                // Ignore.
            }

            override fun visit(d: VariableDeclarator, arg: Interpreter) {
                println("[DBG] Class field: $d")

                val varName = d.nameAsString
                val init = d.initializer
                if (init.isEmpty) {
                    putVariable(varName, AbstractValue.Null(d.nameAsExpression, d.type.resolve()))
                } else {
                    putVariable(varName, evalExpr(init.get())!!)
                }
            }
        }, this)
    }

    fun run(path: IntraPath) {
        println("[DBG] Run path ${path.final}: ${path.path}")

        for (edge in path.path) {
            // println("[RUN] edge = ${edge}")
            if (g.idNode[edge.next] != null) {
                evalNode(g.idNode[edge.next]!!)
            }
            when (edge.label) {
                is Label.T -> {}
                is Label.Br -> {
                    evalCond(edge.label.expr)
                }
                is Label.BrNot -> {
                    evalCond(edge.label.expr, not = true)
                }
                else -> {
                    println("[WARN] unknown label: ${edge.label}, assuming to be true")
                }
            }
        }
        val finalNode = g.idNode[path.final]
        if (finalNode != null)
            evalNode(finalNode)
    }

    /**
     * Introduce a fresh free abstract value with name based on `namePattern` for the current environment, and then add
     * as an argument.
     *
     * @param namePattern the pattern will first be normalized so that it's a valid identifier, and then suffixed with number to distinguish from other arguments
     */
    fun freshArg(namePattern: String, type: Type): AbstractValue.Free {
        val ident = "fresh" + namePattern.replace(".", "_")
            .replace("[", "_").replace("]", "_")
            .replace("(", "_").replace(")", "_")
            .capitalize()
        val av = if (lookup(ident) != null) {
            var cnt = 1
            while (lookup("$ident$cnt") != null)
                cnt += 1
            AbstractValue.Free(null, null, "$ident$cnt")
        } else {
            AbstractValue.Free(null, null, ident)
        }
        putVariable(av.name, av)
        effect.addArgv(av.name, type)
        return av
    }
}
