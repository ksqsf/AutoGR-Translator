
class Effect(val sourcePath: IntraPath) {

    fun tryToAnalyze(analyzer: Analyzer) {
        val interpreter = Interpreter(sourcePath.intragraph, analyzer.schema)

        // sourcePath is a known effectual path.
        // 1. Build fields in the static class
        interpreter.runClass(sourcePath.intragraph.classDef)

        // 2. Eval this path, and build an easier representation of its effect.
        interpreter.run(sourcePath)

    }
}
