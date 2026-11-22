import Toybox.Application;
import Toybox.Background;
import Toybox.Lang;
import Toybox.System;
import Toybox.Time;
import Toybox.WatchUi;

(:background :glance)
class UesApp extends Application.AppBase {
    function initialize() {
        AppBase.initialize();
    }

    // Registered only when missing: re-registering would push the next run back.
    function onStart(state as Dictionary?) as Void {
        if (Background.getTemporalEventRegisteredTime() == null) {
            Background.registerForTemporalEvent(new Time.Duration(300));
        }
    }

    // Opening the app shows the Record page; recording starts only from START.
    (:typecheck([disableBackgroundCheck, disableGlanceCheck]))
    function getInitialView() as [Views] or [Views, InputDelegates] {
        var s = new NoteSession();
        return [new UesView(s), new UesDelegate(s)];
    }

    (:glance)
    function getGlanceView() as [GlanceView] or [GlanceView, GlanceViewDelegate] or Null {
        return [new UesGlance()];
    }

    // The background wrote Storage itself; just redraw.
    function onBackgroundData(data as Application.PersistableType) as Void {
        WatchUi.requestUpdate();
    }

    (:typecheck(disableGlanceCheck))
    function getServiceDelegate() as [ServiceDelegate] {
        return [new UesBg()];
    }
}
