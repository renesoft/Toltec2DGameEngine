package org.toltec.engine;

import org.toltec.unit.Unit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Generic A* pathfinder over a grid of (col,row) cells. Doesn't know
 * anything about {@link TileGameEngine} or {@link MapCell} — you supply a
 * {@link Walkable} callback, so it can be reused for any grid, not just
 * units on the game map.
 *
 * Each {@link Unit} owns its own instance and re-runs it whenever
 * {@link Unit#moveTo} is called.
 */
public class PathFinder {

    /** Tells the pathfinder whether a given cell can be walked through. */
    @FunctionalInterface
    public interface Walkable {
        boolean isWalkable(int col, int row);
    }

    private static final double SQRT2 = Math.sqrt(2); // java.lang.Math has no SQRT2 constant, only E and PI

    private static final int[] DX = {0, 1, 0, -1, 1, 1, -1, -1};
    private static final int[] DY = {-1, 0, 1, 0, -1, 1, 1, -1};
    private static final double[] COST = {1, 1, 1, 1, SQRT2, SQRT2, SQRT2, SQRT2};

    private List<int[]> path = Collections.emptyList();
    private boolean allowDiagonal = true;

    /** Whether diagonal steps (the 4 extra of the 8 directions) are allowed. Default true. */
    public void setAllowDiagonal(boolean allow) { allowDiagonal = allow; }
    public boolean isDiagonalAllowed() { return allowDiagonal; }

    /**
     * Computes the shortest walkable path from (startCol,startRow) to
     * (endCol,endRow). The result excludes the start cell (so the first
     * entry is the first step to take) but includes the destination cell.
     * If start == end, the path is empty but the method still returns true.
     *
     * @return true if a path exists (or start == end); false if the goal is
     *         unreachable or either endpoint isn't walkable.
     */
    public boolean findPath(Walkable walkable, int startCol, int startRow, int endCol, int endRow) {
        path = Collections.emptyList();

        if (!walkable.isWalkable(startCol, startRow) || !walkable.isWalkable(endCol, endRow))
            return false;

        if (startCol == endCol && startRow == endRow) {
            path = Collections.emptyList();
            return true;
        }

        int dirCount = allowDiagonal ? 8 : 4;
        long startKey = pack(startCol, startRow);
        long endKey   = pack(endCol, endRow);

        Map<Long, Double> gScore   = new HashMap<>();
        Map<Long, Long>   cameFrom = new HashMap<>();
        Set<Long>          closed  = new HashSet<>();

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        gScore.put(startKey, 0.0);
        open.add(new Node(startKey, heuristic(startCol, startRow, endCol, endRow)));

        while (!open.isEmpty()) {
            Node cur = open.poll();
            if (closed.contains(cur.key)) continue;
            if (cur.key == endKey) {
                path = reconstruct(cameFrom, cur.key);
                return true;
            }
            closed.add(cur.key);

            int cc = unpackCol(cur.key), cr = unpackRow(cur.key);
            double curG = gScore.get(cur.key);

            for (int i = 0; i < dirCount; i++) {
                int nc = cc + DX[i], nr = cr + DY[i];
                if (!walkable.isWalkable(nc, nr)) continue;

                // don't let a diagonal step cut across two blocked orthogonal corners
                if (i >= 4 && !walkable.isWalkable(cc + DX[i], cr) && !walkable.isWalkable(cc, cr + DY[i]))
                    continue;

                long nKey = pack(nc, nr);
                if (closed.contains(nKey)) continue;

                double tentativeG = curG + COST[i];
                if (tentativeG < gScore.getOrDefault(nKey, Double.MAX_VALUE)) {
                    cameFrom.put(nKey, cur.key);
                    gScore.put(nKey, tentativeG);
                    open.add(new Node(nKey, tentativeG + heuristic(nc, nr, endCol, endRow)));
                }
            }
        }
        return false; // open exhausted, goal unreachable
    }

    /** The path computed by the last {@link #findPath} call (empty if none/unreachable). */
    public List<int[]> getPath() { return path; }

    public boolean hasPath() { return !path.isEmpty(); }

    public void clear() { path = Collections.emptyList(); }

    // =========================================================================
    // Internals
    // =========================================================================

    private record Node(long key, double f) {}

    private double heuristic(int c1, int r1, int c2, int r2) {
        int dc = Math.abs(c1 - c2), dr = Math.abs(r1 - r2);
        return allowDiagonal
                ? Math.max(dc, dr) + (SQRT2 - 1) * Math.min(dc, dr) // octile distance
                : dc + dr;                                              // Manhattan distance
    }

    private List<int[]> reconstruct(Map<Long, Long> cameFrom, long endKey) {
        LinkedList<int[]> out = new LinkedList<>();
        long k = endKey;
        while (true) {
            out.addFirst(new int[]{unpackCol(k), unpackRow(k)});
            Long prev = cameFrom.get(k);
            if (prev == null) break;
            k = prev;
        }
        if (!out.isEmpty()) out.removeFirst(); // drop the start cell itself
        return new ArrayList<>(out);
    }

    private static long pack(int col, int row) {
        return (((long) col) << 32) ^ (row & 0xFFFFFFFFL);
    }
    private static int unpackCol(long key) { return (int) (key >> 32); }
    private static int unpackRow(long key) { return (int) key; }
}
