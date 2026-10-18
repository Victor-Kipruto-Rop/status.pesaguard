package com.pesaguard.backend.analytics.algorithm.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Graph analysis for mule-account and fraud-ring detection.
 *
 * <p>Money-mule networks and fraud rings have structure that no per-account rule can
 * see. A ring is a set of accounts that pass money in a tight loop; a mule cluster
 * is a set of accounts that all feed into the same destination. Both show up as
 * dense communities or as tightly-connected components in the transaction graph, and
 * both are invisible when each account is scored on its own.
 *
 * <p>This builds an undirected graph whose vertices are account identifiers (MSISDNs)
 * and whose edges connect accounts that transacted with each other. Two community
 * structures are computed: connected components (weakly-separated subgraphs) via
 * union-find, and denser communities via label propagation. The distinction matters -
 * a connected component can be a long thin chain that is not suspicious, whereas a
 * dense community where everyone is connected to everyone is the shape of a ring.
 *
 * <p>A detected community is a lead, not a finding. It is surfaced for investigation;
 * it never blocks an account or moves money by itself.
 */
public final class GraphCommunityDetector {

    private GraphCommunityDetector() {
    }

    /** A directed or undirected transaction edge between two account vertices. */
    public record Edge(String from, String to) {
    }

    /** A detected community with its members and a density-based suspicion score. */
    public record Community(int id, List<String> members, double density, int edgeCount) {
    }

    /** Union-find over string vertices for connected-component detection. */
    private static final class UnionFind {
        private final Map<String, String> parent = new HashMap<>();

        String find(String x) {
            parent.putIfAbsent(x, x);
            String root = x;
            while (!parent.get(root).equals(root)) {
                root = parent.get(root);
            }
            // Path compression: point every node on the path directly at the root.
            String current = x;
            while (!parent.get(current).equals(root)) {
                String next = parent.get(current);
                parent.put(current, root);
                current = next;
            }
            return root;
        }

        void union(String a, String b) {
            String ra = find(a);
            String rb = find(b);
            if (!ra.equals(rb)) {
                parent.put(ra, rb);
            }
        }
    }

    /**
     * Connected components of the transaction graph.
     *
     * <p>Two accounts belong to the same component if there is any path of
     * transactions between them, however long. This is the coarse structure: it finds
     * every group that has transacted transitively, including long chains that are
     * probably innocent. It is the right first filter to narrow a huge graph down to
     * the subgraphs worth a closer look.
     */
    public static List<Community> connectedComponents(List<Edge> edges) {
        UnionFind uf = new UnionFind();
        for (Edge edge : edges) {
            uf.find(edge.from());
            uf.find(edge.to());
            uf.union(edge.from(), edge.to());
        }
        Map<String, List<String>> byRoot = new HashMap<>();
        for (String vertex : uf.parent.keySet()) {
            byRoot.computeIfAbsent(uf.find(vertex), r -> new ArrayList<>()).add(vertex);
        }
        List<Community> components = new ArrayList<>();
        int id = 0;
        for (List<String> members : byRoot.values()) {
            Set<String> memberSet = new HashSet<>(members);
            int edgeCount = countInternalEdges(edges, memberSet);
            components.add(new Community(id++, members, density(members.size(), edgeCount), edgeCount));
        }
        // Largest components first: reviewers should see the most material groups first.
        components.sort((a, b) -> Integer.compare(b.members().size(), a.members().size()));
        return components;
    }

    /**
     * Community detection via label propagation.
     *
     * <p>Each vertex adopts the most common label among its neighbours, iterated to
     * convergence. Densely-connected groups converge on a single shared label and
     * emerge as a community; loosely-connected vertices stay scattered. It is chosen
     * over modularity-maximising methods because it is near-linear and needs no
     * upfront guess of how many communities exist, which is exactly the situation in
     * fraud detection where the number of rings is unknown.
     *
     * <p>The iteration order and tie-breaking are deterministic (vertices processed in
     * insertion order, lowest label wins a tie) so the same graph always yields the
     * same partition - a non-reproducible partition could not be audited.
     */
    public static List<Community> communities(List<Edge> edges, int maxIterations) {
        Map<String, Set<String>> adjacency = new java.util.LinkedHashMap<>();
        for (Edge edge : edges) {
            adjacency.computeIfAbsent(edge.from(), k -> new LinkedHashSet<>()).add(edge.to());
            adjacency.computeIfAbsent(edge.to(), k -> new LinkedHashSet<>()).add(edge.from());
        }
        Map<String, String> labels = new java.util.LinkedHashMap<>();
        for (String vertex : adjacency.keySet()) {
            labels.put(vertex, vertex);
        }
        List<String> order = new ArrayList<>(adjacency.keySet());
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            boolean changed = false;
            for (String vertex : order) {
                Map<String, Integer> counts = new HashMap<>();
                for (String neighbour : adjacency.get(vertex)) {
                    counts.merge(labels.get(neighbour), 1, Integer::sum);
                }
                if (counts.isEmpty()) {
                    continue;
                }
                String best = null;
                int bestCount = -1;
                for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                    // Deterministic tie-break: higher count, then lexicographically
                    // smaller label, so the outcome does not depend on map order.
                    if (entry.getValue() > bestCount
                            || (entry.getValue() == bestCount
                                && entry.getKey().compareTo(best) < 0)) {
                        best = entry.getKey();
                        bestCount = entry.getValue();
                    }
                }
                if (!labels.get(vertex).equals(best)) {
                    labels.put(vertex, best);
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        Map<String, List<String>> byLabel = new HashMap<>();
        for (Map.Entry<String, String> entry : labels.entrySet()) {
            byLabel.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        }
        List<Community> communities = new ArrayList<>();
        int id = 0;
        for (List<String> members : byLabel.values()) {
            Set<String> memberSet = new HashSet<>(members);
            int edgeCount = countInternalEdges(edges, memberSet);
            communities.add(new Community(id++, members, density(members.size(), edgeCount), edgeCount));
        }
        communities.sort((a, b) -> Double.compare(b.density(), a.density()));
        return communities;
    }

    /** Count edges with both endpoints inside the given vertex set. */
    private static int countInternalEdges(List<Edge> edges, Set<String> members) {
        int count = 0;
        for (Edge edge : edges) {
            if (members.contains(edge.from()) && members.contains(edge.to())) {
                count++;
            }
        }
        return count;
    }

    /**
     * Graph density: edges present divided by the maximum possible edges
     * ({@code n(n-1)/2}). A value near 1 means a fully-connected clique - the
     * signature of a tightly-knit ring; a value near 0 means a sparse, chain-like
     * component that is far less suspicious.
     */
    private static double density(int vertexCount, int edgeCount) {
        if (vertexCount < 2) {
            return 0.0;
        }
        double maxEdges = (double) vertexCount * (vertexCount - 1) / 2.0;
        return edgeCount / maxEdges;
    }
}
