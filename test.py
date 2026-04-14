import math

AIR_DRAG             = 0.91
GRAVITY              = 0.08
VERTICAL_DRAG        = 0.98
AIR_ACCEL_BASE       = 0.02
SPRINT_MULTIPLIER    = 1.3
AIR_ACCEL_SPRINT     = AIR_ACCEL_BASE * SPRINT_MULTIPLIER
AIR_ACCEL_WALK       = AIR_ACCEL_BASE
JUMP_VELOCITY        = 0.42
SPRINT_JUMP_BOOST    = 0.2
JUMP_BOOST_INCREMENT = 0.1
PLAYER_WIDTH         = 0.6
SLIPPERINESS         = 0.6
GROUND_F             = SLIPPERINESS * AIR_DRAG  # 0.546

SPEED_LEVEL = 0
SPEED_ATTRIBUTE = 0.1
BASE_SPEED = SPEED_ATTRIBUTE * (1 + 0.2 * SPEED_LEVEL)

# getFrictionInfluencedSpeed: speed * (0.216 / f^3)
# sprint speed attribute = 0.13 (= 0.1 * 1.3)
# walk  speed attribute = 0.10
SPRINT_SPEED = BASE_SPEED * SPRINT_MULTIPLIER  # 0.13
WALK_SPEED   = BASE_SPEED

def ground_accel(speed, friction=SLIPPERINESS):
    return speed * (0.21600002 / (friction ** 3))

GROUND_ACCEL_SPRINT = ground_accel(SPRINT_SPEED)  # 0.13 * (0.216/0.216) = 0.13
GROUND_ACCEL_WALK   = ground_accel(WALK_SPEED)    # 0.10

print(f"GROUND_ACCEL_SPRINT={GROUND_ACCEL_SPRINT:.6f}")
print(f"GROUND_ACCEL_WALK  ={GROUND_ACCEL_WALK:.6f}")

MAX_AIR_TICKS  = 60
MAX_FWD_POS    = 0.5 + PLAYER_WIDTH / 2  # 0.8
LAND_PAD       = 0.0

# CORRECT ground tick: v_new = (v + accel) * GROUND_F, pos += (v + accel)
def simulate_runup(src, start_fwd, lat, N, sin_yaw, cos_yaw, ground_accel):
    gx = src[0] + 0.5 + start_fwd * (-sin_yaw) + lat * cos_yaw
    gz = src[2] + 0.5 + start_fwd *  cos_yaw   + lat * sin_yaw
    gv = 0.0
    for _ in range(N):
        v_mid = gv + ground_accel          # pre-friction speed
        gx += v_mid * (-sin_yaw)           # pos advances by pre-friction
        gz += v_mid *  cos_yaw
        gv  = v_mid * GROUND_F             # store post-friction for next tick
        fwd = (gx-(src[0]+0.5))*(-sin_yaw)+(gz-(src[2]+0.5))*cos_yaw
        if fwd > MAX_FWD_POS: return None
    fwd_pos = (gx-(src[0]+0.5))*(-sin_yaw)+(gz-(src[2]+0.5))*cos_yaw
    if fwd_pos > MAX_FWD_POS: return None
    return gx, gz, gv, fwd_pos

def apply_jump_tick(gx, gz, gv, sin_yaw, cos_yaw, ground_accel, is_sprint):
    v_mid   = gv + ground_accel            # pre-friction speed
    jx      = gx + v_mid * (-sin_yaw)     # pos advances by pre-friction
    jz      = gz + v_mid *  cos_yaw
    gvL     = v_mid * GROUND_F            # stored deltaMovement post-friction
    if is_sprint:
        gvL += SPRINT_JUMP_BOOST           # sprint boost added after friction
    vx = gvL * (-sin_yaw)
    vz = gvL *  cos_yaw
    return jx, jz, vx, vz, JUMP_VELOCITY

def simulate_airborne(src, dest, jx, jz, vx, vz, vy, jump_boost, sin_yaw, cos_yaw, is_sprint):
    air_accel = AIR_ACCEL_SPRINT if is_sprint else AIR_ACCEL_WALK
    x, y, z   = jx, float(src[1]), jz
    vy       += jump_boost * JUMP_BOOST_INCREMENT
    land_y    = float(dest[1])
    lx0 = dest[0] - LAND_PAD; lx1 = dest[0] + 1.0 + LAND_PAD
    lz0 = dest[2] - LAND_PAD; lz1 = dest[2] + 1.0 + LAND_PAD
    for tick in range(MAX_AIR_TICKS):
        vx += air_accel * (-sin_yaw)
        vz += air_accel *  cos_yaw
        nx = x+vx; ny = y+vy; nz = z+vz

        # Wall collision: player hits side face of dest pillar while below landing height
        if ny < land_y:
            nx_min = nx - PLAYER_WIDTH/2; nx_max = nx + PLAYER_WIDTH/2
            nz_min = nz - PLAYER_WIDTH/2; nz_max = nz + PLAYER_WIDTH/2
            px_min = x - PLAYER_WIDTH/2;  px_max = x + PLAYER_WIDTH/2
            pz_min = z - PLAYER_WIDTH/2;  pz_max = z + PLAYER_WIDTH/2
            dest_x0 = dest[0]; dest_x1 = dest[0] + 1.0
            dest_z0 = dest[2]; dest_z1 = dest[2] + 1.0
            was_overlapping = (px_max > dest_x0 and px_min < dest_x1 and
                               pz_max > dest_z0 and pz_min < dest_z1)
            now_overlapping = (nx_max > dest_x0 and nx_min < dest_x1 and
                               nz_max > dest_z0 and nz_min < dest_z1)
            if now_overlapping and not was_overlapping:
                return False, tick+1, (nx, ny, nz)

        if y >= land_y and ny <= land_y:
            f  = (y-land_y)/(y-ny) if y != ny else 0.0
            lx = x+f*(nx-x); lz = z+f*(nz-z)
            if lx0 <= lx <= lx1 and lz0 <= lz <= lz1:
                return True, tick+1, (lx, land_y, lz)
            if ny < land_y - 0.5:
                return False, tick+1, (nx, ny, nz)
        x=nx; y=ny; z=nz
        vx*=AIR_DRAG; vz*=AIR_DRAG
        vy=(vy-GRAVITY)*VERTICAL_DRAG
        if abs(vy)<0.005: vy=0
        if y < land_y-2: return False, tick+1, (x,y,z)
        if y < -100: break
    return False, MAX_AIR_TICKS, (x,y,z)

def solve(src, dest, jump_boost=0):
    dx = dest[0]-src[0]; dz = dest[2]-src[2]
    yaw = math.atan2(-dx, dz)
    sin_yaw = math.sin(yaw); cos_yaw = math.cos(yaw)
    best = None
    for is_sprint in (True, False):
        ga = GROUND_ACCEL_SPRINT if is_sprint else GROUND_ACCEL_WALK
        for N in range(0, 21):
            for sf_idx in range(-26, 10):
                start_fwd = sf_idx * 0.025
                abs_lat = 0.0
                while abs_lat <= 0.5:
                    lats = [0.0] if abs_lat == 0.0 else [abs_lat, -abs_lat]
                    for lat in lats:
                        run = simulate_runup(src, start_fwd, lat, N, sin_yaw, cos_yaw, ga)
                        if run is None: continue
                        gx, gz, gv, fwd_pre = run
                        jx, jz, vx, vz, vy = apply_jump_tick(gx, gz, gv, sin_yaw, cos_yaw, ga, is_sprint)
                        jtf = (jx-(src[0]+0.5))*(-sin_yaw)+(jz-(src[2]+0.5))*cos_yaw
                        if jtf > MAX_FWD_POS: continue
                        ok, ticks, pos = simulate_airborne(src, dest, jx, jz, vx, vz, vy, jump_boost, sin_yaw, cos_yaw, is_sprint)
                        dx2=pos[0]-(dest[0]+0.5); dz2=pos[2]-(dest[2]+0.5)
                        err=math.sqrt(dx2*dx2+dz2*dz2)
                        score=(0 if ok else 1, err)
                        #if ok:
                            #print(f"DEBUG: is_sprint={is_sprint} N={N} start_fwd={start_fwd:.2f} lat={lat:.2f} "
                            #      f"fwd_pre={fwd_pre:.4f} jtf={jtf:.4f} ticks={ticks} err={err:.4f}")
                        if best is None or score < best['score'] and ok:
                            best=dict(score=score,success=ok,N=N,is_sprint=is_sprint,
                                      error=err,jtf=fwd_pre,actual_jtf=jtf)
                    abs_lat += 0.05
    return best

cases = {
    (1,0,0):True,(2,0,0):True,(3,0,0):True,(4,0,0):True,(5,0,0):True,(6,0,0):False,
    (7,0,0):False,
    (1,1,0):True,(2,2,0):True,(3,3,0):True,(4,4,0):True,(5,5,0):False,
    (3,-4,0):True,(-1,0,0):True,(-1,1,0):True,(-1,2,0):True,(-1,3,0):True,
    (-1,4,0):True,(-1,5,0):True,(-1,6,0):False,
    (1,0,1):True,(2,0,1):True,(3,0,1):True,(4,0,1):True,(5,0,1):False,
    (1,1,1):True,(2,2,1):True,(3,3,1):True,(4,4,1):False,
    (2,0,-1):True,(3,0,-1):True,(4,0,-1):True,(5,0,-1):True,(6,0,-1):True,(7,0,-1):False,
}

src=(0,64,0)
all_ok=True
print(f"{'dest':>16}  {'sim':>7}  {'expected':>8}  {'ok':>8}  {'err':>8}  N  sprint   jtf actual_jtf")
print("-"*80)
for (dx,dz,dy),expected in {(5,0,0):True,(-5,0,0):True,(0,5,0):True,(0,-5,0):True}.items():
    dest=(dx,64+dy,dz)
    plan=solve(src,dest)
    sim_ok=plan['success'] if plan else False
    ok=sim_ok==expected
    all_ok=all_ok and ok
    mark="✓" if ok else "✗ WRONG"
    e=plan['error'] if plan else 999.0
    N=plan['N'] if plan else -1
    sp="sprint" if (plan and plan['is_sprint']) else "walk  "
    jtf=plan['jtf'] if plan else 0.0
    ajtf=plan['actual_jtf'] if plan else 0.0
    print(f"  dest=({dx:2d},{dz:2d},{dy:+d})  {'SUCCESS' if sim_ok else 'FAIL   '}  "
          f"{'YES' if expected else 'NO ':>8}  {mark:<9} {e:7.4f}  {N:2d}  {sp}  {jtf:.4f} {ajtf:.4f}")
print()
print("All correct!" if all_ok else "SOME CASES WRONG.")