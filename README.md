# Horn Link (Fabric, Minecraft 26.2)

Blow a goat horn in window A, and window B right-clicks automatically.
Client-side only. The two windows talk over `127.0.0.1` on your own PC.

## Build it

You need **JDK 25** installed.

Easiest way (gets you the correct Gradle wrapper and newest versions):
1. Go to https://fabricmc.net/develop/template/ and generate a project:
   Minecraft 26.2, mod id `hornlink`, package `com.hornlink`, Mojang mappings.
2. Unzip it, delete its `src` folder, and copy this project's `src` folder in.
3. In a terminal inside the project: `./gradlew build` (Windows: `gradlew build`).
4. The mod is `build/libs/hornlink-1.0.0.jar` (not the `-sources` one).

Or build this folder directly with Gradle 9.5.1: `gradle build`.
(`gradle.properties` has the Fabric API / Loader versions I found; bump them if the build complains.)

## Install

Put the jar in the `mods` folder of **both** Minecraft instances, together with Fabric API 26.2.

## Set up

Start the game once so `config/hornlink.properties` is created, then edit it:

```
receiverName=YourSecondAccountName
senderName=YourMainAccountName
horn=any        # or ponder / sing / seek / feel / admire / call / yearn / dream
```

If both instances share one `.minecraft` folder that is fine, the names decide who is who.
Alternative: add `-Dhornlink.role=receiver` to the JVM arguments of the second instance.

## Use

1. Start the second (receiver) window first, join your world/server.
2. Start the main window, join, hold the goat horn and use it.
3. The second window right-clicks whatever it is holding / looking at.

## Important

- A Minecraft window that loses focus pauses itself (pause menu). Press **F3 + P** in the second
  window to turn "pause on lost focus" off, otherwise it will not react while in the background.
- Horn cooldown is 7 seconds; `debounceMs` matches that.
- On public servers, automated clicking from another account can break the rules. Check first.
