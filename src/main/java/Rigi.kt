import java.lang.StringBuilder

fun generateRigi(appName: String, analyzer: Analyzer): String {
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

    sb.append(generateRigiDBSchema(analyzer.schema))

    // TODO: genArgv

    // TODO: Op classes

    sb.append("""class $appName():
    def __init__(self):
        self.ops = []
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
        for (col in table.columns) {
            sb.append("$table.addAttr('${col.name}', Table.Type.${col.type.toString().toUpperCase()})\n")
        }
        // TODO: set PKey
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
