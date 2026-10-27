package net.siftvanilla.siftcore.feature.friends;

import java.util.regex.Pattern;

/**
 * What a typed player name may look like before it is looked up: letters, digits, underscores and dots (Bedrock
 * players joining through Floodgate have a dot prefix such as {@code .Steve}), 1 to 17 characters. The command path
 * and the dialog form validate with the same rule. Pure.
 */
public final class PlayerNames {

    /** The longest name: 16 plus a Floodgate prefix. */
    public static final int MAX_LENGTH = 17;

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.]{1," + MAX_LENGTH + "}");

    private PlayerNames() {
    }

    public static boolean valid(String name) {
        return name != null && NAME.matcher(name).matches();
    }
}
