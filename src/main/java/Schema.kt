import net.sf.jsqlparser.parser.CCJSqlParser
import net.sf.jsqlparser.statement.create.table.ColumnDefinition
import net.sf.jsqlparser.statement.create.table.CreateTable
import net.sf.jsqlparser.statement.create.table.NamedConstraint
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
                // add columns
                for (col in table.columns) {
                    println("  ${col.name}; ${col.type}; ${col.def.columnSpecs}")
                }
                // set primary key
                if (stmt.indexes != null) {
                    for (index in stmt.indexes) {
                        if (index is NamedConstraint && index.type == "PRIMARY KEY") {
                            for (pcol in index.columns) {
                                table.addPKey(setOf(table.get(pcol.columnName)!!))
                                // table.get(pcol.toString())!!.setPKey()
                            }
                        }
                    }
                }
                add(table)
            }
        }
    }
}

typealias PKey = Set<Column>

class Table(val def: CreateTable) {
    val name = def.table.name
    val columns = def.columnDefinitions.map { Column(it, this) }
    val pkeys = mutableListOf<PKey>()

    fun get(i: Int): Column {
        return columns[i]
    }

    fun get(name: String): Column? {
        for (col in columns) {
            if (col.name.equals(name, ignoreCase = true))
                return col
        }
        return null
    }

    fun addPKey(pkey: PKey) {
        if (!pkeys.contains(pkey) && pkey.isNotEmpty())
            pkeys.add(pkey)
    }

    override fun toString(): String {
        return name
    }
}

class Column(val def: ColumnDefinition, val table: Table) {
    val name: String = def.columnName
    val type = convertType()
    //var pkey = false
    val qualifiedName = "${table.name}_${name}"

//    fun setPKey() {
//        pkey = true
//    }

    override fun toString(): String {
        return "${table.name}.$name"
    }

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
    Datetime;

    fun toRigi(): kotlin.String {
        if (this == Datetime) {
            return "Type.INT"
        } else {
            return "Type.${this.toString().toUpperCase()}"
        }
    }

    fun defaultValue(): AbstractValue {
        when (this) {
            String -> {
                return AbstractValue.Data(null, null, "")
            }
            Real -> {
                return AbstractValue.Data(null, null, 0.0 to Double)
            }
            Int -> {
                return AbstractValue.Data(null, null, 0 to Long)
            }
            Datetime -> {
                return AbstractValue.Data(null, null, 0 to Long)
            }
        }
    }
}

fun main() {
    val ddl = "/Users/kaima/src/oltpbenchmark/src/com/oltpbenchmark/benchmarks/healthplus/ddls/healthplus-ddl.sql"
    val schema = Schema(ddl)
    println(schema.get("bill")?.def)
}
