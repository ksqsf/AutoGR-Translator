fun main() {
    val projectRoot = "/Users/kaima/src/oltpbenchmark/src/com/oltpbenchmark/benchmarks/healthplus/procedures"
    val ddl = "/Users/kaima/src/oltpbenchmark/src/com/oltpbenchmark/benchmarks/healthplus/ddls/healthplus-ddl.sql"

    val analyzer = Analyzer(projectRoot)
    // analyzer.graphviz()
    analyzer.loadSchema(ddl)

    for (effect in analyzer.intergraph.effect) {
        println("*** Effect $effect ***")
        val g = analyzer.intragraphs[effect] ?: continue
        val pathMap = g.collectEffectPaths()
        for ((commitId, pathSet) in pathMap) {
            for (path in pathSet) {
                println("+ Starting from the entry to $commitId:")
                for (edge in path) {
                    println("  $edge")
                }
            }
        }
    }

    //println(generateRigi("HealthPlus", analyzer))
}
