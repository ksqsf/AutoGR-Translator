import java.io.File

fun main() {
    val projectRoot = "/Users/kaima/src/oltpbenchmark/src/com/oltpbenchmark/benchmarks/healthplus/procedures"
    val ddl = "/Users/kaima/src/oltpbenchmark/src/com/oltpbenchmark/benchmarks/healthplus/ddls/healthplus-ddl.sql"

    val analyzer = Analyzer(projectRoot, buildInterGraph = true)
    // analyzer.graphviz()
    analyzer.loadSchema(ddl)

    val effectMap = mutableMapOf<QualifiedName, MutableSet<Effect>>()

    Timer.start("effect")
    for (effectMethodSig in analyzer.intergraph.effect) {
//    for (effectMethodSig in setOf("com.oltpbenchmark.benchmarks.healthplus.procedures.LabAssistant_updateAccountInfo.run(java.sql.Connection, java.lang.String)")) {
        println("*** $effectMethodSig ***")
        val g = analyzer.intragraphs[effectMethodSig] ?: continue
        println("This function contains ${g.loopCnt} loops")
        val pathMap = g.collectEffectPaths()

        // Remove final node that is contained in another path
        // This will remove intermediate paths
        val predMap = mutableMapOf<IntraPath, MutableSet<IntraPath>>()
        for ((finalId, pathSet) in pathMap) {
            for ((_, pathSet2) in pathMap) {
                for (path2 in pathSet2) {
                    if (path2.path.any { it.next == finalId }) {
                        // finalId is contained in path2.
                        // all paths leading to finalId have 'next' path2.
                        predMap.putIfAbsent(path2, mutableSetOf())
                        predMap[path2]!! += pathSet
                    }
                }
            }
        }

        val pathToEffect = mutableMapOf<IntraPath, Effect>()
        for ((_, pathSet) in pathMap) {
            for (path in pathSet) {
                effectMap.putIfAbsent(effectMethodSig, mutableSetOf())
                val effect = Effect(analyzer, path)
                effect.tryToAnalyze()
                effectMap[effectMethodSig]!!.add(effect)
                pathToEffect[path] = effect
            }
        }
        for ((path, preds) in predMap) {
            for (pred in preds) {
                pathToEffect[pred]!!.addNext(pathToEffect[path]!!)
            }
        }
    }
    Timer.end("effect")

    val output = generateRigi("HealthPlus", analyzer, effectMap)
    File("/tmp/output.py").writeText(output)

    Timer.report()
}
