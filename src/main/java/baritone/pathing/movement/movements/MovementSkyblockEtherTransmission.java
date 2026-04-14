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

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.api.utils.input.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

public final class MovementSkyblockEtherTransmission extends Movement {

    private static final BetterBlockPos[] EMPTY = new BetterBlockPos[]{};
    private static final double SNEAKING_EYE_HEIGHT = 1.27D;
    private static final int STABLE_TICKS_REQUIRED = 3;
    private static final int STABILIZE_TIMEOUT_TICKS = 30;
    private static final double STABLE_HORIZONTAL_SPEED = 0.08D;
    private static final double STABLE_VERTICAL_SPEED = 0.08D;
    private static final int CLICK_RETRY_DELAY_TICKS = 5;
    private static final int MAX_CLICK_ATTEMPTS = 3;
    private static final float ROTATION_ALIGNMENT_TOLERANCE = 2.0F;
    private static final int LOOK_SETTLE_TIMEOUT_TICKS = 12;

    private boolean attempted;
    private boolean sneakPrimed;
    private boolean clickPrimed;
    private int sneakHeldTicks;
    private int ticksSincePrime;
    private int ticksSinceAttempt;
    private int ticksAtDestination;
    private int stableTicks;
    private int clickAttempts;
    private int ticksSinceNonConsumeClick;
    private boolean waitingRetryWindow;
    private Vec3 lockedTargetHit;

    public MovementSkyblockEtherTransmission(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest) {
        super(baritone, src, dest, EMPTY);
    }

    @Override
    public void reset() {
        super.reset();
        attempted = false;
        sneakPrimed = false;
        clickPrimed = false;
        sneakHeldTicks = 0;
        ticksSincePrime = 0;
        ticksSinceAttempt = 0;
        ticksAtDestination = 0;
        stableTicks = 0;
        clickAttempts = 0;
        ticksSinceNonConsumeClick = 0;
        waitingRetryWindow = false;
        lockedTargetHit = null;
    }

    @Override
    public double calculateCost(CalculationContext context) {
        return cost(context, src.x, src.y, src.z, dest.x, dest.y, dest.z);
    }

    public static double cost(CalculationContext context, int x, int y, int z, int destX, int destZ) {
        return cost(context, x, y, z, destX, y, destZ);
    }

    public static double cost(CalculationContext context, int x, int y, int z, int destX, int destY, int destZ) {
        // IGNORE ALLOWINTERACT HERE.
        if (!context.skyblockTransportEnabled
                || !context.allowSkyblockEtherTransmission
                || !Baritone.settings().skyblockEtherTransmissionEnabled.value
                || !context.skyblockHasAotv) {
            return COST_INF;
        }
        int manaCost = Baritone.settings().skyblockEtherTransmissionManaCost.value;
        if (manaCost > 0 && context.skyblockMana >= 0 && context.skyblockMana < manaCost) {
            return COST_INF;
        }

        int maxRange = Baritone.settings().skyblockEtherTransmissionRange.value;
        int dx = destX - x;
        int dy = destY - y;
        int dz = destZ - z;
        if (dx * dx + dy * dy + dz * dz > maxRange * maxRange) {
            return COST_INF;
        }
        int minGain = Math.max(4, Baritone.settings().skyblockEtherTransmissionMinGain.value);
        if (dx * dx + dz * dz < minGain * minGain) {
            return COST_INF;
        }

        if (!isSourceUnobstructed(context, x, y, z)) {
            return COST_INF;
        }

        // Etherwarp cannot be initiated while mid-air.
        if (!MovementHelper.canWalkOn(context, x, y - 1, z)) {
            return COST_INF;
        }

        if (!MovementHelper.canWalkOn(context, destX, destY - 1, destZ)
                || !MovementHelper.fullyPassable(context, destX, destY, destZ)
                || !MovementHelper.fullyPassable(context, destX, destY + 1, destZ)) {
            return COST_INF;
        }

        if (!hasLineOfSight(context, x, y, z, destX, destY - 1, destZ)) {
            return COST_INF;
        }

        return WALK_ONE_BLOCK_COST * 2.25D;
    }

    private static boolean hasLineOfSight(CalculationContext context, int srcX, int srcY, int srcZ, int targetX, int targetY, int targetZ) {
        double startX = srcX + 0.5D;
        // Etherwarp is performed while sneaking; use crouched eye height for planning checks.
        double startY = srcY + SNEAKING_EYE_HEIGHT;
        double startZ = srcZ + 0.5D;
        double endX = targetX + 0.5D;
        double endY = targetY + 0.5D;
        double endZ = targetZ + 0.5D;
        HitResult hit = context.world.clip(new ClipContext(
                new Vec3(startX, startY, startZ),
                new Vec3(endX, endY, endZ),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                context.baritone.getPlayerContext().player()
        ));
        return hit instanceof BlockHitResult blockHit
                && blockHit.getBlockPos().getX() == targetX
                && blockHit.getBlockPos().getY() == targetY
                && blockHit.getBlockPos().getZ() == targetZ;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        return Set.of(src, dest);
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }

        if (ctx.playerFeet().equals(dest)) {
            ticksAtDestination++;
            if (isStabilized()) {
                stableTicks++;
                if (stableTicks >= STABLE_TICKS_REQUIRED) {
                    return state.setStatus(MovementStatus.SUCCESS);
                }
            } else {
                stableTicks = 0;
            }
            if (ticksAtDestination > STABILIZE_TIMEOUT_TICKS) {
                return state.setStatus(MovementStatus.UNREACHABLE);
            }
            return state;
        }

        // Server can occasionally place us onto the targeted block itself (one block too low).
        // Treat this movement as failed so the planner can continue from our real position.
        if (attempted && ctx.playerFeet().equals(dest.below())) {
            return state.setStatus(MovementStatus.UNREACHABLE);
        }

        ticksAtDestination = 0;
        stableTicks = 0;

        if (!MovementHelper.canWalkOn(ctx, dest.below())
                || !MovementHelper.fullyPassable(ctx, dest)
                || !MovementHelper.fullyPassable(ctx, dest.above())) {
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        int rdx = dest.x - src.x;
        int rdz = dest.z - src.z;
        if (rdx * rdx + rdz * rdz < 16) {
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        if (!isSourceUnobstructed(ctx.playerFeet().x, ctx.playerFeet().y, ctx.playerFeet().z)) {
            return state.setStatus(MovementStatus.UNREACHABLE);
        }

        BlockPos targetBlock = dest.below();
        state.setInput(Input.SNEAK, true);
        BlockHitResult targetHit = raycastTargetBlock(targetBlock);
        if (targetHit != null && lockedTargetHit == null) {
            lockedTargetHit = targetHit.getLocation();
        }
        Vec3 aimPoint = lockedTargetHit != null ? lockedTargetHit : Vec3.atCenterOf(targetBlock);
        Rotation targetRotation = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), aimPoint, ctx.playerRotations());
        state.setTarget(new MovementState.MovementTarget(targetRotation, true));

        if (!attempted && waitingRetryWindow) {
            ticksSinceNonConsumeClick++;
            if (ticksSinceNonConsumeClick < CLICK_RETRY_DELAY_TICKS) {
                return state;
            }
            waitingRetryWindow = false;
            ticksSinceNonConsumeClick = 0;
            if (clickAttempts >= MAX_CLICK_ATTEMPTS) {
                return state.setStatus(MovementStatus.UNREACHABLE);
            }
        }

        if (!attempted) {
            if (!ctx.player().onGround()) {
                sneakHeldTicks = 0;
                ticksSincePrime++;
                if (ticksSincePrime > 10) {
                    return state.setStatus(MovementStatus.UNREACHABLE);
                }
                return state;
            }
            for (int i = 0; i < 9; i++) {
                String itemName = ctx.player().getInventory().getNonEquipmentItems().get(i).getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
                if (itemName.contains("aspect of the void") || itemName.contains("aspect of the end")) {
                    ctx.player().getInventory().setSelectedSlot(i);
                    break;
                }
            }

            // Wait for camera settle before the click; this avoids no-op right clicks.
            if (!isRotationAligned(targetRotation)) {
                ticksSincePrime++;
                if (ticksSincePrime > LOOK_SETTLE_TIMEOUT_TICKS) {
                    return state.setStatus(MovementStatus.UNREACHABLE);
                }
                return state;
            }
            ticksSincePrime = 0;

            if (ctx.player().isShiftKeyDown()) {
                sneakHeldTicks++;
            } else {
                sneakHeldTicks = 0;
                return state;
            }

            // Prime sneak for one tick first, then click while sneak is still held.
            if (!sneakPrimed || sneakHeldTicks < 2) {
                sneakPrimed = true;
                return state;
            }
            state.setTarget(new MovementState.MovementTarget(
                    targetRotation,
                    true
            ));
            // Give forced rotation one full tick before the first click attempt.
            if (!clickPrimed) {
                clickPrimed = true;
                return state;
            }

            // Click once, then wait for slow server response instead of retry-spamming.
            InteractionResult clickResult = tryEtherwarpClick();
            clickAttempts++;
            if (clickResult.consumesAction()) {
                ((Baritone) baritone).getSkyblockTransportBehavior().predictManaUse(Baritone.settings().skyblockEtherTransmissionManaCost.value);
                attempted = true;
                ticksSinceAttempt = 0;
                waitingRetryWindow = false;
                ticksSinceNonConsumeClick = 0;
            } else {
                waitingRetryWindow = true;
                ticksSinceNonConsumeClick = 0;
            }
            return state;
        }

        state.setTarget(new MovementState.MovementTarget(
                RotationUtils.calcRotationFromVec3d(ctx.playerHead(), aimPoint, ctx.playerRotations()),
                true
        ));

        // Click already consumed once; now just wait for server-side teleport to land.
        ticksSinceAttempt++;
        if (ticksSinceAttempt > 8) {
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        return state;
    }

    private BlockHitResult raycastTargetBlock(BlockPos targetBlock) {
        Vec3 playerPos = ctx.player().position();
        Vec3 start = new Vec3(playerPos.x, playerPos.y + SNEAKING_EYE_HEIGHT, playerPos.z);
        Vec3 end = Vec3.atCenterOf(targetBlock);
        BlockHitResult hit = ctx.world().clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.player()));
        if (hit.getBlockPos().equals(targetBlock)) {
            return hit;
        }
        Vec3 standingEye = ctx.player().getEyePosition();
        BlockHitResult standingHit = ctx.world().clip(new ClipContext(standingEye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.player()));
        return standingHit.getBlockPos().equals(targetBlock) ? standingHit : null;
    }

    private InteractionResult tryEtherwarpClick() {
        InteractionResult result = ctx.playerController().processRightClick(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND);
        if (result.consumesAction()) {
            ctx.player().swing(InteractionHand.MAIN_HAND);
        }
        return result;
    }

    private boolean isStabilized() {
        Vec3 velocity = ctx.player().getDeltaMovement();
        return ctx.player().onGround()
                && Math.abs(velocity.x) <= STABLE_HORIZONTAL_SPEED
                && Math.abs(velocity.z) <= STABLE_HORIZONTAL_SPEED
                && Math.abs(velocity.y) <= STABLE_VERTICAL_SPEED;
    }

    private boolean isRotationAligned(Rotation targetRotation) {
        float yawDiff = Math.abs(Rotation.normalizeYaw(ctx.player().getYRot() - targetRotation.getYaw()));
        float pitchDiff = Math.abs(ctx.player().getXRot() - targetRotation.getPitch());
        return yawDiff <= ROTATION_ALIGNMENT_TOLERANCE && pitchDiff <= ROTATION_ALIGNMENT_TOLERANCE;
    }

    private static boolean isSourceUnobstructed(CalculationContext context, int x, int y, int z) {
        return MovementHelper.canWalkOn(context, x, y - 1, z)
                && MovementHelper.canWalkThrough(context, x, y, z)
                && MovementHelper.canWalkThrough(context, x, y + 1, z);
    }

    private boolean isSourceUnobstructed(int x, int y, int z) {
        BetterBlockPos feet = new BetterBlockPos(x, y, z);
        return MovementHelper.canWalkOn(ctx, feet.below())
                && MovementHelper.canWalkThrough(ctx, feet)
                && MovementHelper.canWalkThrough(ctx, feet.above());
    }


    @Override
    protected boolean safeToCancel(MovementState currentState) {
        return currentState.getStatus() != MovementStatus.RUNNING;
    }
}

