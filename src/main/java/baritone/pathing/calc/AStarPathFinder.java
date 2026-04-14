/*
 * This file is part of Baritone.
 * ... [license header unchanged] ...
 */

package baritone.pathing.calc;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.SettingsUtil;
import baritone.pathing.calc.openset.BinaryHeapOpenSet;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Moves;
import baritone.pathing.movement.movements.MovementParkour;
import baritone.pathing.movement.movements.MovementSkyblockEtherTransmission;
import baritone.utils.pathing.BetterWorldBorder;
import baritone.utils.pathing.Favoring;
import baritone.utils.pathing.MutableMoveResult;

import java.util.Optional;

/**
 * The actual A* pathfinding with any-angle parkour support.
 *
 * @author leijurv
 */
public final class AStarPathFinder extends AbstractNodeCostSearch {

    private static final int[][] ETHERWARP_DIRECTION_STEPS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
            {1, 0, 1}, {1, 0, -1}, {-1, 0, 1}, {-1, 0, -1},
            {1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1},
            {1, -1, 0}, {-1, -1, 0}, {0, -1, 1}, {0, -1, -1},
            {1, 1, 1}, {1, 1, -1}, {-1, 1, 1}, {-1, 1, -1},
            {1, -1, 1}, {1, -1, -1}, {-1, -1, 1}, {-1, -1, -1}
    };

    private static final int[] ETHERWARP_STEP_MULTIPLIERS = {8, 16, 24, 32, 40, 48, 56, 61};

    // Parkour sampling config
    private static final double[] PARKOUR_ANGLE_STEPS = {Math.toRadians(30), Math.toRadians(15), Math.toRadians(7.5)};
    private static final int[] PARKOUR_VERT_OFFSETS = {0, 1, -1}; // Priority order

    private final Favoring favoring;
    private final CalculationContext calcContext;

    public AStarPathFinder(BetterBlockPos realStart, int startX, int startY, int startZ, Goal goal, Favoring favoring, CalculationContext context) {
        super(realStart, startX, startY, startZ, goal, context);
        this.favoring = favoring;
        this.calcContext = context;
    }

    @Override
    protected Optional<IPath> calculate0(long primaryTimeout, long failureTimeout) {
        int minY = calcContext.world.dimensionType().minY();
        int height = calcContext.world.dimensionType().height();
        startNode = getNodeAtPosition(startX, startY, startZ, BetterBlockPos.longHash(startX, startY, startZ));
        startNode.cost = 0;
        startNode.combinedCost = startNode.estimatedCostToGoal;
        BinaryHeapOpenSet openSet = new BinaryHeapOpenSet();
        openSet.insert(startNode);
        double[] bestHeuristicSoFar = new double[COEFFICIENTS.length];
        for (int i = 0; i < bestHeuristicSoFar.length; i++) {
            bestHeuristicSoFar[i] = startNode.estimatedCostToGoal;
            bestSoFar[i] = startNode;
        }
        MutableMoveResult res = new MutableMoveResult();
        BetterWorldBorder worldBorder = new BetterWorldBorder(calcContext.world.getWorldBorder());
        long startTime = System.currentTimeMillis();
        boolean slowPath = Baritone.settings().slowPath.value;
        if (slowPath) {
            logDebug("slowPath is on, path timeout will be " + Baritone.settings().slowPathTimeoutMS.value + "ms instead of " + primaryTimeout + "ms");
        }
        long primaryTimeoutTime = startTime + (slowPath ? Baritone.settings().slowPathTimeoutMS.value : primaryTimeout);
        long failureTimeoutTime = startTime + (slowPath ? Baritone.settings().slowPathTimeoutMS.value : failureTimeout);
        boolean failing = true;
        int numNodes = 0;
        int numMovementsConsidered = 0;
        int numEmptyChunk = 0;
        boolean isFavoring = !favoring.isEmpty();
        int timeCheckInterval = 1 << 6;
        int pathingMaxChunkBorderFetch = Baritone.settings().pathingMaxChunkBorderFetch.value;
        double minimumImprovement = Baritone.settings().minimumImprovementRepropagation.value ? MIN_IMPROVEMENT : 0;
        Moves[] allMoves = Moves.values();

        while (!openSet.isEmpty() && numEmptyChunk < pathingMaxChunkBorderFetch && !cancelRequested) {
            if ((numNodes & (timeCheckInterval - 1)) == 0) {
                long now = System.currentTimeMillis();
                if (now - failureTimeoutTime >= 0 || (!failing && now - primaryTimeoutTime >= 0)) {
                    break;
                }
            }
            if (slowPath) {
                try {
                    Thread.sleep(Baritone.settings().slowPathTimeDelayMS.value);
                } catch (InterruptedException ignored) {}
            }
            PathNode currentNode = openSet.removeLowest();
            mostRecentConsidered = currentNode;
            numNodes++;

            if (goal.isInGoal(currentNode.x, currentNode.y, currentNode.z)) {
                logDebug("Took " + (System.currentTimeMillis() - startTime) + "ms, " + numMovementsConsidered + " movements considered");
                return Optional.of(new Path(realStart, startNode, currentNode, numNodes, goal, calcContext));
            }

            // === 1. BASIC ENUM MOVES ===
            for (Moves moves : allMoves) {
                if (moves == Moves.PARKOUR_DYNAMIC) continue; // Skip placeholder, handled separately

                int newX = currentNode.x + moves.xOffset;
                int newZ = currentNode.z + moves.zOffset;
                if ((newX >> 4 != currentNode.x >> 4 || newZ >> 4 != currentNode.z >> 4) && !calcContext.isLoaded(newX, newZ)) {
                    if (!moves.dynamicXZ) numEmptyChunk++;
                    continue;
                }
                if (!moves.dynamicXZ && !worldBorder.entirelyContains(newX, newZ)) continue;
                if (currentNode.y + moves.yOffset > height || currentNode.y + moves.yOffset < minY) continue;

                res.reset();
                moves.apply(calcContext, currentNode.x, currentNode.y, currentNode.z, res);
                numMovementsConsidered++;

                if (!relaxNode(currentNode, res, moves, PathNode.PreviousEdge.NORMAL_MOVE, res.cost, minimumImprovement,
                        openSet, bestHeuristicSoFar, isFavoring, failing, worldBorder,
                        moves.dynamicXZ, moves.dynamicY, 0, 0)) {
                    if (moves.dynamicXZ) numEmptyChunk++;
                }
            }

            // === 2. ANY-ANGLE PARKOUR (Dynamic Sampling) ===
            if (calcContext.allowParkour && calcContext.canSprint) {
                expandParkourNeighbors(currentNode, worldBorder, minY, height, res, openSet, bestHeuristicSoFar,
                        isFavoring, failing, minimumImprovement, new int[]{numMovementsConsidered}, new int[]{numEmptyChunk});
            }

            // === 3. ETHERWARP (Existing) ===
            if (calcContext.skyblockTransportEnabled
                    && Baritone.settings().skyblockEtherTransmissionEnabled.value
                    && calcContext.skyblockHasAotv
                    && calcContext.skyblockMana >= Baritone.settings().skyblockEtherTransmissionManaCost.value) {
                for (int[] dir : ETHERWARP_DIRECTION_STEPS) {
                    for (int multiplier : ETHERWARP_STEP_MULTIPLIERS) {
                        int targetX = currentNode.x + dir[0] * multiplier;
                        int targetY = currentNode.y + dir[1] * multiplier;
                        int targetZ = currentNode.z + dir[2] * multiplier;
                        if (targetY > height || targetY < minY || !worldBorder.entirelyContains(targetX, targetZ)) continue;
                        if (!calcContext.isLoaded(targetX, targetZ)) continue;

                        double actionCost = MovementSkyblockEtherTransmission.cost(calcContext, currentNode.x, currentNode.y, currentNode.z, targetX, targetY, targetZ);
                        if (actionCost >= ActionCosts.COST_INF) continue;

                        long hashCode = BetterBlockPos.longHash(targetX, targetY, targetZ);
                        if (isFavoring) actionCost *= favoring.calculate(hashCode);

                        PathNode neighbor = getNodeAtPosition(targetX, targetY, targetZ, hashCode);
                        double tentativeCost = currentNode.cost + actionCost;
                        if (neighbor.cost - tentativeCost > minimumImprovement) {
                            neighbor.previous = currentNode;
                            neighbor.previousEdge = PathNode.PreviousEdge.ETHERWARP_DYNAMIC;
                            neighbor.previousMove = null;
                            neighbor.cost = tentativeCost;
                            neighbor.combinedCost = tentativeCost + neighbor.estimatedCostToGoal;
                            if (neighbor.isOpen()) {
                                openSet.update(neighbor);
                            } else {
                                openSet.insert(neighbor);
                            }
                            for (int i = 0; i < COEFFICIENTS.length; i++) {
                                double heuristic = neighbor.estimatedCostToGoal + neighbor.cost / COEFFICIENTS[i];
                                if (bestHeuristicSoFar[i] - heuristic > minimumImprovement) {
                                    bestHeuristicSoFar[i] = heuristic;
                                    bestSoFar[i] = neighbor;
                                    if (failing && getDistFromStartSq(neighbor) > MIN_DIST_PATH * MIN_DIST_PATH) {
                                        failing = false;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (cancelRequested) return Optional.empty();

        System.out.println(numMovementsConsidered + " movements considered");
        System.out.println("Open set size: " + openSet.size());
        System.out.println("PathNode map size: " + mapSize());
        System.out.println((int) (numNodes * 1.0 / ((System.currentTimeMillis() - startTime) / 1000F)) + " nodes per second");

        Optional<IPath> result = bestSoFar(true, numNodes);
        if (result.isPresent()) {
            logDebug("Took " + (System.currentTimeMillis() - startTime) + "ms, " + numMovementsConsidered + " movements considered");
        }
        return result;
    }

    /**
     * Expand any-angle parkour neighbors using adaptive angle sampling.
     */
    private void expandParkourNeighbors(PathNode current, BetterWorldBorder worldBorder,
                                        int minY, int height, MutableMoveResult res,
                                        BinaryHeapOpenSet openSet, double[] bestHeuristicSoFar,
                                        boolean isFavoring, boolean failing, double minimumImprovement,
                                        int[] numMovementsConsidered, int[] numEmptyChunk) {
        BetterBlockPos src = new BetterBlockPos(current.x, current.y, current.z);

        // Fixed angle step (no goal biasing since Goal may not have position)
        // Use medium resolution by default: 15° = 24 directions
        final double ANGLE_STEP = Math.toRadians(3);
        final int[] VERT_OFFSETS = {0, 1, -1, -2, -3, -4, -5, -6, -7, -8, -9, -10}; // Priority: flat, up, down

        // Sample all angles uniformly
        for (double angle = 0; angle < Math.PI * 2; angle += ANGLE_STEP) {
            for (int vert : VERT_OFFSETS) {
                MovementParkour parkour = MovementParkour.cost(calcContext, src, angle, vert);
                if (parkour == null) continue;

                double cost = parkour.calculateCost(calcContext);
                if (cost >= ActionCosts.COST_INF) continue;

                BetterBlockPos dest = parkour.getDest();

                // Chunk/world border validation
                if ((dest.x >> 4 != src.x >> 4 || dest.z >> 4 != src.z >> 4) && !calcContext.isLoaded(dest.x, dest.z)) {
                    numEmptyChunk[0]++;
                    continue;
                }
                if (!worldBorder.entirelyContains(dest.x, dest.z)) continue;
                if (dest.y > height || dest.y < minY) continue;

                // Favoring
                long hashCode = BetterBlockPos.longHash(dest.x, dest.y, dest.z);
                if (isFavoring) cost *= favoring.calculate(hashCode);

                // Relax neighbor
                PathNode neighbor = getNodeAtPosition(dest.x, dest.y, dest.z, hashCode);
                double tentativeCost = current.cost + cost;

                if (neighbor.cost - tentativeCost > minimumImprovement) {
                    neighbor.previous = current;
                    neighbor.previousEdge = PathNode.PreviousEdge.PARKOUR_DYNAMIC;
                    neighbor.previousMove = null;
                    neighbor.parkourAngle = angle;           // Store for Path.java reconstruction
                    neighbor.parkourVertOffset = vert;
                    neighbor.cost = tentativeCost;
                    neighbor.combinedCost = tentativeCost + neighbor.estimatedCostToGoal;

                    if (neighbor.isOpen()) {
                        openSet.update(neighbor);
                    } else {
                        openSet.insert(neighbor);
                    }

                    // Update best-so-far
                    for (int i = 0; i < COEFFICIENTS.length; i++) {
                        double heuristic = neighbor.estimatedCostToGoal + neighbor.cost / COEFFICIENTS[i];
                        if (bestHeuristicSoFar[i] - heuristic > minimumImprovement) {
                            bestHeuristicSoFar[i] = heuristic;
                            bestSoFar[i] = neighbor;
                            if (failing && getDistFromStartSq(neighbor) > MIN_DIST_PATH * MIN_DIST_PATH) {
                                failing = false;
                            }
                        }
                    }
                }
                numMovementsConsidered[0]++;
            }
        }
    }

    /**
     * Helper to relax a node with common validation logic.
     * Returns true if the node was successfully relaxed.
     */
    private boolean relaxNode(PathNode current, MutableMoveResult res, Moves move,
                              PathNode.PreviousEdge edgeType, double actionCost,
                              double minimumImprovement, BinaryHeapOpenSet openSet,
                              double[] bestHeuristicSoFar, boolean isFavoring, boolean failing,
                              BetterWorldBorder worldBorder, boolean dynamicXZ, boolean dynamicY,
                              double parkourAngle, int parkourVert) {
        if (actionCost >= ActionCosts.COST_INF) return false;
        if (actionCost <= 0 || Double.isNaN(actionCost)) {
            throw new IllegalStateException(String.format(
                    "%s from %s %s %s calculated implausible cost %s",
                    move, SettingsUtil.maybeCensor(current.x),
                    SettingsUtil.maybeCensor(current.y), SettingsUtil.maybeCensor(current.z), actionCost));
        }

        // Destination validation
        if (dynamicXZ && !worldBorder.entirelyContains(res.x, res.z)) return false;
        if (!dynamicXZ && (res.x != current.x + move.xOffset || res.z != current.z + move.zOffset)) {
            throw new IllegalStateException("Movement ended at unexpected x/z");
        }
        if (!dynamicY && res.y != current.y + move.yOffset) {
            throw new IllegalStateException("Movement ended at unexpected y");
        }

        long hashCode = BetterBlockPos.longHash(res.x, res.y, res.z);
        if (isFavoring) actionCost *= favoring.calculate(hashCode);

        PathNode neighbor = getNodeAtPosition(res.x, res.y, res.z, hashCode);
        double tentativeCost = current.cost + actionCost;

        if (neighbor.cost - tentativeCost > minimumImprovement) {
            neighbor.previous = current;
            neighbor.previousEdge = edgeType;
            neighbor.previousMove = move;
            neighbor.parkourAngle = parkourAngle;
            neighbor.parkourVertOffset = parkourVert;
            neighbor.cost = tentativeCost;
            neighbor.combinedCost = tentativeCost + neighbor.estimatedCostToGoal;

            if (neighbor.isOpen()) {
                openSet.update(neighbor);
            } else {
                openSet.insert(neighbor);
            }

            for (int i = 0; i < COEFFICIENTS.length; i++) {
                double heuristic = neighbor.estimatedCostToGoal + neighbor.cost / COEFFICIENTS[i];
                if (bestHeuristicSoFar[i] - heuristic > minimumImprovement) {
                    bestHeuristicSoFar[i] = heuristic;
                    bestSoFar[i] = neighbor;
                    if (failing && getDistFromStartSq(neighbor) > MIN_DIST_PATH * MIN_DIST_PATH) {
                        failing = false;
                    }
                }
            }
            return true;
        }
        return false;
    }
}