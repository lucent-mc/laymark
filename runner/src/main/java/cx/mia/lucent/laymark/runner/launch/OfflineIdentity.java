package cx.mia.lucent.laymark.runner.launch;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.UUID;

/**
 * The offline player identity a launch runs under.
 *
 * <p>Laymark never connects to online servers, and singleplayer never validates a token — but
 * {@code --accessToken} is a <em>required</em> {@code jopt-simple} option on Minecraft's
 * {@code Main}, so omitting it fails with {@code MissingRequiredOptionsException}. Verified by
 * experiment: placeholder values boot the full modded pack through world creation and entry.
 *
 * <p>The UUID is the conventional version-3 UUID of {@code "OfflinePlayer:<name>"}. Determinism
 * is not cosmetic here — <strong>player data in a save is keyed by UUID</strong>, so a random
 * placeholder would hand each arm a different player state, which is a stimulus-parity failure
 * of exactly the kind the trust model exists to catch. Username and UUID are pinned like the
 * seed.
 */
public record OfflineIdentity(String username, UUID uuid) {

    /** Placeholder values. Present so the option parser is satisfied; never validated. */
    public static final String ACCESS_TOKEN = "0";

    public static final String XUID = "0";
    public static final String CLIENT_ID = "0";

    public OfflineIdentity {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        if (uuid == null) {
            throw new IllegalArgumentException("uuid must not be null");
        }
    }

    public static OfflineIdentity of(String username) {
        return new OfflineIdentity(username, offlineUuid(username));
    }

    /** Version-3 UUID of {@code "OfflinePlayer:<name>"}, the convention offline launchers use. */
    public static UUID offlineUuid(String username) {
        byte[] digest = md5(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        digest[6] = (byte) ((digest[6] & 0x0f) | 0x30); // version 3
        digest[8] = (byte) ((digest[8] & 0x3f) | 0x80); // RFC 4122 variant
        long high = 0;
        long low = 0;
        for (int i = 0; i < 8; i++) {
            high = (high << 8) | (digest[i] & 0xffL);
        }
        for (int i = 8; i < 16; i++) {
            low = (low << 8) | (digest[i] & 0xffL);
        }
        return new UUID(high, low);
    }

    /** Minecraft wants the UUID undashed on the command line. */
    public String uuidArgument() {
        return uuid.toString().replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static byte[] md5(byte[] input) {
        try {
            return MessageDigest.getInstance("MD5").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is required by every JVM", e);
        }
    }
}
