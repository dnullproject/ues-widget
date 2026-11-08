import Toybox.Background;
import Toybox.Lang;
import Toybox.System;

// Every 5 min: resend what is still queued (a note whose upload failed in the foreground).
(:background)
class UesNoteBg extends System.ServiceDelegate {
    function initialize() {
        ServiceDelegate.initialize();
    }

    function onTemporalEvent() as Void {
        new NoteSender(method(:onIdle)).pump(); // the pending web request keeps the sender alive
    }

    function onIdle() as Void {
        Background.exit(null);
    }
}
