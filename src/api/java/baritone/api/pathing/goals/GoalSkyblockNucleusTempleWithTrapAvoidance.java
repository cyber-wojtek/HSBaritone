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

import baritone.api.Settings;
import baritone.api.utils.SkyblockNucleusTempleArrowPhysics;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.block.DispenserBlock;

import java.util.Objects;

/**
 * A Goal decorator that adds arrow-avoidance behaviour for Skyblock Nucleus
 * Temple trap corridors.
 *
 * <p>This class is intentionally split into two concerns:
 * <ul>
 *   <li>The {@link Goal} interface methods delegate straight to the wrapped goal —
 *       the pathfinder sees no difference.</li>
 *   <li>The trap-avoidance logic lives in {@link #shouldProceed(BlockPos)}, which
 *       must be called each tick by the controller that owns this goal.  It is
 *       <em>not</em> part of the Goal contract; keeping it separate makes the
 *       boundary explicit.</li>
 * </ul>
 *
 * <h2>State machine</h2>
 * <pre>
 *   SAFE ──(enter danger zone)──► WAITING_FOR_ARROW
 *              ▲                         │
 *              │                  (arrow detected)
 *       (reset called)                   ▼
 *              └──────────────── ARROW_FIRED
 *                                        │
 *                               (arrow clears path)
 *                                        ▼
 *                                  (committed, SAFE)
 * </pre>
 */
public final class GoalSkyblockNucleusTempleWithTrapAvoidance implements Goal {

    public enum State {
        /** Path is clear, no trap nearby. */
        SAFE,
        /** Inside the danger zone; waiting for the dispenser to fire. */
        WAITING_FOR_ARROW,
        /** Arrow is in flight; waiting for it to clear the path. */
        ARROW_FIRED
    }

    // How close the player must be to a dispenser's aim-line to enter the danger zone (blocks²).
    private static final double DANGER_ZONE_DIST_SQ = 4.0;
    // Fallback timeout after an arrow fires before we give up waiting (ms).
    private static final long ARROW_TIMEOUT_MS = 2000;
    // Safety clearance past player centre before we consider the arrow gone (blocks).
    private static final double CLEARANCE_BLOCKS = 2.5;

    private final Goal       wrappedGoal;
    private final Settings   settings;
    private final Minecraft  mc;

    private State     state             = State.SAFE;
    private BlockPos  dispenserPos      = null;   // the actual dispenser block
    private long      arrowDetectedTime = 0;
    private boolean   committed         = false;  // once true, never block again

    private static final double CYLINDER_RADIUS = 2.5;

    public GoalSkyblockNucleusTempleWithTrapAvoidance(Goal wrappedGoal, Settings settings) {
        this.wrappedGoal = Objects.requireNonNull(wrappedGoal, "wrappedGoal");
        this.settings    = Objects.requireNonNull(settings,    "settings");
        this.mc          = Minecraft.getInstance();
    }

    // -------------------------------------------------------------------------
    // Goal delegation
    // -------------------------------------------------------------------------

    @Override public boolean isInGoal(int x, int y, int z) { return wrappedGoal.isInGoal(x, y, z); }
    @Override public double  heuristic(int x, int y, int z) { return wrappedGoal.heuristic(x, y, z); }
    @Override public double  heuristic()                    { return wrappedGoal.heuristic(); }

    // -------------------------------------------------------------------------
    // Trap avoidance tick update
    // -------------------------------------------------------------------------

    /**
     * Call once per tick from the game thread while this goal is active.
     *
     * @param playerPos current player block position
     * @return {@code true} if the pathfinder may advance; {@code false} to hold position
     */
    public boolean shouldProceed(BlockPos playerPos) {
        if (!settings.skyblockNucleusTempleTrapAvoidanceEnabled.value) return true;
        if (mc.level == null) return true;
        if (committed)        return true;

        ensureDispenserScanned(playerPos);

        if (dispenserPos == null) {
            state = State.SAFE;
            return true;
        }

        switch (state) {
            case SAFE:
                if (playerPos.distSqr(dispenserPos) <= DANGER_ZONE_DIST_SQ) {
                    state = State.WAITING_FOR_ARROW;
                }
                return true;

            case WAITING_FOR_ARROW:
                if (anyFreshArrow()) {
                    state             = State.ARROW_FIRED;
                    arrowDetectedTime = System.currentTimeMillis();
                }
                return false;

            case ARROW_FIRED:
                if (arrowHasCleared(playerPos)
                        || System.currentTimeMillis() - arrowDetectedTime > ARROW_TIMEOUT_MS) {
                    commit();
                    return true;
                }
                return false;

            default:
                return true;
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void ensureDispenserScanned(BlockPos playerPos) {
        if (dispenserPos != null) return;
        dispenserPos = scanForFacingDispenser(playerPos);
    }

    /**
     * Scans nearby blocks for a dispenser whose facing direction aims an arrow
     * at the player's current position.  Returns the dispenser's block position,
     * or {@code null} if none is found.
     */
    private BlockPos scanForFacingDispenser(BlockPos playerPos) {
        int range = settings.skyblockNucleusTempleTrapDetectionRange.value;

        for (int ox = -range; ox <= range; ox++) {
            for (int oy = -range; oy <= range; oy++) {
                for (int oz = -range; oz <= range; oz++) {
                    BlockPos candidate = playerPos.offset(ox, oy, oz);
                    var blockState = mc.level.getBlockState(candidate);
                    if (!(blockState.getBlock() instanceof DispenserBlock)) continue;

                    var facing = blockState.getValue(DispenserBlock.FACING);
                    int dx = facing.getStepX();
                    int dy = facing.getStepY();
                    int dz = facing.getStepZ();

                    BlockPos ray = candidate;
                    for (int step = 0; step < range; step++) {
                        ray = ray.offset(dx, dy, dz);
                        if (mc.level.getBlockState(ray).blocksMotion()) break;
                        if (ray.distSqr(playerPos) <= DANGER_ZONE_DIST_SQ) {
                            return candidate; // return the dispenser, not the ray position
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Returns true if any arrow in the level appears freshly fired from the known dispenser. */
    private boolean anyFreshArrow() {
        assert mc.level != null;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof AbstractArrow arrow
                    && isFreshlyFired(arrow)) {
                return true;
            }
        }
        return false;
    }

    private boolean arrowHasCleared(BlockPos playerPos) {
        BlockPos goalPos = getGoalPosition();
        if (goalPos == null) return false;

        double ox = playerPos.getX() + 0.5;
        double oy = playerPos.getY() + 0.5;
        double oz = playerPos.getZ() + 0.5;

        double rdx = goalPos.getX() + 0.5 - ox;
        double rdy = goalPos.getY() + 0.5 - oy;
        double rdz = goalPos.getZ() + 0.5 - oz;
        double len = Math.sqrt(rdx * rdx + rdy * rdy + rdz * rdz);
        if (len < 0.001) return true;
        rdx /= len; rdy /= len; rdz /= len;

        assert mc.level != null;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof AbstractArrow arrow)) continue;
            if (!isFreshlyFired(arrow)) continue;

            double ax = arrow.getX() - ox;
            double ay = arrow.getY() - oy;
            double az = arrow.getZ() - oz;

            double proj = ax * rdx + ay * rdy + az * rdz;
            double perpX = ax - proj * rdx;
            double perpY = ay - proj * rdy;
            double perpZ = az - proj * rdz;
            double perpDist = Math.sqrt(perpX * perpX + perpY * perpY + perpZ * perpZ);

            if (!(proj > 0 && perpDist > CYLINDER_RADIUS)) return false;
        }
        return true;
    }

    /**
     * True if this arrow is freshly fired rather than stuck or resting.
     * Speed² >= 0.5 and moving away from the known dispenser.
     */
    private boolean isFreshlyFired(AbstractArrow arrow) {
        var vel = arrow.getDeltaMovement();
        double speedSq = vel.x * vel.x + vel.y * vel.y + vel.z * vel.z;
        if (speedSq < 0.5) return false;

        double dx = arrow.getX() - (dispenserPos.getX() + 0.5);
        double dy = arrow.getY() - (dispenserPos.getY() + 0.5);
        double dz = arrow.getZ() - (dispenserPos.getZ() + 0.5);
        return (dx * vel.x + dy * vel.y + dz * vel.z) > 0;
    }

    private BlockPos getGoalPosition() {
        if (wrappedGoal instanceof GoalBlock gb) return gb.getGoalPos();
        return null;
    }

    private void commit() {
        committed     = true;
        state         = State.SAFE;
        dispenserPos  = null;
    }

    // -------------------------------------------------------------------------
    // Accessors / lifecycle
    // -------------------------------------------------------------------------

    public State    getState()          { return state; }
    public BlockPos getDispenserPos()   { return dispenserPos; }
    public Goal     getWrappedGoal()    { return wrappedGoal; }

    /** Call when the path is recalculated to reset avoidance state. */
    public void reset() {
        state         = State.SAFE;
        dispenserPos  = null;
        committed     = false;
        arrowDetectedTime = 0;
    }

    // -------------------------------------------------------------------------
    // Object
    // -------------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GoalSkyblockNucleusTempleWithTrapAvoidance that)) return false;
        return wrappedGoal.equals(that.wrappedGoal);
    }

    @Override public int    hashCode() { return Objects.hash(wrappedGoal); }
    @Override public String toString() { return "GoalWithTrapAvoidance{state=" + state + ", goal=" + wrappedGoal + "}"; }
}