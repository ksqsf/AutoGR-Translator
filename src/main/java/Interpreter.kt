import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.stmt.*
import com.github.javaparser.ast.visitor.GenericVisitorAdapter
import com.github.javaparser.ast.visitor.VoidVisitorAdapter
import java.lang.IllegalArgumentException
import java.lang.RuntimeException

/**
 * A variable corresponds to a variable definition (statically), or the storage (dynamically).
 */
data class Variable(var value: AbstractValue? = null) {
    /**
     * @exception [NullPointerException] if the variable is not assigned a value
     */
    fun get(): AbstractValue {
        return value!!
    }

    /**
     * Returns null if the value is unknown.
     */
    fun getKnown(): AbstractValue? {
        return if (get().unknown())
            null
        else
            get()
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
    private val loopSet = g.collectLoops()

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

    /**
     * Find a variable definition with the specified name.
     *
     * @return null if not in scope.
     */
    fun lookup(varName: String): Variable? {
        scope.reversed().forEach {
            if (it.containsKey(varName))
                return it[varName]
        }
        return null
    }

    /**
     * Find a variable definition with specified name. If not in scope, create an empty variable definition at the current
     * scope level, so that a new value could be filled.
     */
    fun lookupOrCreate(varName: String): Variable {
        val existing: Variable? = lookup(varName)
        if (existing != null)
            return existing
        val new = Variable()
        scope.last()[varName] = new
        return new
    }

    fun putVariable(varName: String, value: AbstractValue) {
        val variable = lookupOrCreate(varName)
        variable.set(value)
    }

    // null is returned when it's a variable declaration expression.
    // If any expr is of unknown kind, AbstractValue.Null is returned.
    // Therefore, it's generally safe to unwrap the return value.
    private fun evalExpr(expr: Expression): AbstractValue? {
        val result = expr.accept(object : GenericVisitorAdapter<AbstractValue?, Interpreter>() {
            // Null
            override fun visit(expr: NullLiteralExpr, arg: Interpreter): AbstractValue {
                return AbstractValue.Null(expr)
            }

            // Literals
            override fun visit(expr: StringLiteralExpr, arg: Interpreter): AbstractValue {
                return AbstractValue.Data(expr, expr.asString())
            }
            override fun visit(expr: BooleanLiteralExpr, arg: Interpreter): AbstractValue {
                return AbstractValue.Data(expr, expr.value)
            }
            override fun visit(expr: CharLiteralExpr, arg: Interpreter): AbstractValue {
                return AbstractValue.Data(expr, expr.asChar())
            }
            override fun visit(expr: DoubleLiteralExpr, arg: Interpreter): AbstractValue {
                return AbstractValue.Data(expr, expr.asDouble())
            }
            override fun visit(expr: IntegerLiteralExpr, arg: Interpreter): AbstractValue {
                return AbstractValue.Data(expr, expr.asNumber().toLong())
            }
            override fun visit(expr: LongLiteralExpr, arg: Interpreter): AbstractValue {
                return AbstractValue.Data(expr, expr.asNumber().toLong())
            }
            override fun visit(expr: TextBlockLiteralExpr, arg: Interpreter): AbstractValue {
                return AbstractValue.Data(expr, expr.asString())
            }

            // Variable
            override fun visit(expr: NameExpr, arg: Interpreter): AbstractValue {
                val varName = expr.nameAsString
                val variable = lookup(varName)
                return variable?.get() ?: // varName is undefined.
                AbstractValue.Unknown(expr)
            }
            override fun visit(expr: VariableDeclarator, arg: Interpreter): AbstractValue? {
                val name = expr.nameAsString
                val value = if (expr.initializer.isEmpty) {
                    AbstractValue.Null(expr.nameAsExpression)
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
                val innerE = expr.expression
                val inner = evalExpr(innerE)!!
                return when (expr.operator) {
                    UnaryExpr.Operator.PLUS -> inner
                    UnaryExpr.Operator.MINUS -> inner.negate(expr)
                    UnaryExpr.Operator.LOGICAL_COMPLEMENT -> inner.not(expr)
                    UnaryExpr.Operator.POSTFIX_INCREMENT -> {
                        return if (innerE is NameExpr) {
                            arg.lookup(innerE.nameAsString)?.set(inner.add(null, AbstractValue.from(1L)))
                            inner
                        } else {
                            println("[WARN] cannot handle ${expr.expression} ++")
                            AbstractValue.Unknown(expr)
                        }
                    }
                    UnaryExpr.Operator.POSTFIX_DECREMENT -> {
                        return if (innerE is NameExpr) {
                            arg.lookup(innerE.nameAsString)?.set(inner.sub(null, AbstractValue.from(1L)))
                            inner
                        } else {
                            println("[WARN] cannot handle ${expr.expression} ++")
                            AbstractValue.Unknown(expr)
                        }
                    }
                    UnaryExpr.Operator.PREFIX_INCREMENT -> {
                        val new = inner.add(null, AbstractValue.from(1L))
                        return if (innerE is NameExpr) {
                            arg.lookup(innerE.nameAsString)?.set(new)
                            return new
                        } else {
                            println("[WARN] cannot handle ${expr.expression} ++")
                            AbstractValue.Unknown(expr)
                        }
                    }
                    UnaryExpr.Operator.PREFIX_DECREMENT -> {
                        val new = inner.sub(null, AbstractValue.from(1L))
                        return if (innerE is NameExpr) {
                            arg.lookup(innerE.nameAsString)?.set(new)
                            return new
                        } else {
                            println("[WARN] cannot handle ${expr.expression} ++")
                            AbstractValue.Unknown(expr)
                        }
                    }
                    else -> {
                        println("[WARN] unary operator ${expr.operator} unsupported")
                        AbstractValue.Unknown(expr)
                    }
                }
            }
            override fun visit(expr: BinaryExpr, arg: Interpreter): AbstractValue {
                val left = evalExpr(expr.left)!!
                val right = evalExpr(expr.right)!!

                if (left.expr?.calculateResolvedType().toString().toLowerCase().contains("bool")) {
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
                    BinaryExpr.Operator.OR -> left.or(expr, right)
                    BinaryExpr.Operator.AND -> left.and(expr, right)
                    else -> {
                        println("[WARN] binary operator ${expr.operator} unsupported")
                        AbstractValue.Unknown(expr)
                    }
                }
            }
            override fun visit(methodCallExpr: MethodCallExpr, arg: Interpreter): AbstractValue {
                val methodDecl = methodCallExpr.resolve()

                val receiver = if (methodCallExpr.scope.isPresent) {
                    val scope = methodCallExpr.scope.get()
                    if (scope !is NameExpr) {
                        println("[WARN] complex scope: $methodCallExpr")
                    }
                    evalExpr(scope)!!
                } else {
                    println("[WARN] empty scope: $methodCallExpr")
                    AbstractValue.Unknown(null)
                }
                val args = methodCallExpr.arguments.map { evalExpr(it)!! }

                val qname = methodDecl.qualifiedName
                return if (hasSemantics(methodDecl.qualifiedName)) {
                    dispatchSemantics(methodCallExpr, arg, receiver, args, qname)
                } else {
                    AbstractValue.Call(methodCallExpr, receiver, methodCallExpr.nameAsString, args)
                }
            }
            override fun visit(expr: ObjectCreationExpr, arg: Interpreter): AbstractValue {
                val qname = expr.type.resolve().qualifiedName
                if (hasSemantics(qname)) {
                    return dispatchSemantics(expr, arg, null, expr.arguments.map { evalExpr(it)!! }, qname)
                }
                return AbstractValue.Unknown(expr)
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
                    return AbstractValue.Unknown(conditionalExpr)
                }
            }

            // (expr)
            override fun visit(expr: EnclosedExpr, arg: Interpreter): AbstractValue {
                return expr.inner.accept(this, arg)!!
            }

            // Object
            override fun visit(expr: FieldAccessExpr, arg: Interpreter): AbstractValue {
                // If this refers to a static constant, we can directly get its value from the compiled class file.
                val decl = expr.resolve()
                if (decl.isField) {
                    val field = decl.asField()
                    if (field.isStatic && expr.scope.isNameExpr) {
                        val scope = expr.scope.asNameExpr()
                        val className = scope.calculateResolvedType().asReferenceType().qualifiedName
                        return getStaticFieldByReflection(className, expr.nameAsString, expr)
                    }
                }
                // Otherwise, we know nothing about it.
                println("[WARN] unknown ${expr::class}: $expr")
                return AbstractValue.Unknown(expr)
            }

            //
            // Array
            //
            override fun visit(expr: ArrayAccessExpr, arg: Interpreter): AbstractValue {
                val array = evalExpr(expr.name)!!
                val idx = evalExpr(expr.index)!!
                if (array is AbstractValue.Array && idx is AbstractValue.Data && (idx.data is Long || idx.data is Int)) {
                    val i = if (idx.data is Long) idx.data.toInt() else idx.data as Int
                    return array[i] ?: AbstractValue.Unknown(expr)
                }
                println("[WARN] unknown array access: $expr")
                return AbstractValue.Unknown(expr)
            }
            override fun visit(expr: ArrayInitializerExpr, arg: Interpreter): AbstractValue {
                val values = expr.values.map { evalExpr(it)!! }
                val array = AbstractValue.Array(expr)
                for ((i, v) in values.withIndex()) {
                    array[i] = v
                }
                return array
            }
            override fun visit(expr: ArrayCreationExpr, arg: Interpreter): AbstractValue {
                return if (expr.initializer.isPresent) {
                    visit(expr.initializer.get(), arg)
                } else {
                    AbstractValue.Array(expr)
                }
            }

            //
            // Type related
            //
            override fun visit(expr: CastExpr, arg: Interpreter): AbstractValue {
                val ty = expr.type
                return if (ty.isPrimitiveType)
                    evalExpr(expr.expression)!!
                else {
                    println("[WARN] unknown cast expr: $expr")
                    AbstractValue.Unknown(expr)
                }
            }

            //
            // The following are expressions currently not supported.
            //
            override fun visit(expr: LambdaExpr, arg: Interpreter): AbstractValue {
                println("[WARN] unknown ${expr::class}: $expr")
                return AbstractValue.Unknown(expr)
            }
            override fun visit(expr: ThisExpr, arg: Interpreter): AbstractValue {
                println("[WARN] unknown ${expr::class}: $expr")
                return AbstractValue.Unknown(expr)
            }
            override fun visit(expr: TypeExpr, arg: Interpreter): AbstractValue {
                println("[WARN] unknown ${expr::class}: $expr")
                return AbstractValue.Unknown(expr)
            }
            override fun visit(expr: ClassExpr, arg: Interpreter): AbstractValue {
                println("[WARN] unknown ${expr::class}: $expr")
                return AbstractValue.Unknown(expr)
            }
            override fun visit(expr: SuperExpr, arg: Interpreter): AbstractValue {
                println("[WARN] unknown ${expr::class}: $expr")
                return AbstractValue.Unknown(expr)
            }
            override fun visit(expr: PatternExpr, arg: Interpreter): AbstractValue {
                println("[WARN] unknown ${expr::class}: $expr")
                return AbstractValue.Unknown(expr)
            }
            override fun visit(expr: InstanceOfExpr, arg: Interpreter): AbstractValue {
                println("[WARN] unknown ${expr::class}: $expr")
                return AbstractValue.Unknown(expr)
            }

            // Helper functions
            fun getStaticFieldByReflection(className: String, fieldName: String, expr: Expression?): AbstractValue {
                val klass = Class.forName(className, true, effect.analyzer.classLoader)
                val field = klass.getField(fieldName)
                val value = field.get(null)
                return AbstractValue.Data(expr, value)
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
            override fun visit(stmt: LabeledStmt, arg: Void?) {
                println("[WARN] can't handle ${stmt::class}: $stmt")
            }
            override fun visit(stmt: SynchronizedStmt, arg: Void?) {
                println("[WARN] can't handle ${stmt::class}: $stmt")
            }
            override fun visit(stmt: LocalClassDeclarationStmt, arg: Void?) {
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

    /**
     * @param addPathCond if false, the expression is evaluated but not added to the current effect's pcond. used in loop analysis.
     */
    private fun evalCond(cond: Expression, not: Boolean = false, addPathCond: Boolean = true) {
        when (val value = evalExpr(cond)) {
            null -> {
                throw IllegalArgumentException("Condition `$cond` is not a boolean expression")
            }
            is AbstractValue.DbNotNil -> {
                if (not)
                    value.reverse()

                if (addPathCond) {
                    println("[DB] condition: $value")
                    effect.addCondition(value)
                }
            }
            else -> {
                if (addPathCond) {
                    println("[COND] condition: $cond")
                    effect.addCondition(value)
                }
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
                val varName = d.nameAsString
                val init = d.initializer
                if (init.isEmpty) {
                    putVariable(varName, AbstractValue.Null(d.nameAsExpression))
                } else {
                    putVariable(varName, evalExpr(init.get())!!)
                }
                println("[DBG] Class field: $varName = ${lookup(varName)?.get()}")
            }
        }, this)
    }

    /**
     * Collect variables modified by loop at base. The result set can contain variables defined inside the loop, but since
     * they cannot be used outside the loop, it's safe.
     *
     * ASSUME the base node is immutable.
     */
    private fun variablesModifiedByLoop(base: Int): Set<Pair<Variable, String>> {
        val body = loopSet[base]!!.body.toList()
        val res = mutableSetOf<Pair<Variable, String>>()
        pushScope()
        for (id in body) {
            val node = g.idNode[id] ?: continue
            if (containsUpdate(node.statement) || containsCommit(node.statement)) {
                throw RuntimeException("Can't analyze effect inside loop")
            }
            node.statement.accept(object : VoidVisitorAdapter<MutableSet<Pair<Variable, String>>>() {
                override fun visit(n: MethodCallExpr, res: MutableSet<Pair<Variable, String>>) {
                    when (val scope = n.scope) {
                        is NameExpr -> {
                            val method = n.resolve()
                            if (method.name.startsWith("set")) {
                                println("[loop] modify $scope")
                                res.add(Pair(lookupOrCreate(scope.nameAsString), scope.nameAsString))
                            }
                        }
                        else -> println("[loop] unknown scope $scope")
                    }
                }
                override fun visit(n: VariableDeclarator, res: MutableSet<Pair<Variable, String>>) {
                    lookupOrCreate(n.nameAsString)
                }
                override fun visit(n: AssignExpr, res: MutableSet<Pair<Variable, String>>) {
                    when (val target = n.target) {
                        is NameExpr -> {
                            println("[loop] modify $target")
                            res.add(Pair(lookupOrCreate(target.nameAsString), target.nameAsString))
                        }
                        else -> println("[loop] unknown target $target")
                    }
                }
            }, res)
        }
        popScope()
        return res
    }

    fun run(path: IntraPath) {
        println("[DBG] Run path ${path.final}: ${path.path}")

        for (edge in path.path) {
            // println("[RUN] edge = ${edge}")
            // Since the path is linear, we will eventually:
            // 1. leave this loop;
            // 2. find an effect.
            //    a) the effect is outside the loop: the simplest case.
            //    b) the effect is inside the loop
            //        i. the effect is the final node of this path: ask the user to confirm it's loop-independent.
            //           the loop is effectively unrolled once.
            //       ii. the effect is not the final node this path: we can't analyze!
            // This logic is strongly coupled with `collectEffectPaths`.
            val id = edge.next
            val loopBase = loopSet.findBase(id)
            var enterLoop = false
            if (id == loopBase) {
                // find a new loop.
                println("[loop] new loop $loopBase at ${edge.label}")
                for ((variable, name) in variablesModifiedByLoop(id)) {
                    variable.set(AbstractValue.Unknown(null, tag = name))
                }
                enterLoop = true
            }

            if (g.idNode[edge.next] != null) {
                evalNode(g.idNode[edge.next]!!)
            }
            when (edge.label) {
                is Label.T -> {}
                is Label.Br -> {
                    evalCond(edge.label.expr, addPathCond = !enterLoop)
                }
                is Label.BrNot -> {
                    evalCond(edge.label.expr, not = true, addPathCond = !enterLoop)
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
            AbstractValue.Free(null, "$ident$cnt", type)
        } else {
            AbstractValue.Free(null, ident, type)
        }
        putVariable(av.name, av)
        effect.addArgv(av.name, type)
        return av
    }
}
