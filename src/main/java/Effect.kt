import net.sf.jsqlparser.expression.Expression

// Each IntraPath corresponds to an "effect".
//
// An effect is represented as (argv, cond, side effect).
// Argv is a list of (name, type). Read-only DB queries are considered as argv too.
// Cond is a list of boolean expressions.
// Side effect is a SQL statement that updates the DB state.
class Effect(analyzer: Analyzer, val sourcePath: IntraPath) {

    val interpreter = Interpreter(sourcePath.intragraph, analyzer.schema, this)
    val argv = mutableMapOf<String, Type>()
    val pathCondition = mutableListOf<AbstractValue>()

    var shadows = mutableListOf<Shadow>()

    fun addCondition(cond: AbstractValue) {
        pathCondition.add(cond)
    }

    fun addArgv(name: String, type: Type) {
        argv.putIfAbsent(name, type)
    }

    fun addShadow(shadow: Shadow) {
        shadows.add(shadow)
    }

    fun tryToAnalyze() {

        // sourcePath is a known effectual path.
        // 1. Build fields in the static class
        interpreter.runClass(sourcePath.intragraph.classDef)

        // 2. Eval this path, and build an easier representation of its effect.
        interpreter.run(sourcePath)

    }
}

sealed class Shadow {
    data class Delete(val table: Table, val where: Expression)
    data class Insert(val table: Table, val values: Map<Column, AbstractValue?>)
    data class Update(val table: Table, val where: Expression, val values: Map<Column, AbstractValue?>)
}
