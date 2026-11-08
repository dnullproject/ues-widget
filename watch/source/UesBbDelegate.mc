import Toybox.Application;
import Toybox.Background;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.SensorHistory;
import Toybox.System;

(:background)
class UesBbDelegate extends System.ServiceDelegate {
    private var _level as Number = 0;
    private var _ts as Number = 0;

    function initialize() {
        ServiceDelegate.initialize();
    }

    function onTemporalEvent() as Void {
        var it = SensorHistory.getBodyBatteryHistory({:period => 1, :order => SensorHistory.ORDER_NEWEST_FIRST});
        var s = (it == null) ? null : it.next();
        if (s == null || s.data == null) {
            Background.exit(null);
            return;
        }
        _level = s.data.toNumber();
        _ts = s.when.value();
        if (_ts == Application.Storage.getValue("sentTs")) {
            Background.exit(null); // nothing new since the last successful POST
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

    // Write Storage here so dedup works while the widget is closed (onBackgroundData only runs when it is open);
    // also hand the result to the app in case this device refuses background writes.
    function onReceive(code as Number, data as Dictionary or String or Null) as Void {
        var ok = code >= 200 && code < 300;
        try {
            Application.Storage.setValue("status", code);
            if (ok) {
                Application.Storage.setValue("sentTs", _ts);
                Application.Storage.setValue("level", _level);
            }
        } catch (e) {
        }
        Background.exit({"status" => code, "sentTs" => ok ? _ts : null, "level" => ok ? _level : null});
    }
}
