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

package baritone.api.pathing.goals;

import net.minecraft.core.BlockPos;

/**
 * Wrapper that adds TrapAvoidanceGoal marker to any existing Goal.
 * <p>
 * Usage:
 * <pre>
 * Goal base = new GoalBlock(x, y, z);
 * Goal withTraps = new TrappedGoalWrapper(base);
 * baritone.getCustomGoalProcess().setGoal(withTraps);
 * </pre>
 */
public class GoalWrapperSkyblockNucleusTempleTrapAvoid implements Goal, GoalSkyblockNucleusTempleTrapAvoid {

    private final Goal wrapped;

    public GoalWrapperSkyblockNucleusTempleTrapAvoid(Goal wrapped) {
        this.wrapped = wrapped;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return wrapped.isInGoal(x, y, z);
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return wrapped.heuristic(x, y, z);
    }

    @Override
    public double heuristic() {
        return wrapped.heuristic();
    }

    @Override
    public boolean isInGoal(BlockPos pos) {
        return wrapped.isInGoal(pos);
    }

    @Override
    public double heuristic(BlockPos pos) {
        return wrapped.heuristic(pos);
    }

    public Goal getWrapped() {
        return wrapped;
    }
}
