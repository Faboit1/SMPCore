package net.siftvanilla.siftcore.feature.orders;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * An exact kind of item an order can ask for besides plain items. Its id is stored with the order
 * ({@code orders.variant}) and is part of the order key. Pure: the server side builds the canonical item from it
 * ({@link OrderItem}) and matches deliveries with {@code isSimilar} against that item, so a variant order is exactly as
 * strict as a plain one.
 * <ul>
 *   <li>{@link Enchant}: an enchanted book with exactly one stored enchantment at one level, and nothing else (no
 *       anvil repair cost, no name). Books combined in an anvil or with extra enchantments don't match.</li>
 *   <li>{@link Potion}: a potion, splash potion, lingering potion or tipped arrow of one base potion type.</li>
 *   <li>{@link Spawner}: a SiftCore spawner of one mob, matched by the spawner feature's own item identity.</li>
 * </ul>
 */
sealed interface Variant permits Variant.Enchant, Variant.Potion, Variant.Spawner {

    /** The stored id, e.g. {@code enchant:minecraft:mending:1}. */
    String id();

    String ENCHANT = "enchant:";
    String POTION = "potion:";
    String SPAWNER = "spawner:";

    Pattern KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    Pattern MOB = Pattern.compile("[a-z0-9_]{1,64}");

    /** One stored enchantment at one level. */
    record Enchant(String enchantment, int level) implements Variant {
        public Enchant {
            if (enchantment == null || !KEY.matcher(enchantment).matches()) {
                throw new IllegalArgumentException("Bad enchantment key " + enchantment);
            }
            if (level < 1 || level > 255) {
                throw new IllegalArgumentException("Bad enchantment level " + level);
            }
        }

        @Override
        public String id() {
            return ENCHANT + this.enchantment + ":" + this.level;
        }

        /**
         * True when a book with these facts is exactly this variant: the one stored enchantment at this level, no anvil
         * repair cost and no other changed data. This is the rule {@code isSimilar} against the canonical book
         * enforces; the server checks both agree in its self-test.
         */
        boolean accepts(BookFacts facts) {
            return !facts.otherData() && facts.repairCost() == 0 && facts.stored().equals(Map.of(this.enchantment, this.level));
        }
    }

    /** One base potion type, e.g. {@code minecraft:long_swiftness}. */
    record Potion(String type) implements Variant {
        public Potion {
            if (type == null || !KEY.matcher(type).matches()) {
                throw new IllegalArgumentException("Bad potion type " + type);
            }
        }

        @Override
        public String id() {
            return POTION + this.type;
        }
    }

    /** A SiftCore spawner of one mob, e.g. {@code skeleton}. */
    record Spawner(String mob) implements Variant {
        public Spawner {
            if (mob == null || !MOB.matcher(mob).matches()) {
                throw new IllegalArgumentException("Bad spawner mob " + mob);
            }
        }

        @Override
        public String id() {
            return SPAWNER + this.mob;
        }
    }

    /**
     * What a book carries, as far as the variant rule cares.
     *
     * @param stored     stored enchantments by key ({@code minecraft:mending}) to level
     * @param repairCost the anvil repair cost (0 for books that never went through an anvil)
     * @param otherData  whether anything else differs from a fresh book (a name, lore, custom data)
     */
    record BookFacts(Map<String, Integer> stored, int repairCost, boolean otherData) {
        public BookFacts {
            stored = Map.copyOf(stored);
        }
    }

    /** Parses a stored id; throws {@link IllegalArgumentException} with the reason when it is not one. */
    static Variant parse(String id) {
        if (id == null) {
            throw new IllegalArgumentException("No variant");
        }
        String text = id.strip().toLowerCase(Locale.ROOT);
        if (text.startsWith(ENCHANT)) {
            String rest = text.substring(ENCHANT.length());
            int colon = rest.lastIndexOf(':');
            if (colon <= 0 || colon == rest.length() - 1) {
                throw new IllegalArgumentException("Bad enchanted book variant " + id);
            }
            String level = rest.substring(colon + 1);
            if (!level.matches("[0-9]{1,3}")) {
                throw new IllegalArgumentException("Bad enchantment level in " + id);
            }
            return new Enchant(rest.substring(0, colon), Integer.parseInt(level));
        }
        if (text.startsWith(POTION)) {
            return new Potion(text.substring(POTION.length()));
        }
        if (text.startsWith(SPAWNER)) {
            return new Spawner(text.substring(SPAWNER.length()));
        }
        throw new IllegalArgumentException("Unknown variant " + id);
    }

    /** The variant of a stored id, or null when the id is not a valid variant. */
    static Variant tryParse(String id) {
        try {
            return parse(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A level as a roman numeral (1 to 10; larger levels stay digits), as the game shows enchantment levels. */
    static String roman(int level) {
        String[] numerals = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return level >= 1 && level <= numerals.length ? numerals[level - 1] : Integer.toString(level);
    }
}
