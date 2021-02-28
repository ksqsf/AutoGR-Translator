import java.io.File

fun main() {
    val projectRoot = "/Users/kaima/src/oltpbenchmark/src/com/oltpbenchmark/benchmarks/healthplus/procedures"
    val ddl = "/Users/kaima/src/oltpbenchmark/src/com/oltpbenchmark/benchmarks/healthplus/ddls/healthplus-ddl.sql"

    val analyzer = Analyzer(projectRoot, buildInterGraph = true)
    // analyzer.graphviz()
    analyzer.loadSchema(ddl)

    val effectMap = mutableMapOf<QualifiedName, MutableSet<Effect>>()

    for (effectMethodSig in analyzer.intergraph.effect) {
//    for (effectMethodSig in setOf("com.oltpbenchmark.benchmarks.healthplus.procedures.LabAssistant_updateAccountInfo.run(java.sql.Connection, java.lang.String)")) {
        println("*** $effectMethodSig ***")
        val g = analyzer.intragraphs[effectMethodSig] ?: continue
        val pathMap = g.collectEffectPaths()
        for ((commitId, pathSet) in pathMap) {
            for (path in pathSet) {
                effectMap.putIfAbsent(effectMethodSig, mutableSetOf())
                val effect = Effect(analyzer, path)
                effect.tryToAnalyze()
                effectMap[effectMethodSig]!!.add(effect)
            }
        }
    }

    val output = generateRigi("HealthPlus", analyzer, effectMap)
    File("/tmp/output.py").writeText(output)
}
