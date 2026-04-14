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

package baritone.api.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.projectile.AbstractArrow;

/**
 * Closed-form arrow trajectory calculations using Minecraft dispenser physics.
 * Used for Skyblock Nucleus Temple trap avoidance.
 *
 * <h2>Minecraft arrow physics per tick:</h2>
 * <pre>
 *   pos += velocity
 *   velocity *= 0.99  (drag)
 *   velocity.y -= 0.05 (gravity)
 * </pre>
 *
 * <h2>Closed-form solutions (fast, no loop):</h2>
 * <pre>
 *   vx[t] = vx[0] * drag^t
 *   vy[t] = vy[0] * drag^t - g * (1 - drag^t) / (1 - drag)
 *   x[t]  = x[0] + vx[0] * (1 - drag^t) / (1 - drag)
 *   y[t]  = y[0] + vy[0] * (1 - drag^t) / (1 - drag)
 *           - g * (t / (1-drag) - (1 - drag^t) / (1-drag)^2)
 * </pre>
 */
public final class SkyblockNucleusTempleArrowPhysics {

    // Minecraft constants
    public static final double DRAG = 0.99;
    public static final double GRAVITY = 0.05; // blocks/tick^2
    public static final double ONE_MINUS_DRAG = 1.0 - DRAG; // 0.01
    public static final double ONE_MINUS_DRAG_SQ = ONE_MINUS_DRAG * ONE_MINUS_DRAG; // 0.0001

    /** Player hitbox dimensions (half-width, full height). */
    public static final double PLAYER_HALF_WIDTH = 0.3;
    public static final double PLAYER_HEIGHT = 1.8;

    private SkyblockNucleusTempleArrowPhysics() {}

    // =================================================================
    // Closed-form position at tick t
    // =================================================================

    /**
     * Horizontal position component at tick t.
     */
    public static double horizontalPos(double x0, double v0x, int t) {
        double dragT = Math.pow(DRAG, t);
        return x0 + v0x * (1.0 - dragT) / ONE_MINUS_DRAG;
    }

    /**
     * Vertical position at tick t.
     */
    public static double verticalPos(double y0, double v0y, int t) {
        double dragT = Math.pow(DRAG, t);
        double term1 = v0y * (1.0 - dragT) / ONE_MINUS_DRAG;
        double term2 = GRAVITY * (t / ONE_MINUS_DRAG - (1.0 - dragT) / ONE_MINUS_DRAG_SQ);
        return y0 + term1 - term2;
    }

    // =================================================================
    // Arrow entity presence detection
    // =================================================================

    /**
     * Check if any arrow entity is currently within danger distance of
     * the player position.
     *
     * @param mc         Minecraft instance
     * @param playerPos  player position to check
     * @param dangerRadius radius in blocks to consider "dangerous"
     * @return the closest arrow entity, or null if none found
     */
    public static AbstractArrow findNearbyArrow(Minecraft mc, BlockPos playerPos, double dangerRadius) {
        if (mc.level == null) return null;

        double px = playerPos.getX() + 0.5;
        double py = playerPos.getY() + 0.5;
        double pz = playerPos.getZ() + 0.5;
        double maxDistSq = dangerRadius * dangerRadius;

        AbstractArrow closest = null;
        double closestDistSq = maxDistSq;

        for (net.minecraft.world.entity.Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof AbstractArrow arrow) {
                double dx = arrow.getX() - px;
                double dy = arrow.getY() - py;
                double dz = arrow.getZ() - pz;
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq < closestDistSq) {
                    closestDistSq = distSq;
                    closest = arrow;
                }
            }
        }
        return closest;
    }

    /**
     * Compute how many ticks until the arrow reaches the player's path.
     *
     * @param arrow     the arrow entity
     * @param playerPos player position on the path
     * @return ticks until arrow reaches player position, or -1 if moving away
     */
    public static int ticksUntilReach(AbstractArrow arrow, BlockPos playerPos) {
        double ax = arrow.getX();
        double ay = arrow.getY();
        double az = arrow.getZ();
        double vx = arrow.getDeltaMovement().x;
        double vy = arrow.getDeltaMovement().y;
        double vz = arrow.getDeltaMovement().z;

        double px = playerPos.getX() + 0.5;
        double py = playerPos.getY() + PLAYER_HEIGHT * 0.5;
        double pz = playerPos.getZ() + 0.5;

        double dx = px - ax;
        double dy = py - ay;
        double dz = pz - az;

        // Check if arrow is moving toward player
        double dot = dx * vx + dy * vy + dz * vz;
        if (dot <= 0) return -1; // arrow moving away

        // Estimate time of closest approach using relative velocity
        double speedSq = vx * vx + vy * vy + vz * vz;
        if (speedSq < 0.0001) return -1; // arrow barely moving

        double t = dot / speedSq;
        return Math.max(0, (int) Math.ceil(t));
    }

    /**
     * Predict arrow position at a future tick using current velocity.
     *
     * @param arrow    current arrow entity
     * @param ticksInFuture how many ticks ahead to predict
     * @return predicted position, or null if arrow won't exist
     */
    public static net.minecraft.world.phys.Vec3 predictPosition(AbstractArrow arrow, int ticksInFuture) {
        double ax = arrow.getX();
        double ay = arrow.getY();
        double az = arrow.getZ();
        double vx = arrow.getDeltaMovement().x;
        double vy = arrow.getDeltaMovement().y;
        double vz = arrow.getDeltaMovement().z;

        double dragT = Math.pow(DRAG, ticksInFuture);
        double px = ax + vx * (1.0 - dragT) / ONE_MINUS_DRAG;
        double py = ay + vy * (1.0 - dragT) / ONE_MINUS_DRAG
                  - GRAVITY * (ticksInFuture / ONE_MINUS_DRAG - (1.0 - dragT) / ONE_MINUS_DRAG_SQ);
        double pz = az + vz * (1.0 - dragT) / ONE_MINUS_DRAG;

        return new net.minecraft.world.phys.Vec3(px, py, pz);
    }

    /**
     * Simulate arrow trajectory from dispenser to player position.
     *
     * @param origin   dispenser position
     * @param target   player position
     * @param v0       initial speed
     * @param maxTicks maximum flight ticks to check
     * @return tick number when arrow reaches target, or -1 if not reached
     */
    public static int simulateFlightTime(BlockPos origin, BlockPos target, double v0, int maxTicks) {
        double ax = origin.getX() + 0.5;
        double ay = origin.getY() + 0.5;
        double az = origin.getZ() + 0.5;
        double px = target.getX() + 0.5;
        double py = target.getY() + PLAYER_HEIGHT * 0.5;
        double pz = target.getZ() + 0.5;

        double dx = px - ax;
        double dy = py - ay;
        double dz = pz - az;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (dist < 0.001) return 0;

        double vx = dx / dist * v0;
        double vy = dy / dist * v0;
        double vz = dz / dist * v0;

        double cx = ax, cy = ay, cz = az;
        for (int t = 1; t <= maxTicks; t++) {
            cx += vx; cy += vy; cz += vz;
            vx *= DRAG; vy *= DRAG; vz *= DRAG;
            vy -= GRAVITY;

            double d = Math.sqrt((cx-px)*(cx-px) + (cy-py)*(cy-py) + (cz-pz)*(cz-pz));
            if (d < 0.5) return t; // close enough
        }
        return -1;
    }

    /**
     * Compute the clearance time: when the arrow has fully passed the
     * player position plus safety margin.
     *
     * @param arrow        current arrow entity
     * @param playerPos    player position
     * @param safetyMargin safety distance beyond player
     * @return ticks until arrow is clear (past safety margin), or -1
     */
    public static int ticksUntilClear(AbstractArrow arrow, BlockPos playerPos, double safetyMargin) {
        double px = playerPos.getX() + 0.5;
        double py = playerPos.getY() + PLAYER_HEIGHT * 0.5;
        double pz = playerPos.getZ() + 0.5;

        double vx = arrow.getDeltaMovement().x;
        double vy = arrow.getDeltaMovement().y;
        double vz = arrow.getDeltaMovement().z;

        // Check if arrow is past player already
        double dx = arrow.getX() - px;
        double dz = arrow.getZ() - pz;

        // Distance traveled along arrow direction since launch point is complex
        // Instead: compute distance from arrow to player center
        double distToPlayer = Math.sqrt(
            (arrow.getX() - px) * (arrow.getX() - px) +
            (arrow.getY() - py) * (arrow.getY() - py) +
            (arrow.getZ() - pz) * (arrow.getZ() - pz)
        );

        double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (speed < 0.01) return 0; // arrow stopped, assume clear

        // Check if arrow is moving toward or away from player
        double dot = dx * vx + (arrow.getY() - py) * vy + dz * vz;
        if (dot > 0) {
            // Arrow has passed player, moving away
            // Time to be safetyMargin away
            double extraDist = safetyMargin - distToPlayer;
            if (extraDist <= 0) return 1; // already clear
            return (int) Math.ceil(extraDist / speed) + 2; // +2 safety ticks
        }

        // Arrow still approaching
        double timeToPlayer = distToPlayer / speed;
        double timeToClear = timeToPlayer + safetyMargin / speed;
        return (int) Math.ceil(timeToClear) + 2;
    }
}
