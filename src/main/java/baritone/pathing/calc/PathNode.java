/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.calc;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.SettingsUtil;
import baritone.pathing.movement.Moves;

/**
 * A node in the path, containing the cost and steps to get to it.
 *
 * @author leijurv
 */
public final class PathNode {

    public enum PreviousEdge {
        NONE,
        NORMAL_MOVE,
        ETHERWARP_DYNAMIC,
        PARKOUR_DYNAMIC,
    }

    /**
     * The position of this node
     */
    public final int x;
    public final int y;
    public final int z;

    /**
     * Cached, should always be equal to goal.heuristic(pos)
     */
    public final double estimatedCostToGoal;

    /**
     * Total cost of getting from start to here
     * Mutable and changed by PathFinder
     */
    public double cost;

    /**
     * Should always be equal to estimatedCosttoGoal + cost
     * Mutable and changed by PathFinder
     */
    public double combinedCost;

    /**
     * In the graph search, what previous node contributed to the cost
     * Mutable and changed by PathFinder
     */
    public PathNode previous;

    public PreviousEdge previousEdge;

    public Moves previousMove;

    // In PathNode class fields:
    public double parkourAngle = 0;           // Radians, 0 = +Z, clockwise
    public int parkourVertOffset = 0;         // -2 to +2

    /**
     * Where is this node in the array flattenization of the binary heap? Needed for decrease-key operations.
     */
    public int heapPosition;

    public PathNode(int x, int y, int z, Goal goal) {
        this.previous = null;
        this.previousEdge = PreviousEdge.NONE;
        this.previousMove = null;
        this.cost = ActionCosts.COST_INF;
        this.estimatedCostToGoal = goal.heuristic(x, y, z);
        if (Double.isNaN(estimatedCostToGoal)) {
            throw new IllegalStateException(String.format(
                    "%s calculated implausible heuristic NaN at %s %s %s",
                    goal,
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)));
        }
        this.heapPosition = -1;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public boolean isOpen() {
        return heapPosition != -1;
    }

    /**
     * TODO: Possibly reimplement hashCode and equals. They are necessary for this class to function but they could be done better
     *
     * @return The hash code value for this {@link PathNode}
     */
    @Override
    public int hashCode() {
        return (int) BetterBlockPos.longHash(x, y, z);
    }

    @Override
    public boolean equals(Object obj) {
        // GOTTA GO FAST
        // ALL THESE CHECKS ARE FOR PEOPLE WHO WANT SLOW CODE
        // SKRT SKRT
        //if (obj == null || !(obj instanceof PathNode)) {
        //    return false;
        //}

        final PathNode other = (PathNode) obj;
        //return Objects.equals(this.pos, other.pos) && Objects.equals(this.goal, other.goal);

        return x == other.x && y == other.y && z == other.z;
    }
}
