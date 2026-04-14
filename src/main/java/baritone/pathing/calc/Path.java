/*
 * This file is part of Baritone.
 * ... [license header unchanged] ...
 */

package baritone.pathing.calc;

import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.Moves;
import baritone.pathing.movement.movements.MovementParkour;
import baritone.pathing.movement.movements.MovementSkyblockEtherTransmission;
import baritone.pathing.path.CutoffPath;
import baritone.utils.pathing.PathBase;
import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A node based implementation of IPath.
 * <p>
 * Updated to support reconstruction of any-angle parkour movements from PathNode metadata.
 *
 * @author leijurv
 */
class Path extends PathBase {

    private final BetterBlockPos start;
    private final BetterBlockPos end;
    private final List<BetterBlockPos> path;
    private final List<Movement> movements;
    private final List<PathNode> nodes;
    private final Goal goal;
    private final int numNodes;
    private final CalculationContext context;
    private volatile boolean verified;

    Path(BetterBlockPos realStart, PathNode start, PathNode end, int numNodes, Goal goal, CalculationContext context) {
        this.end = new BetterBlockPos(end.x, end.y, end.z);
        this.numNodes = numNodes;
        this.movements = new ArrayList<>();
        this.goal = goal;
        this.context = context;

        PathNode current = end;
        List<BetterBlockPos> tempPath = new ArrayList<>();
        List<PathNode> tempNodes = new ArrayList<>();
        while (current != null) {
            tempNodes.add(current);
            tempPath.add(new BetterBlockPos(current.x, current.y, current.z));
            current = current.previous;
        }

        var startNodePos = new BetterBlockPos(start.x, start.y, start.z);
        if (!realStart.equals(startNodePos) && start.equals(end)) {
            this.start = realStart;
            PathNode fakeNode = new PathNode(realStart.x, realStart.y, realStart.z, goal);
            fakeNode.cost = 0;
            tempNodes.add(fakeNode);
            tempPath.add(realStart);
        } else {
            this.start = startNodePos;
        }

        this.path = Lists.reverse(tempPath);
        this.nodes = Lists.reverse(tempNodes);
    }

    @Override
    public Goal getGoal() {
        return goal;
    }

    private boolean assembleMovements() {
        if (path.isEmpty() || !movements.isEmpty()) {
            throw new IllegalStateException("Path must not be empty");
        }
        for (int i = 0; i < path.size() - 1; i++) {
            double cost = nodes.get(i + 1).cost - nodes.get(i).cost;
            Movement move = runBackwards(nodes.get(i), nodes.get(i + 1), cost);
            if (move == null) {
                return true;
            } else {
                movements.add(move);
            }
        }
        return false;
    }

    private Movement runBackwards(PathNode srcNode, PathNode destNode, double cost) {
        BetterBlockPos src = new BetterBlockPos(srcNode.x, srcNode.y, srcNode.z);
        BetterBlockPos dest = new BetterBlockPos(destNode.x, destNode.y, destNode.z);

        // === ETHERWARP ===
        if (destNode.previousEdge == PathNode.PreviousEdge.ETHERWARP_DYNAMIC) {
            Movement ether = new MovementSkyblockEtherTransmission(context.getBaritone(), src, dest);
            double etherCost = ether.calculateCost(context);
            if (etherCost < ActionCosts.COST_INF) {
                ether.override(Math.min(etherCost, cost));
                return ether;
            }
            return null;
        }

        // === ANY-ANGLE PARKOUR (NEW) ===
        if (destNode.previousEdge == PathNode.PreviousEdge.PARKOUR_DYNAMIC) {
            // Reconstruct parkour movement using stored angle + vertical offset
            double angle = destNode.parkourAngle;      // radians, 0 = +Z, clockwise
            int vertOffset = destNode.parkourVertOffset; // -2 to +2

            MovementParkour parkour = MovementParkour.cost(context, src, angle, vertOffset);

            assert parkour != null;
            double parkourCost = parkour.calculateCost(context);
            if (parkourCost < ActionCosts.COST_INF && parkour.getDest().equals(dest)) {
                parkour.override(Math.min(parkourCost, cost));
                return parkour;
            }
            // Fallback: try to find any valid parkour to this dest if params drifted
            return tryReconstructParkourFallback(src, dest, cost);
        }

        // === STANDARD MOVEMENTS (enum-based) ===
        if (destNode.previousEdge == PathNode.PreviousEdge.NORMAL_MOVE && destNode.previousMove != null) {
            Movement move = destNode.previousMove.apply0(context, src);
            if (move != null && move.getDest().equals(dest)) {
                move.override(Math.min(move.calculateCost(context), cost));
                return move;
            }
            return null;
        }

        // === LEGACY FALLBACK: brute-force enum search ===
        for (Moves moves : Moves.values()) {
            if (moves == Moves.PARKOUR_DYNAMIC) continue; // Skip dynamic placeholder
            Movement move = moves.apply0(context, src);
            if (move != null && move.getDest().equals(dest)) {
                move.override(Math.min(move.calculateCost(context), cost));
                return move;
            }
        }

        // === ETHERWARP FALLBACK ===
        Movement etherFallback = new MovementSkyblockEtherTransmission(context.getBaritone(), src, dest);
        double etherCost = etherFallback.calculateCost(context);
        if (etherCost < ActionCosts.COST_INF) {
            etherFallback.override(Math.min(etherCost, cost));
            return etherFallback;
        }

        Helper.HELPER.logDebug("Movement became impossible during calculation " + src + " " + dest + " " + dest.subtract(src));
        return null;
    }

    /**
     * Fallback: try to reconstruct a valid parkour movement when exact params aren't available.
     * Used when node metadata is incomplete or floating-point drift occurs.
     */
    private Movement tryReconstructParkourFallback(BetterBlockPos src, BetterBlockPos dest, double cost) {
        // Try cardinal angles first (most common)
        double[] cardinalAngles = {0, Math.PI/2, Math.PI, -Math.PI/2};
        for (double angle : cardinalAngles) {
            for (int vert : new int[]{-1, 0, 1}) {
                MovementParkour test = MovementParkour.cost(context, src, angle, vert);
                if (test != null && test.getDest().equals(dest)) {
                    double testCost = test.calculateCost(context);
                    if (testCost < ActionCosts.COST_INF) {
                        test.override(Math.min(testCost, cost));
                        return test;
                    }
                }
            }
        }
        // Try diagonal angles
        double[] diagonalAngles = {Math.PI/4, -Math.PI/4, Math.PI/4 + Math.PI, -Math.PI/4 + Math.PI};
        for (double angle : diagonalAngles) {
            for (int vert : new int[]{-1, 0, 1}) {
                MovementParkour test = MovementParkour.cost(context, src, angle, vert);
                if (test != null && test.getDest().equals(dest)) {
                    double testCost = test.calculateCost(context);
                    if (testCost < ActionCosts.COST_INF) {
                        test.override(Math.min(testCost, cost));
                        return test;
                    }
                }
            }
        }
        return null;
    }

    @Override
    public IPath postProcess() {
        if (verified) {
            throw new IllegalStateException("Path must not be verified twice");
        }
        verified = true;
        boolean failed = assembleMovements();
        movements.forEach(m -> m.checkLoadedChunk(context));

        if (failed) {
            CutoffPath res = new CutoffPath(this, movements().size());
            if (res.movements().size() != movements.size()) {
                throw new IllegalStateException("Path has wrong size after cutoff");
            }
            return res;
        }
        sanityCheck();
        return this;
    }

    @Override
    public List<IMovement> movements() {
        if (!verified) {
            throw new IllegalStateException("Path not yet verified");
        }
        return Collections.unmodifiableList(movements);
    }

    @Override
    public List<BetterBlockPos> positions() {
        return Collections.unmodifiableList(path);
    }

    @Override
    public int getNumNodesConsidered() {
        return numNodes;
    }

    @Override
    public BetterBlockPos getSrc() {
        return start;
    }

    @Override
    public BetterBlockPos getDest() {
        return end;
    }
}