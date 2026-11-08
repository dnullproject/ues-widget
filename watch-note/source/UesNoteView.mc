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
import Toybox.Timer;
import Toybox.WatchUi;

enum NoteState { ST_REC, ST_STOPPING, ST_SENDING, ST_DONE, ST_FAIL, ST_ERR }

const NOTE_MAX_S = 180; // auto-stop
const NOTE_CHUNK = 6000; // raw bytes per upload chunk
const NOTE_RETRY_S = 300; // background retry period (Garmin minimum)

// Records, queues chunks in Storage as they arrive (so RAM stays at ~2 chunks, far below the 768 KB limit,
// whatever the codec bitrate) and uploads them while recording. A note is only ever held back for the last chunk.
class NoteSession {
    var state as NoteState = ST_REC;
    var err as String = "";
    var sender as NoteSender;
    private var _id as String = "";
    private var _buf as ByteArray = []b;
    private var _startMs as Number = 0;
    private var _dur as Number = 0;
    private var _deadlineMs as Number = 0; // ST_STOPPING: finish anyway; ST_DONE/FAIL/ERR: exit
    private var _timer as Timer.Timer = new Timer.Timer();

    function initialize() {
        sender = new NoteSender(method(:onIdle));
        sender.dropUnfinished();
        _timer.start(method(:onTick), 500, true);
        Math.srand(System.getTimer() + Time.now().value());
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

    private function begin(f as AudioFormat, name as String) as Void {
        try {
            Capture.initializeAudioCapture(self as Capture.Callbacks, {
                :format => f, :windowSize => 4096, :windowFilledBehavior => Capture.WINDOW_FILLED_CONTINUE, :filters => []
            });
            _id = Time.now().value().toString() + "-" + (Math.rand() % 100000).format("%05d");
            sender.create(_id, name, Time.now().value());
            Capture.start();
        } catch (e) {
            fail("Mic unavailable");
            return;
        }
        _startMs = System.getTimer();
        buzz();
        sender.pump(); // retry notes left over from an earlier failure while this one records
        ensureRetry();
    }

    function elapsed() as Number {
        if (state == ST_REC) {
            _dur = (System.getTimer() - _startMs) / 1000;
        }
        return _dur;
    }

    // START / BACK
    function stop() as Void {
        if (state == ST_REC) {
            state = ST_STOPPING;
            _deadlineMs = System.getTimer() + 2000;
            buzz();
            Capture.stop(); // flushes the rest through onData, then onComplete
        } else if (state != ST_STOPPING) {
            System.exit(); // leaving early: the queue goes on in the background
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
            sender.drop(_id);
            fail("Nothing recorded");
            return;
        }
        sender.finish(_id, _dur);
        state = ST_SENDING;
        ensureRetry();
        sender.pump();
    }

    private function fail(msg as String) as Void {
        if (_id.length() > 0) {
            sender.drop(_id); // an unfinished note would block the queue
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
            Background.deleteTemporalEvent();
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
            System.exit();
        }
        WatchUi.requestUpdate();
    }

    // Registered only when missing: re-registering would push the next run back.
    private function ensureRetry() as Void {
        if (Background.getTemporalEventRegisteredTime() == null) {
            Background.registerForTemporalEvent(new Time.Duration(NOTE_RETRY_S));
        }
    }

    private function buzz() as Void {
        if (Attention has :vibrate) {
            Attention.vibrate([new Attention.VibeProfile(100, 200)] as Array<Attention.VibeProfile>);
        }
    }
}

class UesNoteView extends WatchUi.View {
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

    // Pure black background: AMOLED pixels stay off except the few drawn ones.
    function onUpdate(dc as Dc) as Void {
        if (dc has :setAntiAlias) {
            dc.setAntiAlias(true);
        }
        var w = dc.getWidth();
        var h = dc.getHeight();
        var edge = w / 2;
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        var st = _s.state;
        var mid = Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER;
        if (st == ST_REC || st == ST_STOPPING) {
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
            dc.drawText(w / 2, h * 58 / 100, Graphics.FONT_SMALL, "will retry", mid);
        } else {
            dc.setColor(Graphics.COLOR_RED, Graphics.COLOR_TRANSPARENT);
            dc.drawText(w / 2, h / 2, Graphics.FONT_MEDIUM, _s.err, mid);
        }
        var n = _s.sender.ids().size() - ((st == ST_REC || st == ST_STOPPING) ? 1 : 0);
        if (n > 0) { // notes still queued on the watch
            dc.setColor(st == ST_FAIL ? Graphics.COLOR_ORANGE : Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
            dc.drawText(w / 2, h * 12 / 100, Graphics.FONT_XTINY, n + " on watch", mid);
        }
    }
}

class UesNoteDelegate extends WatchUi.BehaviorDelegate {
    private var _s as NoteSession;

    function initialize(s as NoteSession) {
        BehaviorDelegate.initialize();
        _s = s;
    }

    function onSelect() as Boolean {
        _s.stop();
        return true;
    }

    // BACK stops and sends instead of leaving; once the note is sending/failed it leaves the app.
    function onBack() as Boolean {
        _s.stop();
        return true;
    }
}
