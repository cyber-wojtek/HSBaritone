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

import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.RelativeCoordinate;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.process.RouteSegmentRange;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

final class RouteArgumentParser {

    private RouteArgumentParser() {
    }

    static ParsedRoute parseRoute(IArgConsumer args, BetterBlockPos origin) throws CommandException {
        args.requireMin(6);
        List<Goal> goals = new ArrayList<>();
        List<RouteSegmentRange> noSkyblockAbilityRanges = new ArrayList<>();
        while (args.hasAny()) {
            if (";".equals(args.peekString())) {
                args.get();
                continue;
            }
            if (isRangeSpecifierToken(args.peekString())) {
                args.get();
                parseNoSkyblockAbilityRanges(args, noSkyblockAbilityRanges);
                break;
            }
            goals.add(new GoalBlock(
                    Mth.floor(args.getDatatypePost(RelativeCoordinate.INSTANCE, (double) origin.x)),
                    Mth.floor(args.getDatatypePost(RelativeCoordinate.INSTANCE, (double) origin.y)),
                    Mth.floor(args.getDatatypePost(RelativeCoordinate.INSTANCE, (double) origin.z))
            ));
            if (args.hasAny() && ";".equals(args.peekString())) {
                args.get();
            }
        }
        return new ParsedRoute(goals, noSkyblockAbilityRanges);
    }

    private static boolean isRangeSpecifierToken(String token) {
        return "nosb".equalsIgnoreCase(token) || "noskyblock".equalsIgnoreCase(token);
    }

    private static void parseNoSkyblockAbilityRanges(IArgConsumer args, List<RouteSegmentRange> output) throws CommandException {
        if (!args.hasAny()) {
            throw new CommandInvalidStateException("Expected at least one range after nosb (example: nosb 0-2,4-5)");
        }
        while (args.hasAny()) {
            String token = args.getString();
            if (";".equals(token)) {
                continue;
            }
            String[] parts = token.split(",");
            for (String part : parts) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                output.add(parseRange(trimmed));
            }
        }
        if (output.isEmpty()) {
            throw new CommandInvalidStateException("Expected at least one valid range after nosb (example: nosb 0-2,4-5)");
        }
    }

    private static RouteSegmentRange parseRange(String token) throws CommandException {
        int dash = token.indexOf('-');
        if (dash <= 0 || dash >= token.length() - 1 || token.indexOf('-', dash + 1) >= 0) {
            throw new CommandInvalidStateException("Invalid range '" + token + "'. Use format from-to, e.g. 0-2");
        }
        int from;
        int to;
        try {
            from = Integer.parseInt(token.substring(0, dash));
            to = Integer.parseInt(token.substring(dash + 1));
        } catch (NumberFormatException ex) {
            throw new CommandInvalidStateException("Invalid range '" + token + "'. Use integer indices like 0-2");
        }
        try {
            return RouteSegmentRange.betweenGoals(from, to);
        } catch (IllegalArgumentException ex) {
            throw new CommandInvalidStateException(ex.getMessage());
        }
    }

    static final class ParsedRoute {
        final List<Goal> goals;
        final List<RouteSegmentRange> noSkyblockAbilityRanges;

        ParsedRoute(List<Goal> goals, List<RouteSegmentRange> noSkyblockAbilityRanges) {
            this.goals = goals;
            this.noSkyblockAbilityRanges = noSkyblockAbilityRanges;
        }
    }
}

