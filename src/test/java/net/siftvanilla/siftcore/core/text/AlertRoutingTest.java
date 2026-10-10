package net.siftvanilla.siftcore.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;
import java.util.Set;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.text.Routing.Place;
import org.junit.jupiter.api.Test;

class AlertRoutingTest {

    private static final MessageKey SUCCESS = MessageKey.success("test.success");
    private static final MessageKey ERROR = MessageKey.error("test.error");
    private static final MessageKey INFO = MessageKey.info("test.info");
    private static final MessageKey STATUS = MessageKey.status("test.status", "time");
    private static final MessageKey CHAT = MessageKey.chat("test.chat");
    private static final MessageKey TITLE = MessageKey.title("test.title");

    @Test
    void successAndErrorLinesFollowTheFeedbackChannel() {
        assertEquals(EnumSet.of(Place.ACTIONBAR), Routing.feedback(SUCCESS, SUCCESS.channel(), AlertStyle.ACTIONBAR));
        assertEquals(EnumSet.of(Place.CHAT), Routing.feedback(SUCCESS, SUCCESS.channel(), AlertStyle.CHAT));
        assertEquals(EnumSet.of(Place.CHAT, Place.ACTIONBAR), Routing.feedback(ERROR, ERROR.channel(), AlertStyle.BOTH));
        assertEquals(EnumSet.of(Place.CHAT), Routing.feedback(ERROR, Channel.ACTIONBAR, AlertStyle.CHAT), "explicit action bar too");
    }

    @Test
    void statusLinesInfoAndOtherChannelsKeepTheirPlace() {
        for (AlertStyle preference : AlertStyle.values()) {
            assertEquals(EnumSet.of(Place.ACTIONBAR), Routing.feedback(STATUS, STATUS.channel(), preference), "status " + preference);
            assertEquals(EnumSet.of(Place.ACTIONBAR), Routing.feedback(INFO, INFO.channel(), preference), "info " + preference);
            assertEquals(EnumSet.of(Place.CHAT), Routing.feedback(CHAT, CHAT.channel(), preference), "chat " + preference);
            assertEquals(EnumSet.of(Place.TITLE), Routing.feedback(TITLE, TITLE.channel(), preference), "title " + preference);
        }
        assertEquals(EnumSet.of(Place.CHAT), Routing.feedback(MessageKey.ui("test.ui"), Channel.NONE, AlertStyle.ACTIONBAR),
            "ui keys sent on their own go to chat");
        assertEquals(EnumSet.of(Place.ACTIONBAR), Routing.feedback(SUCCESS, SUCCESS.channel(), AlertStyle.OFF),
            "an unexpected preference keeps the action bar");
    }

    @Test
    void alertsGoWhereTheSettingSays() {
        assertEquals(Set.of(), Routing.alert(AlertStyle.OFF, false));
        assertEquals(EnumSet.of(Place.CHAT), Routing.alert(AlertStyle.CHAT, false));
        assertEquals(EnumSet.of(Place.ACTIONBAR), Routing.alert(AlertStyle.ACTIONBAR, false));
        assertEquals(EnumSet.of(Place.TITLE), Routing.alert(AlertStyle.TITLE, false));
        assertEquals(EnumSet.of(Place.CHAT, Place.ACTIONBAR), Routing.alert(AlertStyle.BOTH, false));
        assertEquals(EnumSet.of(Place.ACTIONBAR), Routing.alert(AlertStyle.BOSSBAR, false), "one-shot alerts have no bar");
    }

    @Test
    void quietInCombatTurnsPopUpsIntoChatLines() {
        assertEquals(EnumSet.of(Place.CHAT), Routing.alert(AlertStyle.ACTIONBAR, true));
        assertEquals(EnumSet.of(Place.CHAT), Routing.alert(AlertStyle.TITLE, true));
        assertEquals(EnumSet.of(Place.CHAT), Routing.alert(AlertStyle.BOTH, true));
        assertEquals(EnumSet.of(Place.CHAT), Routing.alert(AlertStyle.BOSSBAR, true));
        assertEquals(EnumSet.of(Place.CHAT), Routing.alert(AlertStyle.CHAT, true));
        assertEquals(Set.of(), Routing.alert(AlertStyle.OFF, true), "off stays off");
    }

    @Test
    void repeatingRefusalsStayOnTheActionBarWithTheirErrorColoursAndSound() {
        MessageKey refusal = MessageKey.error("test.refusal", "time").asStatus();
        assertEquals(Feedback.ERROR, refusal.feedback(), "still an error: red text and the error note");
        assertEquals(Channel.ACTIONBAR, refusal.channel());
        for (AlertStyle preference : AlertStyle.values()) {
            assertEquals(EnumSet.of(Place.ACTIONBAR), Routing.feedback(refusal, refusal.channel(), preference), "refusal " + preference);
        }
    }

    @Test
    void onlyActionBarLinesTheChannelMovedCountAsMovedToChat() {
        assertEquals(true, Routing.movedToChat(ERROR, Channel.ACTIONBAR, Routing.feedback(ERROR, Channel.ACTIONBAR, AlertStyle.CHAT)));
        assertEquals(true, Routing.movedToChat(ERROR, Channel.ACTIONBAR, Routing.feedback(ERROR, Channel.ACTIONBAR, AlertStyle.BOTH)));
        assertEquals(false, Routing.movedToChat(ERROR, Channel.ACTIONBAR, Routing.feedback(ERROR, Channel.ACTIONBAR, AlertStyle.ACTIONBAR)));
        assertEquals(false, Routing.movedToChat(CHAT, Channel.CHAT, Routing.feedback(CHAT, Channel.CHAT, AlertStyle.CHAT)),
            "chat keys were in chat anyway");
        MessageKey refusal = ERROR.asStatus();
        assertEquals(false, Routing.movedToChat(refusal, Channel.ACTIONBAR, Routing.feedback(refusal, Channel.ACTIONBAR, AlertStyle.CHAT)));
    }

    @Test
    void statusKeysAreMarked() {
        assertEquals(true, STATUS.status());
        assertEquals(Channel.ACTIONBAR, STATUS.channel());
        assertEquals(Feedback.NONE, STATUS.feedback());
        assertEquals(false, INFO.status());
        assertEquals(true, STATUS.withFeedback(Feedback.CLICK).status(), "kept by withFeedback");
    }
}
