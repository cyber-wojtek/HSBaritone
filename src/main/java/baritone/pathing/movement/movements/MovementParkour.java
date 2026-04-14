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

package baritone.pathing.movement.movements;

import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.Set;

/**
 * Sprint-jump (parkour) movement — fully tick-based simulation.
 *
 * Physics are ported exactly from the Python reference simulator:
 *
 *   Ground tick order (per tick, simulate_runup):
 *     gv = gv * f + groundAccel
 *     gx += gv * (-sinYaw)
 *     gz += gv *   cosYaw
 *     // per-tick check: if fwdPos > MAX_FWD_POS → skip this combo
 *
 *   Jump tick order (apply_jump_tick — one extra ground tick at the moment of jumping):
 *     gv_launch = gv * GROUND_F + groundAccel
 *     jx = gx + gv_launch * (-sinYaw);  jz = gz + gv_launch * cosYaw
 *     vx = (gv_launch + h_boost) * (-sinYaw)   // h_boost = SPRINT_JUMP_BOOST if sprint
 *     vz = (gv_launch + h_boost) *   cosYaw
 *     vy = JUMP_VELOCITY + jumpBoost * INCREMENT
 *
 *   Air tick order (simulate_airborne, starting from jx/jz):
 *     vx += airAccel * (-sinYaw); vz += airAccel * cosYaw  // air acceleration
 *     nx = jx + vx; ny = y + vy; nz = jz + vz             // candidate position
 *     landing check: y >= landY && ny <= landY             // "will-cross" style
 *       → interpolate to exact Y=landY crossing: lx = x + f*(nx-x), lz = z + f*(nz-z)
 *       → if [lx,lz] in [dest-0.1, dest+1.1] → SUCCESS
 *       → if ny < landY-0.5 → bail (fell past)
 *     x=nx; y=ny; z=nz                                     // commit move
 *     vx *= AIR_DRAG; vz *= AIR_DRAG                      // horizontal drag
 *     vy = (vy - GRAVITY) * VERTICAL_DRAG                  // vertical update
 *
 * Runtime execution uses a position-based jump trigger: the sim records the
 * exact forward projection of the player at the moment of jumping (post-jump-tick
 * position jx/jz), and the RUN_UP phase fires the jump as soon as the player's
 * real forward projection reaches or exceeds that value.
 */
public class MovementParkour extends Movement {

    /**
     * Master debug switch — set true to enable all parkour diagnostic output.
     * Each category can also be toggled independently below.
     */
    private static final boolean DEBUG = true;

    // Fine-grained toggles (only take effect when DEBUG == true)
    /** Log the best plan found by calculateCost and why others were rejected. */
    private static final boolean DEBUG_COST     = true;
    /** Log every phase transition and jump-trigger evaluation at runtime. */
    private static final boolean DEBUG_PHASES   = true;
    /** Log each air-tick position/velocity during the sim (verbose). */
    private static final boolean DEBUG_AIR_TICKS = false;
    /** Log per-tick runtime projFwd/projLat while in RUN_UP. */
    private static final boolean DEBUG_RUNUP     = false;

    // ── Minecraft physics constants (match Python sim exactly) ───────────────
    private static final double AIR_DRAG             = 0.91;
    private static final double GRAVITY              = 0.08;
    private static final double VERTICAL_DRAG        = 0.98;
    private static final double AIR_ACCEL_BASE       = 0.02;
    private static final double SPRINT_MULTIPLIER    = 1.3;
    private static final double AIR_ACCEL_SPRINT     = AIR_ACCEL_BASE * SPRINT_MULTIPLIER; // 0.026
    private static final double AIR_ACCEL_WALK       = AIR_ACCEL_BASE;                     // 0.02
    private static final double JUMP_VELOCITY        = 0.42;
    private static final double SPRINT_JUMP_BOOST    = 0.2;   // horizontal boost on sprint-jump
    private static final double JUMP_BOOST_INCREMENT = 0.1;   // per Jump Boost amplifier level
    private static final float YAW_PACKET_EPSILON_DEGREES = 0.71f; // 360.0f / 256.0f / 2.0f

    // Ground physics — default block slipperiness = 0.6
    // f = slipperiness * AIR_DRAG = 0.6 * 0.91 = 0.546
    // groundAccel = 0.1 * speedMult * (0.6 / slipperiness)^3
    // For default blocks (slip=0.6): (0.6/0.6)^3 = 1, so accel = 0.1 * speedMult
    private static final double DEFAULT_SLIPPERINESS  = 0.6;
    private static final double GROUND_F              = DEFAULT_SLIPPERINESS * AIR_DRAG; // 0.546
    private static final double GROUND_ACCEL_SPRINT_NO_BASE   = SPRINT_MULTIPLIER * Math.pow(0.6 / DEFAULT_SLIPPERINESS, 3); // 0.13
    private static final double GROUND_ACCEL_WALK_NO_BASE     = 1.0             * Math.pow(0.6 / DEFAULT_SLIPPERINESS, 3); // 0.10

    // ── Player geometry ───────────────────────────────────────────────────────
    private static final double PLAYER_WIDTH  = 0.6;
    private static final double PLAYER_HEIGHT = 1.8;

    /**
     * Landing pad margin — player centre must land within [dest - LAND_PAD, dest + 1 + LAND_PAD].
     * Matches Python LAND_PAD = PLAYER_WIDTH / 2 = 0.3.
     */
    private static final double LAND_PAD = 0.3;

    private static final float YAW_ISSUED_SENTINEL = Float.POSITIVE_INFINITY;

    /**
     * Maximum forward extension from source block centre before the player is
     * fully off the block.  0.5 (half block) + 0.3 (half player width) = 0.8
     * Matches Python's MAX_FWD_POS.
     */
    private static final double MAX_FWD_POS = 0.5 + PLAYER_WIDTH / 2.0; // 0.8

    // ── Simulation limits ─────────────────────────────────────────────────────
    private static final int    MAX_RUNUP_TICKS    = 20;
    private static final int    MAX_AIR_TICKS      = 60;

    // Start-position sweep: -0.65 … +0.20 in steps of 0.05 (matches Python)
    private static final double START_SWEEP_MIN    = -0.65;
    private static final double START_SWEEP_MAX    =  0.20;
    private static final double START_SWEEP_STEP   =  0.05;

    // Lateral sweep
    private static final double MAX_LATERAL_OFFSET = 0.2;
    private static final double LATERAL_SWEEP_STEP = 0.05;

    // ── Geometry ──────────────────────────────────────────────────────────────
    private final int    dx, dz, dy;
    private final double horizDist;
    private final boolean ascend;
    private final double yawRad;
    private final double sinYaw, cosYaw;
    // ── Results of calculateCost ──────────────────────────────────────────────
    private double  startFwdOffset;
    private double  startLatOffset;
    private boolean bestIsSprint;
    private int     bestN;           // ground ticks to run before jumping (kept for reference / fallback)
    private float settledYaw = Float.NaN;
    private boolean lastInputWasForward = false;

    /**
     * Forward projection (along jump direction, relative to src block centre)
     * of the player position at the exact tick the sim fires the jump.
     * At runtime we jump as soon as projFwd >= jumpTriggerFwd instead of
     * counting ticks, making the trigger immune to tick-rate drift.
     */
    private double jumpTriggerFwd;

    /**
     * Lateral projection at jump time (same coordinate system as jumpTriggerFwd).
     * Stored for debugging / future lateral-correction logic.
     */
    private double jumpTriggerLat;

    // ── Runtime state ─────────────────────────────────────────────────────────
    private enum Phase { BACK_UP, SETTLE, RUN_UP, AIRBORNE }
    private Phase phase         = Phase.BACK_UP;
    private int   settleTimer   = 0;
    private int   airborneTimer = 0;
    private double lastSettleFwd = 0.0;
    private int   runupTick     = 0;  // counts ground ticks since RUN_UP began

    // =========================================================================

    public MovementParkour(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest) {
        super(baritone, src, dest, new BetterBlockPos[0], null);
        this.dx        = dest.x - src.x;
        this.dz        = dest.z - src.z;
        this.dy        = dest.y - src.y;
        this.horizDist = Math.sqrt((double) dx * dx + (double) dz * dz);
        this.ascend    = dy > 0;
        this.yawRad    = Math.atan2(-dx, dz);
        this.sinYaw = snapToExact(Math.sin(yawRad));
        this.cosYaw = snapToExact(Math.cos(yawRad));
    }

    // =========================================================================
    //  Cost calculation
    // =========================================================================

    @Override
    public double calculateCost(CalculationContext context) {
        if (!context.allowParkour)               return COST_INF;
        if (src.distanceTo(dest) > 5.2) return COST_INF;
        if (dy > 1)                              return COST_INF;
        if (phase != Phase.BACK_UP)                 return COST_INF; // parkour in progress (do NOT try to start a new one)


        if (!MovementHelper.canWalkOn(context, src.x, src.y - 1, src.z))       return COST_INF;
        if (!MovementHelper.canWalkThrough(context, src.x, src.y + 1, src.z))  return COST_INF;
        if (!MovementHelper.canWalkThrough(context, src.x, src.y + 2, src.z))  return COST_INF;
        if (!isValidLanding(context, dest.x, dest.y, dest.z))                  return COST_INF;
        if (ascend && !MovementHelper.canWalkThrough(context, dest.x, dest.y + 2, dest.z)) return COST_INF;

        // No parkour needed if there is solid ground the whole way
        boolean hasGap = false;
        for (int i = 1; i < (int) horizDist; i++) {
            int cx = src.x + (int) Math.round(dx * i / horizDist);
            int cz = src.z + (int) Math.round(dz * i / horizDist);
            if (!MovementHelper.canWalkOn(context, cx, src.y - 1, cz)) { hasGap = true; break; }
        }
        if (!hasGap) return COST_INF;

        int    bestN         = Integer.MAX_VALUE;
        double bestFwd       = 0;
        double bestLat       = 0;
        boolean bestSprint   = true;
        double bestJumpFwd   = 0;
        double bestJumpLat   = 0;
        double bestScoreErr  = Double.MAX_VALUE;
        boolean bestSuccess  = false;

        double destCx = dest.x + 0.5;
        double destCz = dest.z + 0.5;

        // Sweep both sprint and walk physics, matching the Python solver exactly.
        for (boolean isSprint : new boolean[]{true, false}) {
            final double groundAccelNoBase = isSprint ? GROUND_ACCEL_SPRINT_NO_BASE : GROUND_ACCEL_WALK_NO_BASE;
            final double groundAccel = groundAccelNoBase * context.playerMovementSpeed;
            logDebug(String.format("Testing %s: groundAccel=%.4f (no base %.4f)", isSprint ? "SPRINT" : "walk", groundAccel, groundAccelNoBase));

            for (int N = 0; N <= MAX_RUNUP_TICKS; N++) {
                for (int latIdx = 0; latIdx <= (int)Math.floor(MAX_LATERAL_OFFSET / LATERAL_SWEEP_STEP) + 1; latIdx++) {
                    double absLat = latIdx * LATERAL_SWEEP_STEP;

                    double[] lats = (absLat < 1e-9) ? new double[]{0.0} : new double[]{absLat, -absLat};

                    for (double lat : lats) {
                        // Sweep start positions from back to front (−0.65 … +0.20).
                        for (double startFwd = START_SWEEP_MIN; startFwd <= START_SWEEP_MAX + 1e-9; startFwd += START_SWEEP_STEP) {

                            // ── Ground simulation (matches Python simulate_runup) ──────────
                            double gx = src.x + 0.5 + startFwd * (-sinYaw) + lat * cosYaw;
                            double gz = src.z + 0.5 + startFwd *   cosYaw  + lat * sinYaw;
                            double gv = 0.0;
                            boolean runupValid = true;

                            for (int k = 0; k < N; k++) {
                                double vMid = gv + groundAccel;  // pre-friction speed
                                gx += vMid * (-sinYaw);          // pos advances by pre-friction
                                gz += vMid *   cosYaw;
                                gv  = vMid * GROUND_F;           // store post-friction velocity
                                // Mid-runup check: don't walk off the edge (matches Python)
                                double midFwd = (gx - (src.x + 0.5)) * (-sinYaw)
                                        + (gz - (src.z + 0.5)) * cosYaw;
                                if (midFwd > MAX_FWD_POS) { runupValid = false; break; }
                            }
                            if (!runupValid) continue;

                            // Post-runup check: player must still be on source block.
                            double fwdPos = (gx - (src.x + 0.5)) * (-sinYaw)
                                    + (gz - (src.z + 0.5)) *   cosYaw;
                            if (fwdPos > MAX_FWD_POS) continue;

                            if (startFwd < -0.5) {
                                int behindX = src.x - (int) Math.signum(dx);
                                int behindZ = src.z - (int) Math.signum(dz);
                                if (!MovementHelper.canWalkOn(context,      behindX, src.y - 1, behindZ)) continue;
                                if (!MovementHelper.canWalkThrough(context, behindX, src.y,     behindZ)) continue;
                                if (!MovementHelper.canWalkThrough(context, behindX, src.y + 1, behindZ)) continue;
                            }

                            // ── Airborne simulation (includes apply_jump_tick internally) ────
                            double jumpHBoost = isSprint ? SPRINT_JUMP_BOOST : 0.0;
                            double vMidJump = gv + groundAccel;                    // pre-friction speed
                            double jx = gx + vMidJump * (-sinYaw);                // pos advances by pre-friction
                            double jz = gz + vMidJump *   cosYaw;
                            double gvLaunch = vMidJump * GROUND_F + jumpHBoost;   // stored dM + sprint boost
                            double jtf = (jx - (src.x + 0.5)) * (-sinYaw) + (jz - (src.z + 0.5)) * cosYaw;
                            if (jtf > MAX_FWD_POS) continue;
                            double simJumpFwd = jtf;
                            double simJumpLat = (jx - (src.x + 0.5)) *  cosYaw
                                    + (jz - (src.z + 0.5)) * sinYaw;

                            double[] air = simulateAirborne(
                                    context, gx, gz, gv, groundAccel,
                                    context.jumpBoostLevel, isSprint);
                            if (air == null) continue;

                            boolean landed = (air[0] > 0);
                            double  lx     = air[1];
                            double  lz     = air[2];

                            double ldx   = lx - destCx;
                            double ldz   = lz - destCz;
                            double err   = Math.sqrt(ldx * ldx + ldz * ldz);

                            boolean better = false;
                            if (landed && !bestSuccess) {
                                better = true;
                            } else if (landed == bestSuccess && err < bestScoreErr) {
                                better = true;
                            }

                            if (better) {
                                bestSuccess = landed;
                                bestScoreErr = err;
                                bestN = N;
                                bestFwd = startFwd;
                                bestLat = lat;
                                bestSprint = isSprint;
                                bestJumpFwd = simJumpFwd;
                                bestJumpLat = simJumpLat;
                                HELPER.logDebug(String.format(
                                        "[Parkour] NEW BEST bestSuccess=%s err=%.4f src=%s → dest=%s dist=%.2f dy=%d mode=%s N=%d startFwd=%.3f startLat=%.3f jumpTrigFwd=%.4f jumpTrigLat=%.4f",
                                        bestSuccess, err, src, dest, horizDist, dy,
                                        bestSprint ? "SPRINT" : "walk",
                                        bestN, bestFwd, bestLat, simJumpFwd, simJumpLat));
                            }
                        }
                    }
                }
            }
        }

        if (bestN == Integer.MAX_VALUE || !bestSuccess) {
            if (DEBUG && DEBUG_COST) {
                HELPER.logDebug(String.format("[Parkour] NO PLAN src=%s → dest=%s dist=%.2f dy=%d",
                        src, dest, horizDist, dy));
            }
            return COST_INF;
        }

        this.startFwdOffset  = bestFwd;
        this.startLatOffset  = bestLat;
        this.bestIsSprint    = bestSprint;
        this.bestN           = bestN;
        this.jumpTriggerFwd  = bestJumpFwd;
        this.jumpTriggerLat  = bestJumpLat;

        double cost = bestN + horizDist * 2;
        if (DEBUG && DEBUG_COST) {
            HELPER.logDebug(String.format(
                    "[Parkour] PLAN src=%s → dest=%s dist=%.2f dy=%d mode=%s N=%d startFwd=%.3f startLat=%.3f jumpTrigFwd=%.4f jumpTrigLat=%.4f cost=%.2f",
                    src, dest, horizDist, dy,
                    bestSprint ? "SPRINT" : "walk",
                    bestN, bestFwd, bestLat,
                    bestJumpFwd, bestJumpLat, cost));
        }
        return cost;
    }

    private static double snapToExact(double v) {
        if (Math.abs(v) < 1e-10) return 0.0;
        if (Math.abs(v - 1.0) < 1e-10) return 1.0;
        if (Math.abs(v + 1.0) < 1e-10) return -1.0;
        return v;
    }

    // =========================================================================
    //  Airborne simulation — exact port of Python simulate_airborne
    // =========================================================================

    /**
     * Simulates the player from (gx, src.y, gz) through the jump tick and then
     * airborne, matching the Python apply_jump_tick + simulate_airborne exactly.
     *
     * apply_jump_tick (one extra ground tick before becoming airborne):
     *   gv_launch = gv * GROUND_F + groundAccel
     *   jx = gx + gv_launch * (-sinYaw)
     *   jz = gz + gv_launch *   cosYaw
     *   vx = (gv_launch + h_boost) * (-sinYaw)    // h_boost = SPRINT_JUMP_BOOST if sprint
     *   vz = (gv_launch + h_boost) *   cosYaw
     *   vy = JUMP_VELOCITY + jumpBoost * INCREMENT
     *
     * simulate_airborne tick order:
     *   1. vx += airAccel*(-sinYaw); vz += airAccel*cosYaw
     *   2. nx = x+vx; ny = y+vy; nz = z+vz
     *   3. landing check: y>=landY && ny<=landY
     *      → interpolate exact crossing: lx = x + f*(nx-x), lz = z + f*(nz-z)
     *      → if [lx,lz] in [dest-PAD, dest+1+PAD] → SUCCESS
     *      → if ny < landY-0.5 → bail
     *   4. x=nx; y=ny; z=nz
     *   5. vx*=AIR_DRAG; vz*=AIR_DRAG; vy=(vy-GRAVITY)*VERTICAL_DRAG
     *   6. if |vy|<0.005 → vy=0
     *   7. early exit if y < landY-2
     *
     * @param gx          player X after N ground ticks (pre-jump-tick)
     * @param gz          player Z after N ground ticks (pre-jump-tick)
     * @param gv          ground speed scalar after N ground ticks (pre-jump-tick)
     * @param groundAccel ground acceleration for current mode (sprint/walk)
     * @param jumpBoostLevel Jump Boost potion level (0 = none)
     * @param isSprint    whether sprint physics apply
     * @return double[]{successFlag, landX, landZ, ticks} or null if landing plane never reached
     */
    private double[] simulateAirborne(CalculationContext context,
                                      double gx, double gz,
                                      double gv,
                                      double groundAccel,
                                      int jumpBoostLevel,
                                      boolean isSprint) {
        double landY    = (double) dest.y;
        double landMinX = dest.x - LAND_PAD;
        double landMaxX = dest.x + 1.0 + LAND_PAD;
        double landMinZ = dest.z - LAND_PAD;
        double landMaxZ = dest.z + 1.0 + LAND_PAD;

        double jumpHBoost = isSprint ? SPRINT_JUMP_BOOST : 0.0;
        double airAccel   = isSprint ? AIR_ACCEL_SPRINT  : AIR_ACCEL_WALK;

        // ── apply_jump_tick: one more ground tick then jump ──────────────────────
        // MC order: accel first, pos advances by pre-friction speed, then friction stored.
        // Sprint boost added separately after friction (jumpFromGround addDeltaMovement).
        double vMidJump = gv + groundAccel;                  // pre-friction speed
        double jx = gx + vMidJump * (-sinYaw);               // pos advances by pre-friction
        double jz = gz + vMidJump *   cosYaw;
        double gvLaunch = vMidJump * GROUND_F + jumpHBoost;  // stored deltaMovement + sprint boost

        double vx = gvLaunch * (-sinYaw);
        double vz = gvLaunch *   cosYaw;

        // Guard: if the jump tick pushes us off the source block, skip this combo.
        double jtf = (jx - (src.x + 0.5)) * (-sinYaw) + (jz - (src.z + 0.5)) * cosYaw;
        if (jtf > MAX_FWD_POS) return null;


        double vy = JUMP_VELOCITY + jumpBoostLevel * JUMP_BOOST_INCREMENT;

        double x = jx, y = (double) src.y, z = jz;

        if (DEBUG && DEBUG_AIR_TICKS) {
            HELPER.logDebug(String.format("[Parkour][air] START  pos=(%.4f,%d,%.4f) gv=%.4f groundAccel=%.4f jumpBoost=%d",
                    gx, src.y, gz, gv, groundAccel, jumpBoostLevel));
        }

        for (int tick = 0; tick < MAX_AIR_TICKS; tick++) {

            // Step 1: Air acceleration
            vx += airAccel * (-sinYaw);
            vz += airAccel *   cosYaw;

            // Step 2: Candidate next position
            double nx = x + vx;
            double ny = y + vy;
            double nz = z + vz;

            double nxMin = nx - PLAYER_WIDTH/2, nxMax = nx + PLAYER_WIDTH/2;
            double nzMin = nz - PLAYER_WIDTH/2, nzMax = nz + PLAYER_WIDTH/2;
            double pxMin =  x - PLAYER_WIDTH/2, pxMax =  x + PLAYER_WIDTH/2;
            double pzMin =  z - PLAYER_WIDTH/2, pzMax =  z + PLAYER_WIDTH/2;

            // Step 2b: Obstruction check (swept AABB — catches diagonal corners)
            if (collidesWithBlocks(context, x, y, z, nx, ny, nz, tick)) {
                HELPER.logDebug(String.format("[Parkour][air] TICK %2d  COLLISION at (%.4f,%.4f,%.4f) → (%.4f,%.4f,%.4f), aborting sim",
                        tick, x, y, z, nx, ny, nz));
                return new double[]{0.0, nx, nz, tick + 1};
            }


            if (DEBUG && DEBUG_AIR_TICKS) {
                HELPER.logDebug(String.format("[Parkour][air] TICK %2d  pos=(%.4f,%.4f,%.4f) vel=(%.4f,%.4f,%.4f) → candidate=(%.4f,%.4f,%.4f)",
                        tick, x, y, z, vx, vy, vz, nx, ny, nz));
            }

            // Step 3: Will-cross landing check with Y-interpolation (matches Python).
            if (y >= landY && ny <= landY) {
                double f  = (y != ny) ? (y - landY) / (y - ny) : 0.0;
                double lx = x + f * (nx - x);
                double lz = z + f * (nz - z);

                boolean inBounds = lx >= landMinX && lx <= landMaxX && lz >= landMinZ && lz <= landMaxZ;
                if (DEBUG && DEBUG_AIR_TICKS) {
                    HELPER.logDebug(String.format("[Parkour][air]   tick=%2d  WILL-CROSS  f=%.4f  land=(%.4f,%.4f)  bounds=[%.2f..%.2f, %.2f..%.2f]  inBounds=%s",
                            tick, f, lx, lz,
                            landMinX, landMaxX, landMinZ, landMaxZ, inBounds));
                }

                if (inBounds) {
                    return new double[]{1.0, lx, lz, tick + 1};
                }
                if (ny < landY - 0.5) {
                    if (DEBUG && DEBUG_AIR_TICKS) {
                        HELPER.logDebug(String.format("[Parkour][air]   tick=%2d  FELL PAST  ny=%.4f landY=%.1f",
                                tick, ny, landY));
                    }
                    HELPER.logDebug(String.format("[Parkour][air] TICK %2d  FELL PAST landing plane at Y=%.1f (ny=%.4f), aborting sim",
                            tick, landY, ny));
                    return new double[]{0.0, nx, nz, tick + 1};
                }
            }

            // Step 4: Commit move
            x = nx;
            y = ny;
            z = nz;

            // Step 5 & 6: Drag, gravity, vy clamp
            // On tick 0 the player is still on the ground when horizontal motion is
            // processed — Minecraft applies ground friction (GROUND_F) not air drag.
            vx *= AIR_DRAG;
            vz *= AIR_DRAG;
            vy  = (vy - GRAVITY) * VERTICAL_DRAG;
            if (Math.abs(vy) < 0.005) vy = 0.0;

            // Step 7: Early termination
            if (y < landY - 2.0) {
                //HELPER.logDebug(String.format("[Parkour][air] TICK %2d  TOO LOW  y=%.4f landY=%.1f, aborting sim",
                //        tick, y, landY));
                return new double[]{0.0, x, z, tick + 1};
            }
            if (y < -100) break;
        }

        return null;
    }

    private AABB makeAABB(double x, double y, double z) {
        return new AABB(
                x - PLAYER_WIDTH / 2.0,
                y,
                z - PLAYER_WIDTH / 2.0,
                x + PLAYER_WIDTH / 2.0,
                y + PLAYER_HEIGHT,
                z + PLAYER_WIDTH / 2.0
        );
    }

    private AABB sweptAABB(double x, double y, double z,
                           double nx, double ny, double nz) {

        double minX = Math.min(x, nx) - PLAYER_WIDTH / 2.0;
        double maxX = Math.max(x, nx) + PLAYER_WIDTH / 2.0;

        double minY = Math.min(y, ny);
        double maxY = Math.max(y, ny) + PLAYER_HEIGHT;

        double minZ = Math.min(z, nz) - PLAYER_WIDTH / 2.0;
        double maxZ = Math.max(z, nz) + PLAYER_WIDTH / 2.0;

        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    // =========================================================================
    //  Block collision
    // =========================================================================

    private boolean collidesWithBlocks(CalculationContext context,
                                       double x, double y, double z,
                                       double nx, double ny, double nz,
                                       int airTick) {
        AABB box = sweptAABB(x, y, z, nx, ny, nz);

        int x0 = Mth.floor(box.minX), x1 = Mth.floor(box.maxX);
        int y0 = Mth.floor(box.minY), y1 = Mth.floor(box.maxY);
        int z0 = Mth.floor(box.minZ), z1 = Mth.floor(box.maxZ);

        for (int bx = x0; bx <= x1; bx++) {
            for (int by = y0; by <= y1; by++) {
                for (int bz = z0; bz <= z1; bz++) {
                    if (bx == src.x && bz == src.z
                        && (by == src.y - 1 || by == src.y || by == src.y + 1)) {
                        continue;
                    }
                    if (bx == dest.x && bz == dest.z
                            && (by == dest.y - 1 || by == dest.y || by == dest.y + 1)) {
                        continue;
                    }
                    if (!MovementHelper.canWalkThrough(context, bx, by, bz)) return true;
                }
            }
        }
        return false;
    }

    // =========================================================================
    //  Landing validity
    // =========================================================================

    private static boolean isValidLanding(CalculationContext context, int x, int y, int z) {
        if (!MovementHelper.canWalkOn(context, x, y - 1, z))      return false;
        if (!MovementHelper.canWalkThrough(context, x, y, z))     return false;
        if (!MovementHelper.canWalkThrough(context, x, y + 1, z)) return false;
        if (MovementHelper.isBottomSlab(context.get(x, y - 1, z))) {
            return MovementHelper.canWalkThrough(context, x, y, z) &&
                    MovementHelper.canWalkThrough(context, x, y + 1, z);
        }
        return true;
    }

    // =========================================================================
    //  Static factory
    // =========================================================================

    public static MovementParkour cost(CalculationContext context, BetterBlockPos src, double yawRad, int dy) {
        if (!context.allowParkour) return null;
        if (dy < -1 || dy > 1)    return null;

        double sin = Math.sin(yawRad);
        double cos = Math.cos(yawRad);

        for (int dist = 5; dist >= 1; dist--) {
            int destX = src.x - (int) Math.round(sin * dist);
            int destZ = src.z + (int) Math.round(cos * dist);
            int destY = src.y + dy;

            if (destY < context.world.getMinY() || destY > context.world.getMaxY()) continue;
            if (!context.bsi.worldContainsLoadedChunk(destX, destZ))               continue;
            if (!isValidLanding(context, destX, destY, destZ))                     continue;
            if (dy == 1 && !MovementHelper.canWalkThrough(context, destX, destY + 1, destZ)) continue;

            MovementParkour m = new MovementParkour(
                    context.getBaritone(), src, new BetterBlockPos(destX, destY, destZ));
            double c = m.calculateCost(context);
            if (c < COST_INF) {
                m.override(c);
                return m;
            }
        }
        return null;
    }

    // =========================================================================
    //  Runtime execution
    // =========================================================================

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) return state;
        if (ctx.playerFeet().equals(dest)) {
            if (DEBUG && DEBUG_PHASES) {
                HELPER.logDebug(String.format("[Parkour][SUCCESS] src=%s → dest=%s  airborne ticks=%d",
                        src, dest, airborneTimer));
            }
            return state.setStatus(MovementStatus.SUCCESS);
        }

        float jumpYaw = (float) Math.toDegrees(yawRad);
        float currentYaw = ctx.playerRotations().getYaw();

        if (Float.isNaN(settledYaw)) {
            // Tick 1: issue target
            state.setTarget(new MovementState.MovementTarget(
                    new Rotation(jumpYaw, ctx.playerRotations().getPitch()), true));
            settledYaw = YAW_ISSUED_SENTINEL;
        } else if (settledYaw == YAW_ISSUED_SENTINEL) {
            // Tick 2: read back what engine produced
            settledYaw = currentYaw;
            state.setTarget(new MovementState.MovementTarget(
                    new Rotation(settledYaw, ctx.playerRotations().getPitch()), true));
        } else {
            if (Math.abs(settledYaw - currentYaw) > YAW_PACKET_EPSILON_DEGREES) {
                settledYaw = Float.NaN;
                state.setTarget(new MovementState.MovementTarget(
                        new Rotation(jumpYaw, ctx.playerRotations().getPitch()), true));
            }
        }

        // Project the player's actual world position onto the src-relative
        // forward/lateral axes — the same coordinate frame the sim uses.
        double px      = ctx.player().position().x - (src.x + 0.5);
        double pz      = ctx.player().position().z - (src.z + 0.5);
        double projFwd = px * (-sinYaw) + pz *  cosYaw;
        double projLat = px *   cosYaw  + pz *  sinYaw;

        switch (phase) {

            case BACK_UP: {
                // Seed lastSettleFwd on the very first tick so posDelta is meaningful.
                if (settleTimer == 0) {
                    lastSettleFwd = projFwd;
                    settleTimer   = 1;
                }

                boolean fwdOk = Math.abs(projFwd - startFwdOffset) <= 0.06;
                boolean latOk = Math.abs(projLat - startLatOffset) <= 0.06;

                double posDelta = projFwd - lastSettleFwd;
                lastSettleFwd = projFwd;

                if (fwdOk && latOk) {
                    if (posDelta < 0.06) {
                        runupTick = 0;
                        phase = Phase.RUN_UP;
                        if (DEBUG && DEBUG_PHASES) {
                            HELPER.logDebug(String.format("[Parkour][BACK_UP] DONE  projFwd=%.4f (target=%.3f)  delta=%.5f",
                                    projFwd, startFwdOffset, posDelta));
                        }
                    } else {
                        // In position but still moving — dampen based on direction
                        if (posDelta < 0) {
                            // Moving backwards — apply just a tiny forward to slow it but keep it drifting back
                            // i.e. do nothing, let drag handle it — we WANT slight backward drift
                        } else {
                            // Moving forwards — fight it back hard
                            state.setInput(Input.MOVE_BACK, true);
                        }
                    }
                } else {
                    // Not in position yet — continuous move toward target
                    if (projFwd > startFwdOffset + 0.06) {
                        state.setInput(Input.MOVE_BACK, true);
                    } else if (projFwd < startFwdOffset - 0.06) {
                        state.setInput(Input.MOVE_FORWARD, true);
                    }
                    if (projLat < startLatOffset - 0.06) state.setInput(Input.MOVE_LEFT,  true);
                    else if (projLat > startLatOffset + 0.06) state.setInput(Input.MOVE_RIGHT, true);
                }
                break;
            }

            case RUN_UP: {
                if (bestIsSprint) state.setInput(Input.SPRINT, true);
                state.setInput(Input.MOVE_FORWARD, true);

                // CHECK position FIRST — before any movement this tick.
                // This makes N=0 (jump from standing start) fire immediately,
                // and N>0 fire after exactly N ground ticks, matching the sim.
                if (projFwd >= jumpTriggerFwd) {
                    if (DEBUG && DEBUG_PHASES) {
                        HELPER.logDebug(String.format("[Parkour][RUN_UP]  JUMP TRIGGERED  projFwd=%.4f >= jumpTrigFwd=%.4f  projLat=%.4f (jumpTrigLat=%.4f)",
                                projFwd, jumpTriggerFwd, projLat, jumpTriggerLat));
                        // print all the calculateCost info
                        HELPER.logDebug(String.format("[Parkour][RUN_UP]  src=%s dest=%s  startFwd=%.3f startLat=%.3f  jumpTrigFwd=%.4f jumpTrigLat=%.4f bestN=%d  sprint=%s",
                                src, dest, startFwdOffset, startLatOffset, jumpTriggerFwd, jumpTriggerLat, bestN, bestIsSprint ? "YES" : "no"));
                    }
                    state.setInput(Input.JUMP, true);
                    phase = Phase.AIRBORNE;
                    airborneTimer = 0;
                } else {
                    if (DEBUG && DEBUG_RUNUP) {
                        HELPER.logDebug(String.format("[Parkour][RUN_UP]  tick %d  projFwd=%.4f (target=%.4f)  projLat=%.4f (target=%.3f)",
                                runupTick, projFwd, jumpTriggerFwd, projLat, jumpTriggerLat));
                    }
                }
                // If not triggered, the game engine advances the player this tick
                // (MOVE_FORWARD is already set above), so next call projFwd will be higher.
                break;
            }

            case AIRBORNE: {
                if (bestIsSprint) state.setInput(Input.SPRINT, true);
                state.setInput(Input.MOVE_FORWARD, true);
                airborneTimer++;
                if (DEBUG && DEBUG_PHASES) {
                    HELPER.logDebug(String.format("[Parkour][AIRBORNE]  tick %d  projFwd=%.4f (jumpTrigFwd=%.4f)  projLat=%.4f (jumpTrigLat=%.4f)  playerPos=(%.4f,%.4f,%.4f)  feetY=%d",
                            airborneTimer, projFwd, jumpTriggerFwd, projLat, jumpTriggerLat,
                            ctx.player().position().x, ctx.player().position().y, ctx.player().position().z,
                            ctx.playerFeet().y));
                }
                if (airborneTimer > 80) {
                    if (DEBUG && DEBUG_PHASES) {
                        HELPER.logDebug(String.format("[Parkour][AIRBORNE] TIMEOUT after %d ticks — UNREACHABLE  src=%s dest=%s",
                                airborneTimer, src, dest));
                    }
                    return state.setStatus(MovementStatus.UNREACHABLE);
                }
                break;
            }
        }

        return state;
    }

    // =========================================================================
    //  Boilerplate
    // =========================================================================

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        Set<BetterBlockPos> set = new HashSet<>();
        set.add(src);
        set.add(dest);
        for (int i = 0; i <= (int) horizDist; i++) {
            int cx = src.x + (int) Math.round(dx * i / horizDist);
            int cz = src.z + (int) Math.round(dz * i / horizDist);
            set.add(new BetterBlockPos(cx, src.y, cz));
            set.add(new BetterBlockPos(cx, src.y + 1, cz));
        }
        for (double fwd = START_SWEEP_MIN - 1.0; fwd <= START_SWEEP_MAX + 1e-9; fwd += START_SWEEP_STEP) {
            for (double lat = -MAX_LATERAL_OFFSET; lat <= MAX_LATERAL_OFFSET + 1e-9; lat += LATERAL_SWEEP_STEP) {
                double px = src.x + 0.5 + fwd * (-sinYaw) + lat * cosYaw;
                double pz = src.z + 0.5 + fwd *  cosYaw   + lat * sinYaw;
                int bx = (int) Math.floor(px);
                int bz = (int) Math.floor(pz);
                set.add(new BetterBlockPos(bx, src.y, bz));
                set.add(new BetterBlockPos(bx, src.y + 1, bz));
            }
        }

        return set;
    }

    @Override
    public void reset() {
        super.reset();
        phase         = Phase.BACK_UP;
        settleTimer   = 0;
        airborneTimer = 0;
        this.startFwdOffset  = 0;
        this.startLatOffset  = 0;
        this.bestIsSprint    = false;
        this.bestN           = Integer.MAX_VALUE;
        this.jumpTriggerFwd  = Double.MAX_VALUE;
        this.jumpTriggerLat  = 0;
        runupTick = 0;
        settledYaw = Float.NaN;
        lastInputWasForward = false;
        lastSettleFwd = 0.0;
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        return false;
    }
}
