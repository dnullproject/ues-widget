import Toybox.Application;
import Toybox.Background;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.SensorHistory;
import Toybox.System;
import Toybox.Time;

// Every 5 min: (a) POST the newest Body Battery sample if it is new, then (b) pump the voice-note queue.
// Sequential on purpose: one web request at a time keeps the 64 KB background memory low.
(:background)
class UesBg extends System.ServiceDelegate {
    private var _level as Number = 0;
    private var _ts as Number = 0;
    private var _sender as NoteSender? = null; // held so the sender outlives onTemporalEvent

    function initialize() {
        ServiceDelegate.initialize();
    }

    function onTemporalEvent() as Void {
        var it = SensorHistory.getBodyBatteryHistory({:period => 1, :order => SensorHistory.ORDER_NEWEST_FIRST});
        var s = (it == null) ? null : it.next();
        if (s == null || s.data == null) {
            notes();
            return;
        }
        _level = s.data.toNumber();
        _ts = s.when.value();
        if (_ts == Application.Storage.getValue("sentTs")) {
            notes(); // nothing new since the last successful POST
            return;
        }
        var url = Application.Properties.getValue("baseUrl") as String;
        // strip a trailing slash so "<base>/" does not become "//api"
        if (url.length() > 0 && url.substring(url.length() - 1, url.length()).equals("/")) {
            url = url.substring(0, url.length() - 1);
        }
        Communications.makeWebRequest(
            url + "/api/body-battery",
            {"level" => _level, "timestamp" => _ts, "source" => "watch"},
            {
                :method => Communications.HTTP_REQUEST_METHOD_POST,
                :headers => {
                    "Content-Type" => Communications.REQUEST_CONTENT_TYPE_JSON,
                    "X-API-Key" => Application.Properties.getValue("apiKey") as String
                },
                // must match the server's application/json reply, or Connect IQ reports -400 instead of the status
                :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON
            },
            method(:onReceive)
        );
    }

    // Write Storage here so dedup and the Status page work while the app is closed.
    function onReceive(code as Number, data as Dictionary or String or Null) as Void {
        try {
            Application.Storage.setValue("status", code);
            if (code >= 200 && code < 300) {
                Application.Storage.setValue("sentTs", _ts);
                Application.Storage.setValue("level", _level);
                Application.Storage.setValue("bbAt", Time.now().value());
            }
        } catch (e) {
        }
        notes();
    }

    private function notes() as Void {
        _sender = new NoteSender(method(:onIdle));
        (_sender as NoteSender).pump(); // calls onIdle at once when the queue is empty
    }

    function onIdle() as Void {
        Background.exit(null);
    }
}
