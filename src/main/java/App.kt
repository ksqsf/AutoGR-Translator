import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.stmt.*
import com.github.javaparser.ast.visitor.GenericVisitorAdapter
import com.github.javaparser.ast.visitor.VoidVisitorAdapter
import com.github.javaparser.resolution.types.ResolvedType
import com.github.javaparser.symbolsolver.JavaSymbolSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ClassLoaderTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.MemoryTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver
import com.github.javaparser.utils.SourceRoot
import io.github.classgraph.ClassGraph
import io.github.classgraph.MethodInfo
import io.github.classgraph.ScanResult
import java.nio.file.Path

typealias QualifiedName = String

fun main() {
    val projectRoot = "/Users/kaima/src/RedBlue_consistency/src/applications/RUBiStxmud/Servlets/edu"
//    val projectRoot = "/Users/kaima/src/translator/src/main/resources/"
    val typeSolver = CombinedTypeSolver(
        MemoryTypeSolver(),
        ReflectionTypeSolver(),
        ClassLoaderTypeSolver(ClassLoader.getSystemClassLoader())
    )
    val symbolSolver = JavaSymbolSolver(typeSolver)

    val config = ParserConfiguration()
    config.setSymbolResolver(symbolSolver)

    val project = SourceRoot(Path.of(projectRoot))
    project.parserConfiguration = config
    val results = project.tryToParseParallelized()
    val projectFiles = project.compilationUnits

    // Debugging
    for (result in results) {
        if (!result.isSuccessful) {
            println(result.problems)
        }
    }

    // Step 0. Desugar various forms of loops to simplify analysis
    for (file in projectFiles) {
        transformLoops(file)
    }

    // Step 1. Construct interprocedural call graph for the project
    val interGraph = InterGraph()
    for (file in projectFiles) {
        println("Intergraph: ${file.storage.get().fileName}")
        val fileG = buildInterGraph(file)
        interGraph.union(fileG)
    }
    val basicEffects = listOf(
        "java.sql.PreparedStatement.executeUpdate",
        "java.sql.Statement.executeUpdate"
    )
    basicEffects.forEach { interGraph.markNameAsEffect(it) }
    interGraph.graphviz()

    // Step 2. Construct intraprocedural flow graph for each method
    val intraGraphs: MutableMap<QualifiedName, IntraGraph> = mutableMapOf()
    for (file in projectFiles) {
        buildIntraGraph(file, intraGraphs)
    }

    println("Finished")
}

typealias IntraGraphSet = MutableMap<QualifiedName, IntraGraph>

/**
 * Build intra-graph for a compilation unit.
 */
fun buildIntraGraph(file: CompilationUnit, intraGraphs: IntraGraphSet) {

    val ndebug = true

    fun debug(s: String) {
        if (!ndebug)
            println(s)
    }

    val exceptionVisitor = object : GenericVisitorAdapter<List<ResolvedType>, Void>() {
        override fun visit(binaryExpr: BinaryExpr, arg: Void?): List<ResolvedType>? {
            val left = binaryExpr.left.accept(this, arg)
            val right = binaryExpr.right.accept(this, arg)
            if (left == null) return right
            if (right == null) return left
            return left + right
        }

        override fun visit(call: MethodCallExpr, arg: Void?): List<ResolvedType>? {
            val args = call.arguments
            val argExc = args.mapNotNull { it.accept(this, arg) }.flatten()
            val resolvedCall = call.resolve()
            val res = (resolvedCall.specifiedExceptions + argExc).filterNotNull()
            if (res.isNotEmpty())
                return res
            return null
        }
    }

    val blockVisitor = object : GenericVisitorAdapter<IntraGraph, Void>() {
        var depth = 0

        override fun visit(blockStmt: BlockStmt, arg: Void?): IntraGraph {
            debug(" ".repeat(depth) + ".Find block")
            depth += 1
            val g = IntraGraph()
            if (blockStmt.childNodes.size == 0) {
                g.addEdgeFromEntryToExit()
                return g
            }
            val subG = blockStmt.statements.map { it.accept(this, null) }
            g.sequence(subG)
            depth -= 1
            return g
        }

        override fun visit(whileStmt: WhileStmt, arg: Void?): IntraGraph {
            val g = IntraGraph()
            val bodyG = whileStmt.body.accept(this, arg)
            g.union(bodyG, mergeBreak = false, mergeContinue = false)
            val cond = whileStmt.condition
            if (cond == BooleanLiteralExpr(true)) {
                g.addEdgeId(g.entryId, bodyG.entryId)
            } else {
                g.addEdgeId(g.entryId, bodyG.entryId, Label.Br(cond))
                g.addEdgeFromEntryToExit(Label.Br(UnaryExpr(cond, UnaryExpr.Operator.LOGICAL_COMPLEMENT)))
            }
            g.addEdgeId(bodyG.exitId, g.entryId)
            g.addEdgeId(bodyG.breakId, g.exitId)
            g.addEdgeId(bodyG.continueId, g.entryId)
            return g
        }

        override fun visit(doStmt: DoStmt, arg: Void?): IntraGraph {
            val g = IntraGraph()
            val bodyG = doStmt.body.accept(this, arg)
            g.union(bodyG, mergeBreak = false, mergeContinue = false)
            val cond = doStmt.condition
            g.addEdgeId(g.entryId, bodyG.entryId)
            if (cond == BooleanLiteralExpr(true)) {
                g.addEdgeId(bodyG.exitId, g.entryId)
            } else {
                g.addEdgeId(bodyG.exitId, g.entryId, Label.Br(cond))
                g.addEdgeId(
                    bodyG.exitId,
                    g.exitId,
                    Label.Br(UnaryExpr(cond, UnaryExpr.Operator.LOGICAL_COMPLEMENT))
                )
            }
            g.addEdgeId(bodyG.continueId, bodyG.exitId)
            g.addEdgeId(bodyG.breakId, g.exitId)
            return g
        }

        override fun visit(breakStmt: BreakStmt, arg: Void?): IntraGraph {
            val g = IntraGraph()
            g.addEdgeId(g.entryId, g.breakId)
            return g
        }

        override fun visit(continueStmt: ContinueStmt, arg: Void?): IntraGraph {
            val g = IntraGraph()
            g.addEdgeId(g.entryId, g.continueId)
            return g
        }

        override fun visit(ifStmt: IfStmt, arg: Void?): IntraGraph {
            val g = IntraGraph()
            val cond = ifStmt.condition
            debug(" ".repeat(depth) + ".Find if " + cond)

            val thenStmt = ifStmt.thenStmt
            val thenG = thenStmt.accept(this, null)
            if (ifStmt.elseStmt.isPresent) {
                val elseStmt = ifStmt.elseStmt.get()
                val elseG = elseStmt.accept(this, null)
                g.addBetweenEntryAndExit(thenG, Label.Br(cond))
                g.addBetweenEntryAndExit(elseG, Label.Br(UnaryExpr(cond, UnaryExpr.Operator.LOGICAL_COMPLEMENT)))
            } else {
                g.addBetweenEntryAndExit(thenG, Label.Br(cond))
                g.addEdgeFromEntryToExit(Label.Br(UnaryExpr(cond, UnaryExpr.Operator.LOGICAL_COMPLEMENT)))
            }
            return g
        }

        override fun visit(expressionStmt: ExpressionStmt, arg: Void?): IntraGraph {
            debug(" ".repeat(depth) + ".Find expr " + expressionStmt)
            val g = IntraGraph()
            g.addEdgeFromEntry(expressionStmt)
            g.addEdgeToExit(expressionStmt)
            val exc = expressionStmt.expression.accept(exceptionVisitor, null)
            if (exc != null) {
                for (e in exc) {
                    g.addEdgeToExcept(expressionStmt, Label.Raise(e, expressionStmt.expression))
                }
            }
            return g
        }

        override fun visit(throwStmt: ThrowStmt, arg: Void?): IntraGraph {
            debug(" ".repeat(depth) + ".Find expr " + throwStmt)
            val g = IntraGraph()
            val e = throwStmt.expression
            val ty = e.calculateResolvedType()
            g.addEdgeFromEntry(throwStmt)
            g.addEdgeToExcept(throwStmt, Label.Raise(ty, e))
            return g
        }

        override fun visit(tryStmt: TryStmt, arg: Void?): IntraGraph {
            debug(" ".repeat(depth) + ".Find try")
            val g = IntraGraph()
            val bodyG = tryStmt.tryBlock.accept(this, null)
            g.union(bodyG, mergeExcept = false)
            g.addEdgeId(g.entryId, bodyG.entryId)
            if (tryStmt.finallyBlock.isPresent) {
                val finallyG = tryStmt.finallyBlock.get().accept(this, null)
                val finallyGEx = finallyG.clone()
                g.union(finallyG, mergeExcept = false)
                g.union(finallyGEx, mergeExcept = false)
                g.addEdgeId(bodyG.exitId, finallyG.entryId)
                g.addEdgeId(finallyG.exitId, g.exitId)
                for (catch in tryStmt.catchClauses) {
                    val catchG = catch.body.accept(this, null)
                    val ty = catch.parameter.type.resolve()
                    val name = catch.parameter.name
                    g.union(catchG, mergeExcept = false)
                    g.addEdgeId(bodyG.exceptId, catchG.entryId, Label.Catch(name, ty))
                    g.addEdgeId(catchG.exceptId, finallyGEx.entryId)
                    g.addEdgeId(finallyGEx.exitId, g.exceptId)
                    g.addEdgeId(catchG.exitId, finallyG.entryId)
                }
            } else {
                g.addEdgeId(bodyG.exitId, g.exitId)
                for (catch in tryStmt.catchClauses) {
                    val catchG = catch.body.accept(this, null)
                    val ty = catch.parameter.type.resolve()
                    val name = catch.parameter.name
                    g.union(catchG, mergeExcept = false)
                    g.addEdgeId(bodyG.exceptId, catchG.entryId, Label.Catch(name, ty))
                    g.addEdgeId(catchG.exceptId, g.exceptId)
                    g.addEdgeId(catchG.exitId, g.exitId)
                }
            }
            return g
        }

        override fun visit(emptyStmt: EmptyStmt, arg: Void?): IntraGraph {
            val g = IntraGraph()
            g.addEdgeFromEntryToExit()
            return g
        }

        override fun visit(returnStmt: ReturnStmt, arg: Void?): IntraGraph {
            debug(" ".repeat(depth) + ".Find return")
            val g = IntraGraph()
            g.addEdgeFromEntry(returnStmt)
            g.addEdgeToReturn(returnStmt)
            return g
        }
    }

    val topVisitor = object : VoidVisitorAdapter<IntraGraphSet>() {
        override fun visit(decl: MethodDeclaration, gs: IntraGraphSet) {
            val qname = decl.resolve().qualifiedSignature
            println("Analyzing ${qname} hasBody=${decl.body.isPresent}")
            if (decl.body.isPresent) {
                val g = decl.body.get().accept(blockVisitor, null)
                g.graphviz(qname)
                gs.put(qname, g)
            }
        }
    }

    file.accept(topVisitor, intraGraphs)
}

/**
 * Class Hierarchy Analysis
 */
fun chaResolve(expr: MethodCallExpr, classGraph: ScanResult): List<String> {
    val decl = expr.resolve()
    val result = mutableListOf<String>()
    if (decl.isStatic || expr.scope.isPresent && expr.scope.get().isSuperExpr) {
        // Java Symbol Solver can handle `super`
        return listOf(decl.qualifiedSignature)
    }

    // Virtual calls
    val staticType = if (decl.packageName == "") {
        decl.className
    } else {
        decl.packageName + "." + decl.className
    }

    // Treat SQL-related packages specially
    if (setOf("java.sql", "javax.sql").contains(decl.packageName)) {
        return listOf(decl.qualifiedSignature)
    }

    val typeInfo = classGraph.getClassInfo(staticType)
        ?: // There's no more information
        return listOf(decl.qualifiedSignature)

    // A default method has code
    if (typeInfo.isInterface && decl.isDefaultMethod) {
        result.add(decl.qualifiedSignature)
    } else if (typeInfo.isArrayClass || typeInfo.isExternalClass || typeInfo.isInnerClass
        || typeInfo.isOuterClass || typeInfo.isStandardClass || typeInfo.isAnonymousInnerClass
    ) {
        result.add(decl.qualifiedSignature)
    }

    // Add all implementations / subclasses
    fun getSig(m: MethodInfo): String {
        val sb = StringBuilder()
        sb.append(m.name)
        sb.append("(")
        if (m.parameterInfo.isNotEmpty()) {
            sb.append(m.parameterInfo[0].typeDescriptor)
            for (i in 1 until m.parameterInfo.size) {
                sb.append(", ")
                sb.append(m.parameterInfo[i].typeDescriptor)
            }
        }
        sb.append(")")
        return sb.toString()
    }

    fun getQualifiedSig(m: MethodInfo): String {
        val sb = StringBuilder()
        if (m.classInfo.packageName != "") {
            sb.append(m.classInfo.packageName)
            sb.append(".")
        }
        sb.append(m.className)
        sb.append(".")
        sb.append(getSig(m))
        return sb.toString()
    }

    val downcast = if (typeInfo.isInterface) {
        typeInfo.classesImplementing
    } else {
        typeInfo.subclasses
    }
//    println("virtual call: $expr, which has sig ${decl.signature}")
    for (klass in downcast) {
        for (m in klass.methodInfo[decl.name]) {
            if (getSig(m) == decl.signature) {
                result.add(getQualifiedSig(m))
//                println("  Possibly ${getQualifiedSig(m)}")
            }
        }
    }
    return result.toList()
}

/**
 * Build the inter-graph for the project.
 */
fun buildInterGraph(file: CompilationUnit): InterGraph {
    val fileVisitor = object : VoidVisitorAdapter<InterGraph>() {
        var currentSig: String? = null
        val classGraph = ClassGraph().enableAllInfo().scan()

        override fun visit(decl: ClassOrInterfaceDeclaration, g: InterGraph) {
            currentSig = decl.resolve().qualifiedName
            super.visit(decl, g)
        }

        override fun visit(decl: ConstructorDeclaration, g: InterGraph) {
            currentSig = decl.resolve().qualifiedSignature
            super.visit(decl, g)
        }

        override fun visit(decl: MethodDeclaration, g: InterGraph) {
            currentSig = decl.resolve().qualifiedSignature
            super.visit(decl, g)
        }

        override fun visit(expr: ObjectCreationExpr, g: InterGraph) {
            g.add(currentSig!!, expr.resolve().qualifiedSignature)
            super.visit(expr, g)
        }

        override fun visit(expr: MethodCallExpr, g: InterGraph) {
            super.visit(expr, g) // Arguments and/or Scope
            for (sig in chaResolve(expr, classGraph)) {
                g.add(currentSig!!, sig)
            }
        }
    }
    val g = InterGraph()
    file.accept(fileVisitor, g)
    return g
}

/**
 * Transform for loops into while loops.
 */
fun transformLoops(file: CompilationUnit) {
    file.accept(object : VoidVisitorAdapter<Void>() {
        override fun visit(forStmt: ForStmt, arg: Void?) {
            // Build while loop
            val whileStmt = WhileStmt()
            val whileBody = BlockStmt()
            whileStmt.body = whileBody
            whileStmt.condition = forStmt.compare.orElse(BooleanLiteralExpr(true)).clone()
            whileBody.addStatement(forStmt.body)
            forStmt.update.forEach { whileBody.addStatement(it) }
            // Wrap
            val blockStmt = BlockStmt()
            forStmt.initialization.forEach { blockStmt.addStatement(it) }
            blockStmt.addStatement(whileStmt)
            // Replace
            forStmt.replace(blockStmt)
        }
    }, null)
}
