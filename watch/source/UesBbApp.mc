import Toybox.Application;
import Toybox.Background;
import Toybox.Lang;
import Toybox.Time;
import Toybox.WatchUi;

(:background :glance)
class UesBbApp extends Application.AppBase {
    function initialize() {
        AppBase.initialize();
    }

    // The widget must be opened once after install so this registers the 5 min background event.
    // Registered only when missing: re-registering would push the next run back.
    function onStart(state as Dictionary?) as Void {
        if (Background.getTemporalEventRegisteredTime() == null) {
            Background.registerForTemporalEvent(new Time.Duration(300));
        }
    }

    function getInitialView() as [Views] or [Views, InputDelegates] {
        return [new UesBbView()];
    }

    (:glance)
    function getGlanceView() as [GlanceView] or [GlanceView, GlanceViewDelegate] or Null {
        return [new UesBbGlance()];
    }

    function onBackgroundData(data as Application.PersistableType) as Void {
        if (data instanceof Dictionary) {
            var d = data as Dictionary;
            Application.Storage.setValue("status", d["status"]);
            if (d["sentTs"] != null) {
                Application.Storage.setValue("sentTs", d["sentTs"]);
                Application.Storage.setValue("level", d["level"]);
            }
        }
        WatchUi.requestUpdate();
    }

    function getServiceDelegate() as [ServiceDelegate] {
        return [new UesBbDelegate()];
    }
}
