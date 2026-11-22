import Toybox.Application;
import Toybox.Background;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.SensorHistory;
import Toybox.System;
import Toybox.Time;

// Every 5 min: (0) fire due watch alarms locally, (a) POST the newest Body Battery sample if it is new,
// (b) refresh the alarm list if the last fetch is > 25 min old, (c) pump the voice-note queue.
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
        fire(); // first, before any web request: it works with no phone
        var it = SensorHistory.getBodyBatteryHistory({:period => 1, :order => SensorHistory.ORDER_NEWEST_FIRST});
        var s = (it == null) ? null : it.next();
        if (s == null || s.data == null) {
            alarms();
            return;
        }
        _level = s.data.toNumber();
        _ts = s.when.value();
        if (_ts == Application.Storage.getValue("sentTs")) {
            alarms(); // nothing new since the last successful POST
            return;
        }
        Communications.makeWebRequest(
            baseUrl() + "/api/body-battery",
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
        alarms();
    }

    // strip a trailing slash so "<base>/" does not become "//api"
    private function baseUrl() as String {
        var url = Application.Properties.getValue("baseUrl") as String;
        if (url.length() > 0 && url.substring(url.length() - 1, url.length()).equals("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    // Storage "al" = [[fire_at, "HH:MM title", kind]...]. Fire each alarm that is due (up to 15 min late) once;
    // "alFired" keeps the last 20 fire_at values. Several due at once become one wake message.
    private function fire() as Void {
        var al = Application.Storage.getValue("al");
        if (!(al instanceof Array)) {
            return;
        }
        var fd = Application.Storage.getValue("alFired");
        var fired = (fd instanceof Array) ? fd as Array : [];
        var seen = fired.slice(0, null); // two tasks at the same minute share fire_at: check the old list only
        var now = Time.now().value();
        var msg = "";
        for (var i = 0; i < (al as Array).size(); i++) {
            var a = (al as Array)[i] as Array;
            var t = a[0] as Number;
            if (t <= now && t > now - 900 && seen.indexOf(t) < 0) {
                msg += ((msg.length() > 0) ? "\n" : "") + (a[1] as String);
                fired.add(t);
            }
        }
        if (msg.length() == 0) {
            return;
        }
        if (fired.size() > 20) {
            fired = fired.slice(fired.size() - 20, null);
        }
        try {
            Application.Storage.setValue("alFired", fired); // before the wake: never fire twice
        } catch (e) {
        }
        Background.requestApplicationWake("\u23F0 " + msg);
    }

    private function alarms() as Void {
        var at = Application.Storage.getValue("alAt");
        if (at instanceof Number && Time.now().value() - (at as Number) < 1500) {
            notes();
            return;
        }
        Communications.makeWebRequest(
            baseUrl() + "/api/alarms",
            {"channel" => "watch", "hours" => "24"},
            {
                :method => Communications.HTTP_REQUEST_METHOD_GET,
                :headers => {"X-API-Key" => Application.Properties.getValue("apiKey") as String},
                :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON
            },
            method(:onAlarms)
        );
    }

    // Parse straight into the compact array; on failure the old list stays.
    function onAlarms(code as Number, data as Dictionary or String or Null) as Void {
        try {
            Application.Storage.setValue("alCode", code);
            if (code == 200 && data instanceof Dictionary) {
                var src = (data as Dictionary)["alarms"];
                var out = [];
                if (src instanceof Array) {
                    for (var i = 0; i < (src as Array).size() && i < 20; i++) {
                        var d = (src as Array)[i] as Dictionary;
                        var txt = (d["start"] as String) + " " + (d["title"] as String);
                        out.add([d["fire_at"] as Number, (txt.length() > 40) ? txt.substring(0, 40) : txt, d["kind"] as String]);
                    }
                }
                Application.Storage.setValue("al", out);
                Application.Storage.setValue("alAt", Time.now().value());
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
