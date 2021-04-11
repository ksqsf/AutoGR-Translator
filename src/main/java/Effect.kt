// Each IntraPath corresponds to an "effect".
//
// An effect is represented as (argv, cond, side effect).
// Argv is a list of (name, type). Read-only DB queries are considered as argv too.
// Cond is a list of boolean expressions.
// Side effect is a SQL statement that updates the DB state.
class Effect(val analyzer: Analyzer, val sourcePath: IntraPath) {

    val interpreter = Interpreter(sourcePath.intragraph, analyzer.schema, this)
    val argv = mutableMapOf<String, Type>()
    val pathCondition = mutableListOf<AbstractValue>()
    val next = mutableListOf<Effect>()

    var atoms = mutableListOf<Atom>()

    // Unique ID axioms
    val uniqueArgv = mutableSetOf<String>()

    // Last-Writer-Win axioms
    val updatedTables = mutableSetOf<Table>()

    init {
        // Each operation is denoted with a timestamp.
        if (analyzer.enableCommute) {
            addArgv("__ts", Type.Int)
        }
    }

    fun addCondition(cond: AbstractValue) {
        pathCondition.add(cond)
    }

    fun addArgv(name: String, type: Type) {
        argv.putIfAbsent(name, type)

        if (analyzer.enableCommute) {
            if (name.startsWith("new")) {
                uniqueArgv.add(name)
            }
        }
    }

    fun addArgv(column: Column) {
        argv.putIfAbsent("${column.table.name}_${column.name}", column.type)
    }

    fun addUpdatedTable(table: Table) {
        updatedTables.add(table)
    }

    fun addAtom(atom: Atom) {
        println("[DBG] add atom $atom")
        atoms.add(atom)
    }

    fun addNext(effect: Effect) {
        next.add(effect)
    }

    private fun introduceParameters() {
        for (arg in sourcePath.intragraph.methodDecl.parameters) {
            val av = AbstractValue.Free(null, null, arg.name.asString())
            interpreter.putVariable(arg.name.asString(), av)
            println("-free ${arg.name.asString()}")
        }
    }

    /**
     * Initialize this effect by running the interpreter on the corresponding intrapath.
     */
    fun tryToAnalyze() {

        // sourcePath is a known effectual path.
        // 1. Build fields in the static class
        interpreter.runClass(sourcePath.intragraph.classDef)

        // 2. All parameters are declared free.
        introduceParameters()
        println("after introduce ${interpreter.lookup("userid")}")

        // 3. Eval this path, and build an easier representation of its effect.
        interpreter.run(sourcePath)

    }
}

typealias Locators = Map<Column, AbstractValue>

// Atom is the unit of DB changes. An effect may contain many atoms.
sealed class Atom(val table: Table) {
    class Delete(table: Table, val locators: Locators): Atom(table) {
        override fun toString(): String {
            return "(DELETE $table $locators)"
        }
    }
    class Insert(table: Table, var values: Map<Column, AbstractValue>): Atom(table) {
        override fun toString(): String {
            return "(INSERT $table $values)"
        }
    }
    class Update(table: Table, val locators: Locators, val values: Map<Column, AbstractValue>): Atom(table) {
        override fun toString(): String {
            return "(UPDATE $table $values $locators)"
        }
    }
}
