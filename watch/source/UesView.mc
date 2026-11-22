import Toybox.Application;
import Toybox.Attention;
import Toybox.Audio;
import Toybox.Audio.Capture;
import Toybox.Background;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Math;
import Toybox.StringUtil;
import Toybox.System;
import Toybox.Time;
import Toybox.Time.Gregorian;
import Toybox.Timer;
import Toybox.WatchUi;

enum NoteState { ST_IDLE, ST_REC, ST_STOPPING, ST_SENDING, ST_DONE, ST_FAIL, ST_ERR }
enum { PG_REC, PG_QUEUE, PG_STATUS, PG_COUNT }

const NOTE_MAX_S = 180; // auto-stop
const NOTE_CHUNK = 6000; // raw bytes per upload chunk

// Records, queues chunks in Storage as they arrive (so RAM stays at ~2 chunks, far below the 768 KB limit,
// whatever the codec bitrate) and uploads them while recording. A note is only ever held back for the last chunk.
class NoteSession {
    var state as NoteState = ST_IDLE;
    var page as Number = PG_REC; // UI page, kept here so the view and delegate share it
    var err as String = "";
    var sender as NoteSender;
    private var _id as String = "";
    private var _buf as ByteArray = []b;
    private var _startMs as Number = 0;
    private var _dur as Number = 0;
    private var _deadlineMs as Number = 0; // ST_STOPPING: finish anyway; ST_DONE/FAIL/ERR: exit
    private var _timer as Timer.Timer = new Timer.Timer();
    private var _nextRing as Number = 0; // System.getTimer() ms of the next alarm buzz

    function initialize() {
        sender = new NoteSender(method(:onIdle));
        sender.dropUnfinished();
        _timer.start(method(:onTick), 500, true);
        Math.srand(System.getTimer() + Time.now().value());
    }

    // START on the Record page while idle
    function start() as Void {
        _id = "";
        _buf = []b;
        _dur = 0;
        err = "";
        // [format, fallback format]: Opus in Ogg is ~10x smaller than LPCM
        var fmts = [
            [{:containerFormat => Audio.CONTAINER_OGG, :codec => Audio.CODEC_OPUS, :frequency => Audio.FREQ_16_KHZ, :channels => Audio.CHANNEL_MONO}, "ogg_opus"],
            [{:containerFormat => Audio.CONTAINER_WAV, :codec => Audio.CODEC_LPCM, :frequency => Audio.FREQ_8_KHZ, :channels => Audio.CHANNEL_MONO}, "wav_lpcm"]
        ];
        for (var i = 0; i < fmts.size(); i++) {
            var f = fmts[i][0] as AudioFormat;
            if (Capture.isFormatSupported(f)) {
                begin(f, fmts[i][1] as String);
                return;
            }
        }
        fail("No mic format");
    }

    function recording() as Boolean {
        return state == ST_REC || state == ST_STOPPING;
    }

    // Queue page: START. Pumps now; a note left unfinished by a crash is sealed first so it cannot block the queue.
    function retry() as Void {
        if (!recording()) {
            sender.dropUnfinished();
            if (sender.ids().size() > 0) {
                state = ST_SENDING;
            }
        }
        sender.pump();
    }

    function deleteFirst() as Void {
        var p = sender.ids();
        if (p.size() > 0 && !recording()) {
            sender.drop(p[0], "");
        }
    }

    function deleteAll() as Void {
        if (recording()) {
            return;
        }
        var p = sender.ids();
        for (var i = 0; i < p.size(); i++) {
            sender.drop(p[i], "");
        }
    }

    private function begin(f as AudioFormat, name as String) as Void {
        try {
            Capture.initializeAudioCapture(self as Capture.Callbacks, {
                :format => f, :windowSize => 4096, :windowFilledBehavior => Capture.WINDOW_FILLED_CONTINUE, :filters => []
            });
            _id = Time.now().value().toString() + "-" + (Math.rand() % 100000).format("%05d");
            sender.create(_id, name, Time.now().value());
            state = ST_REC;
            _startMs = System.getTimer();
            Capture.start();
        } catch (e) {
            fail("Mic unavailable");
            return;
        }
        buzz();
        sender.pump(); // retry notes left over from an earlier failure while this one records
    }

    function elapsed() as Number {
        if (state == ST_REC) {
            _dur = (System.getTimer() - _startMs) / 1000;
        }
        return _dur;
    }

    // START / BACK while recording; the note is queued and the background sends it if the app is left
    function stop() as Void {
        if (state == ST_REC) {
            state = ST_STOPPING;
            _deadlineMs = System.getTimer() + 2000;
            buzz();
            Capture.stop(); // flushes the rest through onData, then onComplete
        }
    }

    function onData(audioData as ByteArray) as Void {
        if (state != ST_REC && state != ST_STOPPING) {
            return;
        }
        _buf.addAll(audioData);
        // strictly greater: the tail always keeps >= 1 byte to carry the "last" flag
        while (_buf.size() > NOTE_CHUNK) {
            if (!store(_buf.slice(0, NOTE_CHUNK))) {
                return;
            }
            _buf = _buf.slice(NOTE_CHUNK, null);
        }
        sender.pump();
    }

    function onComplete() as Void {
        finish();
    }

    function onError(error as Capture.Error) as Void {
        // BACKGROUNDED / INTERNAL after some audio: keep what we have
        if (_buf.size() > 0 || (sender.meta(_id) != null && (sender.meta(_id)["c"] as Number) > 0)) {
            finish();
        } else {
            fail("Mic error " + error);
        }
    }

    private function store(raw as ByteArray) as Boolean {
        var b64 = StringUtil.convertEncodedString(raw, {
            :fromRepresentation => StringUtil.REPRESENTATION_BYTE_ARRAY,
            :toRepresentation => StringUtil.REPRESENTATION_STRING_BASE64
        }) as String;
        if (!sender.addChunk(_id, b64, raw.size())) { // Storage full
            _buf = []b;
            if (state == ST_REC) {
                stop();
            }
            return false;
        }
        return true;
    }

    private function finish() as Void {
        if (state != ST_REC && state != ST_STOPPING) {
            return;
        }
        _dur = elapsed();
        state = ST_STOPPING; // freezes elapsed()
        if (_buf.size() > 0) {
            store(_buf);
            _buf = []b;
        }
        var m = sender.meta(_id);
        if (m == null || (m["c"] as Number) == 0) {
            sender.drop(_id, "nothing recorded");
            fail("Nothing recorded");
            return;
        }
        sender.finish(_id, _dur);
        state = ST_SENDING;
        sender.pump();
    }

    private function fail(msg as String) as Void {
        if (_id.length() > 0) {
            sender.drop(_id, msg); // an unfinished note would block the queue
        }
        err = msg;
        state = ST_ERR;
        _deadlineMs = System.getTimer() + 3000;
    }

    // Queue went idle (all sent, waiting for a chunk, or a request failed)
    function onIdle() as Void {
        if (state != ST_SENDING) {
            return;
        }
        if (sender.failed) {
            state = ST_FAIL;
            _deadlineMs = System.getTimer() + 3000;
        } else if (sender.ids().size() == 0) {
            state = ST_DONE;
            _deadlineMs = System.getTimer() + 2000;
            buzz();
        }
    }

    function onTick() as Void {
        var now = System.getTimer();
        if (state == ST_REC && elapsed() >= NOTE_MAX_S) {
            stop();
        } else if (state == ST_STOPPING && now >= _deadlineMs) {
            finish(); // onComplete never came
        } else if ((state == ST_DONE || state == ST_FAIL || state == ST_ERR) && now >= _deadlineMs) {
            state = ST_IDLE;
        }
        if (!recording() && now >= _nextRing && uesDue().size() > 0) { // due alarm: buzz now and every 10 s until dismissed
            _nextRing = now + 10000;
            if (Attention has :vibrate) {
                var on = new Attention.VibeProfile(100, 400);
                var off = new Attention.VibeProfile(0, 200);
                Attention.vibrate([on, off, on, off, on] as Array<Attention.VibeProfile>);
            }
            if (Attention has :playTone) {
                Attention.playTone(Attention.TONE_ALARM);
            }
            WatchUi.requestUpdate();
        }
        if (state != ST_IDLE) {
            WatchUi.requestUpdate();
        }
    }

    private function buzz() as Void {
        if (Attention has :vibrate) {
            Attention.vibrate([new Attention.VibeProfile(100, 200)] as Array<Attention.VibeProfile>);
        }
    }
}

// "BB <level> · Q <n>" (Q only when notes are queued); reads Storage only, to stay within the 64 KB glance limit
(:glance)
class UesGlance extends WatchUi.GlanceView {
    function initialize() {
        GlanceView.initialize();
    }

    function onUpdate(dc as Dc) as Void {
        var level = Application.Storage.getValue("level");
        var p = Application.Storage.getValue("pend");
        var q = (p instanceof Array) ? (p as Array).size() : 0;
        var n = uesNextAlarm(Time.now().value() - 300);
        var tail = (q > 0) ? " · Q " + q : "";
        if (n != null && (n as Number) <= Time.now().value() + 3600) { // alarm within 60 min replaces the Q part
            tail = " · \u23F0 " + uesHm(n);
        }
        var txt = "BB " + ((level == null) ? "--" : level.toString()) + tail;
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(0, dc.getHeight() / 2, Graphics.FONT_GLANCE, txt, Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
    }
}

// fire_at of the first stored alarm at or after minFire, null when none
(:glance)
function uesNextAlarm(minFire as Number) as Number? {
    var al = Application.Storage.getValue("al");
    if (al instanceof Array) {
        for (var i = 0; i < (al as Array).size(); i++) {
            var t = ((al as Array)[i] as Array)[0] as Number;
            if (t >= minFire) {
                return t;
            }
        }
    }
    return null;
}

// Stored alarms due now (up to 15 min late) and not acknowledged yet
function uesDue() as Array {
    var al = Application.Storage.getValue("al");
    var ack = Application.Storage.getValue("alAck");
    var now = Time.now().value();
    var out = [];
    if (al instanceof Array) {
        for (var i = 0; i < (al as Array).size(); i++) {
            var a = (al as Array)[i] as Array;
            var t = a[0] as Number;
            if (t <= now && t > now - 900 && !(ack instanceof Array && (ack as Array).indexOf(t) >= 0)) {
                out.add(a);
            }
        }
    }
    return out;
}

// START / BACK on the alarm screen: mark every due alarm acknowledged (keeps the last 20 fire_at values)
function uesAck() as Void {
    var due = uesDue();
    var ack = Application.Storage.getValue("alAck");
    var l = (ack instanceof Array) ? ack as Array : [];
    for (var i = 0; i < due.size(); i++) {
        l.add((due[i] as Array)[0]);
    }
    Application.Storage.setValue("alAck", (l.size() > 20) ? l.slice(l.size() - 20, null) : l);
}

// HH:mm of a Storage timestamp, "--:--" when never
(:glance)
function uesHm(ts as Application.PropertyValueType) as String {
    if (!(ts instanceof Number)) {
        return "--:--";
    }
    var t = Gregorian.info(new Time.Moment(ts as Number), Time.FORMAT_SHORT);
    return t.hour.format("%02d") + ":" + t.min.format("%02d");
}

function uesStr(v as Application.PropertyValueType) as String {
    return (v == null) ? "--" : v.toString();
}

// Three pages (UP/DOWN): Record, Queue, Status. Pure black background: AMOLED pixels stay off except the few drawn ones.
class UesView extends WatchUi.View {
    private var _s as NoteSession;

    function initialize(s as NoteSession) {
        View.initialize();
        _s = s;
    }

    // Arc from 12 o'clock, clockwise, covering fraction f of the circle
    private function arc(dc as Dc, r as Number, width as Number, f as Float) as Void {
        if (f <= 0) {
            return;
        }
        dc.setPenWidth(width);
        if (f >= 1) {
            dc.drawCircle(dc.getWidth() / 2, dc.getHeight() / 2, r);
        } else {
            dc.drawArc(dc.getWidth() / 2, dc.getHeight() / 2, r, Graphics.ARC_CLOCKWISE, 90, 90 - 360.0 * f);
        }
    }

    function onUpdate(dc as Dc) as Void {
        if (dc has :setAntiAlias) {
            dc.setAntiAlias(true);
        }
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        var w = dc.getWidth();
        var h = dc.getHeight();
        var due = _s.recording() ? [] : uesDue(); // recording is never interrupted
        if (due.size() > 0) {
            drawAlarm(dc, w, h, due);
            return;
        }
        if (_s.page == PG_REC) {
            drawRecord(dc, w, h);
        } else if (_s.page == PG_QUEUE) {
            drawQueue(dc, w, h);
        } else {
            drawStatus(dc, w, h);
        }
        for (var i = 0; i < PG_COUNT; i++) { // page dots
            dc.setColor(i == _s.page ? Graphics.COLOR_WHITE : Graphics.COLOR_DK_GRAY, Graphics.COLOR_TRANSPARENT);
            dc.fillCircle(w / 2 + (i - 1) * 16, h * 5 / 100, 4);
        }
    }

    // Big time, title below; START or BACK dismisses all due alarms
    private function drawAlarm(dc as Dc, w as Number, h as Number, due as Array) as Void {
        var mid = Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER;
        var txt = (due[0] as Array)[1] as String; // "HH:MM title"
        dc.setColor(Graphics.COLOR_ORANGE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h * 30 / 100, Graphics.FONT_NUMBER_HOT, txt.substring(0, 5), mid);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        var title = (txt.length() > 6) ? txt.substring(6, txt.length()) : "";
        dc.drawText(w / 2, h * 54 / 100, (title.length() > 20) ? Graphics.FONT_XTINY : Graphics.FONT_SMALL, title, mid);
        if (due.size() > 1) {
            dc.drawText(w / 2, h * 66 / 100, Graphics.FONT_XTINY, "+" + (due.size() - 1), mid);
        }
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h * 82 / 100, Graphics.FONT_XTINY, "START: ok", mid);
    }

    private function drawQueue(dc as Dc, w as Number, h as Number) as Void {
        var mid = Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER;
        var p = _s.sender.ids();
        var n = p.size() - (_s.recording() ? 1 : 0); // the note being recorded is not "unsent" yet
        dc.setColor(n > 0 ? Graphics.COLOR_ORANGE : Graphics.COLOR_GREEN, Graphics.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h * 22 / 100, Graphics.FONT_MEDIUM, "Unsent: " + n, mid);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        var m = (p.size() == 0) ? null : _s.sender.meta(p[0]);
        if (m != null) {
            var total = m["b"] as Number;
            var sent = (m["n"] as Number) * NOTE_CHUNK;
            dc.drawText(w / 2, h * 38 / 100, Graphics.FONT_SMALL, ((sent > total) ? total : sent) + "/" + total + " B", mid);
        }
        var code = Application.Storage.getValue("noteCode");
        dc.drawText(w / 2, h * 52 / 100, Graphics.FONT_SMALL, "code " + uesStr(code), mid);
        var ld = Application.Storage.getValue("lastDrop");
        if (ld != null) {
            dc.setColor(Graphics.COLOR_ORANGE, Graphics.COLOR_TRANSPARENT);
            dc.drawText(w / 2, h * 66 / 100, Graphics.FONT_XTINY, "drop: " + ld, mid);
        }
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h * 80 / 100, Graphics.FONT_XTINY, "START: retry", mid);
        dc.drawText(w / 2, h * 88 / 100, Graphics.FONT_XTINY, "hold UP: delete", mid);
    }

    private function drawStatus(dc as Dc, w as Number, h as Number) as Void {
        var mid = Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER;
        var phone = System.getDeviceSettings().phoneConnected;
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h * 18 / 100, Graphics.FONT_MEDIUM, "UES " + BUILD, mid);
        dc.setColor(phone ? Graphics.COLOR_GREEN : Graphics.COLOR_RED, Graphics.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h * 30 / 100, Graphics.FONT_SMALL, phone ? "phone: connected" : "phone: NO", mid);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h * 42 / 100, Graphics.FONT_SMALL,
            "BB " + uesStr(Application.Storage.getValue("level")) + " at " + uesHm(Application.Storage.getValue("bbAt")), mid);
        dc.drawText(w / 2, h * 53 / 100, Graphics.FONT_SMALL, "BB HTTP " + uesStr(Application.Storage.getValue("status")), mid);
        dc.drawText(w / 2, h * 64 / 100, Graphics.FONT_SMALL, "note ok " + uesHm(Application.Storage.getValue("noteAt")), mid);
        var now = Time.now().value();
        var al = Application.Storage.getValue("al");
        var n = 0;
        if (al instanceof Array) {
            for (var i = 0; i < (al as Array).size(); i++) {
                if ((((al as Array)[i] as Array)[0] as Number) > now) {
                    n++;
                }
            }
        }
        var nx = uesNextAlarm(now);
        dc.drawText(w / 2, h * 75 / 100, Graphics.FONT_XTINY, "Будильники: " + n + " · next " + uesHm(nx), mid);
        dc.drawText(w / 2, h * 85 / 100, Graphics.FONT_XTINY,
            "fetch " + uesHm(Application.Storage.getValue("alAt")) + " · " + uesStr(Application.Storage.getValue("alCode")), mid);
    }

    private function drawRecord(dc as Dc, w as Number, h as Number) as Void {
        var edge = w / 2;
        var mid = Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER;
        var st = _s.state;
        if (st == ST_IDLE) {
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
            dc.drawText(w / 2, h / 2, Graphics.FONT_MEDIUM, "START — запис", mid);
        } else if (st == ST_REC || st == ST_STOPPING) {
            var on = (System.getTimer() / 500) % 2 == 0;
            dc.setColor(on ? Graphics.COLOR_RED : Graphics.COLOR_DK_RED, Graphics.COLOR_TRANSPARENT);
            var rw = on ? 16 : 8;
            arc(dc, edge - rw / 2 - 2, rw, 1.0); // pulsing ring
            dc.setColor(Graphics.COLOR_DK_RED, Graphics.COLOR_TRANSPARENT);
            arc(dc, edge - 28, 4, _s.elapsed().toFloat() / NOTE_MAX_S); // time left until auto-stop
            dc.setColor(Graphics.COLOR_RED, Graphics.COLOR_TRANSPARENT);
            dc.fillCircle(w * 36 / 100, h * 30 / 100, h / 16);
            dc.drawText(w * 46 / 100, h * 30 / 100, Graphics.FONT_MEDIUM, "REC", Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
            var e = _s.elapsed();
            dc.drawText(w / 2, h * 55 / 100, Graphics.FONT_NUMBER_HOT, (e / 60).format("%02d") + ":" + (e % 60).format("%02d"), mid);
            var sp = _s.sender.progress(); // small, so the red ring stays the main signal
            if (sp[1] > 0) {
                dc.setColor(0x00D0B0, Graphics.COLOR_TRANSPARENT);
                dc.drawText(w / 2, h * 72 / 100, Graphics.FONT_XTINY, "sent " + sp[0] + "/" + sp[1], mid);
            }
            dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
            dc.drawText(w / 2, h * 82 / 100, Graphics.FONT_XTINY, "START: stop", mid);
        } else if (st == ST_SENDING) {
            var p = _s.sender.progress();
            dc.setColor(0x00D0B0, Graphics.COLOR_TRANSPARENT);
            arc(dc, edge - 4, 6, (p[1] == 0) ? 0.0 : p[0].toFloat() / p[1]);
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
            dc.drawText(w / 2, h / 2, Graphics.FONT_MEDIUM, "Sending " + p[0] + "/" + p[1], mid);
        } else if (st == ST_DONE) {
            dc.setColor(Graphics.COLOR_GREEN, Graphics.COLOR_TRANSPARENT);
            dc.setPenWidth(h / 16); // a font glyph for a check mark is not guaranteed
            dc.drawLine(w * 30 / 100, h * 52 / 100, w * 44 / 100, h * 66 / 100);
            dc.drawLine(w * 44 / 100, h * 66 / 100, w * 72 / 100, h * 34 / 100);
        } else if (st == ST_FAIL) {
            dc.setColor(Graphics.COLOR_ORANGE, Graphics.COLOR_TRANSPARENT);
            dc.drawText(w / 2, h * 44 / 100, Graphics.FONT_MEDIUM, "Not sent", mid);
            dc.drawText(w / 2, h * 58 / 100, Graphics.FONT_SMALL, "will retry (" + _s.sender.lastCode + ")", mid);
        } else {
            dc.setColor(Graphics.COLOR_RED, Graphics.COLOR_TRANSPARENT);
            dc.drawText(w / 2, h / 2, Graphics.FONT_MEDIUM, _s.err, mid);
        }
        var n = _s.sender.ids().size() - (_s.recording() ? 1 : 0);
        if (n > 0) { // notes still queued on the watch
            dc.setColor(st == ST_FAIL ? Graphics.COLOR_ORANGE : Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
            dc.drawText(w / 2, h * 12 / 100, Graphics.FONT_XTINY, n + " on watch", mid);
        }
    }
}

class UesDelegate extends WatchUi.BehaviorDelegate {
    private var _s as NoteSession;

    function initialize(s as NoteSession) {
        BehaviorDelegate.initialize();
        _s = s;
    }

    // START: Record page toggles recording, Queue page retries
    function onSelect() as Boolean {
        if (!_s.recording() && uesDue().size() > 0) {
            uesAck();
        } else if (_s.page == PG_REC) {
            if (_s.state == ST_REC) {
                _s.stop();
            } else if (!_s.recording()) {
                _s.start();
            }
        } else if (_s.page == PG_QUEUE) {
            _s.retry();
        }
        WatchUi.requestUpdate();
        return true;
    }

    // BACK while recording stops and queues (never drops audio); otherwise leaves the app
    function onBack() as Boolean {
        if (!_s.recording() && uesDue().size() > 0) {
            uesAck();
            WatchUi.requestUpdate();
            return true;
        }
        if (_s.recording()) {
            _s.stop();
            return true;
        }
        return false;
    }

    function onNextPage() as Boolean {
        _s.page = (_s.page + 1) % PG_COUNT;
        WatchUi.requestUpdate();
        return true;
    }

    function onPreviousPage() as Boolean {
        _s.page = (_s.page + PG_COUNT - 1) % PG_COUNT;
        WatchUi.requestUpdate();
        return true;
    }

    // long-press UP on the Queue page
    function onMenu() as Boolean {
        if (_s.page == PG_QUEUE && !_s.recording() && _s.sender.ids().size() > 0) {
            var m = new WatchUi.Menu2({:title => "Queue"});
            m.addItem(new WatchUi.MenuItem("Видалити першу", null, :first, {}));
            m.addItem(new WatchUi.MenuItem("Видалити все", null, :all, {}));
            WatchUi.pushView(m, new UesMenuDelegate(_s), WatchUi.SLIDE_UP);
        }
        return true;
    }
}

class UesMenuDelegate extends WatchUi.Menu2InputDelegate {
    private var _s as NoteSession;

    function initialize(s as NoteSession) {
        Menu2InputDelegate.initialize();
        _s = s;
    }

    function onSelect(item as WatchUi.MenuItem) as Void {
        var first = item.getId() == :first;
        WatchUi.switchToView(new WatchUi.Confirmation(first ? "Видалити першу?" : "Видалити все?"),
            new UesConfirmDelegate(_s, first), WatchUi.SLIDE_IMMEDIATE);
    }
}

class UesConfirmDelegate extends WatchUi.ConfirmationDelegate {
    private var _s as NoteSession;
    private var _first as Boolean;

    function initialize(s as NoteSession, first as Boolean) {
        ConfirmationDelegate.initialize();
        _s = s;
        _first = first;
    }

    function onResponse(response as WatchUi.Confirm) as Boolean {
        if (response == WatchUi.CONFIRM_YES) {
            if (_first) {
                _s.deleteFirst();
            } else {
                _s.deleteAll();
            }
        }
        return true;
    }
}
