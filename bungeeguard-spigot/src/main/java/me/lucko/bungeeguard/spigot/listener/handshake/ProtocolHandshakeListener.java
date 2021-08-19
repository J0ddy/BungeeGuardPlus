/*
 * This file is part of BungeeGuard, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.bungeeguard.spigot.listener.handshake;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.injector.server.TemporaryPlayerFactory;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import me.lucko.bungeeguard.spigot.TokenStore;
import me.lucko.bungeeguard.spigot.listener.AbstractTokenListener;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.chat.ComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

/**
 * A handshake listener using ProtocolLib.
 */
public class ProtocolHandshakeListener extends AbstractTokenListener {

    private static final Set<String> ALLOWED_ADDRESSES = new HashSet<>(Arrays.asList("localhost", "127.0.0.1", "172.17.0.1"));

    public ProtocolHandshakeListener(TokenStore tokenStore, Logger logger, ConfigurationSection config) {
        super(tokenStore, logger, config);
    }

    public void registerAdapter(Plugin plugin) {
        ProtocolLibrary.getProtocolManager().addPacketListener(new Adapter(plugin));
    }

    private final class Adapter extends PacketAdapter {
        Adapter(Plugin plugin) {
            super(plugin, ListenerPriority.LOWEST, PacketType.Handshake.Client.SET_PROTOCOL);
        }

        @Override
        public void onPacketReceiving(PacketEvent event) {
            PacketContainer packet = event.getPacket();

            // only handle the LOGIN phase
            PacketType.Protocol state = packet.getProtocols().read(0);
            boolean login = state == PacketType.Protocol.LOGIN;
            if (!login && !protectStatus && isAllowed(event.getPlayer().getAddress().getAddress().getHostAddress())) {
                return;
            }

            String handshake = packet.getStrings().read(0);
            BungeeCordHandshake decoded = BungeeCordHandshake.decodeAndVerify(handshake,  tokenStore);

            if (decoded instanceof BungeeCordHandshake.Fail) {
                BungeeCordHandshake.Fail fail = (BungeeCordHandshake.Fail) decoded;
                if (login && !isThrottled()) {
                    String ip = "null";
                    InetSocketAddress address = event.getPlayer().getAddress();
                    if (address != null) {
                        ip = address.getHostString();
                        if (ip.length() > 15) {
                            ip = BungeeCordHandshake.encodeBase64(ip);
                        }
                    }
                    logger.warning("[BungeeGuard] Denying connection from " + ip + " - " + fail.connectionDescription() + " - reason: " + fail.reason().name());
                }

                String kickMessage = fail.reason() == BungeeCordHandshake.Fail.Reason.INVALID_HANDSHAKE ? noDataKickMessage : invalidTokenKickMessage;
                try {
                    closeConnection(event.getPlayer(), kickMessage, login);
                } catch (Exception e) {
                    e.printStackTrace();
                }

                // just in-case the connection didn't close, screw up the hostname
                // so Spigot can't pick up anything that might've been spoofed in nms.HandshakeListener
                packet.getStrings().write(0, "null");

                return;
            }

            // great, handshake was decoded and verified successfully.
            // we can re-encode the handshake now so Spigot can pick up the spoofed stuff.
            BungeeCordHandshake.Success data = (BungeeCordHandshake.Success) decoded;
            packet.getStrings().write(0, data.encode());
        }
    }

    private static void closeConnection(Player player, String kickMessage, boolean login) throws Exception {
        if (login) {
            PacketContainer packet = new PacketContainer(PacketType.Login.Server.DISCONNECT);
            packet.getModifier().writeDefaults();

            WrappedChatComponent component = WrappedChatComponent.fromJson(ComponentSerializer.toString(TextComponent.fromLegacyText(kickMessage)));
            packet.getChatComponents().write(0, component);

            ProtocolLibrary.getProtocolManager().sendServerPacket(player, packet);
        }
        TemporaryPlayerFactory.getInjectorFromPlayer(player).getSocket().close();
    }

    private static boolean isAllowed(String address) {
        return ALLOWED_ADDRESSES.contains(address);
    }

}
