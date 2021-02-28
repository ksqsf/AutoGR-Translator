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
    sb.appendLine(generateRigiDBSchema(analyzer.schema))
    sb.appendLine(generateGenArgv(effectMap))

    for ((op, effectSet) in effectMap) {
        val ob = StringBuilder()
        ob.append("""class @OP@():
    def __init__(self):
        self.ops = [${(effectSet.indices).map { "(self.cond$it, self.csop$it, self.sop$it)" }.joinToString(", ")}]

""")
        var cnt = 0
        for (effect in effectSet) {
            ob.appendLine(generateCond(effect, cnt))
            ob.appendLine(generateCondSop(effect, cnt))
            ob.appendLine(generateSop(effect, cnt))
            cnt++
        }
        sb.appendLine(ob.toString().replace("@OP@", op))
    }

    sb.append("""class $appName():
    def __init__(self):
        self.ops = [${effectMap.keys.joinToString(",")}]
        self.tables = [${analyzer.schema.getTables().map { it.name }.joinToString(",")}]
        self.state = GenState
        self.argv = GenArgv
        self.axiom = AxiomEmpty()

check($appName()) 
""")

    return sb.toString()
}

fun generateRigiDBSchema(schema: Schema): String {
    val sb = StringBuilder()
    for (table in schema.getTables()) {
        sb.append("\n")
        sb.append("$table = Table('$table')\n")
        val pkeys = mutableListOf<Column>()
        for (col in table.columns) {
            sb.append("$table.addAttr('${col.name}', Table.Type.${col.type.toString().toUpperCase()})\n")
            if (col.pkey) {
                pkeys.add(col)
            }
        }
        for (pk in pkeys) {
            sb.append("$table.setPKey('${pk.name}')\n")
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

fun generateGenArgv(effectMap: Map<QualifiedName, Set<Effect>>): String {
    val argvMap = mutableMapOf<QualifiedName, MutableSet<Pair<String, Type>>>()
    for ((eSig, eSet) in effectMap) {
        val eName = eSig.substringBefore('(')
        argvMap.putIfAbsent(eName, mutableSetOf())
        for (eff in eSet) {
            for ((argName, argType) in eff.argv) {
                argvMap[eName]!!.add(Pair(argName, argType))
            }
        }
    }
    val sb = StringBuilder()
    sb.append("def GenArgv():\n")
    sb.append("    builder = ArgvBuilder()\n")
    for ((eName, args) in argvMap) {
        sb.append("    builder.NewOp('$eName')\n")
        for ((argName, argType) in args) {
            sb.append("    builder.AddArgv('$argName', ArgvBuilder.Type.${argType.toString().toUpperCase()})\n")
        }
        sb.append("\n")
    }
    return sb.toString()
}

fun generateCond(effect: Effect, suffix: Int): String {
    val result = mutableListOf<String>()
    if (effect.pathCondition.isEmpty()) {
        result.add("True")
    } else {
        for (cond in effect.pathCondition) {
            when (cond) {
                is AbstractValue.DbNotNil -> {
                    result.add(cond.toRigi())
                }
                else -> {
                    println("[ERR] unknown cond $cond")
                }
            }
        }
    }
    val sb = StringBuilder()
    sb.append("    def cond$suffix(self, state, argv):\n")
    if (result.size == 0) {
        sb.append("        return True\n")
    } else if (result.size == 1) {
        sb.append("        return " + result[0] + "\n")
    } else {
        sb.append("        return And(${result.joinToString(",")})\n")
    }
    return sb.toString()
}

fun generateCondSop(effect: Effect, suffix: Int): String {
    return """    def csop$suffix():
        return True
"""
}

fun generateSop(effect: Effect, suffix: Int): String {
    val sb = StringBuilder()
    sb.append("    def sop$suffix(self, state, argv):\n")

    fun locatorsToRigi(locators: Map<Column, AbstractValue>): String {
        val dict = locators.map {
            "'${it.key.name}': ${it.value.toRigi()}"
        }
        val lb = StringBuilder()
        lb.append("{")
        lb.append(dict.joinToString(","))
        lb.append("}")
        return lb.toString()
    }

    // Read arguments
    for ((argName, _) in effect.argv) {
        sb.append("        $argName = argv['@OP@']['$argName']\n")
    }
    // Generate sops
    for (shadow in effect.shadows) {
        when (shadow) {
            is Shadow.Delete -> {
                sb.append("        state['TABLE_${shadow.table.name}'].delete(${locatorsToRigi(shadow.locators)})\n")
            }
            is Shadow.Update -> {
                val table = shadow.table
                val locatorStr = locatorsToRigi(shadow.locators)
                // Read old values
                for (col in table.columns) {
                    sb.append("        ${col.qualifiedName} = state['TABLE_${table.name}'].get($locatorStr)\n")
                }
                // Update values
                for ((col, newValue) in shadow.values) {
                    val newValStr = newValue?.toRigi() ?: col.qualifiedName
                    sb.append("        ${col.qualifiedName} = $newValStr\n")
                }
                // Call update with new values
                val valueDictStr = table.columns.map { "'${it.name}': ${it.qualifiedName}" }.joinToString(", ")
                sb.append("        state['TABLE_${table.name}'].update($locatorStr, {$valueDictStr})\n")
            }
            is Shadow.Insert -> {
                val table = shadow.table
                for ((col, value) in shadow.values) {
                    if (value == null || value is AbstractValue.Null) {
                        println("[DBG] null value $shadow")
                    } else {
                        sb.append("        ${col.qualifiedName} = ${value!!.toRigi()}\n")
                    }
                }
                val locator = table.columns.filter { it.pkey }.map { "'${it.name}': ${it.qualifiedName}" }.joinToString(", ")
                val otherKeys = table.columns.filter { !it.pkey }.map { "'${it.name}': ${it.qualifiedName}" }.joinToString(",")
                sb.appendLine("        state['TABLE_${table.name}'].add({$locator}, {$otherKeys})")
            }
        }
    }
    return sb.toString()
}
