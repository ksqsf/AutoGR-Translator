import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.SimpleName
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.resolution.types.ResolvedType
import java.io.File
import java.util.*

data class IntraPath(val path: List<IntraGraph.OutEdge>, val intragraph: IntraGraph, val final: Int) {
    operator fun iterator(): Iterator<IntraGraph.OutEdge> {
        return path.iterator()
    }
}

data class Node(val statement: Statement, val scopingDepth: Int) {
    override fun toString(): String {
        return "$statement @ $scopingDepth"
    }
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
    data class BrNot(val expr: Expression): Label() {
        override fun toString(): String {
            return "!(${quote(expr.toString())})"
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
 * An empty node is either an entry or an exit, and never will be between two 'real' nodes.
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
class IntraGraph(val classDef: ClassOrInterfaceDeclaration, val methodDecl: MethodDeclaration) {

    private fun newId(): Int {
        return IDGen.gen()
    }

    var nodeId: MutableMap<Node, Int> = mutableMapOf()
    var idNode: MutableMap<Int, Node> = mutableMapOf()
    var entryId: Int = newId()
    var exitId: Int = newId()
    var returnId: Int = newId()
    var exceptId: Int = newId()
    var breakId: Int = newId()
    var continueId: Int = newId()

    var loopCnt = 0

    fun isSpecial(id: Int): Boolean {
        return id == entryId || id == exitId || id == returnId || id == exceptId || id == breakId || id == continueId
    }

    private fun addNodeIfAbsent(node: Node): Int {
        return if (nodeId.containsKey(node)) {
            nodeId[node]!!
        } else {
            val id = newId()
            nodeId[node] = id
            idNode[id] = node
            id
        }
    }

    private fun addNodeIfAbsent(s: Statement, d: Int): Int {
        return addNodeIfAbsent(Node(s, d))
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
    fun addEdgeFromEntry(s: Statement, depth: Int, cond: Label = Label.T) {
        addEdgeId(entryId, addNodeIfAbsent(s, depth), cond)
    }

    fun addEdgeToExit(s: Statement, depth: Int, label : Label = Label.T) {
        addEdgeId(addNodeIfAbsent(s, depth), exitId, label)
    }

    fun addEdgeFromEntryToExit(label: Label = Label.T) {
        addEdgeId(entryId, exitId, label)
    }

    fun addEdgeToReturn(s: Statement, depth: Int, label: Label = Label.T) {
        addEdgeId(addNodeIfAbsent(s, depth), returnId, label)
    }

    fun addEdgeToExcept(s: Statement, depth: Int, label: Label = Label.T) {
        addEdgeId(addNodeIfAbsent(s, depth), exceptId, label)
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
        this.loopCnt += rhs.loopCnt
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
    fun graphviz(name: String = "G", opt: Boolean = true): Process {
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

        val normalize = { s: String ->
            s.takeWhile { it != '(' }
        }

        val f = File("/tmp/${normalize(name)}.dot", )
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
        val r = Runtime.getRuntime().exec("dot -Tpng /tmp/${normalize(name)}.dot -O")
        return r
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
        val clone = IntraGraph(classDef, methodDecl)
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

    /**
     * This function finds all linear paths leading to an effect.
     *
     * Consider a loop,
     * ```
     * a -(c1)-> b -> c -> a
     *   -(!c1)> d -> effect
     * ```
     * A linear path is `a` -> d -> effect`. All loop body is ignored and the handling is delayed until we run the path.
     * Therefore, one path can correspond to more than one [Effect]. If a is a loop base, two effects will be generated:
     * 1. `a -(!c1)> d -> effect`, when the initial state is `!c1`.
     * 2. `a -(c1)-> ... -> d -> effect`, when the initial state is `c1`.
     * When leaving the loop, all variables modified by the loop will be assigned Unknown.
     *
     * A complex case arises when the effect appears INSIDE the loop:
     * ```
     * a -> b -> effect -> a
     * ```
     * In this case, the path is `a -> b -> effect`.
     */
    fun effectPathsFromEntry(dest: Int): Set<IntraPath> {
        val res = mutableSetOf<IntraPath>()
        val vis = mutableSetOf<Int>()
        // FIXME: generate two paths forking at loop base
        fun dfs(cur: Int, path: MutableList<OutEdge>, effect: Boolean) {
            if (vis.contains(cur))
                return
            vis.add(cur)
            if (cur == entryId) {
                if (effect)
                    res.add(IntraPath(path.reversed().toList(), this, dest))
                return
            }
            for (edge in rgraph[cur]!!) {
                path.add(edge)
                if (effect) {
                    dfs(edge.next, path, true)
                } else {
                    dfs(edge.next, path, idNode.containsKey(cur) && containsUpdate(idNode[cur]!!.statement))
                }
                path.removeLast()
            }
        }
        dfs(dest, mutableListOf(), false)
        return res.toSet()
    }

    /**
     * For details on loop handling, see [effectPathsFromEntry].
     */
    fun collectEffectPaths(): Map<Int, Set<IntraPath>> {
        val paths = mutableMapOf<Int, MutableSet<IntraPath>>()
        // From each committing node, traverse up to the entry.
        // There can be many paths leading to a committing node,
        // and only effectful ones are collected here.
        for ((id, node) in idNode) {
            // FIXME: check if a method is committing precisely.
            // This should include wrappers.
            // FIXME: Should remove containsUpdate.
            if (containsCommit(node.statement) || containsUpdate(node.statement)) {
                for (path in effectPathsFromEntry(id)) {
                    paths.putIfAbsent(id, mutableSetOf())
                    paths[id]!!.add(path)
                }
            }
        }
        return paths.mapValues { it.value.toSet() }.toMap()
    }

    private var memoizedLoopSet : LoopSet? = null

    /**
     * Collect loop information of this intragraph. It outputs a set of SCCs (of node IDs). The result is cached for
     * future calls.
     *
     * @param refresh Force this method to recompute the loop set.
     */
    fun collectLoops(refresh: Boolean = false): LoopSet {
        if (!refresh && memoizedLoopSet != null)
            return memoizedLoopSet!!

        // Compute startTime and endTime
        val startTime = mutableMapOf<Int, Int>()
        val endTime = mutableMapOf<Int, Int>()
        var time = 0
        val visited = mutableSetOf<Int>()
        fun DFS1(cur: Int) {
            time += 1
            startTime[cur] = time
            visited.add(cur)
            for (edge in graph[cur] ?: emptySet()) {
                val next = edge.next
                if (!visited.contains(next)) {
                    DFS1(next)
                }
            }
            time += 1
            endTime[cur] = time
        }
        DFS1(entryId)

        // Compute SCC.
        // If outstanding = [ a, b, c, d, b ]
        // then we found a SCC { b, c, d }, which is a loop.
        // The loop base is the node with smallest startTime.
        val outstanding = mutableListOf<Int>()
        val sccs = mutableSetOf<Set<Int>>()
        fun DFS2(cur: Int) {
            if (outstanding.contains(cur)) {
                val i = outstanding.indexOf(cur)
                val scc = outstanding.subList(i, outstanding.size).toSet()
                sccs.add(scc)
                return
            }
            val nextUnsorted = mutableListOf<Int>()
            for (edge in rgraph[cur] ?: emptySet()) {
                nextUnsorted.add(edge.next)
            }
            val nexts = nextUnsorted.distinct().sortedBy { endTime[it]!! }
            outstanding.add(cur)
            for (next in nexts) {
                DFS2(next)
            }
            outstanding.removeLast()
        }
        DFS2(exitId)
        DFS2(returnId)

        // Convert SCC into a loop info
        fun loopBaseOf(scc: Set<Int>): Int {
            var base = scc.first()
            var minStart = startTime[base]!!
            for (x in scc) {
                if (startTime[x]!! < minStart) {
                    minStart = startTime[x]!!
                    base = x
                }
            }
            return base
        }
        val loops = mutableSetOf<LoopInfo>()
        for (scc in sccs) {
            val base = loopBaseOf(scc)
            val body = scc - base
            loops.add(LoopInfo(base, body))
        }
        memoizedLoopSet = LoopSet(loops)
        return memoizedLoopSet!!
    }
}

data class LoopInfo(val base: Int, val body: Set<Int>) {
    fun contains(x: Int): Boolean {
        return base == x || containsBody(x)
    }

    fun containsBody(x: Int): Boolean {
        return body.contains(x)
    }
}

data class LoopSet(val loops: Set<LoopInfo>) {
    operator fun iterator(): Iterator<LoopInfo> {
        return loops.iterator()
    }

    fun filterContains(x: Int): LoopSet {
        return LoopSet(loops.filter { it.contains(x) }.toSet())
    }

    fun isEmpty(): Boolean {
        return loops.isEmpty()
    }

    fun nested(base: Int): Boolean {
        val containers = filterContains(base)
        for (loop in containers) {
            if (base != loop.base)
                return true
        }
        return false
    }

    fun nested(loop: LoopInfo): Boolean {
        return nested(loop.base)
    }

    private val nodesInsideLoop: Set<Int> by lazy {
        val res = mutableSetOf<Int>()
        for (loop in loops) {
            res.add(loop.base)
            res += loop.body
        }
        res
    }

    fun isPartOfLoop(id: Int): Boolean {
        return nodesInsideLoop.contains(id)
    }
}
