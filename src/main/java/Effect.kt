// Each IntraPath corresponds to an "effect".
//
// An effect is represented as (argv, cond, side effect).
// Argv is a list of (name, type). Read-only DB queries are considered as argv too.
// Cond is a list of boolean expressions.
// Side effect is a SQL statement that updates the DB state.
class Effect(val analyzer: Analyzer, val sourcePath: IntraPath) {

    private val interpreter = Interpreter(sourcePath.intragraph, analyzer.schema, this)
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
        // Workaround: Config-specified types takes precedence. This is needed when inferred types are wrong, or
        // there's strange type conversion we don't yet support.
        val curMethodName = sourcePath.intragraph.qualifiedName
        val patchedTypes = mutableMapOf<String, String>()
        for ((method, args) in analyzer.cfg[AnalyzerSpec.Patches.argTypes]) {
            if (curMethodName.startsWith(method, ignoreCase = true)) {
                patchedTypes += args
            }
        }

        for (arg in sourcePath.intragraph.methodDecl.parameters) {
            val name = arg.name.asString()
            val tyStr = patchedTypes[name] ?: arg.typeAsString.toLowerCase()
            val ty = if (tyStr.contains("string")) {
                Type.String
            } else if (tyStr.contains("int") || tyStr.contains("long") || tyStr.contains("short")) {
                Type.Int
            } else if (tyStr.contains("float") || tyStr.contains("double")) {
                Type.Real
            } else {
                println("[WARN] param $name of unknown type $tyStr")
                continue
            }
            val av = AbstractValue.Free(null, arg.name.asString(), ty)
            interpreter.putVariable(name, av)
            addArgv(name, ty)
            println("-free ${arg.name.asString()} $ty")
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

        // 3. Eval this path, and build an easier representation of its effect.
        interpreter.run(sourcePath)

    }

    fun add(rhs: Effect) {
        for ((name, type) in rhs.argv) {
            addArgv(name, type)
        }
        for (c in rhs.pathCondition) {
            addCondition(c)
        }
        for (a in rhs.atoms) {
            addAtom(a)
        }
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
