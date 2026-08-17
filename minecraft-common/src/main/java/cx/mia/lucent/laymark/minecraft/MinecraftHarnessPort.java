package cx.mia.lucent.laymark.minecraft;

import cx.mia.lucent.laymark.core.Laymark;
import cx.mia.lucent.laymark.core.harness.FrameSample;
import cx.mia.lucent.laymark.core.harness.HarnessException;
import cx.mia.lucent.laymark.core.harness.HarnessPort;
import cx.mia.lucent.laymark.core.harness.Measurement;
import cx.mia.lucent.laymark.core.harness.MemorySnapshot;
import cx.mia.lucent.laymark.core.harness.GpuSample;
import cx.mia.lucent.laymark.core.harness.SparkStatistics;
import cx.mia.lucent.laymark.core.harness.WorkCounters;
import cx.mia.lucent.laymark.core.harness.Pose;
import cx.mia.lucent.laymark.core.harness.Preset;
import cx.mia.lucent.laymark.core.harness.PresetReadback;
import cx.mia.lucent.laymark.core.harness.WorldSpec;
import cx.mia.lucent.laymark.core.plan.StopCondition;
import cx.mia.lucent.laymark.core.harness.BarrierReport;
import cx.mia.lucent.laymark.core.scenario.ScenePlacement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.time.Duration;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
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
    private final GpuTimer gpuTimer;
    private final SparkChannel spark;

    /** The save this run created, remembered so disk-level questions can be asked about it. */
    private volatile String currentLevelId;

    public MinecraftHarnessPort(ClientChannels channels) {
        this.recorder = channels.frames();
        this.gpuTimer = channels.gpu();
        this.spark = channels.spark();
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
                    currentLevelId = spec.levelId();

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
    public void openWorld(WorldSpec spec) {
        ClientThread.call(
                "opening the world",
                Duration.ofMinutes(10),
                () -> {
                    Minecraft minecraft = Minecraft.getInstance();
                    currentLevelId = spec.levelId();

                    if (!minecraft.getLevelSource().levelExists(spec.levelId())) {
                        // The scenario this one depends on was supposed to leave this behind.
                        // Continuing would generate the terrain here and time it as streaming.
                        throw new HarnessException(
                                "save " + spec.levelId() + " is not there; the scenario that"
                                        + " generates it did not complete");
                    }
                    minecraft.createWorldOpenFlows().openWorld(spec.levelId(), () -> {});
                    return null;
                });
    }

    /**
     * Polls the composite barrier, timing each condition separately.
     *
     * <p>Per condition rather than one predicate because the result has to say <em>which</em> one
     * gated the run. A stack whose renderer takes twenty seconds to build out and one whose server
     * takes twenty seconds to report loaded are different problems that a single "ready after 20s"
     * cannot distinguish — and one of those conditions is answered by a subsystem that
     * renderer-replacing mods reimplement.
     */
    @Override
    public BarrierReport awaitReady(Duration timeout) {
        List<Readiness.Condition> conditions =
                ClientThread.call("listing barrier conditions", Readiness::worldConditions);
        long startedAt = System.nanoTime();
        long deadline = startedAt + timeout.toNanos();

        Map<String, Long> satisfiedAt = new LinkedHashMap<>();
        Set<String> everBlocked = new LinkedHashSet<>();
        int consecutive = 0;

        while (System.nanoTime() < deadline) {
            List<String> unmet =
                    ClientThread.call(
                            "checking the barrier",
                            () ->
                                    conditions.stream()
                                            .filter(c -> !c.satisfied().getAsBoolean())
                                            .map(Readiness.Condition::name)
                                            .toList());

            long elapsed = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            for (Readiness.Condition condition : conditions) {
                if (unmet.contains(condition.name())) {
                    everBlocked.add(condition.name());
                    satisfiedAt.remove(condition.name());
                } else {
                    satisfiedAt.putIfAbsent(condition.name(), elapsed);
                }
            }

            // Stability, not a single true. These signals flicker: the renderer reports everything
            // built and then another chunk arrives.
            consecutive = unmet.isEmpty() ? consecutive + 1 : 0;
            if (consecutive >= STABLE_POLLS) {
                return report(conditions, satisfiedAt, everBlocked, startedAt);
            }
            ClientThread.sleep(POLL_INTERVAL);
        }

        throw new HarnessException(
                "world was not measurable within "
                        + timeout.toSeconds()
                        + "s; still waiting on "
                        + conditions.stream()
                                .map(Readiness.Condition::name)
                                .filter(name -> !satisfiedAt.containsKey(name))
                                .toList());
    }

    private static BarrierReport report(
            List<Readiness.Condition> conditions,
            Map<String, Long> satisfiedAt,
            Set<String> everBlocked,
            long startedAt) {
        List<BarrierReport.Condition> recorded =
                conditions.stream()
                        .map(
                                condition ->
                                        new BarrierReport.Condition(
                                                condition.name(),
                                                satisfiedAt.getOrDefault(condition.name(), 0L),
                                                everBlocked.contains(condition.name())))
                        .toList();
        return new BarrierReport(
                recorded,
                STABLE_POLLS,
                Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
    }

    @Override
    public void position(Pose pose) {
        teleport(pose);
        // Let the renderer rebuild around the new position, then re-confirm the barrier: the
        // teleport invalidated the sections the earlier barrier was satisfied by.
        ClientThread.sleep(SETTLE_AFTER_MOVE);
        awaitReady(Duration.ofMinutes(2));
    }

    /**
     * Pins the game rules that would otherwise vary inside a capture.
     *
     * <p>Post-join, because {@code createFreshLevel} takes no game rules — the sibling that does is
     * a different entry point entirely. Left at vanilla defaults, time advances, weather changes
     * and mobs spawn <em>during the measured window</em>: four variables moving independently of
     * the thing under test, in both arms but never identically.
     */
    @Override
    public void pinGameRules() {
        ClientThread.run(
                "pinning game rules",
                () -> {
                    MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
                    if (server == null) {
                        throw new HarnessException("no integrated server to pin rules on");
                    }
                    GameRules rules = server.getGameRules();
                    rules.set(GameRules.ADVANCE_TIME, false, server);
                    rules.set(GameRules.ADVANCE_WEATHER, false, server);
                    rules.set(GameRules.SPAWN_MOBS, false, server);
                    rules.set(GameRules.SPAWN_MONSTERS, false, server);
                });
    }

    @Override
    public void teleport(Pose pose) {
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
    }

    /**
     * Whether the target region has never been generated, judged from the region file on disk.
     *
     * <p>Deliberately conservative, and the direction matters. A region file's <em>absence</em>
     * proves nothing in that 512-block square was ever generated; its presence only proves
     * something nearby was. So an absent file answers "ungenerated" and a present one answers
     * "assume generated", which fails the repetition. Erring the other way would let a traversal
     * measure a cost that had already been paid and report it as a fast run.
     */
    @Override
    public boolean targetIsUngenerated(Pose pose) {
        return ClientThread.call(
                "checking whether the target was generated",
                () -> {
                    MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
                    if (server == null) {
                        throw new HarnessException("no integrated server");
                    }
                    ServerLevel level = server.overworld();
                    if (level.getChunkSource().hasChunk(pose.chunkX(), pose.chunkZ())) {
                        // Loaded right now, so certainly generated.
                        return false;
                    }
                    if (currentLevelId == null) {
                        throw new HarnessException("no world has been created by this run");
                    }
                    Path region =
                            Minecraft.getInstance()
                                    .getLevelSource()
                                    .getLevelPath(currentLevelId)
                                    .resolve("region")
                                    .resolve(
                                            "r."
                                                    + Math.floorDiv(pose.chunkX(), REGION_CHUNKS)
                                                    + "."
                                                    + Math.floorDiv(pose.chunkZ(), REGION_CHUNKS)
                                                    + ".mca");
                    return !Files.exists(region);
                });
    }

    /** A region file covers a 32x32 square of chunks. */
    private static final int REGION_CHUNKS = 32;

    /**
     * Whether the client holds no compiled mesh for the target.
     *
     * <p>Not a claim about disk. After generation the region files sit in the OS page cache and a
     * mod cannot portably evict them, so this asks only what the renderer has built.
     */
    @Override
    public boolean targetHasNoBuiltSections(Pose pose) {
        return ClientThread.call(
                "checking whether the target is meshed",
                () -> {
                    Minecraft minecraft = Minecraft.getInstance();
                    if (minecraft.level == null) {
                        return true;
                    }
                    BlockPos at =
                            BlockPos.containing(pose.x(), pose.y(), pose.z());
                    return !minecraft.levelRenderer.isSectionCompiledAndVisible(at);
                });
    }

    /**
     * Places scene geometry, in declaration order, verifying counts against each file.
     *
     * <p>On the server thread rather than the client's: blocks and entities are server state, and
     * the client learns about them through the same packets it would in play.
     */
    @Override
    public void placeContent(List<ScenePlacement> content) {
        if (content.isEmpty()) {
            return;
        }
        ClientThread.call(
                "placing scene geometry",
                Duration.ofMinutes(5),
                () -> {
                    MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
                    if (server == null) {
                        throw new HarnessException("no integrated server to place a scene in");
                    }
                    ServerLevel level = server.overworld();
                    Path sceneRoot =
                            Minecraft.getInstance().gameDirectory.toPath().resolve(Laymark.SCENE_DIR);

                    for (ScenePlacement placement : content) {
                        Path file = sceneRoot.resolve(placement.schematic());
                        Schematic schematic = SchematicReader.read(
                                        file, level.registryAccess().lookupOrThrow(Registries.BLOCK));
                        ScenePlacer.Placed placed =
                                ScenePlacer.place(level, schematic, placement, file);

                        // A scene that placed most of itself looks exactly like one that placed all
                        // of itself, and the difference would read as a performance result.
                        if (placed.blocks() != schematic.cellCount()) {
                            throw new HarnessException(
                                    file + " declares " + schematic.cellCount()
                                            + " blocks but " + placed.blocks() + " were placed");
                        }
                        if (placed.entities() != schematic.entities().size()) {
                            throw new HarnessException(
                                    file + " declares " + schematic.entities().size()
                                            + " entities but " + placed.entities()
                                            + " were spawned");
                        }
                    }
                    return null;
                });
    }

    @Override
    public void beginCapture() {
        MemorySnapshot memoryBefore = Counters.memory();
        WorkCounters workBefore = ClientThread.call("reading work counters", Counters::work);
        spark.start();
        ClientThread.run(
                "starting the capture",
                () -> {
                    gpuTimer.start();
                    recorder.start();
                });
        openedAt = new Opened(workBefore, memoryBefore);
    }

    @Override
    public Measurement endCapture() {
        Opened opened = openedAt;
        if (opened == null) {
            throw new HarnessException("no capture is open");
        }
        openedAt = null;

        List<FrameSample> frames = ClientThread.call("ending the capture", recorder::stop);
        List<GpuSample> gpu = ClientThread.call("collecting gpu timings", gpuTimer::stop);
        SparkStatistics sparkStatistics = spark.stop();
        WorkCounters workAfter = ClientThread.call("reading work counters", Counters::work);

        return new Measurement(
                frames,
                gpu,
                sparkStatistics,
                opened.work(),
                workAfter,
                opened.memory(),
                Counters.memory());
    }

    /** State of the world when the open window started, so its deltas can be taken. */
    private record Opened(WorkCounters work, MemorySnapshot memory) {}

    private volatile Opened openedAt;

    @Override
    public Measurement capture(StopCondition stop, Pose around, int viewDistance) {
        String throttled = ClientThread.call("checking throttle", PresetOptions::activeThrottle);
        if (throttled != null) {
            // Refused up front rather than flagged. A window that starts throttled is capped for
            // all of it, so there is no measurement to qualify -- unlike a throttle that engages
            // partway through, which the per-frame channel records and the sequence flags.
            throw new HarnessException(
                    "framerate was already throttled before the capture began: " + throttled);
        }

        beginCapture();
        awaitStop(stop, around, viewDistance);
        return endCapture();
    }

    /**
     * Blocks until the capture's target is reached.
     *
     * <p>A duration capture just waits. The other two poll, because they end on the game making
     * progress — which is exactly what a broken stack fails to do, hence the timeout. Reaching the
     * timeout <strong>fails</strong> the repetition rather than returning what was collected: a
     * short capture and a completed one are not the same measurement, and silently substituting
     * one for the other would put the arms on different workloads.
     */
    @Override
    public void awaitStop(StopCondition stop, Pose around, int viewDistance) {
        if (openedAt == null) {
            throw new HarnessException("no capture is open");
        }
        if (stop.kind() == StopCondition.Kind.TIME) {
            ClientThread.sleep(stop.duration());
            return;
        }

        long deadline = System.nanoTime() + stop.timeout().toNanos();
        long progress = 0;
        long stalledSince = System.nanoTime();
        long best = -1;

        while (System.nanoTime() < deadline) {
            progress =
                    switch (stop.kind()) {
                        case FRAMES -> recorder.sampleCount();
                        case CHUNKS ->
                                ClientThread.call(
                                        "counting loaded chunks",
                                        () ->
                                                Counters.chunksLoadedAround(
                                                        around.chunkX(), around.chunkZ(), viewDistance));
                        case TIME -> throw new IllegalStateException("handled above");
                    };
            if (progress >= stop.target()) {
                return;
            }
            if (progress > best) {
                best = progress;
                stalledSince = System.nanoTime();
            } else if (System.nanoTime() - stalledSince > STALL_TIMEOUT.toNanos()) {
                // Nothing arriving for minutes means the game stopped working, not that it is
                // slow. Waiting out the full timeout on a dead run costs an operator the rest of
                // an unattended schedule; saying so costs nothing.
                throw new HarnessException(
                        "capture stalled at " + progress + " of " + stop.target() + " " + stop.kind()
                                + " -- nothing arrived for " + STALL_TIMEOUT.toSeconds()
                                + "s. The integrated server pauses whenever a screen is open"
                                + " (Screen.isPauseScreen defaults to true), which stops chunk"
                                + " generation entirely; check the game window.");
            }
            ClientThread.sleep(PROGRESS_POLL);
        }
        throw new HarnessException(
                "capture reached only "
                        + progress
                        + " of "
                        + stop.target()
                        + " "
                        + stop.kind()
                        + " within "
                        + stop.timeout().toSeconds()
                        + "s");
    }

    /** How long a completion target may make no progress at all before the run is called dead. */
    private static final Duration STALL_TIMEOUT = Duration.ofMinutes(3);

    /** How often to ask whether a completion target has been met. */
    private static final Duration PROGRESS_POLL = Duration.ofMillis(100);

    /** Why the GPU channel is empty, or null when it worked. Reported as a run-level flag. */
    public String gpuUnavailableReason() {
        return ClientThread.call("checking gpu channel", gpuTimer::unavailableReason);
    }

    /** Why the Spark channel is empty, or null when it worked. */
    public String sparkUnavailableReason() {
        return spark.unavailableReason();
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
