/*
 * This file is part of Baritone.
 * ... [license header unchanged] ...
 */

package baritone.pathing.movement.movements;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import net.minecraft.world.phys.Vec3;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.utils.BlockStateInterface;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.WaterFluid;

import java.util.HashSet;
import java.util.Set;

public class MovementParkour extends Movement {

    private static final BetterBlockPos[] EMPTY = new BetterBlockPos[]{};

    // === PHYSICS CONSTANTS (Minecraft 1.19+) ===
    private static final double GRAVITY = 0.08;
    private static final double VERTICAL_DRAG = 0.98;
    private static final double HORIZONTAL_DRAG_AIR = 0.91;
    private static final double GROUND_ACCEL_BASE = 0.1;
    private static final double SPRINT_JUMP_BOOST = 0.2;
    private static final double AIR_ACCEL = 0.02;
    private static final double JUMP_VELOCITY_BASE = 0.42;

    // Slipperiness values
    private static final double SLIP_DEFAULT = 0.6;
    private static final double SLIP_ICE = 0.98;
    private static final double SLIP_SLIME = 0.8;

    // Movement type multipliers
    private static final double MT_SPRINT = 1.3;
    private static final double MT_WALK = 1.0;

    // Minimum horizontal speed (projected onto jump axis) needed at jump-off.
    // Sprint steady-state is ~0.364 m/t; we require at least 80% of that.
    private static final double MIN_JUMP_SPEED = 0.28;

    private final double angleRad;      // Direction in radians (0 = +Z, clockwise)
    private final int horizontalDist;   // Horizontal distance in blocks
    private final int vertDelta;        // Vertical displacement (-2 to +2)
    private final boolean requiresSprint;

    /**
     * Set to true once we have issued a back-up maneuver so we never loop.
     * Volatile because updateState is called every tick from the game thread.
     */
    private volatile boolean backupDone = false;

    /**
     * Cached backup target position, computed once when needed.
     * Null if no backup is required (player already has enough speed).
     */
    private volatile net.minecraft.world.phys.Vec3 cachedBackupTarget = null;

    public MovementParkour(IBaritone baritone, BetterBlockPos src, double angle, int dist, int vertDelta) {
        super(baritone, src, computeDest(src, angle, dist, vertDelta), EMPTY,
                computeLandingSupport(src, angle, dist, vertDelta));
        this.angleRad = angle;
        this.horizontalDist = dist;
        this.vertDelta = vertDelta;
        this.requiresSprint = dist >= 4 || vertDelta > 0;
    }

    private static BetterBlockPos computeDest(BetterBlockPos src, double angle, int dist, int vertDelta) {
        // Minecraft forward vector: dx = -sin(yaw), dz = cos(yaw)
        return new BetterBlockPos(
                (int) Math.floor(src.x - Math.sin(angle) * dist),
                src.y + vertDelta,
                (int) Math.floor(src.z + Math.cos(angle) * dist)
        );
    }

    private static BetterBlockPos computeLandingSupport(BetterBlockPos src, double angle, int dist, int vertDelta) {
        // Minecraft forward vector: dx = -sin(yaw), dz = cos(yaw)
        int lx = (int) Math.floor(src.x - Math.sin(angle) * dist);
        int lz = (int) Math.floor(src.z + Math.cos(angle) * dist);
        // The support block is always one below where the player's feet land.
        int ly = src.y + vertDelta - 1;
        return new BetterBlockPos(lx, ly, lz);
    }

    // === PUBLIC API ===

    public static MovementParkour cost(CalculationContext context, BetterBlockPos src, Direction direction) {
        return cost(context, src, directionToAngle(direction), 0);
    }

    /**
     * Calculate parkour with any angle and vertical offset.
     * @param angleRad Direction in radians (0 = +Z/south, clockwise)
     * @param vertOffset Vertical displacement (-2 to +2)
     */
    public static MovementParkour cost(CalculationContext context, BetterBlockPos src, double angleRad, int vertOffset) {
        MutableMoveResult res = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, angleRad, vertOffset, res);
        if (res.x == src.x && res.y == src.y && res.z == src.z) return null; // No valid move

        int dist = (int) Math.round(Math.hypot(res.x - src.x, res.z - src.z));
        int vDelta = res.y - src.y;
        return new MovementParkour(context.getBaritone(), src, angleRad, dist, vDelta);
    }

    private static double directionToAngle(Direction dir) {
        return switch (dir) {
            case SOUTH -> 0;
            case WEST -> Math.PI / 2;
            case NORTH -> Math.PI;
            case EAST -> -Math.PI / 2;
            default -> 0;
        };
    }

    // === CORE COST CALCULATION ===

    public static void cost(CalculationContext context, int x, int y, int z,
                            double angle, int vertOffset, MutableMoveResult res) {
        if (!context.allowParkour || (y >= context.world.getMaxY() && !context.allowJumpAtBuildLimit)) return;

        double sin = Math.sin(angle), cos = Math.cos(angle);
        BlockState standingOn = context.get(x, y - 1, z);

        // === PRE-FLIGHT CHECKS ===
        if (!preFlightChecks(context, x, y, z, sin, cos, standingOn)) return;

        // === PHYSICS SETUP ===
        double slipperiness = getSlipperiness(standingOn);
        double drag = HORIZONTAL_DRAG_AIR * slipperiness;
        double mt = context.canSprint ? MT_SPRINT : MT_WALK;
        double groundAccel = GROUND_ACCEL_BASE * mt * Math.pow(0.6 / slipperiness, 3);
        double maxDist = computeMaxJumpDistance(standingOn, context, slipperiness);

        if (Baritone.settings().parkourDebugTelemetry.value) {
            debugParkour("SIM: src=(" + x + "," + y + "," + z + ") angle=" + angle + " vertOffset=" + vertOffset + " maxDist=" + maxDist);
        }
        // === TRAJECTORY SIMULATION ===
        for (double dist = 2; dist <= maxDist; dist += 0.1) {
            TrajectoryResult result = simulateTrajectory(context, x, y, z, angle, dist, vertOffset,
                    slipperiness, drag, groundAccel, mt);
            if (result.valid) {
                // Don't use parkour for trivial flat jumps of 1-2 blocks - walking is always better
                if (vertOffset == 0 && dist <= 2) {
                    debugParkour("SIM: rejected trivial flat jump dist=" + dist);
                    continue;
                }
                res.set(result.destX, result.destY, result.destZ, result.cost + context.jumpPenalty);
                debugParkour("SIM: valid trajectory to (" + result.destX + "," + result.destY + "," + result.destZ + ") cost=" + result.cost);
                return;
            }
        }

        // === PARKOUR-PLACE FALLBACK ===
        if (context.allowParkourPlace) {
            debugParkour("FALLBACK: attempting parkour-place fallback");
            attemptParkourPlace(context, x, y, z, angle, vertOffset, sin, cos, res, groundAccel, mt);
        }
    }

    private static boolean preFlightChecks(CalculationContext ctx, int x, int y, int z,
                                           double sin, double cos, BlockState standingOn) {
        // For any angle, check all blocks the player's foot might enter on the first step.
        // We check the block directly in front (rounded), and for diagonals the two
        // axis-aligned neighbours that could be clipped.
        // Minecraft forward vector: dx = -sin(yaw), dz = cos(yaw)
        int ax = x - (int) Math.round(sin);
        int az = z + (int) Math.round(cos);
        if (!MovementHelper.fullyPassable(ctx, ax, y, az)) return false;
        // Also check the two cardinal-adjacent blocks for diagonal angles — at y+1 (head clearance).
        // These are the platform's side faces at foot level which are often solid; only the space
        // ABOVE them (head height) actually needs to be clear for the player to leave at that angle.
        if (Math.abs(sin) > 0.1 && Math.abs(cos) > 0.1) {
            if (!MovementHelper.fullyPassable(ctx, x - (int) Math.signum(sin), y + 1, z)) return false;
            if (!MovementHelper.fullyPassable(ctx, x, y + 1, z + (int) Math.signum(cos))) return false;
        }

        // Prefer walking over parkour — check adjacent block
        BlockState adj = ctx.get(ax, y - 1, az);
        if (MovementHelper.canWalkOn(ctx, ax, y - 1, az, adj)) return false;
        if (MovementHelper.avoidWalkingInto(adj) && !(adj.getFluidState().getType() instanceof WaterFluid)) return false;

        // Headroom check
        for (int dy = 1; dy <= 2; dy++) {
            if (!MovementHelper.fullyPassable(ctx, x, y + dy, z)) return false;
        }

        // Invalid takeoff surfaces
        Block block = standingOn.getBlock();
        if (block == Blocks.VINE || block == Blocks.LADDER || block instanceof StairBlock ||
                MovementHelper.isBottomSlab(standingOn)) return false;
        if (ctx.assumeWalkOnWater && !standingOn.getFluidState().isEmpty()) return false;
        if (!ctx.get(x, y, z).getFluidState().isEmpty()) return false; // Can't jump from water

        return true;
    }

    private static double getSlipperiness(BlockState state) {
        Block b = state.getBlock();
        if (b == Blocks.SLIME_BLOCK) return SLIP_SLIME;
        if (b == Blocks.ICE || b == Blocks.PACKED_ICE || b == Blocks.BLUE_ICE) return SLIP_ICE;
        return SLIP_DEFAULT;
    }

    private static double computeMaxJumpDistance(BlockState standingOn, CalculationContext ctx, double slip) {
        if (ctx.allowWalkOnMagmaBlocks && standingOn.is(Blocks.MAGMA_BLOCK)) return 2;
        if (standingOn.getBlock() == Blocks.SOUL_SAND) return 2;
        if (!ctx.canSprint) return 3;

        // With accurate physics, sprint-jumps can reliably reach 5 blocks
        // Ice is slippery but still allows long slides - allow up to 6
        return (slip >= SLIP_ICE) ? 6 : 5.5;
    }

    // === TRAJECTORY SIMULATION (Physics-Based) ===

    private static class TrajectoryResult {
        final boolean valid, hardObstructed;
        final int destX, destY, destZ;
        final double cost;
        TrajectoryResult(boolean v, boolean ho, int dx, int dy, int dz, double c) {
            valid = v; hardObstructed = ho; destX = dx; destY = dy; destZ = dz; cost = c;
        }
    }

    private static TrajectoryResult simulateTrajectory(CalculationContext ctx, int sx, int sy, int sz,
                                                       double angle, double targetDist, int targetVert,
                                                       double slip, double drag, double groundAccel, double mt) {

        double sin = Math.sin(angle), cos = Math.cos(angle);
        // Pre-calculate intended destination to ignore it during collision checks
        // Use floor to match computeDest() and Minecraft's block containment logic
        // Minecraft forward vector: dx = -sin(yaw), dz = cos(yaw)
        int destX = (int) Math.floor(sx - sin * targetDist);
        int destY = sy + targetVert;
        int destZ = (int) Math.floor(sz + cos * targetDist);

        double baseSpeed = ctx.canSprint ? 0.28 * MT_SPRINT : 0.28 * MT_WALK;
        // Velocity components split by angle; magnitude is constant regardless of direction.
        // Minecraft forward vector: vx = -sin * speed, vz = cos * speed
        double vx = -sin * baseSpeed + (ctx.canSprint ? -sin * SPRINT_JUMP_BOOST : 0);
        double vz = cos * baseSpeed + (ctx.canSprint ? cos * SPRINT_JUMP_BOOST : 0);
        double vy = JUMP_VELOCITY_BASE + 0.1 * ctx.jumpBoostLevel;
        // Start simulation from 0.85 blocks back from center along jump angle
        // This matches execution trigger and gives more horizontal distance for clearing jumps
        double px = sx + 0.5 - sin * 0.85;
        double py = sy;
        double pz = sz + 0.5 + cos * 0.85;
        final int MAX_TICKS = 25;

        for (int tick = 0; tick < MAX_TICKS; tick++) {
            px += vx;
            py += vy;
            pz += vz;

            // Apply air drag and acceleration (Minecraft forward vector: dx = -sin, dz = cos)
            vx = vx * HORIZONTAL_DRAG_AIR - sin * AIR_ACCEL * mt;
            vz = vz * HORIZONTAL_DRAG_AIR + cos * AIR_ACCEL * mt;
            vy = (vy - GRAVITY) * VERTICAL_DRAG;

            // Pass destination coordinates to collision check
            if (!isPlayerClear(ctx, px, py, pz, destX, destY, destZ, sin, cos)) {
                debugParkour("SIM: obstructed at tick=" + tick + " pos=(" + px + ", " + py + ", " + pz + ")");
                return new TrajectoryResult(false, true, 0, 0, 0, 0);
            }

            double currentDist = Math.hypot(px - (sx + 0.5), pz - (sz + 0.5));
            if (currentDist >= targetDist - 0.5) {
                debugParkour("SIM: evaluating landing at tick=" + tick + " pos=(" + px + ", " + py + ", " + pz + ")");
                return evaluateLanding(ctx, sx, sy, sz, angle, targetDist, targetVert, sin, cos, py, tick);
            }
            if (py < sy - 4) return new TrajectoryResult(false, true, 0, 0, 0, 0);
        }
        return new TrajectoryResult(false, false, 0, 0, 0, 0);
    }

    private static boolean isPlayerClear(CalculationContext ctx, double x, double y, double z,
                                         int destX, int destY, int destZ, double sin, double cos) {
        // Check player AABB corners: width ±0.3, height 0 (feet) and 1 (upper body).
        // For diagonal movement, the AABB sweeps a parallelogram; expand bounds accordingly.
        double cardinalness = Math.max(Math.abs(sin), Math.abs(cos));
        double sweepExpansion = 0.15 * (1.0 - cardinalness);
        double halfWidth = 0.3 + sweepExpansion;

        for (int dx = 0; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = 0; dz <= 1; dz++) {
                    int bx = (int) Math.floor(x - halfWidth + dx * (halfWidth * 2));
                    int by = (int) Math.floor(y + dy);
                    int bz = (int) Math.floor(z - halfWidth + dz * (halfWidth * 2));

                    // Allow the trajectory to intersect the destination block.
                    // Final landing validation is handled safely in evaluateLanding().
                    if (bx == destX && by == destY && bz == destZ) continue;

                    if (!MovementHelper.fullyPassable(ctx, bx, by, bz)) return false;
                }
            }
        }
        return true;
    }

    private static TrajectoryResult evaluateLanding(CalculationContext ctx, int sx, int sy, int sz,
                                                    double angle, double dist, int vertOffset,
                                                    double sin, double cos, double currentY, int ticksUsed) {
        // Use floor to match computeDest() and Minecraft's block containment logic
        // Minecraft forward vector: dx = -sin(yaw), dz = cos(yaw)
        int lx = (int) Math.floor(sx - sin * dist);
        int lz = (int) Math.floor(sz + cos * dist);
        int ly = sy + vertOffset;

        BlockState landingInto = ctx.bsi.get0(lx, ly, lz);
        BlockState landingOn = ctx.bsi.get0(lx, ly - 1, lz);

        // Allow a small margin for landing position (±0.2 blocks)
        double expectedX = sx - sin * dist;
        double expectedZ = sz + cos * dist;
        // Compute angle-aware landing margin: interpolate continuously based on angle.
        // Cardinal (0°, 90°): ~0.25 margin; 45°: ~0.15; smooth interpolation between.
        double cardinalness = Math.max(Math.abs(sin), Math.abs(cos));
        double margin = 0.25 - (1.0 - cardinalness) * 0.125;
        margin = Math.max(0.15, Math.min(0.25, margin));
        boolean withinMargin = Math.abs(expectedX - lx) <= margin && Math.abs(expectedZ - lz) <= margin;

        if (vertOffset > 0) {
            // Ascending: land on top of the block at (lx, ly-1, lz).
            // landingOn is the surface block; landingInto is the air block at feet level — must be passable.
            if (withinMargin &&
                    MovementHelper.fullyPassable(ctx, lx, ly, lz) &&
                    MovementHelper.canWalkOn(ctx, lx, ly - 1, lz, landingOn) &&
                    checkOvershootSafety(ctx.bsi, lx - (int)Math.round(sin), ly + 1, lz + (int)Math.round(cos))) {
                double cost = costFromJumpDistance(dist) + vertOffset * 0.8 + ticksUsed * 0.1;
                return new TrajectoryResult(true, false, lx, ly, lz, cost);
            }
        } else {
            // Flat/descending: land on top
            boolean validLanding = (landingOn.getBlock() != Blocks.FARMLAND &&
                    MovementHelper.canWalkOn(ctx, lx, ly - 1, lz, landingOn)) ||
                    (Math.min(16, ctx.frostWalker + 2) >= dist &&
                            MovementHelper.canUseFrostWalker(ctx, landingOn));
            if (withinMargin && validLanding && checkOvershootSafety(ctx.bsi, lx + (int)Math.round(sin), ly, lz + (int)Math.round(cos))) {
                double cost = costFromJumpDistance(dist) + Math.abs(vertOffset) * 0.4 + ticksUsed * 0.1;
                return new TrajectoryResult(true, false, lx, ly, lz, cost);
            }
        }
        return new TrajectoryResult(false, false, lx, ly, lz, 0);
    }

    private static boolean checkOvershootSafety(BlockStateInterface bsi, int x, int y, int z) {
        return !MovementHelper.avoidWalkingInto(bsi.get0(x, y, z)) &&
                !MovementHelper.avoidWalkingInto(bsi.get0(x, y + 1, z));
    }

    private static double costFromJumpDistance(double dist) {
        if (dist <= 2) return WALK_ONE_BLOCK_COST * dist;
        if (dist <= 3) return SPRINT_ONE_BLOCK_COST * dist;
        if (dist <= 6) return SPRINT_ONE_BLOCK_COST * dist * 1.2;
        return COST_INF;
    }

    // === PARKOUR-PLACE SUPPORT ===

    private static void attemptParkourPlace(CalculationContext ctx, int sx, int sy, int sz,
                                            double angle, int vertOffset, double sin, double cos,
                                            MutableMoveResult res, double groundAccel, double mt) {
        for (int dist = 5; dist >= 2; dist--) {
            // Minecraft forward vector: dx = -sin(yaw), dz = cos(yaw)
            int dx = (int) Math.round(sx - sin * dist);
            int dz = (int) Math.round(sz + cos * dist);

            BlockState toReplace = ctx.get(dx, sy - 1, dz);
            double placeCost = ctx.costOfPlacingAt(dx, sy - 1, dz, toReplace);
            if (placeCost >= COST_INF || !MovementHelper.isReplaceable(dx, sy - 1, dz, toReplace, ctx.bsi)) continue;
            if (!checkOvershootSafety(ctx.bsi, dx - (int)Math.round(sin), sy, dz + (int)Math.round(cos))) continue;

            // Find valid adjacent face for placement
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                int ax = dx + dir.getStepX(), ay = sy - 1, az = dz + dir.getStepZ();
                double placeAngle = directionToAngle(dir);
                // Avoid placing against incoming direction (can't turn fast enough mid-air)
                if (Math.abs(angle - placeAngle) < 0.3) continue;

                if (MovementHelper.canPlaceAgainst(ctx.bsi, ax, ay, az)) {
                    res.set(dx, sy, dz, costFromJumpDistance(dist) + placeCost + ctx.jumpPenalty);
                    debugParkour("PARKOUR-PLACE: placed at (" + dx + "," + sy + "," + dz + ") cost=" + (costFromJumpDistance(dist) + placeCost + ctx.jumpPenalty));
                    return;
                }
            }
        }
    }

    // === MOVEMENT INTERFACE ===

    @Override
    public double calculateCost(CalculationContext context) {
        MutableMoveResult res = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, angleRad, vertDelta, res);
        return (res.x == dest.x && res.y == dest.y && res.z == dest.z) ? res.cost : COST_INF;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        Set<BetterBlockPos> set = new HashSet<>();
        double sin = Math.sin(angleRad), cos = Math.cos(angleRad);
        // Minecraft forward vector: dx = -sin(yaw), dz = cos(yaw)
        for (int i = 0; i <= horizontalDist; i++) {
            int bx = (int) Math.round(src.x - sin * i);
            int bz = (int) Math.round(src.z + cos * i);
            for (int dy = -1; dy <= 2; dy++) {
                set.add(new BetterBlockPos(bx, src.y + dy, bz));
            }
        }
        return set;
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        return state.getStatus() != MovementStatus.RUNNING;
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) return state;

        // Failure condition: fallen below start
        if (ctx.playerFeet().y < src.y - 1) {
            logDebug("Parkour failed: player fell");
            debugParkour("FAIL: fell below start (" + ctx.playerFeet() + ") from (" + src + ")");
            return state.setStatus(MovementStatus.UNREACHABLE);
        }

        // Enable sprint for long/ascending jumps
        if (requiresSprint) state.setInput(Input.SPRINT, true);

        // Magma block handling
        if (Baritone.settings().allowWalkOnMagmaBlocks.value &&
                ctx.world().getBlockState(ctx.playerFeet().below()).is(Blocks.MAGMA_BLOCK)) {
            state.setInput(Input.SNEAK, true);
        }

        // --- VELOCITY / RUNUP CHECK ---
        // Back up along jump angle until we reach the physics-simulated backup target.
        // Compute backup target once and cache it.
        if (!backupDone && ctx.playerFeet().equals(src) && ctx.player().onGround()) {
            if (needsRunup()) {
                // Compute and cache backup target on first call
                if (cachedBackupTarget == null) {
                    cachedBackupTarget = computeBackupTarget();
                }
                if (cachedBackupTarget != null) {
                    double sin = Math.sin(angleRad), cos = Math.cos(angleRad);

                    // Move backwards towards the precise backup target along the angle
                    state.setTarget(new MovementState.MovementTarget(
                            RotationUtils.calcRotationFromVec3d(ctx.playerHead(),
                                    cachedBackupTarget,
                                    ctx.playerRotations()),
                            true
                    )).setInput(Input.MOVE_BACK, true);

                    // Check if we've reached/passed the target along the jump axis (Minecraft: dx = -sin, dz = cos)
                    double toTargetX = cachedBackupTarget.x - ctx.player().position().x;
                    double toTargetZ = cachedBackupTarget.z - ctx.player().position().z;
                    double toTargetAlongAngle = -toTargetX * sin + toTargetZ * cos;
                    if (toTargetAlongAngle <= 0.02) {
                        backupDone = true;
                        logDebug("Backup completed, ready to jump");
                    }

                    logDebug("Backing up for run-up: target=" + cachedBackupTarget + " toTargetAlongAngle=" + toTargetAlongAngle);
                    debugParkour("BACKUP: target=" + cachedBackupTarget + " toTargetAlongAngle=" + toTargetAlongAngle);
                } else {
                    // Already have enough speed, skip backup
                    backupDone = true;
                }
                return state;
            }
        }

        // Navigate towards destination
        logDebug("Navigating towards destination: " + dest);
        MovementHelper.moveTowards(ctx, state, dest);

        // Jump trigger: Euclidean distance from source block center, threshold 0.85
        if (!ctx.playerFeet().equals(src) && !ctx.playerFeet().equals(dest)) {
            double px = ctx.player().position().x - (src.x + 0.5);
            double pz = ctx.player().position().z - (src.z + 0.5);
            double movedDist = Math.hypot(px, pz);

            // Trigger jump after moving 0.85 blocks from block center (matches simulation)
            // OR when player starts ascending (already airborne)
            if (movedDist > 0.85 || ctx.player().position().y > src.y + 0.01) {
                // Mid-air block placement fallback
                if (Baritone.settings().allowPlace.value &&
                        !ctx.player().onGround() &&
                        !MovementHelper.canWalkOn(ctx, dest.below()) &&
                        ((Baritone) baritone).getInventoryBehavior().hasGenericThrowaway()) {
                    var result = MovementHelper.attemptToPlaceABlock(state, baritone, dest.below(), true, false);
                    if (result == PlaceResult.READY_TO_PLACE) {
                        state.setInput(Input.CLICK_RIGHT, true);
                        logDebug("Attempting mid-air block placement at " + dest.below());
                        debugParkour("FALLBACK: mid-air block placement at " + dest.below());
                    }
                }
                state.setInput(Input.JUMP, true);
                debugParkour("JUMP: triggered at (" + ctx.player().position() + ") from (" + src + ") to (" + dest + ") movedDist=" + movedDist);
            }
        }

        // Success: jump completed - player landed on ground within ~1 block of expected destination.
        // Euclidean distance tolerance accounts for physics + collision variance.
        BetterBlockPos playerFeet = ctx.playerFeet();
        if (ctx.player().onGround() && !playerFeet.equals(src)) {
            double dx = playerFeet.x + 0.5 - (dest.x + 0.5);
            double dy = playerFeet.y - dest.y;
            double dz = playerFeet.z + 0.5 - (dest.z + 0.5);
            if (Math.sqrt(dx*dx + dy*dy + dz*dz) <= 1.0) {
                logDebug("Parkour successful: landed at " + playerFeet + " (target was " + dest + ")");
                debugParkour("SUCCESS: landed within 1 block of " + dest + " at " + playerFeet);
                return state.setStatus(MovementStatus.SUCCESS);
            }
        }

        return state;
    }

    /**
     * Returns true if the player's current horizontal velocity projected onto the jump
     * direction is below the minimum needed to make this jump with a normal run-up.
     */
    private boolean needsRunup() {
        double sin = Math.sin(angleRad);
        double cos = Math.cos(angleRad);
        // Project current velocity onto jump axis (Minecraft: dx = -sin, dz = cos)
        double vx = ctx.player().getDeltaMovement().x;
        double vz = ctx.player().getDeltaMovement().z;
        double projectedSpeed = -vx * sin + vz * cos;
        return projectedSpeed < MIN_JUMP_SPEED;
    }

    /**
     * Compute the precise backup target (Vec3) so the player backs up exactly
     * enough along the jump angle to reach MIN_JUMP_SPEED at takeoff.
     *
     * Uses the same physics simulation as the trajectory predictor.
     * Returns null if no backup is needed (player already has enough speed).
     */
    private net.minecraft.world.phys.Vec3 computeBackupTarget() {
        double sin = Math.sin(angleRad), cos = Math.cos(angleRad);

        // Project velocity onto jump axis (Minecraft: dx = -sin, dz = cos)
        double vx = ctx.player().getDeltaMovement().x;
        double vz = ctx.player().getDeltaMovement().z;
        double speed = -vx * sin + vz * cos;

        if (speed >= MIN_JUMP_SPEED) return null;

        // Simulate acceleration to MIN_JUMP_SPEED
        final double groundDrag = 0.546, groundAccel = 0.13;
        double runupDist = 0.0;
        for (int t = 0; t < 40 && speed < MIN_JUMP_SPEED; t++) {
            speed = speed * groundDrag + groundAccel;
            runupDist += speed;
        }

        double backDist = Math.min(runupDist, 0.8);

        // Angle-aware exit offset (cardinal=0.2, 45°≈0.28)
        double cardinalness = Math.max(Math.abs(sin), Math.abs(cos));
        double exitOffset = 0.2 + 0.08 * (1.0 - cardinalness);

        // Compute target: takeoff point minus run-up distance along the angle
        // Minecraft forward vector: dx = -sin(yaw), dz = cos(yaw)
        double targetX = (src.x + 0.5) - sin * (exitOffset - backDist);
        double targetZ = (src.z + 0.5) + cos * (exitOffset - backDist);

        // Clamp to stay within source block
        targetX = Math.clamp(targetX, src.x + 0.1, src.x + 0.9);
        targetZ = Math.clamp(targetZ, src.z + 0.1, src.z + 0.9);

        return new net.minecraft.world.phys.Vec3(targetX, src.y, targetZ);
    }

    // In MovementParkour class:
    public double getAngleRad() {
        return angleRad;
    }

    public int getVertOffset() {
        return vertDelta;
    }

    // Helper for parkour debug telemetry
    private static void debugParkour(String msg) {
        if (Baritone.settings().parkourDebugTelemetry.value) {
            HELPER.logDebug("[ParkourTelemetry] " + msg);
        }
    }
}

