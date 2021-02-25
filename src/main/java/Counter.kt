import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.visitor.GenericVisitorAdapter

fun main() {
    //val projectRoot = "/Users/kaima/src/translator/src/main/resources/"
//    val projectRoot = "/Users/kaima/src/RedBlue_consistency/src/applications/RUBiStxmud/Servlets/edu"
    val projectRoot = "/Users/kaima/src/HealthPlus/src/main/java/"
    val analyzer = Analyzer(projectRoot)

    // analyzer.graphviz()

//    var explicitRollbackCnt = countExplicitRollback(analyzer)
    val multipleCommits = countMultipleCommits(analyzer)
    val effectInCatch = countEffectInCatch(analyzer)
//    var localStateDepCnt = countLocalStateDep(analyzer)

    println("====================")
    println("     STATISTICS")
    println("====================")
    println("Multiple Commit    ${multipleCommits.size}")
    println("Effect In Catch    ${effectInCatch.size}")
}

// A condition is said to be local-dependent if it contains local states.
fun countLocalStateDep(analyzer: Analyzer): Int {
    TODO("Not yet implemented")
}

fun countEffectInCatch(analyzer: Analyzer): Set<QualifiedName> {
    println("====================")
    println("Counting effects behind catch")
    println("====================")
    val result = mutableSetOf<QualifiedName>()
    for (effect in analyzer.intergraph.effect) {
        val g = analyzer.intragraphs[effect] ?: continue
        if (g.collectEffectPaths().values.any { it.any { it.path.any { it.label is Label.Catch } } }) {
            println(". Effect $effect...")
            result.add(effect)
        }
    }
    return result
}

// If a committing path contains two or more commit calls.
fun countMultipleCommits(analyzer: Analyzer): Set<QualifiedName> {
    println("====================")
    println("Counting multiple commits")
    println("====================")

    fun containsCommitId(g: IntraGraph, id: Int): Boolean {
        val s = g.idNode[id]?: return false
        return containsCommit(s.statement)
    }

    val result = mutableSetOf<QualifiedName>()
    for (effect in analyzer.intergraph.effect) {
        val g = analyzer.intragraphs[effect] ?: continue
        // println("$effect: " + g.collectEffectPaths())
        if (g.collectEffectPaths().values.any { it.any { it.path.any { containsCommitId(g, it.next) } } }) {
            println(". Effect $effect")
            result.add(effect)
        }
    }
    return result
}

// Count effects with paths like
//    ... -> update -> ... -> query -> if (xxx) -> commit
// That is, make sure the final state is decided by the update
//fun countStaged(analyzer: Analyzer): Int {
//    println("====================")
//    println("Counting staged updates")
//    println("====================")
//
//    for (effect in analyzer.intergraph.effect) {
//        fun containsUpdateId(g: IntraGraph, id: Int): Boolean {
//            val s = g.idNode[id] ?: return false
//            return containsUpdate(s)
//        }
//
//        val g = analyzer.intragraphs[effect] ?: continue
//        val p = g.collectEffectPaths()
//        for ((commitId, paths) in p) {
//            var ok = true
//            for (path in paths) {
//                val rpath = path.reversed()
//                for (edge in rpath) {
//                    if (containsUpdateId(g, edge.next)) {
//                        break
//                    }
//                    // FIXME: make sure Br is db-dependent
//                    if (edge.label is Label.Br) {
//
//                    }
//                }
//                if (!ok)
//                    break
//            }
//        }
//    }
//}
