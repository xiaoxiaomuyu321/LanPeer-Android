package cn.lanpeer;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Point;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayDeque;

/** Does not inspect the UI tree. Only an explicitly approved host session can invoke this service. */
public final class ControlService extends AccessibilityService {
    private static ControlService instance;
    private boolean gestureBusy;
    private final ArrayDeque<StrokePart> strokes = new ArrayDeque<>();
    private GestureDescription.StrokeDescription continuation;
    private boolean strokeBusy;
    private Session strokeSession;

    private static final class StrokePart {
        final String phase;
        final Path path;
        final long duration;
        StrokePart(String phase, Path path, long duration) {
            this.phase = phase; this.path = path; this.duration = duration;
        }
    }

    public static boolean available() { return instance != null; }
    @Override protected void onServiceConnected() { instance = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
    @Override public void onDestroy() { if (instance == this) instance = null; super.onDestroy(); }

    public static boolean execute(PeerApp app, Session source, JSONObject command) {
        ControlService service = instance;
        if (service == null || app.session != source || !source.host || !source.approved || source.closed) return false;
        return service.perform(source, command);
    }

    @SuppressWarnings("deprecation")
    private boolean perform(Session source, JSONObject command) {
        String type = command.optString("type");
        switch (type) {
            case "back": return performGlobalAction(GLOBAL_ACTION_BACK);
            case "home": return performGlobalAction(GLOBAL_ACTION_HOME);
            case "recents": return performGlobalAction(GLOBAL_ACTION_RECENTS);
            case "stroke": return enqueueStroke(source, command);
            case "gesture":
                if (gestureBusy || strokeBusy || continuation != null || !strokes.isEmpty()) return false;
                try {
                    JSONArray points = command.getJSONArray("points");
                    int count = points.length();
                    long duration = command.getLong("duration");
                    if (count < 1 || count > 80 || duration < 1 || duration > 8000) return false;
                    Point size = new Point();
                    ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealSize(size);
                    // Reject gestures made against a previous screen rotation.
                    if (command.optBoolean("portrait") != (size.y >= size.x)) return false;
                    Path path = new Path();
                    for (int i = 0; i < count; i++) {
                        JSONArray p = points.getJSONArray(i);
                        double nx = p.getDouble(0), ny = p.getDouble(1);
                        if (!Double.isFinite(nx) || !Double.isFinite(ny) || nx < 0 || nx > 1 || ny < 0 || ny > 1) return false;
                        float x = (float) (nx * (size.x - 1)), y = (float) (ny * (size.y - 1));
                        if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                    }
                    gestureBusy = true;
                    boolean accepted = dispatchGesture(new GestureDescription.Builder().addStroke(
                            new GestureDescription.StrokeDescription(path, 0, duration)).build(),
                            new GestureResultCallback() {
                                @Override public void onCompleted(GestureDescription gesture) { gestureBusy = false; }
                                @Override public void onCancelled(GestureDescription gesture) { gestureBusy = false; }
                            }, null);
                    if (!accepted) gestureBusy = false;
                    return accepted;
                } catch (Exception e) { gestureBusy = false; return false; }
            default: return false;
        }
    }

    @SuppressWarnings("deprecation")
    private boolean enqueueStroke(Session source, JSONObject command) {
        if (gestureBusy || strokes.size() >= 48) return false;
        try {
            String phase = command.getString("phase");
            if (!"start".equals(phase) && !"move".equals(phase) && !"end".equals(phase)) return false;
            Point size = new Point();
            ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealSize(size);
            if (command.optBoolean("portrait") != (size.y >= size.x)) return false;
            JSONArray from = command.getJSONArray("from"), to = command.getJSONArray("to");
            if (from.length() != 2 || to.length() != 2) return false;
            double fx = from.getDouble(0), fy = from.getDouble(1), tx = to.getDouble(0), ty = to.getDouble(1);
            if (!valid(fx) || !valid(fy) || !valid(tx) || !valid(ty)) return false;
            long duration = command.getLong("duration");
            if (duration < 1 || duration > 250) return false;
            if ("start".equals(phase) && (strokeBusy || continuation != null || !strokes.isEmpty())) return false;
            if (source != strokeSession || "start".equals(phase)) {
                if (!"start".equals(phase)) return false;
                strokes.clear(); continuation = null; strokeBusy = false; strokeSession = source;
            } else if (continuation == null && strokes.isEmpty() && !strokeBusy) return false;
            Path path = new Path();
            path.moveTo((float)(fx * (size.x - 1)), (float)(fy * (size.y - 1)));
            path.lineTo((float)(tx * (size.x - 1)), (float)(ty * (size.y - 1)));
            strokes.add(new StrokePart(phase, path, duration));
            pumpStroke();
            return true;
        } catch (Exception e) { return false; }
    }

    private static boolean valid(double value) { return Double.isFinite(value) && value >= 0 && value <= 1; }

    private void pumpStroke() {
        if (strokeBusy || strokes.isEmpty()) return;
        StrokePart part = strokes.removeFirst();
        boolean last = "end".equals(part.phase);
        try {
            GestureDescription.StrokeDescription next = "start".equals(part.phase)
                    ? new GestureDescription.StrokeDescription(part.path, 0, part.duration, true)
                    : continuation.continueStroke(part.path, 0, part.duration, !last);
            continuation = next;
            strokeBusy = true;
            boolean accepted = dispatchGesture(new GestureDescription.Builder().addStroke(next).build(), new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription gesture) {
                    strokeBusy = false;
                    if (last) { continuation = null; strokeSession = null; strokes.clear(); }
                    else pumpStroke();
                }
                @Override public void onCancelled(GestureDescription gesture) {
                    strokeBusy = false; continuation = null; strokeSession = null; strokes.clear();
                }
            }, null);
            if (!accepted) { strokeBusy = false; continuation = null; strokeSession = null; strokes.clear(); }
        } catch (Exception e) { strokeBusy = false; continuation = null; strokeSession = null; strokes.clear(); }
    }
}
