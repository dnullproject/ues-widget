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
                // body is irrelevant; plain text avoids a -400 when it is empty or not JSON
                :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_TEXT_PLAIN
            },
            method(:onReceive)
        );
    }

    // Background code may not write Storage on every device, so hand the result to the app (onBackgroundData).
    function onReceive(code as Number, data as Dictionary or String or Null) as Void {
        var ok = code >= 200 && code < 300;
        Background.exit({"status" => code, "sentTs" => ok ? _ts : null, "level" => ok ? _level : null});
    }
}
