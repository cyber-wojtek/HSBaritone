# CLAUDE.md
This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Baritone is a Minecraft pathfinding bot that provides automated movement and task execution in Minecraft. It supports multiple mod loaders (Forge, NeoForge, Fabric, and Tweaker) and multiple Minecraft versions ranging from 1.12.2 to 1.21.x.

The project is written in Java and uses a modular architecture with:
- **API layer** (`src/api/java/baritone/api`): Public API interfaces and contracts
- **Implementation** (`src/main/java/baritone`): Core pathfinding and behavior logic  
- **Platform modules**: Separate modules for each mod loader (`fabric/`, `neoforge/`, `forge/`, `tweaker/`)
- **BuildSrc**: Custom Gradle tasks for ProGuard optimization and distribution

## Build Commands

### Building the Project
```bash
./gradlew build                    # Build all platforms
./gradlew :tweaker:build          # Build tweaker (vanilla) version
./gradlew :fabric:build           # Build Fabric version
./gradlew :neoforge:build         # Build NeoForge version
./gradlew :forge:build            # Build Forge version
```

### Running Development Instances
```bash
./gradlew :tweaker:runClient       # Run tweaker client with IDE
./gradlew :fabric:runClient        # Run Fabric client
./gradlew :neoforge:runClient      # Run NeoForge client
./gradlew :forge:runClient         # Run Forge client
```

### Testing
```bash
./gradlew test                     # Run unit tests
./gradlew test --tests "*SkyblockTextParserTest"  # Run specific test
```

### Generate Sources/Runs (if needed)
```bash
./gradlew :genIntellijRuns         # Generate run configurations for IntelliJ
./gradlew setupDecompWorkspace     # For older branches that require setup
```

## Architecture

### Core Components

**Baritone.java** - Central coordinator that manages all behaviors and processes
- Initializes all behaviors (Pathing, Loot, Look, etc.)
- Manages process stack (functions that control the bot)
- Provides the IBaritone implementation

**Behaviors** (`src/main/java/baritone/behavior/`)
- `PathingBehavior`: Core pathfinding execution
- `LookBehavior`: Camera/head movement control  
- `MemoryBehavior`: World caching and memory management
- `InventoryBehavior`: Inventory management
- `SkyblockTransportBehavior`: NEW - Skyblock-specific transport mechanics

**Pathfinding Engine** (`src/main/java/baritone/pathing/`)
- `calc/AStarPathFinder`: Core A* implementation
- `calc/Path`: Path representation with movements
- `calc/PathNode`: Individual path nodes
- `movement/Movement`: Abstract movement types
- `movement/movements/`: Concrete movement implementations
  - NEW: MovementSkyblockEtherTransmission, MovementSkyblockInstantTransmission

**Processes** (`src/main/java/baritone/process/`)
- `CustomGoalProcess`: Path to arbitrary goals
- `GetToBlockProcess`: Navigate to specific blocks
- `MineProcess`: Mining logic
- `FarmProcess`: Farming automation
- `ElytraProcess`: Elytra flying with fireworks
- `ElytraBehavior`: NEW - Extended elytra mechanics

**Commands** (`src/main/java/baritone/command/`)
- `defaults/*`: Command implementations (GoalCommand, GotoCommand, etc.)
- `defaults/RouteArgumentParser`: NEW - Route/chunk pattern parsing

### Minecraft Version Notes

- Current branch (1.21.10) uses Java 21
- Each Minecraft version release gets its own major Baritone version
- Version mapping: 1.12 = v1.2, 1.13 = v1.3, ..., 1.21.x = v1.15+

## Recent Changes (Skyblock Integration)

This branch includes new Skyblock functionality:
- SkyblockTransportBehavior: Enhanced transport for skyblock islands
- MovementSkyblockEtherTransmission: "Ether transmission" movement type
- MovementSkyblockInstantTransmission: "Instant transmission" movement type
- SkyblockTextParser: Parse Skyblock chat/route patterns
- RouteArgumentParser: Parse route instructions for sky navigation

Files modified for Skyblock:
- `src/main/java/baritone/behavior/SkyblockTransportBehavior.java` (NEW)
- `src/main/java/baritone/utils/SkyblockTextParser.java` (NEW)  
- `src/main/java/baritone/command/defaults/RouteArgumentParser.java` (NEW)
- `src/main/java/baritone/pathing/movement/MovementSkyblock*.java` (NEW)

## Key Design Patterns

**Process Stack**: Baritone uses a stack-based process system where processes can be pushed/popped. Only the top process executes.

**Event System**: Uses a custom event bus for game events (Tick, Chunk, Render, etc.). See `src/api/java/baritone/api/event/`.

**Goals**: Pathfinding goals implement the Goal interface. Common types: GoalBlock, GoalXZ, GoalGetToBlock, GoalComposite (for multiple targets).

**Movement Types**: Each movement type extends Movement and implements cost calculation and execution logic.

**Settings**: All settings are in `baritone.api.Settings` for API compatibility and persistence.

## Development Tips

**Testing Changes**: 
- Use `./gradlew test` to verify no regressions
- Test commands in Minecraft: `#goto X Y Z`, `#mine diamond_ore`, `#stop`
- Check path rendering is working (F3+G shows chunk borders)

**Performance**: 
- Paths calculate on a separate thread
- Chunk caching uses 2-bit compact representation
- Timeout defaults in Settings.java (primaryTimeoutMS, failureTimeoutMS)

**Debugging**:
- Enable `renderCachedChunks` to see cached chunks
- Use `repack` command to refresh cache
- Check `baritone/settings.txt` in Minecraft directory for saved settings

## Common Pitfalls

1. **Access Wideners**: This project uses Mixin and careful access management. Changes to access patterns may break reflection in BaritoneProvider.

2. **Version Strings**: Version is auto-detected from git tags via `git describe`. If no tags, falls back to `mod_version` in gradle.properties.

3. **ProGuard**: Standalone/optimized builds use ProGuard. This can make stack traces hard to read - use unoptimized builds for debugging.

4. **Async**: Path calculation is asynchronous. The main thread accesses results via `bestPathSoFar` and `pathToTarget`.

5. **Skyblock Changes**: The recent Skyblock additions are experimental. When modifying pathfinding, ensure compatibility with both normal and Skyblock worlds.