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
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

public final class MovementSkyblockInstantTransmission extends Movement {

    private static final BetterBlockPos[] EMPTY = new BetterBlockPos[]{};
    private static final int STABLE_TICKS_REQUIRED = 3;
    private static final int STABILIZE_TIMEOUT_TICKS = 30;
    private static final double STABLE_HORIZONTAL_SPEED = 0.08D;
    private static final double STABLE_VERTICAL_SPEED = 0.08D;
    private static final int LOOK_SETTLE_TIMEOUT_TICKS = 12;
    private static final int CLICK_RETRY_DELAY_TICKS = 4;
    private static final int MAX_CLICK_ATTEMPTS = 3;
    private static final float ROTATION_ALIGNMENT_TOLERANCE = 2.0F;
    private static final int TP_UNDERSHOOT_BLOCKS = 1;

    private boolean attempted;
    private boolean lookPrimed;
    private int lookWaitTicks;
    private int ticksSinceAttempt;
    private int ticksAtDestination;
    private int stableTicks;
    private int clickAttempts;
    private int retryDelayTicks;

    public MovementSkyblockInstantTransmission(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest) {
        super(baritone, src, dest, EMPTY);
    }

    @Override
    public void reset() {
        super.reset();
        attempted = false;
        lookPrimed = false;
        lookWaitTicks = 0;
        ticksSinceAttempt = 0;
        ticksAtDestination = 0;
        stableTicks = 0;
        clickAttempts = 0;
        retryDelayTicks = 0;
    }

    @Override
    public double calculateCost(CalculationContext context) {
        return cost(context, src.x, src.y, src.z, dest.x, dest.z);
    }

    public static MovementSkyblockInstantTransmission cost(CalculationContext context, BetterBlockPos src, Direction direction) {
        MutableMoveResult res = new MutableMoveResult();
        res.x = src.x;
        res.y = src.y;
        res.z = src.z;
        res.cost = COST_INF;
        cost(context, src.x, src.y, src.z, direction, res);
        return new MovementSkyblockInstantTransmission(context.getBaritone(), src, new BetterBlockPos(res.x, res.y, res.z));
    }

    public static void cost(CalculationContext context, int x, int y, int z, Direction direction, MutableMoveResult result) {
        result.x = x;
        result.y = y;
        result.z = z;
        result.cost = COST_INF;
        if (!context.skyblockTransportEnabled
                || !context.allowSkyblockInstantTransmission
                || !Baritone.settings().skyblockInstantTransmissionEnabled.value
                || !context.skyblockHasInstantTransmissionItem) {
            return;
        }
        int manaCost = Baritone.settings().skyblockInstantTransmissionManaCost.value;
        if (manaCost > 0 && context.skyblockMana >= 0 && context.skyblockMana < manaCost) {
            return;
        }
        int minGain = Baritone.settings().skyblockInstantTransmissionMinGain.value;
        int maxRange = Math.max(1, Baritone.settings().skyblockInstantTransmissionRange.value);
        int xStep = direction.getStepX();
        int zStep = direction.getStepZ();
        for (int distance = maxRange; distance >= minGain; distance--) {
            int destX = x + xStep * distance;
            int destZ = z + zStep * distance;
            double candidateCost = cost(context, x, y, z, destX, destZ);
            if (candidateCost < COST_INF) {
                result.x = destX;
                result.y = y;
                result.z = destZ;
                result.cost = candidateCost;
                return;
            }
        }
    }

    public static double cost(CalculationContext context, int x, int y, int z, int destX, int destZ) {
        // IGNORE ALLOWINTERACT HERE.
        if (!context.skyblockTransportEnabled
                || !context.allowSkyblockInstantTransmission
                || !Baritone.settings().skyblockInstantTransmissionEnabled.value
                || !context.skyblockHasInstantTransmissionItem) {
            return COST_INF;
        }
        int manaCost = Baritone.settings().skyblockInstantTransmissionManaCost.value;
        if (manaCost > 0 && context.skyblockMana >= 0 && context.skyblockMana < manaCost) {
            return COST_INF;
        }
        int dx = destX - x;
        int dz = destZ - z;
        int minGain = Baritone.settings().skyblockInstantTransmissionMinGain.value;
        if (dx * dx + dz * dz < minGain * minGain) {
            return COST_INF;
        }
        if (!isSourceUnobstructed(context, x, y, z)) {
            return COST_INF;
        }
        if (!isValidLanding(context, destX, y, destZ)) {
            return COST_INF;
        }

        BetterBlockPos undershootPos = getUndershootDestination(x, y, z, destX, destZ);
        if (undershootPos == null || !isValidLanding(context, undershootPos.x, undershootPos.y, undershootPos.z)) {
            return COST_INF;
        }
        int effectiveDx = undershootPos.x - x;
        int effectiveDz = undershootPos.z - z;
        if (effectiveDx * effectiveDx + effectiveDz * effectiveDz < minGain * minGain) {
            return COST_INF;
        }

        if (!hasClearRay(context, x, y, z, destX, y, destZ)) {
            return COST_INF;
        }

        // Slightly cheaper than long sprinting, but not free.
        return WALK_ONE_BLOCK_COST * 3.5D + context.jumpPenalty * 0.25D;
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

        if (attempted) {
            BetterBlockPos undershootPos = getUndershootDestination(src.x, src.y, src.z, dest.x, dest.z);
            if (undershootPos != null && ctx.playerFeet().equals(undershootPos)) {
                // We intentionally model occasional 1-block short teleports as a failed movement so replanning can continue.
                return state.setStatus(MovementStatus.UNREACHABLE);
            }
        }

        ticksAtDestination = 0;
        stableTicks = 0;

        Rotation targetRotation = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), Vec3.atCenterOf(dest), ctx.playerRotations());
        state.setTarget(new MovementState.MovementTarget(targetRotation, true));
        if (!isSourceUnobstructed(ctx.playerFeet().x, ctx.playerFeet().y, ctx.playerFeet().z)) {
            return state.setStatus(MovementStatus.UNREACHABLE);
        }

        if (!attempted) {
            if (!lookPrimed) {
                lookPrimed = true;
                return state;
            }
            if (!isRotationAligned(targetRotation)) {
                lookWaitTicks++;
                if (lookWaitTicks > LOOK_SETTLE_TIMEOUT_TICKS) {
                    return state.setStatus(MovementStatus.UNREACHABLE);
                }
                return state;
            }
            lookWaitTicks = 0;
            if (!hasClearRay(ctx.player().position().x, ctx.playerFeet().y + 1.62D, ctx.player().position().z, dest.x + 0.5D, dest.y + 0.5D, dest.z + 0.5D)) {
                return state.setStatus(MovementStatus.UNREACHABLE);
            }
            for (int i = 0; i < 9; i++) {
                String name = ctx.player().getInventory().getNonEquipmentItems().get(i).getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
                if (name.contains("aspect of the void") || name.contains("aspect of the end")) {
                    ctx.player().getInventory().setSelectedSlot(i);
                    break;
                }
            }

            if (retryDelayTicks > 0) {
                retryDelayTicks--;
                return state;
            }

            InteractionResult result = ctx.playerController().processRightClick(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND);
            clickAttempts++;
            if (!result.consumesAction()) {
                if (clickAttempts >= MAX_CLICK_ATTEMPTS) {
                    return state.setStatus(MovementStatus.UNREACHABLE);
                }
                retryDelayTicks = CLICK_RETRY_DELAY_TICKS;
                return state;
            }
            ctx.player().swing(InteractionHand.MAIN_HAND);
            ((Baritone) baritone).getSkyblockTransportBehavior().predictManaUse(Baritone.settings().skyblockInstantTransmissionManaCost.value);
            attempted = true;
            ticksSinceAttempt = 0;
            return state;
        }

        ticksSinceAttempt++;
        if (ticksSinceAttempt > 8) {
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        return state;
    }

    private static boolean hasClearRay(CalculationContext context, int srcX, int srcY, int srcZ, int destX, int destY, int destZ) {
        HitResult hit = context.world.clip(new ClipContext(
                new Vec3(srcX + 0.5D, srcY + 1.62D, srcZ + 0.5D),
                new Vec3(destX + 0.5D, destY + 0.5D, destZ + 0.5D),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                context.baritone.getPlayerContext().player()
        ));
        return hit.getType() == HitResult.Type.MISS;
    }

    private boolean isRotationAligned(Rotation targetRotation) {
        float yawDiff = Math.abs(Rotation.normalizeYaw(ctx.player().getYRot() - targetRotation.getYaw()));
        float pitchDiff = Math.abs(ctx.player().getXRot() - targetRotation.getPitch());
        return yawDiff <= ROTATION_ALIGNMENT_TOLERANCE && pitchDiff <= ROTATION_ALIGNMENT_TOLERANCE;
    }

    private boolean isStabilized() {
        Vec3 velocity = ctx.player().getDeltaMovement();
        return ctx.player().onGround()
                && Math.abs(velocity.x) <= STABLE_HORIZONTAL_SPEED
                && Math.abs(velocity.z) <= STABLE_HORIZONTAL_SPEED
                && Math.abs(velocity.y) <= STABLE_VERTICAL_SPEED;
    }

    private boolean hasClearRay(double startX, double startY, double startZ, double endX, double endY, double endZ) {
        HitResult hit = ctx.world().clip(new ClipContext(
                new Vec3(startX, startY, startZ),
                new Vec3(endX, endY, endZ),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                ctx.player()
        ));
        return hit.getType() == HitResult.Type.MISS;
    }

    private static boolean isSourceUnobstructed(CalculationContext context, int x, int y, int z) {
        return MovementHelper.canWalkOn(context, x, y - 1, z)
                && MovementHelper.canWalkThrough(context, x, y, z)
                && MovementHelper.canWalkThrough(context, x, y + 1, z);
    }

    private static boolean isValidLanding(CalculationContext context, int x, int y, int z) {
        return MovementHelper.fullyPassable(context, x, y, z)
                && MovementHelper.fullyPassable(context, x, y + 1, z)
                && MovementHelper.canWalkOn(context, x, y - 1, z);
    }

    private static BetterBlockPos getUndershootDestination(int srcX, int srcY, int srcZ, int destX, int destZ) {
        int dx = destX - srcX;
        int dz = destZ - srcZ;
        int manhattan = Math.abs(dx) + Math.abs(dz);
        if (manhattan <= TP_UNDERSHOOT_BLOCKS) {
            return null;
        }
        int stepX = Integer.signum(dx);
        int stepZ = Integer.signum(dz);
        return new BetterBlockPos(destX - (stepX * TP_UNDERSHOOT_BLOCKS), srcY, destZ - (stepZ * TP_UNDERSHOOT_BLOCKS));
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

