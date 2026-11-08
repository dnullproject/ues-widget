import Toybox.Application;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.System;

// Persistent upload queue shared by the foreground recorder and the background retry.
// Storage layout: "pend" = [note_id...] (oldest first), "m<id>" = meta dict, "c<id>.<seq>" = base64 chunk string.
// Meta: c = chunks stored, b = raw audio bytes stored, n = next seq to send, done = recording finished, fmt, rec, dur, x = 409 resends.
// Chunks stay until the server answers 201, so a 409 {"missing":[..]} can be answered by resending.
(:background)
class NoteSender {
    var failed as Boolean = false; // last request failed (retry later); cleared by pump()
    private var _busy as Boolean = false;
    private var _id as String = "";
    private var _onIdle as Method() as Void;

    // _onIdle runs whenever pump() ends without a request in flight (queue empty, waiting for chunks, or failure)
    function initialize(onIdle as Method() as Void) {
        _onIdle = onIdle;
    }

    function ids() as Array<String> {
        var p = Application.Storage.getValue("pend");
        return (p == null) ? [] as Array<String> : p as Array<String>;
    }

    function meta(id as String) as Dictionary? {
        return Application.Storage.getValue("m" + id) as Dictionary?;
    }

    // [sent, total] chunks of the first queued note
    function progress() as Array<Number> {
        var p = ids();
        var m = (p.size() == 0) ? null : meta(p[0]);
        return (m == null) ? [0, 0] : [m["n"] as Number, m["c"] as Number];
    }

    function create(id as String, fmt as String, rec as Number) as Void {
        var p = ids();
        p.add(id);
        Application.Storage.setValue("m" + id, {"c" => 0, "b" => 0, "n" => 0, "done" => false, "fmt" => fmt, "rec" => rec, "dur" => 0, "x" => 0});
        Application.Storage.setValue("pend", p);
    }

    // false when Storage is full (or the note is gone): the caller stops recording
    function addChunk(id as String, b64 as String, rawBytes as Number) as Boolean {
        var m = meta(id);
        if (m == null) {
            return false;
        }
        var c = m["c"] as Number;
        try {
            Application.Storage.setValue("c" + id + "." + c, b64);
            m["c"] = c + 1;
            m["b"] = (m["b"] as Number) + rawBytes;
            Application.Storage.setValue("m" + id, m);
        } catch (e) {
            Application.Storage.deleteValue("c" + id + "." + c);
            return false;
        }
        return true;
    }

    function finish(id as String, dur as Number) as Void {
        var m = meta(id);
        if (m != null) {
            m["done"] = true;
            m["dur"] = dur;
            Application.Storage.setValue("m" + id, m);
        }
    }

    function drop(id as String) as Void {
        var m = meta(id);
        var c = (m == null) ? 0 : m["c"] as Number;
        for (var i = 0; i < c; i++) {
            Application.Storage.deleteValue("c" + id + "." + i);
        }
        Application.Storage.deleteValue("m" + id);
        var p = ids();
        p.remove(id);
        Application.Storage.setValue("pend", p);
    }

    // A crash mid-recording leaves an unfinished note at the head of the queue; drop those on app start.
    function dropUnfinished() as Void {
        var p = ids();
        for (var i = 0; i < p.size(); i++) {
            var m = meta(p[i]);
            if (m == null || !(m["done"] as Boolean)) {
                drop(p[i]);
            }
        }
    }

    // Send the next chunk of the oldest note; continues on its own after each answer until idle or failed.
    function pump() as Void {
        if (_busy) {
            return;
        }
        failed = false;
        var p = ids();
        if (p.size() == 0) {
            _onIdle.invoke();
            return;
        }
        var id = p[0];
        var m = meta(id);
        if (m == null) {
            drop(id);
            pump();
            return;
        }
        var n = m["n"] as Number;
        var c = m["c"] as Number;
        var done = m["done"] as Boolean;
        if (n >= c) {
            if (done) {
                m["n"] = c - 1; // all acked but no 201 yet: resend the last chunk to get the verdict
                Application.Storage.setValue("m" + id, m);
                n = c - 1;
            } else {
                _onIdle.invoke(); // still recording: wait for the next chunk
                return;
            }
        }
        var data = Application.Storage.getValue("c" + id + "." + n) as String?;
        if (data == null) {
            drop(id); // chunk lost from Storage: the note can never complete
            pump();
            return;
        }
        var last = done && n == c - 1;
        var body = {"note_id" => id, "seq" => n, "last" => last, "data" => data} as Dictionary;
        if (n == 0) {
            body["format"] = m["fmt"];
            body["recorded_at"] = m["rec"];
        }
        if (last) {
            body["duration_s"] = m["dur"];
            body["total_bytes"] = m["b"]; // the server answers 201 only if the assembled audio has this length
            body["chunks"] = c;
        }
        var url = Application.Properties.getValue("baseUrl") as String;
        // strip a trailing slash so "<base>/" does not become "//api"
        if (url.length() > 0 && url.substring(url.length() - 1, url.length()).equals("/")) {
            url = url.substring(0, url.length() - 1);
        }
        _busy = true;
        _id = id;
        Communications.makeWebRequest(
            url + "/api/voice-notes/chunk",
            body,
            {
                :method => Communications.HTTP_REQUEST_METHOD_POST,
                :headers => {
                    "Content-Type" => Communications.REQUEST_CONTENT_TYPE_JSON,
                    "X-API-Key" => Application.Properties.getValue("apiKey") as String
                },
                // plain text avoids a -400 on an empty or non-JSON body; 409 is parsed by hand
                :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_TEXT_PLAIN
            },
            method(:onReceive)
        );
    }

    function onReceive(code as Number, data as Dictionary or String or Null) as Void {
        _busy = false;
        var m = meta(_id);
        if (m == null) {
            pump();
            return;
        }
        var n = m["n"] as Number;
        var c = m["c"] as Number;
        var last = (m["done"] as Boolean) && n == c - 1;
        if (code == 201) {
            drop(_id); // only an explicit 201 confirms delivery
        } else if (code >= 200 && code < 300 && !last) {
            m["n"] = n + 1;
            Application.Storage.setValue("m" + _id, m);
        } else if (code == 409 && (m["x"] as Number) < 5) {
            m["x"] = (m["x"] as Number) + 1;
            m["n"] = firstMissing(data, n);
            Application.Storage.setValue("m" + _id, m);
        } else if (code == 400 || code == 409) {
            drop(_id); // the server will never accept this note: do not block the queue
        } else {
            failed = true; // 401, 5xx, no phone / connection (negative codes): keep and retry later
            _onIdle.invoke();
            return;
        }
        pump();
    }

    // first seq in {"missing":[2,5]}; falls back to `dflt` when the body cannot be read
    private function firstMissing(data as Dictionary or String or Null, dflt as Number) as Number {
        if (data instanceof Dictionary) {
            var a = (data as Dictionary)["missing"];
            return (a instanceof Array && (a as Array).size() > 0) ? (a as Array)[0] as Number : dflt;
        }
        if (data instanceof String) {
            var s = data as String;
            var i = s.find("[");
            var j = s.find("]");
            if (i != null && j != null && j > i + 1) {
                var sub = s.substring(i + 1, j);
                var k = sub.find(",");
                var n = ((k == null) ? sub : sub.substring(0, k)).toNumber();
                return (n == null) ? dflt : n;
            }
        }
        return dflt;
    }
}
