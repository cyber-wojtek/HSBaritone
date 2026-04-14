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

package baritone.behavior;

import baritone.Baritone;
import baritone.api.event.events.PacketEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.WorldEvent;
import baritone.api.event.events.type.EventState;
import baritone.utils.SkyblockTextParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import java.util.OptionalInt;

public final class SkyblockTransportBehavior extends Behavior {

    private long tickCounter;
    private volatile int currentMana = -1;
    private long manaUpdatedAtTick = Long.MIN_VALUE;

    public SkyblockTransportBehavior(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() != TickEvent.Type.IN) {
            return;
        }
        tickCounter++;

        if (currentMana >= 0) {
            long age = tickCounter - manaUpdatedAtTick;
            if (age > Baritone.settings().skyblockManaStaleTicks.value) {
                currentMana = -1;
            }
        }
    }

    @Override
    public void onPostTick(TickEvent event) {
        // No-op: movement classes consume this behavior as telemetry only.
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        if (event.getState() == EventState.POST) {
            resetState();
        }
    }

    @Override
    public void onReceivePacket(PacketEvent event) {
        if (event.getState() != EventState.PRE) {
            return;
        }

        if (event.getPacket() instanceof ClientboundSetActionBarTextPacket packet) {
            updateMana(packet.text());
            return;
        }
        if (event.getPacket() instanceof ClientboundSetTitleTextPacket packet) {
            updateMana(packet.text());
            return;
        }
        if (event.getPacket() instanceof ClientboundSetSubtitleTextPacket packet) {
            updateMana(packet.text());
            return;
        }
        if (event.getPacket() instanceof ClientboundSystemChatPacket packet && packet.overlay()) {
            updateMana(packet.content());
        }
    }

    private void updateMana(Component message) {
        OptionalInt mana = SkyblockTextParser.extractMana(message.getString());
        if (mana.isPresent()) {
            currentMana = mana.getAsInt();
            manaUpdatedAtTick = tickCounter;
        }
    }

    public int getEstimatedMana() {
        return currentMana;
    }

    public void predictManaUse(int cost) {
        if (currentMana >= 0 && cost > 0) {
            currentMana = Math.max(0, currentMana - cost);
            manaUpdatedAtTick = tickCounter;
        }
    }

    private void resetState() {
        tickCounter = 0;
        currentMana = -1;
        manaUpdatedAtTick = Long.MIN_VALUE;
    }
}



