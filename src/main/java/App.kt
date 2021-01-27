import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.BooleanLiteralExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.UnaryExpr
import com.github.javaparser.ast.stmt.*
import com.github.javaparser.ast.visitor.GenericVisitorAdapter
import com.github.javaparser.ast.visitor.GenericVisitorWithDefaults
import com.github.javaparser.ast.visitor.VoidVisitorAdapter
import com.github.javaparser.resolution.types.ResolvedType
import com.github.javaparser.symbolsolver.JavaSymbolSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ClassLoaderTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.MemoryTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver
import com.github.javaparser.utils.SourceRoot
import net.sf.jsqlparser.statement.Block
import java.nio.file.Path

typealias QualifiedName = String
var effectfulMethods: Set<QualifiedName> = emptySet()

fun main() {
//    val projectRoot = "/Users/kaima/src/RedBlue_consistency/src/applications/RUBiStxmud/Servlets/edu"
    val projectRoot = "/Users/kaima/src/translator/src/main/resources/"
    val typeSolver = CombinedTypeSolver(MemoryTypeSolver(), ReflectionTypeSolver(), ClassLoaderTypeSolver(ClassLoader.getSystemClassLoader()))
    val symbolSolver = JavaSymbolSolver(typeSolver)

    val config = ParserConfiguration()
    config.setSymbolResolver(symbolSolver)

    val project = SourceRoot(Path.of(projectRoot))
    project.parserConfiguration = config
    project.tryToParse()
    val projectFiles = project.compilationUnits

    // Step 0. Desugar various forms of loops to simplify analysis
    for (file in projectFiles) {
        transformLoops(file)
        print(file)
    }

    // Step 1. Construct intraprocedural flow graph for each method
    val intraGraphs: MutableMap<QualifiedName, IntraGraph> = mutableMapOf()
    for (file in projectFiles) {
        // println(file.storage.get().fileName)
        buildIntraGraph(file, intraGraphs)
    }

    // Step 2. Build interprocedural call graph for the project
    val interGraphs: MutableMap<QualifiedName, InterGraph> = mutableMapOf()
    for (file in projectFiles) {
        buildInterGraph(file, interGraphs)
    }

    println("Finished!!!")
}

typealias IntraGraphSet = MutableMap<QualifiedName, IntraGraph>
typealias InterGraphSet = MutableMap<QualifiedName, InterGraph>

/**
 * Build intra-graph for a compilation unit.
 */
fun buildIntraGraph(file: CompilationUnit, intraGraphs: IntraGraphSet) {

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
            val argExcs = args.map { it.accept(this, arg) }.filterNotNull().flatten()
            val call = call.resolve()
            val res = (call.specifiedExceptions + argExcs).filterNotNull()
            if (res.isNotEmpty())
                return res
            return null
        }
    }

    val blockVisitor = object : GenericVisitorAdapter<IntraGraph, Void>() {
        var depth = 0

        override fun visit(blockStmt: BlockStmt, arg: Void?): IntraGraph {
            println(" ".repeat(depth) + ".Find block"); depth+=1
            val g = IntraGraph()
            if (blockStmt.childNodes.size == 0) {
                g.addEdgeFromEntryToExit()
                return g
            }
            val subG = blockStmt.statements.map { it.accept(this, null) }
            g.sequence(subG)
            depth-=1
            return g
        }

        override fun visit(whileStmt: WhileStmt, arg: Void?): IntraGraph {
            val g = IntraGraph()
            val bodyG = whileStmt.body.accept(this, arg)
            g.union(bodyG, mergeBreak = false, mergeContinue = false)
            val cond = whileStmt.condition
            if (cond == BooleanLiteralExpr(true)) {
                g.addEdgeId(g.getEntryId(), bodyG.getEntryId())
            } else {
                g.addEdgeId(g.getEntryId(), bodyG.getEntryId(), Label.Br(cond))
                g.addEdgeFromEntryToExit(Label.Br(UnaryExpr(cond, UnaryExpr.Operator.LOGICAL_COMPLEMENT)))
            }
            g.addEdgeId(bodyG.getExitId(), g.getEntryId())
            g.addEdgeId(bodyG.getBreakId(), g.getExitId())
            g.addEdgeId(bodyG.getContinueId(), g.getEntryId())
            return g
        }

        override fun visit(doStmt: DoStmt, arg: Void?): IntraGraph {
            val g = IntraGraph()
            val bodyG = doStmt.body.accept(this, arg)
            g.union(bodyG, mergeBreak = false, mergeContinue = false)
            val cond = doStmt.condition
            g.addEdgeId(g.getEntryId(), bodyG.getEntryId())
            if (cond == BooleanLiteralExpr(true)) {
                g.addEdgeId(bodyG.getExitId(), g.getEntryId())
            } else {
                g.addEdgeId(bodyG.getExitId(), g.getEntryId(), Label.Br(cond))
                g.addEdgeId(bodyG.getExitId(), g.getExitId(), Label.Br(UnaryExpr(cond, UnaryExpr.Operator.LOGICAL_COMPLEMENT)))
            }
            g.addEdgeId(bodyG.getContinueId(), bodyG.getExitId())
            g.addEdgeId(bodyG.getBreakId(), g.getExitId())
            return g
        }

        override fun visit(breakStmt: BreakStmt, arg: Void?): IntraGraph {
            val g = IntraGraph()
            g.addEdgeId(g.getEntryId(), g.getBreakId())
            return g
        }

        override fun visit(continueStmt: ContinueStmt, arg: Void?): IntraGraph {
            val g = IntraGraph()
            g.addEdgeId(g.getEntryId(), g.getContinueId())
            return g
        }

        override fun visit(ifStmt: IfStmt, arg: Void?): IntraGraph {
            val g = IntraGraph()
            val cond = ifStmt.condition
            println(" ".repeat(depth) + ".Find if " + cond)

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
            println(" ".repeat(depth) + ".Find expr " + expressionStmt)
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
            println(" ".repeat(depth) + ".Find expr " + throwStmt)
            val g = IntraGraph()
            val e = throwStmt.expression
            val ty = e.calculateResolvedType()
            g.addEdgeFromEntry(throwStmt)
            g.addEdgeToExcept(throwStmt, Label.Raise(ty, e))
            return g
        }

        override fun visit(tryStmt: TryStmt, arg: Void?): IntraGraph {
            println(" ".repeat(depth) + ".Find try")
            val g = IntraGraph()
            val bodyG = tryStmt.tryBlock.accept(this, null)
            g.union(bodyG, mergeExcept = false)
            g.addEdgeId(g.getEntryId(), bodyG.getEntryId())
            if (tryStmt.finallyBlock.isPresent) {
                val finallyG = tryStmt.finallyBlock.get().accept(this, null)
                val finallyGEx = finallyG.clone()
                g.union(finallyG, mergeExcept = false)
                g.union(finallyGEx, mergeExcept = false)
                g.addEdgeId(bodyG.getExitId(), finallyG.getEntryId())
                g.addEdgeId(finallyG.getExitId(), g.getExitId())
                for (catch in tryStmt.catchClauses) {
                    val catchG = catch.body.accept(this, null)
                    val ty = catch.parameter.type.resolve()
                    val name = catch.parameter.name
                    g.union(catchG, mergeExcept = false)
                    g.addEdgeId(bodyG.getExceptId(), catchG.getEntryId(), Label.Catch(name, ty))
                    g.addEdgeId(catchG.getExceptId(), finallyGEx.getEntryId())
                    g.addEdgeId(finallyGEx.getExitId(), g.getExceptId())
                    g.addEdgeId(catchG.getExitId(), finallyG.getEntryId())
                }
            } else {
                g.addEdgeId(bodyG.getExitId(), g.getExitId())
                for (catch in tryStmt.catchClauses) {
                    val catchG = catch.body.accept(this, null)
                    val ty = catch.parameter.type.resolve()
                    val name = catch.parameter.name
                    g.union(catchG, mergeExcept = false)
                    g.addEdgeId(bodyG.getExceptId(), catchG.getEntryId(), Label.Catch(name, ty))
                    g.addEdgeId(catchG.getExceptId(), g.getExceptId())
                    g.addEdgeId(catchG.getExitId(), g.getExitId())
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
            println(" ".repeat(depth) + ".Find return")
            val g = IntraGraph()
            g.addEdgeFromEntry(returnStmt)
            g.addEdgeToReturn(returnStmt)
            return g
        }
    }

    val topVisitor = object : VoidVisitorAdapter<IntraGraphSet>() {
        override fun visit(decl: MethodDeclaration, gs: IntraGraphSet) {
            val qname = decl.resolve().qualifiedName
            println("Analyzing ${qname} hasBody=${decl.body.isPresent}")
            if (decl.body.isPresent){
                val g = decl.body.get().accept(blockVisitor, null)
                g.graphviz(qname.filter { it.isLetterOrDigit() }.toUpperCase())
                gs.put(qname, g)
            }
        }
    }

    file.accept(topVisitor, intraGraphs)
}

fun buildInterGraph(file: CompilationUnit, interGraphs: InterGraphSet) {
    val fileVisitor = object : VoidVisitorAdapter<InterGraphSet>() {

    }
}

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

fun flattenLoops(file: CompilationUnit) {
    // unrollNum > 1 only when you assume condition expressions have no side effects
//    val unrollNum = 1

    file.accept(object : VoidVisitorAdapter<Void>() {

        override fun visit(wstmt: WhileStmt, arg: Void?) {
            val istmt = IfStmt()
            istmt.setCondition(wstmt.condition.clone())
            istmt.setThenStmt(wstmt.body.clone())
            val doStmt = DoStmt()
            doStmt.condition = wstmt.condition.clone()
            doStmt.body = istmt
            wstmt.replace(doStmt)
        }

        override fun visit(stmt: ForStmt, arg: Void?) {
            // for(inits; compare; updates) { body }
            // => { inits; [ if(compare) { body; updates; } ]* }
            val block = BlockStmt()
            for (init in stmt.initialization) {
                block.addStatement(init.clone())
            }
//            for (i in 0..unrollNum) {
            val thenStmt = BlockStmt()
            thenStmt.addStatement(stmt.body.clone())
            for (upd in stmt.update) {
                thenStmt.addStatement(upd.clone())
            }
            val doStmt = DoStmt()
            val br = IfStmt()
            if (stmt.compare.isPresent) {
                br.condition = stmt.compare.get().clone()
                doStmt.condition = stmt.compare.get().clone()
            } else {
                System.err.println("WARNING: infinite loop ignored")
                br.condition = BooleanLiteralExpr(true)
                doStmt.condition = BooleanLiteralExpr(true)
            }
            br.thenStmt = thenStmt
            block.addStatement(br)
//            }
            doStmt.body = block
            stmt.replace(doStmt)
        }

        override fun visit(stmt: DoStmt, arg: Void?) {
//            val block = BlockStmt()
//            block.addStatement(stmt.body.clone());
//            block.addStatement(ExpressionStmt(stmt.condition.clone()));
//
//            val doStmt = DoStmt()
//            doStmt.condition = BooleanLiteralExpr(false)
//            doStmt.body = block
//            stmt.replace(doStmt)
        }
    }, null)
//    println("After replacement ${file.storage.get().fileName}: $file")
}
