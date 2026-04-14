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
import baritone.api.utils.Helper;
import baritone.api.utils.SkyblockNucleusTempleArrowPhysics;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.block.DispenserBlock;

import java.util.ArrayList;
import java.util.List;
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
    private List<BlockPos> dispenserPoses = null; // all dispensers threatening this corridor
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

        // DEBUG — log every tick so we can see what's happening
        Helper.HELPER.logDebug("[TrapAvoid] state=" + state
                + " player=" + playerPos
                + " dispensers=" + dispenserPoses
                + " freshArrow=" + anyFreshArrow()
        );

        if (dispenserPoses == null || dispenserPoses.isEmpty()) {
            state = State.SAFE;
            return true;
        }

        switch (state) {
            case SAFE:
                // Enter danger zone if near ANY threatening dispenser
                for (BlockPos dispenser : dispenserPoses) {
                    if (playerPos.distSqr(dispenser) <= DANGER_ZONE_DIST_SQ) {
                        state = State.WAITING_FOR_ARROW;
                        break;
                    }
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
        if (dispenserPoses != null) return;
        dispenserPoses = scanForFacingDispensers(playerPos);
        if (dispenserPoses != null && !dispenserPoses.isEmpty()) {
            // immediately enter waiting state — don't wait for a second tick to notice
            state = State.WAITING_FOR_ARROW;
        }
    }

    private List<BlockPos> scanForFacingDispensers(BlockPos playerPos) {
        BlockPos goalPos = getGoalPosition();
        Helper.HELPER.logDebug("[TrapAvoid] scanning for dispensers around player=" + playerPos
                + " with goal=" + goalPos
                + " range=" + settings.skyblockNucleusTempleTrapDetectionRange.value);

        if (goalPos == null) {
            Helper.HELPER.logDebug("[TrapAvoid] no goal position — scan aborted");
            return null;
        }

        List<BlockPos> found = new ArrayList<>();
        int range = settings.skyblockNucleusTempleTrapDetectionRange.value;

        for (int ox = -range; ox <= range; ox++) {
            for (int oy = -range; oy <= range; oy++) {
                for (int oz = -range; oz <= range; oz++) {
                    BlockPos candidate = playerPos.offset(ox, oy, oz);
                    assert mc.level != null;
                    var blockState = mc.level.getBlockState(candidate);
                    if (!(blockState.getBlock() instanceof DispenserBlock)) continue;

                    var facing = blockState.getValue(DispenserBlock.FACING);

                    // Dispenser centre and player centre in world space
                    double dcx = candidate.getX() + 0.5;
                    double dcy = candidate.getY() + 0.5;
                    double dcz = candidate.getZ() + 0.5;
                    double pcx = playerPos.getX() + 0.5;
                    double pcy = playerPos.getY() + 0.5;
                    double pcz = playerPos.getZ() + 0.5;

                    // Is this a purely vertical dispenser (UP/DOWN)?
                    boolean isFacingVertically = (facing.getStepY() != 0
                            && facing.getStepX() == 0
                            && facing.getStepZ() == 0);

                    double perpDist;
                    boolean dispenserInFront;

                    if (!isFacingVertically) {
                        // ---- Horizontal dispenser: flatten to X/Z ----
                        double rdx = facing.getStepX();
                        double rdz = facing.getStepZ();

                        double tpx = pcx - dcx;
                        double tpz = pcz - dcz;
                        double t   = tpx * rdx + tpz * rdz;

                        double rx = pcx - (dcx + t * rdx);
                        double rz = pcz - (dcz + t * rdz);
                        perpDist       = Math.sqrt(rx * rx + rz * rz);
                        dispenserInFront = (t > 0);

                    } else {
                        // ---- Vertical dispenser: full 3-D check ----
                        double rdy = facing.getStepY();

                        double tpy = pcy - dcy;
                        double t   = tpy * rdy;

                        double rx = pcx - dcx;
                        double rz = pcz - dcz;
                        perpDist       = Math.sqrt(rx * rx + rz * rz);
                        dispenserInFront = (t > 0);
                    }

                    Helper.HELPER.logDebug("[TrapAvoid] candidate dispenser at " + candidate
                            + " facing=" + facing
                            + " vertical=" + isFacingVertically
                            + " perpDist=" + String.format("%.3f", perpDist)
                            + " inFront=" + dispenserInFront);

                    if (dispenserInFront && perpDist <= CYLINDER_RADIUS) {
                        Helper.HELPER.logDebug("[TrapAvoid] >>> MATCH — dispenser threatens corridor");
                        found.add(candidate);
                    }
                }
            }
        }
        return found.isEmpty() ? null : found;
    }

    /** Returns true if any arrow in the level appears freshly fired from any tracked dispenser. */
    private boolean anyFreshArrow() {
        if (dispenserPoses == null || dispenserPoses.isEmpty()) return false;
        int total = 0, fresh = 0;
        assert mc.level != null;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof AbstractArrow arrow)) continue;
            total++;
            if (isFreshlyFired(arrow)) fresh++;
        }
        Helper.HELPER.logDebug("[TrapAvoid] total arrows=" + total + " fresh arrows=" + fresh);
        return fresh > 0;
    }

    private boolean arrowHasCleared(BlockPos playerPos) {
        if (dispenserPoses == null || dispenserPoses.isEmpty()) return false;
        assert mc.level != null;

        // Dispensers fire perpendicular to corridor: just check if any fresh arrow
        // from a tracked dispenser is currently inside the corridor cylinder.
        // No direction projection needed — if it's in the cylinder, it's a threat.
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof AbstractArrow arrow)) continue;
            if (!isFreshlyFired(arrow)) continue;

            // Check if arrow is inside corridor cylinder around player position
            double dx = arrow.getX() - (playerPos.getX() + 0.5);
            double dy = arrow.getY() - (playerPos.getY() + 0.5);
            double dz = arrow.getZ() - (playerPos.getZ() + 0.5);

            // Cylinder: ignore Y, just check X/Z distance
            double perpDistSq = dx*dx + dz*dz;
            if (perpDistSq <= CYLINDER_RADIUS * CYLINDER_RADIUS) {
                Helper.HELPER.logDebug("[TrapAvoid] arrowHasCleared: arrow in cylinder at ("
                        + String.format("%.1f,%.1f,%.1f", arrow.getX(), arrow.getY(), arrow.getZ()) + ")");
                return false;
            }
        }
        return true;
    }

    /**
     * True if this arrow is freshly fired from any tracked dispenser.
     * Checks spawn proximity + velocity threshold to avoid stale/decayed arrows.
     */
    private boolean isFreshlyFired(AbstractArrow arrow) {
        var vel = arrow.getDeltaMovement();
        double speed = vel.length();

        // Skip stale arrows: drag decay reduces velocity; fresh dispenser arrows start ~1.1 blocks/tick
        if (speed < 0.9) return false;

        // Detect if arrow was just fired: check if position is near ANY tracked dispenser
        for (BlockPos dispenser : dispenserPoses) {
            double dx = arrow.getX() - (dispenser.getX() + 0.5);
            double dy = arrow.getY() - (dispenser.getY() + 0.5);
            double dz = arrow.getZ() - (dispenser.getZ() + 0.5);
            double distSq = dx*dx + dy*dy + dz*dz;
            if (distSq <= CYLINDER_RADIUS * CYLINDER_RADIUS) {
                return true;
            }
        }
        return false;
    }

    private BlockPos getGoalPosition() {
        Goal target = wrappedGoal;
        if (target instanceof GoalWrapperSkyblockNucleusTempleTrapAvoid wrapper) {
            target = wrapper.getWrapped();
        }
        if (target instanceof GoalBlock gb) return gb.getGoalPos();
        return null;
    }

    private void commit() {
        committed     = true;
        state         = State.SAFE;
        dispenserPoses  = null;
    }

    // -------------------------------------------------------------------------
    // Accessors / lifecycle
    // -------------------------------------------------------------------------

    public State    getState()          { return state; }
    public List<BlockPos> getDispenserPoses()   { return dispenserPoses; }
    public Goal     getWrappedGoal()    { return wrappedGoal; }

    /** Call when the path is recalculated to reset avoidance state. */
    public void reset() {
        state         = State.SAFE;
        dispenserPoses  = null;
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