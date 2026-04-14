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

package baritone.api.process;

/**
 * A checkpoint index range in a route.
 *
 * <p>For a goal list {@code [g0, g1, g2, g3]}, {@code betweenGoals(1, 3)} affects
 * segments {@code g1->g2} and {@code g2->g3}.</p>
 */
public final class RouteSegmentRange {

    private final int fromGoalIndexInclusive;
    private final int toGoalIndexInclusive;

    private RouteSegmentRange(int fromGoalIndexInclusive, int toGoalIndexInclusive) {
        if (fromGoalIndexInclusive < 0) {
            throw new IllegalArgumentException("fromGoalIndexInclusive must be >= 0");
        }
        if (toGoalIndexInclusive <= fromGoalIndexInclusive) {
            throw new IllegalArgumentException("toGoalIndexInclusive must be greater than fromGoalIndexInclusive");
        }
        this.fromGoalIndexInclusive = fromGoalIndexInclusive;
        this.toGoalIndexInclusive = toGoalIndexInclusive;
    }

    public static RouteSegmentRange betweenGoals(int fromGoalIndexInclusive, int toGoalIndexInclusive) {
        return new RouteSegmentRange(fromGoalIndexInclusive, toGoalIndexInclusive);
    }

    public int getFromGoalIndexInclusive() {
        return fromGoalIndexInclusive;
    }

    public int getToGoalIndexInclusive() {
        return toGoalIndexInclusive;
    }

    /**
     * Segment index uses leg semantics: segment {@code i} is {@code goal[i] -> goal[i + 1]}.
     */
    public boolean includesSegment(int segmentIndex) {
        return segmentIndex >= fromGoalIndexInclusive && segmentIndex < toGoalIndexInclusive;
    }
}

