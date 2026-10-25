package com.hornlink;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Simple key=value config stored in .minecraft/config/hornlink.properties */
public final class HornLinkConfig {
    public boolean enabled = true;
    /** auto | sender | receiver */
    public String role = "auto";
    /** Minecraft username of the account you play on (used when role=auto). */
    public String senderName = "";
    /** Minecraft username of the second account that should right-click (used when role=auto). */
    public String receiverName = "";
    /** any | ponder | sing | seek | feel | admire | call | yearn | dream */
    public String horn = "any";
    /** Local UDP port used between the two game windows. */
    public int port = 47821;
    /** 0 = a single click. >0 = also keep right-click held for this many ticks (20 ticks = 1 second). */
    public int holdTicks = 0;
    /** Minimum time between two triggers, in milliseconds. A goat horn has a 7 second cooldown. */
    public int debounceMs = 7000;

    private static final String DEFAULT_FILE = """
            # Horn Link config
            # enabled: turn the whole mod on/off
            enabled=true
            # role: auto | sender | receiver
            #   auto = decide by username using senderName / receiverName below
            #   (you can also force a role per window with the JVM argument -Dhornlink.role=sender or -Dhornlink.role=receiver)
            role=auto
            # Usernames of your two accounts (case-insensitive)
            senderName=
            receiverName=
            # horn: any | ponder | sing | seek | feel | admire | call | yearn | dream
            horn=any
            # Local port the receiving window listens on (127.0.0.1 only)
            port=47821
            # 0 = single right click, otherwise also hold right click for N ticks
            holdTicks=0
            # Minimum milliseconds between triggers
            debounceMs=7000
            """;

    public static HornLinkConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("hornlink.properties");
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, DEFAULT_FILE);
            } catch (IOException e) {
                HornLinkClient.LOG.warn("Could not write default config", e);
            }
        }

        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            p.load(in);
        } catch (IOException e) {
            HornLinkClient.LOG.warn("Could not read config, using defaults", e);
        }

        HornLinkConfig c = new HornLinkConfig();
        c.enabled = Boolean.parseBoolean(p.getProperty("enabled", "true").trim());
        c.role = p.getProperty("role", "auto").trim().toLowerCase();
        c.senderName = p.getProperty("senderName", "").trim();
        c.receiverName = p.getProperty("receiverName", "").trim();
        c.horn = p.getProperty("horn", "any").trim().toLowerCase();
        c.port = parseInt(p.getProperty("port"), 47821);
        c.holdTicks = Math.max(0, parseInt(p.getProperty("holdTicks"), 0));
        c.debounceMs = Math.max(0, parseInt(p.getProperty("debounceMs"), 7000));
        return c;
    }

    private static int parseInt(String s, int fallback) {
        if (s == null) return fallback;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
