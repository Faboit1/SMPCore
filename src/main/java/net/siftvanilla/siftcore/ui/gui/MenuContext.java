package net.siftvanilla.siftcore.ui.gui;

import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.ui.dialog.Dialogs;
import net.siftvanilla.siftcore.ui.dialog.Templates;

/** The shared services every menu needs, passed as one constructor argument. */
public record MenuContext(Scheduler scheduler, Messenger messenger, Lang lang, Dialogs dialogs, Templates templates) {
}
