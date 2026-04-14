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

package baritone.utils;

import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SkyblockTextParser {

    private static final String NUMBER = "(?:\\d{1,3}(?:,\\d{3})+|\\d{1,7})";
    private static final Pattern MANA_FRACTION = Pattern.compile("(" + NUMBER + ")\\s*/\\s*" + NUMBER + "\\s*(?:\\u270E|mana)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MANA_LABEL = Pattern.compile("(?:\\u270E|mana)\\s*:?[\\s]*(" + NUMBER + ")", Pattern.CASE_INSENSITIVE);

    private SkyblockTextParser() {}

    public static OptionalInt extractMana(String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return OptionalInt.empty();
        }

        Matcher fractionMatcher = MANA_FRACTION.matcher(rawText);
        if (fractionMatcher.find()) {
            return parseInt(fractionMatcher.group(1));
        }

        Matcher labelMatcher = MANA_LABEL.matcher(rawText);
        if (labelMatcher.find()) {
            return parseInt(labelMatcher.group(1));
        }

        return OptionalInt.empty();
    }

    private static OptionalInt parseInt(String value) {
        try {
            return OptionalInt.of(Integer.parseInt(value.replace(",", "")));
        } catch (NumberFormatException ignored) {
            return OptionalInt.empty();
        }
    }
}

