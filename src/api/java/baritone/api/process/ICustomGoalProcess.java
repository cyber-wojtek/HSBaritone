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

package baritone.api.process;

import baritone.api.pathing.goals.Goal;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public interface ICustomGoalProcess extends IBaritoneProcess {

    /**
     * Sets the pathing goal
     *
     * @param goal The new goal
     */
    void setGoal(Goal goal);

    /**
     * Starts path calculation and execution.
     */
    void path();

    /**
     * @return The current goal
     */
    Goal getGoal();

    /**
     * @return The most recent set goal, which doesn't invalidate upon {@link #onLostControl()}
     */
    Goal mostRecentGoal();

    /**
     * Sets the goal and begins the path execution.
     *
     * @param goal The new goal
     */
    default void setGoalAndPath(Goal goal) {
        this.setGoal(goal);
        this.path();
    }

    /**
     * Sets an ordered route of goals and begins path execution.
     * Baritone will path to each goal in order, then finish when the last goal is reached.
     *
     * @param goals Ordered checkpoints, where the last element is the final destination
     */
    default void setGoalAndPath(Goal... goals) {
        if (goals == null || goals.length == 0) {
            this.setGoalAndPath((Goal) null);
            return;
        }
        this.setGoalAndPath(Arrays.asList(goals));
    }

    /**
     * Sets an ordered route of goals and begins path execution.
     * Baritone will path to each goal in order, then finish when the last goal is reached.
     *
     * @param goals Ordered checkpoints, where the last element is the final destination
     */
    default void setGoalAndPath(List<Goal> goals) {
        if (goals == null || goals.isEmpty()) {
            this.setGoalAndPath((Goal) null);
            return;
        }
        this.setGoalAndPath(goals.getLast());
    }

    /**
     * Sets an ordered route of goals and begins path execution.
     *
     * <p>Each {@link RouteSegmentRange} disables Skyblock abilities on route segments
     * between goal checkpoints. Segment index {@code i} represents {@code goals[i] -> goals[i + 1]}.</p>
     *
     * @param goals Ordered checkpoints, where the last element is the final destination
     * @param noSkyblockAbilityRanges Ranges of checkpoint indices where Skyblock abilities should be disabled
     */
    default void setGoalAndPath(List<Goal> goals, List<RouteSegmentRange> noSkyblockAbilityRanges) {
        this.setGoalAndPath(goals);
    }

    /**
     * @return Ordered checkpoints for the active route, excluding the current goal.
     */
    default List<Goal> getGoalRoute() {
        return Collections.emptyList();
    }
}
