import net.sf.jsqlparser.parser.CCJSqlParser
import net.sf.jsqlparser.statement.create.table.ColumnDefinition
import net.sf.jsqlparser.statement.create.table.CreateTable
import java.io.File

class Schema {

    private val tables = mutableMapOf<String, Table>()

    constructor() {}

    constructor(path: String) {
        loadFile(path)
    }

    fun add(table: Table) {
        tables[table.name] = table
    }

    fun get(name: String): Table? {
        return tables[name]
    }

    fun getTables(): List<Table> {
        return tables.values.toList()
    }

    fun loadFile(path: String) {
        val file = File(path).bufferedReader()
        val parser = CCJSqlParser(file.readText())
        for (stmt in parser.Statements().statements) {
            if (stmt is CreateTable) {
                val table = Table(stmt)
                println("- Table: ${table.name}")
                for (col in table.columns) {
                    println("  ${col.name}; ${col.type}; ${col.def.columnSpecs}")
                }
                add(table)
            }
        }
    }
}

class Table(val def: CreateTable) {
    val columns = def.columnDefinitions.map { Column(it) }
    val name = def.table.name

    fun get(i: Int): Column {
        return columns.get(i)
    }
}

class Column(val def: ColumnDefinition) {
    val name: String = def.columnName
    val type = convertType()

    private fun convertType(): Type {
        val raw = def.colDataType.dataType.toLowerCase()
        return if (raw.startsWith("varchar") || raw.startsWith("char")) {
            Type.String
        } else if (raw.startsWith("int") || raw.startsWith("tinyint")) {
            Type.Int
        } else if (raw.startsWith("double") || raw.startsWith("float")) {
            Type.Real
        } else if (raw.startsWith("datetime") || raw.startsWith("date") || raw.startsWith("time")) {
            Type.Datetime
        } else {
            println("Unrecognizable type: ${def.colDataType}")
            assert(false)
            Type.Int
        }
    }
}

enum class Type {
    String,
    Real,
    Int,
    Datetime
}

fun main() {
    val ddl = "/Users/kaima/src/oltpbenchmark/src/com/oltpbenchmark/benchmarks/healthplus/ddls/healthplus-ddl.sql"
    val schema = Schema(ddl)
    println(schema.get("bill")?.def)
}
