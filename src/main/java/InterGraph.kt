import java.io.File
import java.util.*

class InterGraph {
    private val graph = mutableMapOf<QualifiedName, MutableSet<QualifiedName>>()
    private val rgraph = mutableMapOf<QualifiedName, MutableSet<QualifiedName>>()
    val effect = mutableSetOf<QualifiedName>()

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
     * Mark a qualified name as an effect.
     */
    fun markNameAsEffect(name: String) {
        for (ms in graph.keys + rgraph.keys) {
            if (ms.startsWith(name)) {
                markAsEffect(ms)
            }
        }
    }

    /**
     * Mark 'sig' as an effect. All of its callers are also marked.
     */
    fun markAsEffect(sig: QualifiedName) {
        if (effect.contains(sig) || (!graph.keys.contains(sig) && !rgraph.keys.contains(sig)))
            return
        val q: Queue<QualifiedName> = LinkedList()
        val vis = mutableSetOf<QualifiedName>()
        q.add(sig)
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

    fun graphviz(onlyEffect: Boolean = true) {
        var s = ""
        val f = File("/tmp/INTERGRAPH.dot")
        for ((caller,callees) in graph) {
            if (onlyEffect && !effect.contains(caller)) {
                continue
            }
            for (callee in callees) {
                s += "  \"${caller}\" -> \"${callee}\";\n"
            }
        }
        for (e in effect) {
            s += "  \"$e\" [color=red];\n"
        }
        f.writeText("digraph G {\n$s\n}\n")
        val r = Runtime.getRuntime().exec("dot -Tpng /tmp/INTERGRAPH.dot -O")
        r.waitFor()
    }

    /**
     * Sort effects topologically: If a() calls b(), then a precedes b in the returned list.
     *
     * If the graph is cyclic, it prints a warning, but nevertheless returns a result, as if the call causing the cycle
     * didn't exist. (Always equals to the reversal of the post-order traversal.) This relaxation allows more workloads
     * to be analyzed, but can be incorrect for some applications.
     */
    fun sorted(): List<QualifiedName> {
        val res = mutableListOf<QualifiedName>()
        val outstanding = mutableSetOf<QualifiedName>()
        val vis = mutableSetOf<QualifiedName>()
        var acyclic = true
        fun dfs(cur: QualifiedName): Boolean {
            if (cur in vis)
                return true
            if (cur in outstanding)
                return false
            outstanding.add(cur)
            for (next in graph[cur].orEmpty()) {
                dfs(next)
            }
            outstanding.remove(cur)
            vis.add(cur)
            res.add(cur)
            return true
        }
        val zeroIn = effect.filter { rgraph[it]?.isEmpty() ?: true }
        for (e in zeroIn) {
            acyclic = acyclic && dfs(e)
        }
        if (!acyclic) {
            println("[CRITICAL] effect call graph is cyclic!")
        }
        return res
    }
}
