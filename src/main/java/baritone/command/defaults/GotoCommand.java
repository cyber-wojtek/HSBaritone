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

package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.ForBlockOptionalMeta;
import baritone.api.command.datatypes.RelativeCoordinate;
import baritone.api.command.datatypes.RelativeGoal;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.BlockOptionalMeta;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class GotoCommand extends Command {

    protected GotoCommand(IBaritone baritone) {
        super(baritone, "goto");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        if (args.hasAny() && "route".equalsIgnoreCase(args.peekString())) {
            args.get();
            RouteArgumentParser.ParsedRoute route = RouteArgumentParser.parseRoute(args, ctx.playerFeet());
            List<Goal> goals = route.goals;
            if (goals.size() < 2) {
                throw new CommandInvalidStateException("Route mode requires at least 2 checkpoints");
            }
            logDirect(String.format("Routing through %d checkpoints", goals.size()));
            baritone.getCustomGoalProcess().setGoalAndPath(goals, route.noSkyblockAbilityRanges);
            return;
        }

        // If we have a numeric first argument, then parse arguments as coordinates.
        // Note: There is no reason to want to go where you're already at so there
        // is no need to handle the case of empty arguments.
        if (args.peekDatatypeOrNull(RelativeCoordinate.INSTANCE) != null) {
            args.requireMax(3);
            BetterBlockPos origin = ctx.playerFeet();
            Goal goal = args.getDatatypePost(RelativeGoal.INSTANCE, origin);
            logDirect(String.format("Going to: %s", goal.toString()));
            baritone.getCustomGoalProcess().setGoalAndPath(goal);
            return;
        }
        args.requireMax(1);
        BlockOptionalMeta destination = args.getDatatypeFor(ForBlockOptionalMeta.INSTANCE);
        baritone.getGetToBlockProcess().getToBlock(destination);
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return new baritone.api.command.helpers.TabCompleteHelper().append("route").filterPrefix(args.getString()).stream();
        }
        if (args.hasAny() && "route".equalsIgnoreCase(args.peekString())) {
            args.get();
            return args.tabCompleteDatatype(RelativeCoordinate.INSTANCE);
        }
        // since it's either a goal or a block, I don't think we can tab complete properly?
        // so just tab complete for the block variant
        args.requireMax(1);
        return args.tabCompleteDatatype(ForBlockOptionalMeta.INSTANCE);
    }

    @Override
    public String getShortDesc() {
        return "Go to a coordinate or block";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The goto command tells Baritone to head towards a given goal or block.",
                "",
                "Wherever a coordinate is expected, you can use ~ just like in regular Minecraft commands. Or, you can just use regular numbers.",
                "",
                "Usage:",
                "> goto <block> - Go to a block, wherever it is in the world",
                "> goto route <x1> <y1> <z1> [;] <x2> <y2> <z2> ... - Go via ordered checkpoints",
                "> goto route ... nosb <from-to[,from-to...]> - Disable SB abilities on checkpoint segment ranges",
                "> goto <y> - Go to a Y level",
                "> goto <x> <z> - Go to an X,Z position",
                "> goto <x> <y> <z> - Go to an X,Y,Z position"
        );
    }
}
