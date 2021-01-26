import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.BooleanLiteralExpr
import com.github.javaparser.ast.expr.UnaryExpr
import com.github.javaparser.ast.stmt.*
import com.github.javaparser.ast.visitor.GenericVisitorAdapter
import com.github.javaparser.ast.visitor.VoidVisitorAdapter
import com.github.javaparser.symbolsolver.JavaSymbolSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ClassLoaderTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.MemoryTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver
import com.github.javaparser.utils.SourceRoot
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

    // Step 1. Flatten `while`, `for`, `do-while` loops into do { if(cond) {body} } {
    for (file in projectFiles) {
        flattenLoops(file)
        println(file)
    }

    // Step 2. Construct intraprocedural flow graph for each method
    val intraGraphs: MutableMap<QualifiedName, IntraGraph> = mutableMapOf()
    for (file in projectFiles) {
        buildIntraGraph(file, intraGraphs)
    }

    // Step 3. Build interprocedural call graph for the project
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
    val blockVisitor = object : GenericVisitorAdapter<IntraGraph, Void>() {
        var depth = 0

        override fun visit(blockStmt: BlockStmt, arg: Void?): IntraGraph {
            println(".Find block"); depth+=1
            val g = IntraGraph()
            if (blockStmt.childNodes.size == 0) {
                g.addEdgeFromEntryToExit()
                return g
            }
            val subG = blockStmt.statements.map { val g = it.accept(this, null); println("${g==null} ${it::class}");  g }
            g.sequence(subG)
            return g
        }

        override fun visit(doStmt: DoStmt, arg: Void?): IntraGraph {
            val g = IntraGraph()
            val inner = doStmt.body.accept(this, arg)
            g.union(inner, false)
            g.addEdgeId(g.getEntryId(), inner.getEntryId())
            g.addEdgeId(g.getBreakId(), g.getExitId())
            g.addEdgeId(inner.getExitId(), g.getExitId(), Label.Br(UnaryExpr(doStmt.condition.clone(), UnaryExpr.Operator.LOGICAL_COMPLEMENT)))
            return g
        }

        override fun visit(breakStmt: BreakStmt, arg: Void?): IntraGraph {
            val g = IntraGraph()
            g.addEdgeId(g.getEntryId(), g.getBreakId())
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
            return g
        }

        override fun visit(tryStmt: TryStmt, arg: Void?): IntraGraph {
            println(" ".repeat(depth) + ".Find try")
            println("WARNING: ignore ${tryStmt::class.toString()}")
            return tryStmt.tryBlock.accept(this, null)
        }

        override fun visit(emptyStmt: EmptyStmt, arg: Void?): IntraGraph {
            val g = IntraGraph()
            g.addEdgeFromEntryToExit()
            return g
        }

        override fun visit(returnStmt: ReturnStmt, arg: Void?): IntraGraph {
            println(" ".repeat(depth) + ".Find return")
            val g = IntraGraph()
            g.addEdgeId(g.getEntryId(), g.getReturnId())
            return g
        }
    }

    val topVisitor = object : VoidVisitorAdapter<IntraGraphSet>() {
        override fun visit(decl: MethodDeclaration, gs: IntraGraphSet) {
            val qname = decl.resolve().qualifiedName
            println("Analyzing ${qname} hasBody=${decl.body.isPresent}")
            if (decl.body.isPresent){
                val g = decl.body.get().accept(blockVisitor, null)
                g.graphviz()
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
