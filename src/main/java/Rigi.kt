import java.lang.RuntimeException
import java.lang.StringBuilder

fun generateRigi(appName: String, analyzer: Analyzer, effectMap: Map<QualifiedName, Set<Effect>>): String {
    val sb = StringBuilder()
    sb.append("""import sys
sys.path.append("../../")

from z3 import *
from Rigi.axioms import *
from Rigi.checker import *
from Rigi.table import *
from Rigi.tableIns import *
from Rigi.argvbuilder import *

##############################################################################################""")

    val effectMap = effectMap.map {
        val newName = it.key.substringAfter("procedures.").substringBefore("(").replace(".", "_")
        Pair("Op_"+newName+"_"+IDGen.gen(), it.value)
    }.toMap()

    sb.append("\n")
    Timer.start("rigi-db")
    sb.appendLine(generateRigiDBSchema(analyzer.schema, analyzer.enableCommute))
    Timer.end("rigi-db")
    sb.appendLine(generateGenArgv(effectMap, analyzer.enableCommute))

    for ((op, effectSet) in effectMap) {
        val ob = StringBuilder()
        ob.append("""class @OP@():
    def __init__(self):
        self.sops = [${(effectSet.indices).map { "(self.cond$it, self.csop$it, self.sop$it)" }.joinToString(", ")}]
        self.axiom = ${generateOpAxiomFromUniqueArgv(effectSet)}

""")
        println("[RIGI] $op")
        for ((cnt, effect) in effectSet.withIndex()) {
            ob.appendLine(generateCond(effect, cnt, analyzer.enableCommute))
            ob.appendLine(generateCondSop(effect, cnt, analyzer.enableCommute))
            ob.appendLine(generateSop(effect, cnt, analyzer.enableCommute))
        }
        sb.appendLine(ob.toString().replace("@OP@", op))
    }

    val opAxiom = if (analyzer.enableCommute) {
        "AxiomsAnd(BuildArgvAxiom(self.ops))"
    } else {
        "AxiomEmpty()"
    }

    sb.append("""class $appName():
    def __init__(self):
        self.ops = [${effectMap.keys.joinToString(",") { "${it}()" }}]
        self.tables = [${analyzer.schema.getTables().joinToString(", ") { it.name }}]
        self.state = GenState
        self.argv = GenArgv
        self.axiom = $opAxiom

check($appName())
""")

    return sb.toString()
}

fun generateOpAxiomFromUniqueArgv(effectSet: Set<Effect>): String {
    val uniqueArgv = effectSet.map { it.uniqueArgv }.flatten().toSet()
    if (uniqueArgv.isEmpty()) {
        return "AxiomEmpty()"
    } else {
        return "AxiomsAnd(" + uniqueArgv.joinToString(", ") { "AxiomUniqueArgument('@OP@', '$it')" } + ")"
    }
}

fun generateRigiDBSchema(schema: Schema, enableCommute: Boolean): String {
    val sb = StringBuilder()
    for (table in schema.getTables()) {
        sb.append("\n")
        sb.append("$table = Table('$table')\n")
        for (col in table.columns) {
            sb.append("$table.addAttr('${col.name}', Table.${col.type.toRigi()})\n")
        }

        // To enable LWW, each row must have __ts attribute.
        if (enableCommute) {
            sb.append("$table.addAttr('__ts', Table.Type.INT)\n")
        }

        for (pk in table.pkeys) {
            sb.append("$table.setPKey(${pk.joinToString(",") { "'${it.name}'" }})\n")
        }
        sb.append("$table.build()\n")
        sb.append("\n")
    }
    sb.append("\n")
    sb.append("def GenState():\n")
    for (table in schema.getTables()) {
        sb.append("    TABLE_$table = TableInstance($table)\n")
    }
    sb.append("    return {" + schema.getTables().map { "'TABLE_${it}': TABLE_${it}" }.joinToString(",") + "}\n")
    return sb.toString()
}

fun generateGenArgv(effectMap: Map<QualifiedName, Set<Effect>>, enableCommute: Boolean): String {
    val argvMap = mutableMapOf<QualifiedName, MutableMap<String, Type>>()
    for ((eSig, eSet) in effectMap) {
        val eName = eSig.substringBefore('(')
        argvMap.putIfAbsent(eName, mutableMapOf())
        for (eff in eSet) {
            for ((argName, argType) in eff.argv) {
                argvMap[eName]!![argName] = argType
            }
        }
    }
    val sb = StringBuilder()
    sb.append("def GenArgv():\n")
    sb.append("    builder = ArgvBuilder()\n")
    for ((eName, args) in argvMap) {
        sb.append("    builder.NewOp('$eName')\n")
        for ((argName, argType) in args) {
            sb.append("    builder.AddArgv('$argName', ArgvBuilder.${argType.toRigi()})\n")
        }

        // To enable LWW, every operation must receive "now", so that the timestamp can be updated.
        if (enableCommute && !args.keys.contains("now")) {
            sb.append("    builder.AddArgv('now', ArgvBuilder.Type.INT)")
        }

        sb.append("\n")
    }
    sb.append("    return builder.Build()\n")
    return sb.toString()
}

fun generateCond(effect: Effect, suffix: Int, enableCommute: Boolean): String {
    println("    [COND] ${effect.pathCondition}")
    val emitter = Emitter(2)
    fun pathConditionToRigi(pathCondition: MutableList<AbstractValue>): List<String> {
        val result = mutableListOf<String>()
        for (cond in pathCondition) {
            when (cond) {
                is AbstractValue.DbNotNil -> {
                    result.add(cond.toRigi(emitter))
                }
                is AbstractValue.Binary -> {
                    result.add(cond.toRigi(emitter))
                }
                else -> {
                    println("[ERR] unknown cond $cond")
                }
            }
        }
        if (result.isEmpty()) {
            result.add("True")
        }
        return result
    }

    val result = mutableListOf<String>()
    result += pathConditionToRigi(effect.pathCondition)
    for (next in effect.next) {
        for (cond in pathConditionToRigi(next.pathCondition)) {
            result.add("Not($cond)")
        }
    }
    val sb = StringBuilder()
    sb.append("    def cond$suffix(self, state, argv):\n")
    sb.append(loadArgv(effect, loadNext = true))
    sb.append(emitter.toString())
    if (result.size == 0) {
        sb.append("        return True\n")
    } else if (result.size == 1) {
        sb.append("        return " + result[0] + "\n")
    } else {
        sb.append("        return And(${result.joinToString(",")})\n")
    }
    return sb.toString()
}

fun loadArgv(effect: Effect, loadNext: Boolean = false): String {
    val sb = StringBuilder()
    for ((argName, _) in effect.argv) {
        sb.append("        $argName = argv['@OP@']['$argName']\n")
    }
    if (loadNext) {
        for (next in effect.next) {
            for ((argName, _) in next.argv) {
                sb.append("        $argName = argv['@OP@']['$argName']\n")
            }
        }
    }
    return sb.toString()
}

fun generateCondSop(effect: Effect, suffix: Int, enableCommute: Boolean): String {
    if (!enableCommute)  {
        return """    def csop$suffix(self, state, argv):
        return True
"""
    } else {

        val sb = StringBuilder()
        val emitter = Emitter(2)
        sb.appendLine("    def csop$suffix(self, state, argv):")
        sb.appendLine("        now = argv['@OP@']['now']")
        sb.append(loadArgv(effect))

        val timestamps = mutableListOf<String>()

        for (sop in effect.atoms) {
            when (sop) {
                is Atom.Update -> {
                    val table = sop.table
                    val locatorStr = locatorsToRigi(sop.locators, emitter)
                    sb.appendLine("        ${table.name}__ts = state['TABLE_${table.name}'].get($locatorStr, '__ts')")
                    timestamps.add("${table.name}__ts")
                }
                is Atom.Insert -> {
                    sb.append(loadInsertValues(sop, emitter))
                    val table = sop.table
                    val validColumns = sop.values.filter {
                        it.value !is AbstractValue.Null
                    }.map { it.key }
                    val firstPKey = table.pkeys[0].intersect(validColumns)
                    val locator = validColumns.filter { firstPKey.contains(it) }.joinToString(",") { "'${it.name}': ${it.qualifiedName}" }
                    sb.appendLine("        ${table.name}__ts = state['TABLE_${table.name}'].get({$locator}, '__ts')")
                    timestamps.add("${table.name}__ts")
                }
                is Atom.Delete -> {
                    // Do nothing.
                }
            }

        }
        val tsList = timestamps.joinToString(", ") { "($it < now)" }
        sb.appendLine("        return And($tsList)")
        return sb.toString()
    }
}

fun generateSop(effect: Effect, suffix: Int, enableCommute: Boolean): String {
    println("    [SOP]  ${effect.atoms}")

    val sb = StringBuilder()
    val emitter = Emitter(2)
    sb.append("    def sop$suffix(self, state, argv):\n")

    // Read arguments
    if (enableCommute) {
        sb.appendLine("        now = argv['@OP@']['now']")
    }
    // sb.append(loadArgv(effect))

    // Generate sops
    for (atom in effect.atoms) {
        when (atom) {
            is Atom.Delete -> {
                emitter.emitDelete(atom)
                sb.append(emitter.toString())
            }
            is Atom.Update -> {
                emitter.emitUpdate(atom)
                sb.append(emitter.toString())
            }
            is Atom.Insert -> {
                emitter.emitInsert(atom)
                sb.append(emitter.toString())
            }
        }
    }
    sb.appendLine("        return state")
    return sb.toString()
}

fun loadInsertValues(atom: Atom.Insert, emitter: Emitter): String {
    val sb = StringBuilder()
    for ((col, value) in atom.values) {
        if (value is AbstractValue.Null) {
            println("[DBG] null value $atom")
        } else {
            sb.append("        ${col.qualifiedName} = ${value.toRigi(emitter)}\n")
        }
    }
    return sb.toString()
}

fun locatorsToRigi(locators: Locators, emitter: Emitter): String {
    val dict = locators.map {
        "'${it.key.name}': ${it.value.toRigi(emitter)}"
    }
    val lb = StringBuilder()
    lb.append("{")
    lb.append(dict.joinToString(","))
    lb.append("}")
    return lb.toString()
}

class Emitter(val indent: Int) {
    val sb = StringBuilder()
    val scope = mutableMapOf<String, String>()
    val context = EmitContext()

    private fun emitLine(s: String) {
        sb.appendLine("    ".repeat(indent) + s)
    }

    private fun mkFresh(x: String): String {
        var cnt = 1
        while ("$x$cnt" in scope) {
            cnt += 1
        }
        return "$x$cnt"
    }

    /**
     * @return a fresh variable name to avoid naming conflicts.
     */
    fun emitAssign(x: String, y: String): String {
        return when {
            x !in scope -> {
                emitLine("$x = $y")
                x
            }
            scope[x] == y -> {
                x
            }
            else -> {
                val fresh = mkFresh(x)
                emitLine("$fresh = $y")
                fresh
            }
        }
    }

    /**
     * Emit a delete atom at the end of the emitter.
     */
    fun emitDelete(delete: Atom.Delete) {
        emitLine("state[${delete.table.name}].delete(${locatorsToRigi(delete.locators, this)})")
    }

    /**
     * Emit an update atom at the end of the emitter.
     */
    fun emitUpdate(update: Atom.Update) {
        context.currentLocators = update.locators
        val locatorStr = locatorsToRigi(update.locators, this)
        val valueDictStr = emitDict(update.values)
        emitLine("state['TABLE_${update.table.name}'].update($locatorStr, $valueDictStr)")
    }

    /**
     * Emit an insert atom at the end of the emitter.
     *
     * TODO handle NULL values
     */
    fun emitInsert(insert: Atom.Insert) {
        // Find the pkey from filled columns
        val colsWithValues = insert.values.map { it.key }
        var pkey: Set<Column>? = null
        for (k in insert.table.pkeys) {
            if (k.intersect(colsWithValues).isNotEmpty()) {
                pkey = k.intersect(colsWithValues)
                break
            }
        }
        if (pkey == null) {
            throw RuntimeException("insert doesn't fill in any primary key: $insert")
        }
        val pkeyDictStr = emitDict(pkey.associateWith { insert.values[it]!! })

        // Other values
        val valueDict = insert.values.filter { it.key !in pkey }.toMap()
        val valueDictStr = emitDict(valueDict)

        emitLine("state['TABLE_${insert.table.name}'].add($pkeyDictStr, $valueDictStr)")
    }

    /**
     * @return a python dict string
     */
    fun emitDict(values: Map<Column, AbstractValue>): String {
        val valueDict = mutableListOf<Pair<Column, String>>()
        for ((c, v) in values) {
            valueDict.add(Pair(c, emitAssign(c.qualifiedName, v.toRigi(this))))
        }
        return "{" + valueDict.joinToString(", ") { "'${it.first.name}': ${it.second}" } + "}"
    }

    override fun toString(): String {
        return sb.toString()
    }
}

data class EmitContext(
    var currentLocators: Locators? = null
)
