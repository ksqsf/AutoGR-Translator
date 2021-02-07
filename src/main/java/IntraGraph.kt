import com.github.javaparser.ast.expr.BooleanLiteralExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.SimpleName
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.resolution.types.ResolvedType
import javassist.expr.Expr
import java.io.File
import java.util.*

fun quote(str: String): String {
    return str.replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\"", "\\\"")
}

sealed class Label {
    object T: Label() {
        override fun toString(): String {
            return ""
        }
    }
    data class Br(val expr: Expression): Label() {
        override fun toString(): String {
            return quote(expr.toString())
        }
    }
    data class Raise(val ty: ResolvedType, val expr: Expression?): Label() {
        override fun toString(): String {
            return "ex(${quote(ty.describe())} $expr)"
        }
    }
    data class Catch(val name: SimpleName, val ty: ResolvedType): Label() {
        override fun toString(): String {
            return "catch(${quote(ty.describe())} ${quote(name.toString())})"
        }
    }
    object Uncaught : Label()
}

/**
 * Intra-graph encompasses the control flow information of a block.
 *
 * A node is:
 * 1. a line of code ([ExpressionStmt])
 * 2. an empty node, which can be `entry`, `exit`, `return`, or `except`.
 * Each node has a unique (program-wise) ID.
 *
 * Edges between nodes are labeled path conditions. A path condition can either be:
 * 1. T, unconditional transition, no exception
 * 2. Br, conditional transition (expression must evaluate to true), no exception
 * 3. Ex, exception, an exception is just thrown
 * 4. Catch, an exception is just caught here, and the exception is bound to `name` (type `ty`)
 *
 * Upon union, `return` are always merged. `except` and `break` are usually, but not always, merged.
 */
class IntraGraph{

    private fun newId(): Int {
        return IDGen.gen()
    }

    var nodeId: MutableMap<Statement, Int> = mutableMapOf()
    var idNode: MutableMap<Int, Statement> = mutableMapOf()
    var entryId: Int = newId()
    var exitId: Int = newId()
    var returnId: Int = newId()
    var exceptId: Int = newId()
    var breakId: Int = newId()
    var continueId: Int = newId()

    fun isSpecial(id: Int): Boolean {
        return id == entryId || id == exitId || id == returnId || id == exceptId || id == breakId || id == continueId
    }

    fun getEntryId(): Int {
        return entryId
    }

    fun getExitId(): Int {
        return exitId
    }

    fun getBreakId(): Int {
        return breakId
    }

    fun getReturnId(): Int {
        return returnId
    }

    fun getExceptId(): Int {
        return exceptId
    }

    fun getContinueId(): Int {
        return continueId
    }

    private fun addNodeIfAbsent(stmt: Statement): Int {
        return if (nodeId.containsKey(stmt)) {
            nodeId[stmt]!!
        } else {
            val id = newId()
            nodeId[stmt] = id
            idNode[id] = stmt
            id
        }
    }

    data class OutEdge(val next: Int, val label: Label)

    var graph: MutableMap<Int, MutableSet<OutEdge>> = mutableMapOf()
    var rgraph: MutableMap<Int, MutableSet<OutEdge>> = mutableMapOf()

    fun addEdgeId(from: Int, to: Int, label: Label = Label.T) {
        if (!graph.containsKey(from))
            graph[from] = mutableSetOf()
        if (!rgraph.containsKey(to))
            rgraph[to] = mutableSetOf()
        graph[from]!!.add(OutEdge(to, label))
        rgraph[to]!!.add(OutEdge(from, label))
    }

    ///
    /// Build intra-graph
    ///
    fun addEdgeFromEntry(s: Statement, cond: Label = Label.T) {
        addEdgeId(entryId, addNodeIfAbsent(s), cond)
    }

    fun addEdgeToExit(s: Statement, label : Label = Label.T) {
        addEdgeId(addNodeIfAbsent(s), exitId, label)
    }

    fun addEdgeFromEntryToExit(label: Label = Label.T) {
        addEdgeId(entryId, exitId, label)
    }

    fun addEdgeToReturn(s: Statement, label: Label = Label.T) {
        addEdgeId(addNodeIfAbsent(s), returnId, label)
    }

    fun addEdgeToExcept(s: Statement, label: Label = Label.T) {
        addEdgeId(addNodeIfAbsent(s), exceptId, label)
    }

    ///
    /// Merge two nodes
    ///
    fun mergeId(p: Int, q: Int) {
        val pred = mutableSetOf<Int>()
        // If r->q, then link r->p
        if (rgraph.containsKey(q)) {
            for ((r, rcond) in rgraph[q]!!) {
                addEdgeId(r, p, rcond)
                pred.add(r)
            }
            // Unlink q from r
            for (v in pred) {
                graph[v]!!.removeIf { it.next == q }
            }
        }
        // If q->r, then link p->r
        if (graph.containsKey(q)) {
            for ((r, rcond) in graph[q]!!) {
                addEdgeId(p, r, rcond)
            }
        }
        // Remove q
        graph.remove(q)
        rgraph.remove(q)
        val s = idNode.remove(q)
        nodeId.remove(s)
    }

    /**
     * Modify this graph to include rhs. The return nodes are merged.
     */
    fun union(rhs: IntraGraph, mergeBreak: Boolean = true, mergeContinue: Boolean = true, mergeExcept: Boolean = true) {
        this.graph.putAll(rhs.graph)
        this.rgraph.putAll(rhs.rgraph)
        this.nodeId.putAll(rhs.nodeId)
        this.idNode.putAll(rhs.idNode)
        mergeId(this.returnId, rhs.returnId)
        if (mergeBreak)
            mergeId(this.breakId, rhs.breakId)
        if (mergeExcept)
            mergeId(this.exceptId, rhs.exceptId)
        if (mergeContinue)
            mergeId(this.continueId, rhs.continueId)
    }

    /**
     * Include a graph into this graph, and place it between the entry and the exit nodes.
     */
    fun addBetweenEntryAndExit(g: IntraGraph, condEnter: Label = Label.T, condLeave: Label = Label.T) {
        union(g)
        addEdgeId(entryId, g.entryId, condEnter)
        addEdgeId(g.exitId, exitId, condLeave)
    }

    /**
     * Include a chain of graphs into this graph.
     *
     * 1. The first graph will be linked to the entry node, while the last will be linked to the exit node.
     * 2. Return nodes are merged.
     * 3. Break nodes point to the break node of this graph.
     */
    fun sequence(gs: List<IntraGraph>) {
        if (gs.isEmpty()) {
            return
        }
        for (g in gs) {
            union(g, mergeBreak = true, mergeExcept = true)
        }
        addEdgeId(entryId, gs[0].entryId)
        addEdgeId(gs[0].breakId, breakId)
        for (i in 1 until gs.size) {
            addEdgeId(gs[i-1].exitId, gs[i].entryId)
            addEdgeId(gs[0].breakId, breakId)
        }
        addEdgeId(gs.last().exitId, exitId)
    }

    ///
    /// Remove unnecessary nodes
    ///
    private fun canRemoveId(id: Int): Boolean {
        if (isSpecial(id))
            return false
        if (idNode[id] != null)
            return false
        if (!rgraph.containsKey(id))
            return false
        if (graph[id] == null || rgraph[id] == null || graph[id]!!.size != 1 || rgraph[id]!!.size != 1)
            return false
        val (_, scond) = graph[id]!!.first()
        val (_, pcond) = rgraph[id]!!.first()
        if (scond != Label.T || pcond != Label.T)
            return false
//        if (graph[pred] == null || rgraph[succ] == null || graph[pred]!!.size != 1 || rgraph[succ]!!.size != 1)
//            return false
        return true
    }

    fun removeId(id: Int) {
        val removeSym = { graph1: MutableMap<Int, MutableSet<OutEdge>>,
                          graph2: MutableMap<Int, MutableSet<OutEdge>> ->
            for ((next, _) in graph1.getOrDefault(id, mutableSetOf())) {
                if (graph2[next] != null) {
                    val delete = graph2[next]!!.filter { it.next == id }
                    for (del in delete) {
                        graph2[next]!!.remove(del)
                    }
                }
            }
        }
        removeSym(graph, rgraph)
        removeSym(rgraph, graph)
        graph.remove(id)
        rgraph.remove(id)
        val s = idNode.remove(id)
        if (s!=null)
            nodeId.remove(s)
    }

    private fun optimizeOnePass(): Boolean {
        var changed = false
        check()
        // pred --> p --> succ
        val deleted = mutableSetOf<Int>()
        for (p in graph.keys) {
            if (deleted.contains(p))
                continue
            if (canRemoveId(p)) {
                val pred = rgraph[p]!!.first().next
                var cur = p
                while (canRemoveId(cur)) {
                    deleted.add(cur)
                    cur = graph[cur]!!.first().next
                }
//                assert(graph[pred]!!.size == 1)
//                assert(rgraph[p]!!.size == 1)
//                graph[pred]!!.clear() //only p
//                rgraph[p]!!.clear() //only cur
//                println("link $pred $cur")
                addEdgeId(pred, cur)
            }
        }
        for (del in deleted)
            removeId(del)
        // Remove unreachable nodes
        val vis = mutableSetOf<Int>()
        val q: Queue<Int> = LinkedList()
        val reachable: MutableSet<Int> = mutableSetOf()
        q.add(entryId)
        while (!q.isEmpty()) {
            val p = q.remove()
            if (vis.contains(p))
                continue
            vis.add(p)
            reachable.add(p)
            for ((r,_) in graph.getOrDefault(p, mutableSetOf())) {
                q.add(r)
            }
        }
        val all = graph.keys
        for (unreachable in all - reachable) {
            if (unreachable != entryId && unreachable != exitId && unreachable != returnId && unreachable != exceptId) {
                removeId(unreachable)
                changed = true
            }
        }
        return changed
    }

    /**
     * Remove unnecessary nodes.
     *
     * 1. Trivial nodes (ones which have only one pred and one succ, both edge labels are true) are removed.
     * 2. Unreachable nodes are removed.
     */
    fun optimize() {
        while (optimizeOnePass()) {
            //check()
        }
    }

    ///
    /// Debugging
    ///

    /**
     * Print the graphviz representation of this intra-graph.
     *
     * @param opt whether to optimize this graph before printing
     */
    fun graphviz(name: String = "G", opt: Boolean = true) {
        if (opt)
            optimize()

        val str = { id: Int ->
            when {
                idNode[id] != null -> "($id)${quote(idNode[id].toString())}"
                id == entryId -> "entry"
                id == exitId -> "exit"
                id == returnId -> "return"
                id == exceptId -> "except"
                id == breakId -> "break"
                id == continueId -> "continue"
                else -> "ε($id)"
            }
        }

        val f = File("/tmp/$name.dot", )
        var text = ""
        text += "digraph \"$name\" {"
        for ((p, pAdj) in graph) {
            for ((q, cond) in pAdj) {
                val s = "\"${str(p)}\" -> \"${str(q)}\" [label=\"${quote(cond.toString())}\"];"
                text += s
            }
        }
        text += "}"
        f.writeText(text)
        val r = Runtime.getRuntime().exec("dot -Tpng /tmp/$name.dot -O")
        r.waitFor()
    }

    override fun toString(): String {
        var s = ""
        s += ("+ entry=${entryId}, exit=${exitId}, \n")

        val str = { id: Int ->
            if (idNode[id] != null) "${idNode[id]}"
            else if (id == entryId) "entry"
            else if (id == exitId) "exit"
            else if (id == returnId) "return"
            else if (id == exceptId) "except"
            else "empty($id)"
        }

        for (p in graph.keys) {
            s += "- ${str(p)}\n"
            for ((q,c) in graph[p]!!) {
                assert(rgraph[q]!!.contains(OutEdge(p, c)))
                s += ("   ---> ${str(q)} ($c)\n")
            }
        }
        return s
    }

    private fun check() {
        for (p in rgraph.keys) {
            for ((q, cond) in rgraph[p]!!) {
                assert(graph[q]!!.contains(OutEdge(p,cond)))
            }
        }
        for (p in graph.keys) {
            for ((q, cond) in graph[p]!!) {
                assert(rgraph[q]!!.contains(OutEdge(p,cond)))
            }
        }
    }

    fun clone(): IntraGraph {
        val clone = IntraGraph()
        val map: MutableMap<Int,Int> = mutableMapOf() // this id -> clone id
        for (id in graph.keys + rgraph.keys) {
            map[id] = newId()
        }
        // clone graph
        for ((p,pAdj) in graph) {
            clone.graph[map[p]!!] = mutableSetOf()
            for ((q,cond) in pAdj) {
                clone.graph[map[p]!!]!!.add(OutEdge(map[q]!!, cond))
            }
        }
        // clone rgraph
        for ((p,pAdj) in rgraph) {
            clone.rgraph[map[p]!!] = mutableSetOf()
            for ((q,cond) in pAdj) {
                clone.rgraph[map[p]]!!.add(OutEdge(map[q]!!, cond))
            }
        }
        // clone idNode
        for ((id,node) in idNode) {
            clone.idNode[map[id]!!] = node
        }
        // clone node id
        for ((node, id) in nodeId) {
            clone.nodeId[node] = map[id]!!
        }
        return clone
    }
}
