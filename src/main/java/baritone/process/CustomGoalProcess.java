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

package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalSkyblockNucleusTempleTrapAvoid;
import baritone.api.pathing.goals.GoalSkyblockNucleusTempleWithTrapAvoidance;
import net.minecraft.core.BlockPos;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.process.RouteSegmentRange;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.PathingCommandContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * As set by ExampleBaritoneControl or something idk
 *
 * @author leijurv
 */
public final class CustomGoalProcess extends BaritoneProcessHelper implements ICustomGoalProcess {

    /**
     * The current goal
     */
    private Goal goal;

    /**
     * The most recent goal. Not invalidated upon {@link #onLostControl()}
     */
    private Goal mostRecentGoal;

    /**
     * Remaining checkpoints for a multi-goal route, excluding {@link #goal}.
     */
    private final Deque<Goal> route = new ArrayDeque<>();

    /**
     * Route segment ranges where Skyblock abilities are disabled.
     */
    private final List<RouteSegmentRange> noSkyblockAbilityRanges = new ArrayList<>();

    /**
     * Active segment index where {@code i} corresponds to {@code goals[i] -> goals[i + 1]}.
     * -1 means no active checkpoint-to-checkpoint segment yet.
     */
    private int activeRouteSegmentIndex = -1;

    /**
     * The current process state.
     *
     * @see State
     */
    private State state;

    public CustomGoalProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void setGoal(Goal goal) {
        this.route.clear();
        this.noSkyblockAbilityRanges.clear();
        this.activeRouteSegmentIndex = -1;
        // Wrap goal with trap avoidance only if goal explicitly requests it AND setting is enabled
        if (goal instanceof GoalSkyblockNucleusTempleTrapAvoid && Baritone.settings().skyblockNucleusTempleTrapAvoidanceEnabled.value) {
            this.goal = new GoalSkyblockNucleusTempleWithTrapAvoidance(goal, Baritone.settings());
        } else {
            this.goal = goal;
        }
        this.mostRecentGoal = goal;
        if (baritone.getElytraProcess().isActive()) {
            baritone.getElytraProcess().pathTo(goal);
        }
        if (this.state == State.NONE) {
            this.state = State.GOAL_SET;
        }
        if (this.state == State.EXECUTING) {
            this.state = State.PATH_REQUESTED;
        }
    }

    @Override
    public void path() {
        this.state = State.PATH_REQUESTED;
    }

    @Override
    public Goal getGoal() {
        return this.goal;
    }

    @Override
    public Goal mostRecentGoal() {
        return this.mostRecentGoal;
    }

    @Override
    public void setGoalAndPath(Goal... goals) {
        if (goals == null || goals.length == 0) {
            this.setGoal(null);
            this.path();
            return;
        }
        setGoalAndPath(Arrays.asList(goals));
    }

    @Override
    public void setGoalAndPath(List<Goal> goals) {
        this.setGoalAndPath(goals, Collections.emptyList());
    }

    @Override
    public void setGoalAndPath(List<Goal> goals, List<RouteSegmentRange> noSkyblockAbilityRanges) {
        if (goals == null || goals.isEmpty()) {
            this.setGoal(null);
            this.path();
            return;
        }
        this.route.clear();
        this.noSkyblockAbilityRanges.clear();
        this.activeRouteSegmentIndex = -1;
        Goal first = null;
        Goal lastNonNull = null;
        int normalizedGoalCount = 0;
        for (Goal routeGoal : goals) {
            if (routeGoal == null) {
                continue;
            }
            if (first == null) {
                first = routeGoal;
            } else {
                this.route.addLast(routeGoal);
            }
            lastNonNull = routeGoal;
            normalizedGoalCount++;
        }
        if (first == null) {
            this.setGoal(null);
            this.path();
            return;
        }
        setNoSkyblockAbilityRanges(noSkyblockAbilityRanges, normalizedGoalCount);
        // Wrap first goal with trap avoidance only if it explicitly requests it AND setting is enabled
        if (first != null && first instanceof GoalSkyblockNucleusTempleTrapAvoid && Baritone.settings().skyblockNucleusTempleTrapAvoidanceEnabled.value) {
            this.goal = new GoalSkyblockNucleusTempleWithTrapAvoidance(first, Baritone.settings());
        } else {
            this.goal = first;
        }
        this.mostRecentGoal = lastNonNull;
        if (baritone.getElytraProcess().isActive()) {
            baritone.getElytraProcess().pathTo(this.goal);
        }
        if (this.state == State.NONE) {
            this.state = State.GOAL_SET;
        }
        if (this.state == State.EXECUTING) {
            this.state = State.PATH_REQUESTED;
        }
        this.path();
    }

    @Override
    public List<Goal> getGoalRoute() {
        return Collections.unmodifiableList(new ArrayList<>(this.route));
    }

    @Override
    public boolean isActive() {
        return this.state != State.NONE;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        switch (this.state) {
            case GOAL_SET:
                return new PathingCommand(this.goal, PathingCommandType.CANCEL_AND_SET_GOAL);
            case PATH_REQUESTED:
                // return FORCE_REVALIDATE_GOAL_AND_PATH just once
                PathingCommand ret = createPathingCommand(PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
                this.state = State.EXECUTING;
                return ret;
            case EXECUTING:
                if (calcFailed) {
                    onLostControl();
                    return new PathingCommand(this.goal, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                // Check trap avoidance if enabled
                if (this.goal instanceof GoalSkyblockNucleusTempleWithTrapAvoidance trapGoal) {
                    BlockPos playerPos = ctx.playerFeet();
                    if (!trapGoal.shouldProceed(playerPos)) {
                        // Hold position while waiting for arrow to clear
                        return new PathingCommand(this.goal, PathingCommandType.REQUEST_PAUSE);
                    }
                }
                if (this.goal == null || (this.goal.isInGoal(ctx.playerFeet()) && this.goal.isInGoal(baritone.getPathingBehavior().pathStart()))) {
                    if (advanceRoute()) {
                        return createPathingCommand(PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
                    }
                    onLostControl(); // we're there xd
                    if (Baritone.settings().disconnectOnArrival.value) {
                        if (ctx.world() instanceof ClientLevel clientLevel) {
                            clientLevel.disconnect(Component.literal("[Baritone] Arrived at goal!"));
                        }
                    }
                    if (Baritone.settings().notificationOnPathComplete.value) {
                        logNotification("Pathing complete", false);
                    }
                    return new PathingCommand(this.goal, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                return createPathingCommand(PathingCommandType.SET_GOAL_AND_PATH);
            default:
                throw new IllegalStateException("Unexpected state " + this.state);
        }
    }

    @Override
    public void onLostControl() {
        this.state = State.NONE;
        this.goal = null;
        this.route.clear();
        this.noSkyblockAbilityRanges.clear();
        this.activeRouteSegmentIndex = -1;
    }

    @Override
    public String displayName0() {
        return "Custom Goal " + this.goal;
    }

    private boolean advanceRoute() {
        Goal nextGoal = this.route.pollFirst();
        if (nextGoal == null) {
            return false;
        }
        this.activeRouteSegmentIndex++;
        this.goal = nextGoal;
        if (baritone.getElytraProcess().isActive()) {
            baritone.getElytraProcess().pathTo(nextGoal);
        }
        return true;
    }

    private PathingCommand createPathingCommand(PathingCommandType commandType) {
        if (!isSkyblockAbilitiesBlockedOnCurrentSegment()) {
            return new PathingCommand(this.goal, commandType);
        }
        CalculationContext restrictedContext = new CalculationContext(baritone, true, false, false, false);
        return new PathingCommandContext(this.goal, commandType, restrictedContext);
    }

    private boolean isSkyblockAbilitiesBlockedOnCurrentSegment() {
        if (activeRouteSegmentIndex < 0 || noSkyblockAbilityRanges.isEmpty()) {
            return false;
        }
        for (RouteSegmentRange range : noSkyblockAbilityRanges) {
            if (range.includesSegment(activeRouteSegmentIndex)) {
                return true;
            }
        }
        return false;
    }

    private void setNoSkyblockAbilityRanges(List<RouteSegmentRange> ranges, int goalCount) {
        if (ranges == null || ranges.isEmpty() || goalCount < 2) {
            return;
        }
        int maxGoalIndex = goalCount - 1;
        for (RouteSegmentRange range : ranges) {
            if (range == null) {
                continue;
            }
            if (range.getToGoalIndexInclusive() > maxGoalIndex) {
                throw new IllegalArgumentException("Route segment range " + range.getFromGoalIndexInclusive() + "-" + range.getToGoalIndexInclusive() + " exceeds goal count " + goalCount);
            }
            this.noSkyblockAbilityRanges.add(range);
        }
    }

    protected enum State {
        NONE,
        GOAL_SET,
        PATH_REQUESTED,
        EXECUTING
    }
}
