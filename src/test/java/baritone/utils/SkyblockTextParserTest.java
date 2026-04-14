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

import org.junit.Test;

import java.util.OptionalInt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SkyblockTextParserTest {

    @Test
    public void parsesManaFromFractionFormat() {
        OptionalInt mana = SkyblockTextParser.extractMana("1354/1711✎ Mana");
        assertTrue(mana.isPresent());
        assertEquals(1354, mana.getAsInt());
    }

    @Test
    public void parsesManaFromFractionFormatWithCommas() {
        OptionalInt mana = SkyblockTextParser.extractMana("1,308/1,308✎ Mana");
        assertTrue(mana.isPresent());
        assertEquals(1308, mana.getAsInt());
    }

    @Test
    public void parsesManaFromLabelFormat() {
        OptionalInt mana = SkyblockTextParser.extractMana("Mana: 420");
        assertTrue(mana.isPresent());
        assertEquals(420, mana.getAsInt());
    }

    @Test
    public void parsesManaFromLabelFormatWithCommas() {
        OptionalInt mana = SkyblockTextParser.extractMana("Mana: 1,308");
        assertTrue(mana.isPresent());
        assertEquals(1308, mana.getAsInt());
    }

    @Test
    public void ignoresNonManaMessages() {
        OptionalInt mana = SkyblockTextParser.extractMana("Ability is on cooldown");
        assertFalse(mana.isPresent());
    }
}

