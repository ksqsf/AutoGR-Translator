fun main() {
    val projectRoot = "/Users/kaima/src/oltpbenchmark/src/com/oltpbenchmark/benchmarks/healthplus/procedures"
    val ddl = "/Users/kaima/src/oltpbenchmark/src/com/oltpbenchmark/benchmarks/healthplus/ddls/healthplus-ddl.sql"

    val analyzer = Analyzer(projectRoot, buildInterGraph = false)
    // analyzer.graphviz()
    analyzer.loadSchema(ddl)

    for (effectMethodSig in setOf("com.oltpbenchmark.benchmarks.healthplus.procedures.LabAssistant_updateAccountInfo.run(java.sql.Connection, java.lang.String)")) {
        println("*** $effectMethodSig ***")
        val g = analyzer.intragraphs[effectMethodSig] ?: continue
        val pathMap = g.collectEffectPaths()
        for ((commitId, pathSet) in pathMap) {
            for (path in pathSet) {
                val effect = Effect(path)
                effect.tryToAnalyze(analyzer)
                println("argv: ${effect.argv}")
            }
        }
    }

    // println(generateRigi("HealthPlus", analyzer))
}
