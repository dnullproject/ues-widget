import Toybox.Application;
import Toybox.Background;
import Toybox.Lang;
import Toybox.System;
import Toybox.Time;
import Toybox.WatchUi;

(:background)
class UesNoteApp extends Application.AppBase {
    function initialize() {
        AppBase.initialize();
    }

    // Opening the app starts recording at once: no menu.
    (:typecheck([disableBackgroundCheck]))
    function getInitialView() as [Views] or [Views, InputDelegates] {
        var s = new NoteSession();
        return [new UesNoteView(s), new UesNoteDelegate(s)];
    }

    (:typecheck(disableGlanceCheck))
    function getServiceDelegate() as [ServiceDelegate] {
        return [new UesNoteBg()];
    }
}
