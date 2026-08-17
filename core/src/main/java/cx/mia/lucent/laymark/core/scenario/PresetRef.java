package cx.mia.lucent.laymark.core.scenario;

import cx.mia.lucent.laymark.core.harness.Preset;
import cx.mia.lucent.laymark.core.plan.PlanException;
import java.util.function.Function;

/**
 * A scenario's settings, either named or written out.
 *
 * <p>One field rather than two mutually-exclusive ones. A schema with both {@code presetName} and
 * {@code preset} can express a scenario that sets both, which is meaningless — and the only
 * defence against it is a validation rule that has to be written, tested, and explained. A union
 * deletes the bad state instead of policing it, and reads better besides:
 *
 * <pre>
 *   "preset": "near"
 *   "preset": { "renderDistance": 8, ... }
 * </pre>
 */
public sealed interface PresetRef {

    /** Refers to an entry in the config's {@code presets} map. */
    record Named(String name) implements PresetRef {

        public Named {
            if (name == null || name.isBlank()) {
                throw new PlanException("a preset reference has no name");
            }
        }

        @Override
        public Preset resolve(Function<String, Preset> presets) {
            return presets.apply(name);
        }
    }

    /** Settings written out in the scenario itself. */
    record Inline(Preset preset) implements PresetRef {

        public Inline {
            if (preset == null) {
                throw new PlanException("an inline preset is empty");
            }
        }

        @Override
        public Preset resolve(Function<String, Preset> presets) {
            return preset;
        }
    }

    /** @param presets resolves a name; consulted only by {@link Named} */
    Preset resolve(Function<String, Preset> presets);

    static PresetRef named(String name) {
        return new Named(name);
    }

    static PresetRef inline(Preset preset) {
        return new Inline(preset);
    }
}
