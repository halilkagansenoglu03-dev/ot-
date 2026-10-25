package com.hornlink;

import com.hornlink.mixin.MinecraftAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Window A (sender): when its player uses a goat horn, send a tiny UDP packet to 127.0.0.1.
 * Window B (receiver): listen for that packet and perform a right click.
 */
public class HornLinkClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("hornlink");
    private static final String MAGIC = "HORNLINK-CLICK";

    private enum Role { NONE, SENDER, RECEIVER }

    private static HornLinkConfig config;
    private static volatile Role role = Role.NONE;
    private static final AtomicBoolean pendingClick = new AtomicBoolean(false);
    private static int holdRemaining = 0;
    private static long lastSendMs = 0;

    @Override
    public void onInitializeClient() {
        config = HornLinkConfig.load();
        ClientLifecycleEvents.CLIENT_STARTED.register(HornLinkClient::resolveRole);
        UseItemCallback.EVENT.register(HornLinkClient::onUseItem);
        ClientTickEvents.END_CLIENT_TICK.register(HornLinkClient::onTick);
    }

    // ---------------------------------------------------------------- role

    private static void resolveRole(Minecraft client) {
        if (!config.enabled) {
            LOG.info("Horn Link is disabled in the config.");
            return;
        }

        String forced = System.getProperty("hornlink.role");
        String r = (forced != null ? forced : config.role).toLowerCase(Locale.ROOT);

        if (r.equals("auto")) {
            String me = client.getUser().getName();
            if (!config.receiverName.isEmpty() && me.equalsIgnoreCase(config.receiverName)) {
                r = "receiver";
            } else if (!config.receiverName.isEmpty()
                    && (config.senderName.isEmpty() || me.equalsIgnoreCase(config.senderName))) {
                r = "sender";
            } else {
                r = "none";
                LOG.warn("Horn Link: role=auto but receiverName is not set in config/hornlink.properties. Doing nothing.");
            }
        }

        switch (r) {
            case "sender" -> {
                role = Role.SENDER;
                LOG.info("Horn Link: this window is the SENDER (port {}).", config.port);
            }
            case "receiver" -> {
                role = Role.RECEIVER;
                startListener();
            }
            default -> role = Role.NONE;
        }
    }

    // ---------------------------------------------------------------- sender

    private static InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
        // This event also fires on the integrated server; only react to the real local player.
        if (role != Role.SENDER || !(player instanceof LocalPlayer)) return InteractionResult.PASS;

        ItemStack stack = player.getItemInHand(hand);
        if (!stack.is(Items.GOAT_HORN)) return InteractionResult.PASS;

        String components = String.valueOf(stack.getComponents());
        LOG.info("Horn Link: goat horn used ({})", components);

        if (!config.horn.equals("any")
                && !components.toLowerCase(Locale.ROOT).contains(config.horn + "_goat_horn")) {
            return InteractionResult.PASS;
        }

        long now = System.currentTimeMillis();
        if (now - lastSendMs < config.debounceMs) return InteractionResult.PASS;
        lastSendMs = now;

        send();
        return InteractionResult.PASS;
    }

    private static void send() {
        try (DatagramSocket s = new DatagramSocket()) {
            byte[] data = MAGIC.getBytes(StandardCharsets.UTF_8);
            s.send(new DatagramPacket(data, data.length, InetAddress.getLoopbackAddress(), config.port));
        } catch (IOException e) {
            LOG.warn("Horn Link: could not send trigger", e);
        }
    }

    // ---------------------------------------------------------------- receiver

    private static void startListener() {
        Thread t = new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), config.port))) {
                LOG.info("Horn Link: this window is the RECEIVER, listening on 127.0.0.1:{}", config.port);
                byte[] buf = new byte[64];
                while (!socket.isClosed()) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);
                    String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    if (MAGIC.equals(msg)) pendingClick.set(true);
                }
            } catch (IOException e) {
                LOG.error("Horn Link: receiver could not listen on port {} (is it already in use?)", config.port, e);
            }
        }, "hornlink-receiver");
        t.setDaemon(true);
        t.start();
    }

    private static void onTick(Minecraft client) {
        if (role != Role.RECEIVER) return;

        if (holdRemaining > 0 && --holdRemaining == 0) {
            client.options.keyUse.setDown(false);
        }

        if (pendingClick.getAndSet(false)) {
            if (client.player == null || client.level == null || client.screen != null) {
                LOG.info("Horn Link: trigger received but this window is not in-game (or a menu is open). Ignored.");
                return;
            }
            // Same code path as a real right click: uses held item / interacts with block or entity in view.
            ((MinecraftAccessor) (Object) client).hornlink$startUseItem();
            if (config.holdTicks > 0) {
                client.options.keyUse.setDown(true);
                holdRemaining = config.holdTicks;
            }
        }
    }
}
