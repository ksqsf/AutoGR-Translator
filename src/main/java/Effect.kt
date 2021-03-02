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
    val next = mutableListOf<Effect>()

    var shadows = mutableListOf<Shadow>()

    fun addCondition(cond: AbstractValue) {
        pathCondition.add(cond)
    }

    fun addArgv(name: String, type: Type) {
        argv.putIfAbsent(name, type)
    }

    fun addShadow(shadow: Shadow) {
        println("[DBG] add shadow $shadow")
        shadows.add(shadow)
    }

    fun addNext(effect: Effect) {
        next.add(effect)
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
    data class Delete(val table: Table, val locators: Map<Column, AbstractValue>): Shadow() {
        override fun toString(): String {
            return "(DELETE $table $locators)"
        }
    }
    data class Insert(val table: Table, val values: Map<Column, AbstractValue?>): Shadow() {
        override fun toString(): String {
            return "(INSERT $table $values)"
        }
    }
    data class Update(val table: Table, val locators: Map<Column, AbstractValue>, val values: Map<Column, AbstractValue?>): Shadow() {
        override fun toString(): String {
            return "(UPDATE $table $values $locators)"
        }
    }
}
