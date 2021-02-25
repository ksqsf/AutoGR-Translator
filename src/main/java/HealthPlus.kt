fun main() {
    val projectRoot = "/Users/kaima/src/oltpbenchmark/src/com/oltpbenchmark/benchmarks/healthplus/procedures"
    val ddl = "/Users/kaima/src/oltpbenchmark/src/com/oltpbenchmark/benchmarks/healthplus/ddls/healthplus-ddl.sql"

    val analyzer = Analyzer(projectRoot)
    // analyzer.graphviz()
    analyzer.loadSchema(ddl)

    for (effectMethodSig in analyzer.intergraph.effect) {
        println("*** $effectMethodSig ***")
        val g = analyzer.intragraphs[effectMethodSig] ?: continue
        val pathMap = g.collectEffectPaths()
        for ((commitId, pathSet) in pathMap) {
            for (path in pathSet) {
                val effect = Effect(path)
                effect.tryToAnalyze(analyzer)
            }
        }
    }

    // println(generateRigi("HealthPlus", analyzer))
}
