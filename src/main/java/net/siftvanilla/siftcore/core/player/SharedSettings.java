package net.siftvanilla.siftcore.core.player;

import java.util.List;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.Audience;
import net.siftvanilla.siftcore.core.player.options.AutoAccept;
import net.siftvanilla.siftcore.core.player.options.Choices;
import net.siftvanilla.siftcore.core.player.options.PingSound;
import net.siftvanilla.siftcore.core.text.MessageKey;

/**
 * Settings that several features read, defined and registered by core so no feature imports another feature's
 * setting constants: the sound kinds and volume (applied by {@code Sounds}), quiet in combat and the feedback channel
 * (applied by {@code Messenger}), the privacy settings, teleport auto-accept and sale receipts. Their text lives in
 * {@code lang/core.yml} under {@code shared-settings}.
 */
public final class SharedSettings {

    /** The permission for hiding from leaderboards (staff and test accounts; nobody has it by default). */
    public static final String HIDE_FROM_LEADERBOARDS_NODE = "siftcore.stats.hide";
    /** The permission for hiding one's rank tag (granted to rank groups; nobody has it by default). */
    public static final String HIDE_RANK_NODE = "siftcore.settings.hide-rank";
    /**
     * The permission for the staff tools for other players' settings ({@code /sift settings}, added with the settings
     * dialog's next version; operators have it, like every {@code siftcore.admin} node).
     */
    public static final String ADMIN_NODE = "siftcore.admin.settings";

    public static final MessageKey SOUND_VOLUME_LABEL = MessageKey.ui("shared-settings.sound-volume.label");
    public static final MessageKey SOUND_VOLUME_DESCRIPTION = MessageKey.ui("shared-settings.sound-volume.description");
    public static final MessageKey SOUND_NOTIFY_LABEL = MessageKey.ui("shared-settings.sound-notify.label");
    public static final MessageKey SOUND_NOTIFY_DESCRIPTION = MessageKey.ui("shared-settings.sound-notify.description");
    public static final MessageKey SOUND_MENTION_LABEL = MessageKey.ui("shared-settings.sound-mention.label");
    public static final MessageKey SOUND_MENTION_DESCRIPTION = MessageKey.ui("shared-settings.sound-mention.description");
    public static final MessageKey SOUND_PM_LABEL = MessageKey.ui("shared-settings.sound-pm.label");
    public static final MessageKey SOUND_PM_DESCRIPTION = MessageKey.ui("shared-settings.sound-pm.description");
    public static final MessageKey SOUND_CLICKS_LABEL = MessageKey.ui("shared-settings.sound-clicks.label");
    public static final MessageKey SOUND_CLICKS_DESCRIPTION = MessageKey.ui("shared-settings.sound-clicks.description");
    public static final MessageKey SOUND_SUCCESS_LABEL = MessageKey.ui("shared-settings.sound-success.label");
    public static final MessageKey SOUND_SUCCESS_DESCRIPTION = MessageKey.ui("shared-settings.sound-success.description");
    public static final MessageKey SOUND_ERRORS_LABEL = MessageKey.ui("shared-settings.sound-errors.label");
    public static final MessageKey SOUND_ERRORS_DESCRIPTION = MessageKey.ui("shared-settings.sound-errors.description");
    public static final MessageKey SOUND_TEAM_CHAT_LABEL = MessageKey.ui("shared-settings.sound-team-chat.label");
    public static final MessageKey SOUND_TEAM_CHAT_DESCRIPTION = MessageKey.ui("shared-settings.sound-team-chat.description");
    public static final MessageKey QUIET_IN_COMBAT_LABEL = MessageKey.ui("shared-settings.quiet-in-combat.label");
    public static final MessageKey QUIET_IN_COMBAT_DESCRIPTION = MessageKey.ui("shared-settings.quiet-in-combat.description");
    public static final MessageKey FEEDBACK_CHANNEL_LABEL = MessageKey.ui("shared-settings.feedback-channel.label");
    public static final MessageKey FEEDBACK_CHANNEL_DESCRIPTION = MessageKey.ui("shared-settings.feedback-channel.description");
    public static final MessageKey HIDE_COORDINATES_LABEL = MessageKey.ui("shared-settings.hide-coordinates.label");
    public static final MessageKey HIDE_COORDINATES_DESCRIPTION = MessageKey.ui("shared-settings.hide-coordinates.description");
    public static final MessageKey SEEN_PRIVACY_LABEL = MessageKey.ui("shared-settings.seen-privacy.label");
    public static final MessageKey SEEN_PRIVACY_DESCRIPTION = MessageKey.ui("shared-settings.seen-privacy.description");
    public static final MessageKey BALANCE_PRIVACY_LABEL = MessageKey.ui("shared-settings.balance-privacy.label");
    public static final MessageKey BALANCE_PRIVACY_DESCRIPTION = MessageKey.ui("shared-settings.balance-privacy.description");
    public static final MessageKey HIDE_FROM_LEADERBOARDS_LABEL = MessageKey.ui("shared-settings.hide-from-leaderboards.label");
    public static final MessageKey HIDE_FROM_LEADERBOARDS_DESCRIPTION = MessageKey.ui("shared-settings.hide-from-leaderboards.description");
    public static final MessageKey FRIENDS_TPA_LABEL = MessageKey.ui("shared-settings.friends-tpa.label");
    public static final MessageKey FRIENDS_TPA_DESCRIPTION = MessageKey.ui("shared-settings.friends-tpa.description");
    public static final MessageKey SELL_RECEIPTS_LABEL = MessageKey.ui("shared-settings.sell-receipts.label");
    public static final MessageKey SELL_RECEIPTS_DESCRIPTION = MessageKey.ui("shared-settings.sell-receipts.description");

    /** How loud SiftCore's sounds are, 0 to 100 percent (0 mutes them). Applied by {@code Sounds}. */
    public static final NumberSetting SOUND_VOLUME = new NumberSetting("sound-volume", 100, 0, 100, 10, SettingTexts.UNIT_PERCENT,
        SOUND_VOLUME_LABEL, SOUND_VOLUME_DESCRIPTION, null);
    /** Notification pings (requests, payments, sales, alerts). Applied by {@code Sounds}. */
    public static final Toggle SOUND_NOTIFY = new Toggle("sound-notify", true, SOUND_NOTIFY_LABEL, SOUND_NOTIFY_DESCRIPTION, null);
    /** The sound of a mention ({@code Sounds#ping}). */
    public static final Choice<PingSound> SOUND_MENTION = Choices.ping("sound-mention", PingSound.DEFAULT)
        .text(SOUND_MENTION_LABEL, SOUND_MENTION_DESCRIPTION).build();
    /** The sound of a private message ({@code Sounds#ping}). */
    public static final Choice<PingSound> SOUND_PM = Choices.ping("sound-pm", PingSound.DEFAULT)
        .text(SOUND_PM_LABEL, SOUND_PM_DESCRIPTION).build();
    /** Menu click sounds. Applied by {@code Sounds}. */
    public static final Toggle SOUND_CLICKS = new Toggle("sound-clicks", true, SOUND_CLICKS_LABEL, SOUND_CLICKS_DESCRIPTION, null);
    /** Success chimes. Applied by {@code Sounds}. */
    public static final Toggle SOUND_SUCCESS = new Toggle("sound-success", true, SOUND_SUCCESS_LABEL, SOUND_SUCCESS_DESCRIPTION, null);
    /** Error notes. Applied by {@code Sounds}. */
    public static final Toggle SOUND_ERRORS = new Toggle("sound-errors", true, SOUND_ERRORS_LABEL, SOUND_ERRORS_DESCRIPTION, null);
    /** The sound when a teammate writes in team chat ({@code Sounds#ping}); off by default. */
    public static final Choice<PingSound> SOUND_TEAM_CHAT = Choices.ping("sound-team-chat", PingSound.OFF,
        PingSound.OFF, PingSound.DEFAULT, PingSound.BELL, PingSound.PLING, PingSound.CHIME)
        .text(SOUND_TEAM_CHAT_LABEL, SOUND_TEAM_CHAT_DESCRIPTION).build();
    /**
     * While in combat: no notification pings, chimes or pop-ups from other features ({@code Sounds} skips them and
     * {@code Messenger#alert} turns action bar and title alerts into chat lines). Chat lines and error sounds stay.
     */
    public static final Toggle QUIET_IN_COMBAT = new Toggle("quiet-in-combat", false, QUIET_IN_COMBAT_LABEL,
        QUIET_IN_COMBAT_DESCRIPTION, null);
    /**
     * Where short results and errors appear ({@code Messenger}): above the hotbar, in chat, or both. Repeating
     * status lines ({@code MessageKey#status()}) stay above the hotbar.
     */
    public static final Choice<AlertStyle> FEEDBACK_CHANNEL = Choices.alert("feedback-channel", AlertStyle.ACTIONBAR,
        AlertStyle.ACTIONBAR, AlertStyle.CHAT, AlertStyle.BOTH).text(FEEDBACK_CHANNEL_LABEL, FEEDBACK_CHANNEL_DESCRIPTION).build();
    /** Streamer mode: leave coordinates out of homes, random teleport and death messages. */
    public static final Toggle HIDE_COORDINATES = new Toggle("hide-coordinates", false, HIDE_COORDINATES_LABEL,
        HIDE_COORDINATES_DESCRIPTION, null);
    /**
     * Who sees when the player was last online ({@code /seen}, friend profiles). Read offline with {@code lookup}.
     * "Friends" reads as "nobody" on a server without friends, so turning friends off never exposes anyone.
     */
    public static final Choice<Audience> SEEN_PRIVACY = privacy("seen-privacy", SEEN_PRIVACY_LABEL, SEEN_PRIVACY_DESCRIPTION);
    /** Who sees the player's balance ({@code /balance <name>}, stats, chat card). Read offline with {@code lookup}. */
    public static final Choice<Audience> BALANCE_PRIVACY = privacy("balance-privacy", BALANCE_PRIVACY_LABEL,
        BALANCE_PRIVACY_DESCRIPTION);
    /** Leave the player out of every leaderboard (staff and test accounts, {@link #HIDE_FROM_LEADERBOARDS_NODE}). */
    public static final Toggle HIDE_FROM_LEADERBOARDS = new Toggle("hide-from-leaderboards", false, HIDE_FROM_LEADERBOARDS_LABEL,
        HIDE_FROM_LEADERBOARDS_DESCRIPTION, HIDE_FROM_LEADERBOARDS_NODE);
    /**
     * Whose plain {@code /tpa} the player accepts without being asked. Same id and stored values as the friends
     * feature always used; rows of the retired {@code tpa-friends} switch move here (on: all friends, off: nobody) once
     * the teleport feature stops registering it.
     */
    public static final Choice<AutoAccept> FRIENDS_TPA = Choice.ofEnum("friends-tpa", AutoAccept.class, AutoAccept::id, AutoAccept.NOBODY)
        .option(AutoAccept.NOBODY, AutoAccept.NOBODY.label())
        .option(AutoAccept.FAVOURITES, AutoAccept.FAVOURITES.label(), null, AutoAccept.NOBODY.id())
        .option(AutoAccept.ALL, AutoAccept.ALL.label())
        .option(AutoAccept.FRIENDS_TEAM, AutoAccept.FRIENDS_TEAM.label())
        .text(FRIENDS_TPA_LABEL, FRIENDS_TPA_DESCRIPTION).build();
    /**
     * How sale receipts show: a detailed receipt in chat, only the total above the hotbar, or nothing. Was the
     * {@code sell_receipts} switch (on: chat, off: the hotbar). Read by selling and by spawner storage sales.
     */
    public static final Choice<AlertStyle> SELL_RECEIPTS = Choices.alert("sell_receipts", AlertStyle.CHAT,
        AlertStyle.CHAT, AlertStyle.ACTIONBAR, AlertStyle.OFF)
        .legacyValue("true", AlertStyle.CHAT.id()).legacyValue("false", AlertStyle.ACTIONBAR.id())
        .text(SELL_RECEIPTS_LABEL, SELL_RECEIPTS_DESCRIPTION).build();

    /** Every shared setting. */
    public static final List<PlayerSetting<?>> ALL = List.of(SOUND_VOLUME, SOUND_NOTIFY, SOUND_MENTION, SOUND_PM, SOUND_CLICKS,
        SOUND_SUCCESS, SOUND_ERRORS, SOUND_TEAM_CHAT, QUIET_IN_COMBAT, FEEDBACK_CHANNEL, HIDE_COORDINATES, SEEN_PRIVACY,
        BALANCE_PRIVACY, HIDE_FROM_LEADERBOARDS, FRIENDS_TPA, SELL_RECEIPTS);
    /**
     * The shared settings only a feature's code acts on: offered once a feature declares it reads them with
     * {@code services.settings().reads(SharedSettings.X)}.
     */
    public static final List<PlayerSetting<?>> FEATURE_READ = List.of(SOUND_MENTION, SOUND_PM, SOUND_TEAM_CHAT, FRIENDS_TPA,
        SELL_RECEIPTS, HIDE_COORDINATES, SEEN_PRIVACY, BALANCE_PRIVACY, HIDE_FROM_LEADERBOARDS);

    private SharedSettings() {
    }

    /**
     * Registers every shared setting into its category, in the order the catalog lists them. Settings that
     * {@code Sounds} and {@code Messenger} apply (volume, the sound kinds, quiet in combat, the feedback channel) are
     * offered at once; the ones only a feature's code acts on ({@link #FEATURE_READ}) are offered once a feature
     * declares it reads them ({@link PlayerSettings#reads}), so the dialog never shows a switch that does nothing.
     */
    public static void register(PlayerSettings settings, Relations relations) {
        settings.register(SettingCategories.SOUND, SOUND_VOLUME, SettingOptions.<Long>builder().order(1).build());
        settings.register(SettingCategories.SOUND, SOUND_NOTIFY, SettingOptions.<Boolean>builder().order(2).build());
        settings.register(SettingCategories.SOUND, SOUND_MENTION, SettingOptions.<PingSound>builder().order(3)
            .availableWhen(() -> settings.hasReader(SOUND_MENTION)).build());
        settings.register(SettingCategories.SOUND, SOUND_PM, SettingOptions.<PingSound>builder().order(4)
            .availableWhen(() -> settings.hasReader(SOUND_PM)).build());
        settings.register(SettingCategories.SOUND, SOUND_CLICKS, SettingOptions.<Boolean>builder().order(5).build());
        settings.register(SettingCategories.SOUND, SOUND_SUCCESS, SettingOptions.<Boolean>builder().order(6).build());
        settings.register(SettingCategories.SOUND, SOUND_ERRORS, SettingOptions.<Boolean>builder().order(7).build());
        settings.register(SettingCategories.SOUND, SOUND_TEAM_CHAT, SettingOptions.<PingSound>builder().order(8)
            .availableWhen(() -> settings.hasReader(SOUND_TEAM_CHAT)).build());
        settings.register(SettingCategories.TELEPORT, FRIENDS_TPA, SettingOptions.<AutoAccept>builder().order(2)
            .availableWhen(() -> relations.friendsAvailable() && settings.hasReader(FRIENDS_TPA))
            .optionAvailableWhen(AutoAccept.FAVOURITES.id(), relations::favouritesAvailable)
            .legacy("tpa-friends", stored -> {
                Boolean on = Toggle.parse(stored);
                return on == null ? null : (on ? AutoAccept.ALL : AutoAccept.NOBODY).id();
            })
            .placeholder(false).build());
        settings.register(SettingCategories.ECONOMY, SELL_RECEIPTS, SettingOptions.<AlertStyle>builder().order(1)
            .availableWhen(() -> settings.hasReader(SELL_RECEIPTS)).build());
        settings.register(SettingCategories.COMBAT, QUIET_IN_COMBAT, SettingOptions.<Boolean>builder().order(7).build());
        settings.register(SettingCategories.DISPLAY, FEEDBACK_CHANNEL, SettingOptions.<AlertStyle>builder().order(2).build());
        settings.register(SettingCategories.PRIVACY, HIDE_COORDINATES, SettingOptions.<Boolean>builder().order(1)
            .availableWhen(() -> settings.hasReader(HIDE_COORDINATES)).placeholder(false).build());
        settings.register(SettingCategories.PRIVACY, SEEN_PRIVACY, SettingOptions.<Audience>builder().order(2)
            .availableWhen(() -> settings.hasReader(SEEN_PRIVACY))
            .optionAvailableWhen(Audience.FRIENDS.id(), relations::friendsAvailable).placeholder(false).build());
        settings.register(SettingCategories.PRIVACY, BALANCE_PRIVACY, SettingOptions.<Audience>builder().order(3)
            .availableWhen(() -> settings.hasReader(BALANCE_PRIVACY))
            .optionAvailableWhen(Audience.FRIENDS.id(), relations::friendsAvailable).placeholder(false).build());
        settings.register(SettingCategories.PRIVACY, HIDE_FROM_LEADERBOARDS, SettingOptions.<Boolean>builder().order(6)
            .availableWhen(() -> settings.hasReader(HIDE_FROM_LEADERBOARDS)).build());
    }

    private static Choice<Audience> privacy(String id, MessageKey label, MessageKey description) {
        return Choice.ofEnum(id, Audience.class, Audience::id, Audience.EVERYONE)
            .option(Audience.EVERYONE, Audience.EVERYONE.label())
            .option(Audience.FRIENDS, Audience.FRIENDS.label(), null, Audience.NOBODY.id())
            .option(Audience.NOBODY, Audience.NOBODY.label())
            .text(label, description).build();
    }
}
