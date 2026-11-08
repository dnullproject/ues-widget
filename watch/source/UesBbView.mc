import Toybox.Application;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Time;
import Toybox.Time.Gregorian;
import Toybox.WatchUi;

// [last sent level, its HH:mm, last HTTP status] as shown strings
(:glance)
function uesBbLines() as Array<String> {
    var ts = Application.Storage.getValue("sentTs") as Number?;
    var level = Application.Storage.getValue("level");
    var status = Application.Storage.getValue("status");
    var time = "--:--";
    if (ts != null) {
        var t = Gregorian.info(new Time.Moment(ts), Time.FORMAT_SHORT);
        time = t.hour.format("%02d") + ":" + t.min.format("%02d");
    }
    return [
        "BB " + ((level == null) ? "--" : level.toString()),
        time,
        "HTTP " + ((status == null) ? "--" : status.toString())
    ];
}

(:glance)
class UesBbGlance extends WatchUi.GlanceView {
    function initialize() {
        GlanceView.initialize();
    }

    function onUpdate(dc as Dc) as Void {
        var l = uesBbLines();
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(0, dc.getHeight() / 2, Graphics.FONT_GLANCE, l[0] + "  " + l[1] + "  " + l[2],
            Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
    }
}

class UesBbView extends WatchUi.View {
    function initialize() {
        View.initialize();
    }

    function onUpdate(dc as Dc) as Void {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        var l = uesBbLines();
        var h = dc.getHeight();
        for (var i = 0; i < 3; i++) {
            dc.drawText(dc.getWidth() / 2, h * (i + 2) / 6, Graphics.FONT_MEDIUM, l[i],
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }
        dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(dc.getWidth() / 2, h * 90 / 100, Graphics.FONT_XTINY, BUILD,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }
}
