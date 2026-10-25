package com.hornlink;

import com.hornlink.mixin.MinecraftAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
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
 * Status messages are shown in the in-game chat (only you can see them).
 */
public class HornLinkClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("hornlink");
    private static final String MAGIC = "HORNLINK-CLICK";

    private enum Role { NONE, SENDER, RECEIVER }

    private static HornLinkConfig config;
    private static volatile Role role = Role.NONE;
    private static volatile String roleNote = "mod has not finished starting";
    private static final AtomicBoolean pendingClick = new AtomicBoolean(false);
    private static volatile String listenerError = null;
    private static int holdRemaining = 0;
    private static long lastSendMs = 0;
    private static boolean announced = false;

    @Override
    public void onInitializeClient() {
        config = HornLinkConfig.load();
        ClientLifecycleEvents.CLIENT_STARTED.register(HornLinkClient::resolveRole);
        UseItemCallback.EVENT.register(HornLinkClient::onUseItem);
        ClientTickEvents.END_CLIENT_TICK.register(HornLinkClient::onTick);
    }

    private static void say(Minecraft client, String text) {
        LOG.info("Horn Link: {}", text);
        if (client != null && client.player != null) {
            client.player.sendSystemMessage(Component.literal("[HornLink] " + text));
        }
    }

    // ---------------------------------------------------------------- role

    private static void resolveRole(Minecraft client) {
        if (!config.enabled) {
            roleNote = "disabled in config (enabled=false)";
            return;
        }

        String me = client.getUser().getName();
        String forced = System.getProperty("hornlink.role");
        String r = (forced != null ? forced : config.role).toLowerCase(Locale.ROOT);

        if (r.equals("auto")) {
            if (!config.receiverName.isEmpty() && me.equalsIgnoreCase(config.receiverName)) {
                r = "receiver";
            } else if (!config.senderName.isEmpty() && me.equalsIgnoreCase(config.senderName)) {
                r = "sender";
            } else {
                r = "none";
                roleNote = "your account name is '" + me + "' but the config has senderName='"
                        + config.senderName + "' and receiverName='" + config.receiverName
                        + "'. Neither matches, so this window does nothing.";
            }
        }

        switch (r) {
            case "sender" -> {
                role = Role.SENDER;
                roleNote = "account '" + me + "' is the SENDER (use a goat horn here)";
            }
            case "receiver" -> {
                role = Role.RECEIVER;
                roleNote = "account '" + me + "' is the RECEIVER, listening on 127.0.0.1:" + config.port;
                startListener();
            }
            default -> role = Role.NONE;
        }
        LOG.info("Horn Link: {}", roleNote);
    }

    // ---------------------------------------------------------------- sender

    private static InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
        // This event also fires on the integrated server; only react to the real local player.
        if (!(player instanceof LocalPlayer)) return InteractionResult.PASS;

        ItemStack stack = player.getItemInHand(hand);
        if (!stack.is(Items.GOAT_HORN)) return InteractionResult.PASS;

        Minecraft mc = Minecraft.getInstance();
        if (role != Role.SENDER) {
            say(mc, "Goat horn used, but this window is not the sender. Status: " + roleNote);
            return InteractionResult.PASS;
        }

        String components = String.valueOf(stack.getComponents());
        LOG.info("Horn Link: goat horn components = {}", components);

        if (!config.horn.equals("any")
                && !components.toLowerCase(Locale.ROOT).contains(config.horn + "_goat_horn")) {
            say(mc, "Horn ignored: config wants '" + config.horn + "' but this is a different horn.");
            return InteractionResult.PASS;
        }

        long now = System.currentTimeMillis();
        if (now - lastSendMs < config.debounceMs) {
            say(mc, "Horn ignored: less than " + config.debounceMs + " ms since the last trigger.");
            return InteractionResult.PASS;
        }
        lastSendMs = now;

        if (send()) {
            say(mc, "Horn used, signal sent to port " + config.port + ".");
        } else {
            say(mc, "Horn used, but sending the signal FAILED (see latest.log).");
        }
        return InteractionResult.PASS;
    }

    private static boolean send() {
        try (DatagramSocket s = new DatagramSocket()) {
            byte[] data = MAGIC.getBytes(StandardCharsets.UTF_8);
            s.send(new DatagramPacket(data, data.length, InetAddress.getLoopbackAddress(), config.port));
            return true;
        } catch (IOException e) {
            LOG.warn("Horn Link: could not send trigger", e);
            return false;
        }
    }

    // ---------------------------------------------------------------- receiver

    private static void startListener() {
        Thread t = new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), config.port))) {
                LOG.info("Horn Link: listening on 127.0.0.1:{}", config.port);
                byte[] buf = new byte[64];
                while (!socket.isClosed()) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);
                    String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    if (MAGIC.equals(msg)) pendingClick.set(true);
                }
            } catch (IOException e) {
                listenerError = "could not listen on port " + config.port
                        + " (already in use?): " + e.getMessage();
                LOG.error("Horn Link: {}", listenerError, e);
            }
        }, "hornlink-receiver");
        t.setDaemon(true);
        t.start();
    }

    private static void onTick(Minecraft client) {
        // One status line each time you join a world.
        if (client.player == null || client.level == null) {
            announced = false;
        } else if (!announced) {
            announced = true;
            say(client, "Status: " + roleNote);
            if (listenerError != null) say(client, "ERROR: " + listenerError);
        }

        if (role != Role.RECEIVER) return;

        if (holdRemaining > 0 && --holdRemaining == 0) {
            client.options.keyUse.setDown(false);
        }

        if (pendingClick.getAndSet(false)) {
            if (client.player == null || client.level == null) {
                LOG.info("Horn Link: trigger received but this window is not in a world. Ignored.");
                return;
            }
            say(client, "Signal received, right-clicking now.");
            // Same code path as a real right click: uses held item / interacts with block or entity in view.
            ((MinecraftAccessor) (Object) client).hornlink$startUseItem();
            if (config.holdTicks > 0) {
                client.options.keyUse.setDown(true);
                holdRemaining = config.holdTicks;
            }
        }
    }
}
