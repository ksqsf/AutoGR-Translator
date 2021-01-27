import java.io.File
import java.util.*

class InterGraph {
    private val graph = mutableMapOf< QualifiedName, MutableSet<QualifiedName> > ()
    private val rgraph = mutableMapOf<QualifiedName, MutableSet<QualifiedName>>()
    private val effect = mutableSetOf<QualifiedName>()

    fun add(caller: QualifiedName, callee: QualifiedName) {
        graph.putIfAbsent(caller, mutableSetOf())
        rgraph.putIfAbsent(callee, mutableSetOf())
        graph[caller]?.add(callee)
        rgraph[callee]?.add(caller)
    }

    fun union(rhs: InterGraph) {
        for ((caller, callees) in rhs.graph) {
            for (callee in callees) {
                add(caller, callee)
            }
        }
    }

    /**
     * Mark 'name' as an effect. All of its callers are also marked.
     */
    fun markAsEffect(name: QualifiedName) {
        if (effect.contains(name) || (!graph.keys.contains(name) && !rgraph.keys.contains(name)))
            return
        val q: Queue<QualifiedName> = LinkedList()
        val vis = mutableSetOf<QualifiedName>()
        q.add(name)
        while (q.isNotEmpty()) {
            val cur = q.remove()
            if (vis.contains(cur))
                continue
            vis.add(cur)
            effect.add(cur)
            for (pred in rgraph.getOrDefault(cur, mutableSetOf())) {
                q.add(pred)
            }
        }
    }

    fun graphviz() {
        var s = ""
        val f = File("/tmp/INTERGRAPH.dot")
        for ((caller,callees) in graph) {
            for (callee in callees) {
                s += "\"${caller}\" -> \"${callee}\";";
            }
        }
        for (e in effect) {
            s += "\"$e\" [color=red];"
        }
        f.writeText("digraph G {\n$s\n}\n")
        val r = Runtime.getRuntime().exec("dot -Tpng /tmp/INTERGRAPH.dot -O")
        r.waitFor()
        println(r)
    }
}
