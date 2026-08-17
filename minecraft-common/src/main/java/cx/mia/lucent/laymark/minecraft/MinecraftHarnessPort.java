package cx.mia.lucent.laymark.minecraft;

import cx.mia.lucent.laymark.core.harness.FrameSample;
import cx.mia.lucent.laymark.core.harness.HarnessException;
import cx.mia.lucent.laymark.core.harness.HarnessPort;
import cx.mia.lucent.laymark.core.harness.Pose;
import cx.mia.lucent.laymark.core.harness.Preset;
import cx.mia.lucent.laymark.core.harness.PresetReadback;
import cx.mia.lucent.laymark.core.harness.WorldSpec;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.validation.ContentValidationException;

/**
 * The vanilla implementation of the run sequence's requirements.
 *
 * <p>Called from the harness thread; every operation that touches the game marshals onto the
 * client thread through {@link ClientThread} and waits. Nothing here decides benchmark policy —
 * what to measure and in what order lives in {@code core}, and this module only knows how to make
 * the game do it.
 */
public final class MinecraftHarnessPort implements HarnessPort {

    /** How often to re-check a readiness condition. Roughly a tick; polling faster buys nothing. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    /** Consecutive successful polls required before a barrier is believed. */
    private static final int STABLE_POLLS = 5;

    /**
     * Grace period after positioning, before measuring.
     *
     * <p>A teleport invalidates the sections around the old position and queues the ones around
     * the new one. Measuring immediately would time the renderer catching up, which is a real cost
     * but not the one the scenario named.
     */
    private static final Duration SETTLE_AFTER_MOVE = Duration.ofSeconds(3);

    private final FrameRecorder recorder;

    public MinecraftHarnessPort(FrameRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void applyPreset(Preset preset) {
        ClientThread.run("applying the preset", () -> PresetOptions.apply(preset));
    }

    @Override
    public PresetReadback readPreset(Preset requested) {
        return ClientThread.call("reading the preset back", () -> PresetOptions.read(requested));
    }

    @Override
    public void createWorld(WorldSpec spec) {
        ClientThread.call(
                "creating the world",
                // Generous: this blocks the client thread in a nested render loop until the
                // integrated server has completed a tick, and on a heavy pack that is minutes.
                Duration.ofMinutes(10),
                () -> {
                    Minecraft minecraft = Minecraft.getInstance();
                    LevelStorageSource source = minecraft.getLevelSource();
                    if (source.levelExists(spec.levelId())
                            || !source.isNewLevelIdAcceptable(spec.levelId())) {
                        // createFreshLevel swallows this class of failure -- it shows a toast and
                        // returns normally -- so the check has to happen before the call, or the
                        // run proceeds into a world that was never created.
                        throw new HarnessException(
                                "save " + spec.levelId() + " already exists or is unusable");
                    }

                    LevelSettings settings =
                            new LevelSettings(
                                    spec.levelId(),
                                    // Creative so the player can be placed anywhere and will not
                                    // fall, drown or be attacked mid-measurement.
                                    GameType.CREATIVE,
                                    LevelSettings.DifficultySettings.DEFAULT,
                                    false,
                                    WorldDataConfiguration.DEFAULT);

                    minecraft
                            .createWorldOpenFlows()
                            .createFreshLevel(
                                    spec.levelId(),
                                    settings,
                                    new WorldOptions(spec.seed(), spec.generateStructures(), false),
                                    WorldPresets::createNormalWorldDimensions,
                                    null);
                    return null;
                });
    }

    @Override
    public void awaitReady(Duration timeout) {
        if (!ClientThread.await(
                "waiting for the world",
                timeout,
                POLL_INTERVAL,
                STABLE_POLLS,
                Readiness::worldIsMeasurable)) {
            throw new HarnessException(
                    "world was not measurable within " + timeout.toSeconds() + "s");
        }
    }

    @Override
    public void position(Pose pose) {
        ClientThread.run(
                "positioning the player",
                () -> {
                    ServerPlayer player = serverPlayer();
                    ServerLevel level = player.level() instanceof ServerLevel serverLevel
                            ? serverLevel
                            : null;
                    if (level == null) {
                        throw new HarnessException("player is not in a server level");
                    }
                    player.teleportTo(
                            level, pose.x(), pose.y(), pose.z(), Set.of(), pose.yaw(), pose.pitch(), true);

                    // Flying rather than standing: a fixed observation point must not depend on
                    // there being ground under it, and falling would move the camera mid-capture.
                    player.getAbilities().flying = true;
                    player.getAbilities().invulnerable = true;
                    player.onUpdateAbilities();
                });

        // Let the renderer rebuild around the new position, then re-confirm the barrier: the
        // teleport invalidated the sections the earlier barrier was satisfied by.
        ClientThread.sleep(SETTLE_AFTER_MOVE);
        awaitReady(Duration.ofMinutes(2));
    }

    @Override
    public List<FrameSample> capture(Duration duration) {
        String throttleBefore = ClientThread.call("checking throttle", PresetOptions::activeThrottle);
        if (throttleBefore != null) {
            throw new HarnessException("framerate was already throttled: " + throttleBefore);
        }

        ClientThread.run("starting the capture", recorder::start);
        ClientThread.sleep(duration);
        List<FrameSample> samples = ClientThread.call("ending the capture", recorder::stop);

        // Checked after, not only before. Vanilla's inactivity throttle engages partway through a
        // long window, so a clean reading at the start says nothing about the samples that follow.
        String throttleAfter = ClientThread.call("checking throttle", PresetOptions::activeThrottle);
        if (throttleAfter != null) {
            throw new HarnessException("framerate was throttled during the capture: " + throttleAfter);
        }
        return samples;
    }

    @Override
    public void closeWorld() {
        ClientThread.call(
                "leaving the world",
                Duration.ofMinutes(5),
                () -> {
                    Minecraft minecraft = Minecraft.getInstance();
                    if (minecraft.level != null) {
                        minecraft.disconnect(
                                new GenericMessageScreen(Component.literal("Laymark")), false);
                    }
                    return null;
                });

        if (!ClientThread.await(
                "waiting for the world to close",
                Duration.ofMinutes(5),
                POLL_INTERVAL,
                STABLE_POLLS,
                Readiness::idleAtMenu)) {
            // The save's lock is held until the integrated server has stopped, so continuing
            // would fail the next repetition's creation for a reason that looks unrelated.
            throw new HarnessException("the world did not close");
        }
    }

    @Override
    public void deleteWorld(String levelId) {
        if (!WorldSpec.isDisposable(levelId)) {
            // This method deletes save directories. It only ever gets a name the harness generated,
            // and refusing anything else keeps a future caller's mistake from being unrecoverable.
            throw new HarnessException("refusing to delete a save Laymark did not create: " + levelId);
        }
        ClientThread.run(
                "deleting the save",
                () -> {
                    LevelStorageSource source = Minecraft.getInstance().getLevelSource();
                    if (!source.levelExists(levelId)) {
                        return;
                    }
                    try (LevelStorageSource.LevelStorageAccess access =
                            source.validateAndCreateAccess(levelId)) {
                        access.deleteLevel();
                    } catch (IOException | ContentValidationException | RuntimeException e) {
                        throw new HarnessException("could not delete save " + levelId, e);
                    }
                });
    }

    private static ServerPlayer serverPlayer() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            throw new HarnessException("no integrated server");
        }
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            throw new HarnessException("no player has joined");
        }
        return players.get(0);
    }
}
