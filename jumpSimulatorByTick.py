import math
import random

# =========================
# Constants (EXACT Minecraft values)
# =========================
AIR_DRAG = 0.91
GRAVITY = 0.08
VERTICAL_DRAG = 0.98
AIR_ACCEL_BASE = 0.02
SPRINT_MULTIPLIER = 1.3
AIR_ACCEL_SPRINT = AIR_ACCEL_BASE * SPRINT_MULTIPLIER  # 0.026
AIR_ACCEL_WALK   = AIR_ACCEL_BASE                       # 0.02

JUMP_VELOCITY = 0.42
SPRINT_JUMP_BOOST = 0.2          # only added when sprinting at jump moment
JUMP_BOOST_INCREMENT = 0.1       # per amplifier level

PLAYER_WIDTH = 0.6
PLAYER_HEIGHT = 1.8
SLIPPERINESS = 0.6               # default block slipperiness

# f = ground momentum factor (same for sprint and walk on default blocks)
GROUND_F = SLIPPERINESS * AIR_DRAG          # 0.546

# Ground acceleration (sprint vs walk)
# Formula: 0.1 * speed_mult * (0.6 / slip)^3
GROUND_ACCEL_SPRINT = 0.1 * SPRINT_MULTIPLIER * (0.6 / SLIPPERINESS) ** 3  # 0.13
GROUND_ACCEL_WALK   = 0.1 * 1.0            * (0.6 / SLIPPERINESS) ** 3     # 0.10

MAX_AIR_TICKS = 60

# Maximum forward extension from block centre before player is fully off the source block.
# 0.5 (half block width) + 0.3 (half player width) = 0.8
MAX_FWD_POS = 0.5 + PLAYER_WIDTH / 2       # 0.8


# =========================
# Block collision (STUB)
# =========================
def collides_with_blocks(x, y, z, src, dest, tick):
    """Placeholder: always false. Replace with real collision detection."""
    return False


# =========================
# Airborne simulation
# ─ BUG FIX 1: use "will-cross" landing check instead of "did-cross".
#              Old: prev_y > land_y and y <= land_y   (post-move y)
#              New: y >= land_y and ny <= land_y       (pre-move y, post-move ny)
#              This fires in the same tick the player actually hits the surface,
#              rather than one tick late (when they're already partially through).
# ─ BUG FIX 2: support both SPRINT mode (jump boost + sprint air-accel)
#              and WALK mode (no jump boost, walk air-accel).
#              1-block jumps require walk physics; sprint carries the player
#              too far to ever land on a 1-block-away block.
# =========================
def simulate_airborne(src, dest, x, z, ground_speed, jump_boost_level,
                      sin_yaw, cos_yaw, *, is_sprint=True):
    """
    Simulate a jump from (x, src[1], z) toward dest.

    Parameters
    ----------
    src, dest : (block_x, block_y, block_z) — block coordinates.
                block_y is the feet Y of a player standing on the block
                (top face = block_y + 1.0).
    x, z      : player centre at the moment of jumping.
    ground_speed : horizontal speed carried from the ground phase.
    jump_boost_level : Jump Boost amplifier (0 = no effect).
    is_sprint : True → sprint physics (jump boost + larger air-accel).
                False → walk physics (no jump boost, smaller air-accel).
    """
    y     = float(src[1])
    land_y = float(dest[1]) + 1.0          # feet target = top face of dest block

    land_min_x = dest[0] + PLAYER_WIDTH / 2
    land_max_x = dest[0] + 1.0 - PLAYER_WIDTH / 2
    land_min_z = dest[2] + PLAYER_WIDTH / 2
    land_max_z = dest[2] + 1.0 - PLAYER_WIDTH / 2

    jump_h_boost = SPRINT_JUMP_BOOST if is_sprint else 0.0
    air_accel    = AIR_ACCEL_SPRINT  if is_sprint else AIR_ACCEL_WALK

    vx = ground_speed * (-sin_yaw)
    vz = ground_speed * (cos_yaw)
    vy = 0.0

    for tick in range(MAX_AIR_TICKS):
        # ── tick 0: apply jump impulses ──────────────────────────────────────
        if tick == 0:
            vy  = JUMP_VELOCITY + jump_boost_level * JUMP_BOOST_INCREMENT
            vx += jump_h_boost * (-sin_yaw)
            vz += jump_h_boost * (cos_yaw)

        # ── horizontal air acceleration (applied every tick) ─────────────────
        vx += air_accel * (-sin_yaw)
        vz += air_accel * (cos_yaw)

        # ── compute candidate next position ──────────────────────────────────
        nx = x  + vx
        ny = y  + vy
        nz = z  + vz

        # ── block collision (stub) ────────────────────────────────────────────
        if collides_with_blocks(nx, ny, nz, src, dest, tick):
            break

        # ── landing check (FIX: will-cross, not did-cross) ───────────────────
        # Player was at or above land_y and will be at or below it this tick.
        if y >= land_y and ny <= land_y:
            in_xz = (land_min_x <= nx <= land_max_x and
                     land_min_z <= nz <= land_max_z)
            if in_xz:
                return True, tick + 1, (nx, land_y, nz)
            # Crossed the plane but missed horizontally — no point continuing
            # once we have also fallen half a block below.
            if ny < land_y - 0.5:
                return False, tick + 1, (nx, ny, nz)

        # ── advance state ─────────────────────────────────────────────────────
        x, y, z = nx, ny, nz

        vx *= AIR_DRAG
        vz *= AIR_DRAG
        vy  = (vy - GRAVITY) * VERTICAL_DRAG
        if abs(vy) < 0.005:
            vy = 0.0

        # Early termination: fell more than 2 blocks below landing plane
        if y < land_y - 2.0:
            return False, tick + 1, (x, y, z)
        if y < -100:
            break

    return False, MAX_AIR_TICKS, (x, y, z)


# =========================
# Ground run-up simulation
# ─ BUG FIX 3: removed the premature takeoff_edge kill from inside the loop.
#              The old code returned None whenever fwd_pos >= 0.2 during the
#              loop, which eliminated every valid running approach (player
#              reaches fwd_pos > 0.2 after just 3 ticks even from x=0).
#              Now we just simulate N ticks and check the final position once.
# =========================
def simulate_runup(src, start_fwd, lat, N, sin_yaw, cos_yaw,
                   f, ground_accel):
    """
    Simulate N ground ticks.

    start_fwd : initial offset from block centre along the jump direction
                (negative = behind centre, positive = toward dest).
    lat       : lateral offset (perpendicular to jump direction).
    Returns (gx, gz, gv) or None if player steps fully off the source block.
    """
    gx = src[0] + 0.5 + start_fwd * (-sin_yaw) + lat * cos_yaw
    gz = src[2] + 0.5 + start_fwd * (cos_yaw)  + lat * sin_yaw
    gv = 0.0

    for _ in range(N):
        gv  = gv * f + ground_accel
        gx += gv * (-sin_yaw)
        gz += gv * (cos_yaw)

    # Post-runup check: player must still be on (or just barely over) the source block.
    # Project onto the forward direction and allow up to MAX_FWD_POS (0.8) from centre.
    fwd_pos = ((gx - (src[0] + 0.5)) * (-sin_yaw) +
               (gz - (src[2] + 0.5)) * (cos_yaw))
    if fwd_pos > MAX_FWD_POS:
        return None

    return gx, gz, gv


# =========================
# Full jump simulation
# =========================
def simulate_jump(src, dest, N, start_fwd, lat, jump_boost,
                  sin_yaw, cos_yaw, f, ground_accel, *, is_sprint=True):
    run = simulate_runup(src, start_fwd, lat, N,
                         sin_yaw, cos_yaw, f, ground_accel)
    if run is None:
        return None

    gx, gz, gv = run

    success, ticks, pos = simulate_airborne(
        src, dest, gx, gz, gv, jump_boost,
        sin_yaw, cos_yaw, is_sprint=is_sprint
    )

    dx = pos[0] - (dest[0] + 0.5)
    dz = pos[2] - (dest[2] + 0.5)
    error = math.sqrt(dx * dx + dz * dz)

    return success, error, ticks, pos


# =========================
# Testing framework
# =========================
def run_tests(num_tests=1000):
    success_count = 0
    failures = []

    for i in range(num_tests):
        dist = random.randint(1, 5)
        dx   = random.randint(-dist, dist)
        dz   = random.randint(-dist, dist)

        if dx == 0 and dz == 0:
            continue

        length = math.sqrt(dx * dx + dz * dz)
        if length > 0:
            dx = int(round(dx / length * dist))
            dz = int(round(dz / length * dist))

        src  = (0, 64, 0)
        dest = (dx, 64, dz)

        yaw     = math.atan2(-dx, dz)
        sin_yaw = math.sin(yaw)
        cos_yaw = math.cos(yaw)

        best = None

        # ── sweep over:  sprint / walk  ×  run-up ticks  ×  start positions ×  lateral offsets
        for is_sprint in (True, False):
            ga = GROUND_ACCEL_SPRINT if is_sprint else GROUND_ACCEL_WALK

            for N in range(0, 21):
                # start_fwd: -0.65 … +0.20 in steps of 0.05
                # Wider backward range lets the solver find edge-optimised positions
                # for long diagonal jumps without using physically invalid ones.
                for sf_idx in range(-13, 5):
                    start_fwd = sf_idx * 0.05

                    for abs_lat in [0.0, 0.1, 0.2]:
                        lats = [0.0] if abs_lat == 0 else [abs_lat, -abs_lat]

                        for lat in lats:
                            result = simulate_jump(
                                src, dest,
                                N, start_fwd, lat,
                                0,              # jump_boost amplifier
                                sin_yaw, cos_yaw,
                                GROUND_F, ga,
                                is_sprint=is_sprint
                            )

                            if result is None:
                                continue

                            success, error, ticks, pos = result
                            # Prefer success; among successes prefer smaller error.
                            score = (0 if success else 1, error)

                            if best is None or score < best[0]:
                                best = (score, success, error, ticks, pos)

        if best is None:
            print(f"[{i}] FAIL (no valid solution found)")
            failures.append(None)
            continue

        _, success, error, ticks, pos = best

        if success:
            success_count += 1

        print(f"[{i}] {'SUCCESS' if success else 'FAIL   '} "
              f"err={error:.4f} ticks={ticks:2d} "
              f"src={src} dest={dest} "
              f"pos=({pos[0]:.2f},{pos[1]:.2f},{pos[2]:.2f})")

        if i % 100 == 0 and i > 0:
            print(f"--- Progress: {i}/{num_tests} ---")

    print()
    print("==== RESULTS ====")
    print(f"Success rate: {success_count}/{num_tests} "
          f"({100 * success_count / num_tests:.1f}%)")
    if failures:
        print(f"No-solution cases: {len(failures)}")


# =========================
# Quick sanity check
# =========================
def sanity_check():
    """Print results for a fixed set of well-known cases."""
    cases = {
        # (dx, dz) : expected
        (1,  0): True,   # 1-block straight   — walk jump
        (2,  0): True,   # 2-block straight
        (3,  0): True,   # 3-block straight
        (4,  0): True,   # 4-block straight   — needs full sprint
        (5,  0): False,  # 5-block straight   — impossible
        (1,  1): True,   # 1-block diagonal
        (2,  2): True,
        (3,  3): True,
        (4,  4): False,
        (3, -4): True,   # 5-block "diagonal" — possible with angle optimisation
        (-1, 0): True,
        (-1, 1): True,
        (-1, 2): True,
        (-1, 3): True,
        (-1, 4): True,  #
        (-1, 5): False, #
    }

    src = (0, 64, 0)
    print(f"{'dest':>12}  {'result':>8}  {'expected':>8}  {'ok':>4}  err    ticks")
    print("-" * 60)
    all_ok = True
    for (dx, dz), expected in cases.items():
        dest    = (dx, 64, dz)
        yaw     = math.atan2(-dx, dz)
        sin_yaw = math.sin(yaw)
        cos_yaw = math.cos(yaw)
        best    = None

        for is_sprint in (True, False):
            ga = GROUND_ACCEL_SPRINT if is_sprint else GROUND_ACCEL_WALK
            for N in range(0, 15):
                for sf_idx in range(-13, 5):
                    for lat in [0.0, 0.1, -0.1]:
                        r = simulate_jump(
                            src, dest, N, sf_idx * 0.05, lat, 0,
                            sin_yaw, cos_yaw, GROUND_F, ga,
                            is_sprint=is_sprint
                        )
                        if r is None:
                            continue
                        s, e, t, p = r
                        score = (0 if s else 1, e)
                        if best is None or score < best[0]:
                            best = (score, s, e, t, p)

        if best:
            _, success, error, ticks, _ = best
        else:
            success, error, ticks = False, 999.0, -1

        ok    = success == expected
        mark  = "✓" if ok else "✗ WRONG"
        all_ok = all_ok and ok
        print(f"  dest=({dx:2d},{dz:2d})    {'SUCCESS' if success else 'FAIL   '}"
              f"   {'YES' if expected else 'NO ':>6}    {mark}  {error:.4f}  {ticks}")

    print()
    print("All correct!" if all_ok else "SOME CASES WRONG — check above.")


if __name__ == "__main__":
    print("=== Sanity check ===")
    sanity_check()
    print()
    print("=== Random test (100 cases) ===")
    run_tests(100)