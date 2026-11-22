package app.spektralab.ui;

import android.view.MotionEvent;
import android.widget.SeekBar;

public final class ScrubBar extends android.widget.SeekBar {
        private SeekBar.OnSeekBarChangeListener listener;
        private final int thumbSlop;
        private float downX;
        private boolean tracking;
        private boolean dragging;
        private long lastUpMs;
        private int resetProgress = -1;
        private Runnable onReset;

        public ScrubBar(android.content.Context c) {
            super(c);
            thumbSlop = (int) (26 * c.getResources().getDisplayMetrics().density);
        }

        @Override public void setOnSeekBarChangeListener(SeekBar.OnSeekBarChangeListener l) {
            listener = l;
            super.setOnSeekBarChangeListener(l);
        }

        /** Double-tap on the thumb returns the value here. */
        public void setResetTarget(int progress, Runnable action) {
            resetProgress = progress;
            onReset = action;
        }

        private float thumbX() {
            final int span = Math.max(1, getWidth() - getPaddingLeft() - getPaddingRight());
            return getPaddingLeft() + span * (getProgress() / (float) Math.max(1, getMax()));
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent ev) {
            if (!isEnabled()) return false;
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN: {
                    // **Only the thumb moves.** A tap on the track does nothing,
                    // and a gesture that starts away from the thumb belongs to
                    // the list underneath -- which is how Lightroom and
                    // LightCraft both behave. Dragging from anywhere (what this
                    // used to do) fights every scroll.
                    downX = ev.getX();
                    if (Math.abs(downX - thumbX()) > thumbSlop) return false;
                    final long now = android.os.SystemClock.uptimeMillis();
                    if (resetProgress >= 0 && now - lastUpMs < 300) {
                        setProgress(resetProgress);
                        if (onReset != null) onReset.run();
                        lastUpMs = 0;
                        return true;
                    }
                    tracking = true;
                    dragging = false;
                    setPressed(true);
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                case android.view.MotionEvent.ACTION_MOVE: {
                    if (!tracking) return false;
                    if (!dragging) {
                        dragging = true;
                        if (listener != null) listener.onStartTrackingTouch(this);
                    }
                    return seekTo(ev.getX());
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    if (!tracking) return false;
                    tracking = false;
                    setPressed(false);
                    lastUpMs = android.os.SystemClock.uptimeMillis();
                    if (dragging) {
                        dragging = false;
                        if (listener != null) listener.onStopTrackingTouch(this);
                    }
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                }
                default:
                    return true;
            }
        }

        /** **`onProgressChanged`'s `fromUser` is always false here.** The bar
         *  drives itself through `setProgress`, which `AbsSeekBar` forwards with
         *  `fromUser = false`; the flag only means "the framework's own track
         *  touch ran", and this bar overrides that. Dragging the thumb is the
         *  real question a preview needs answered, so it is asked directly. */
        public boolean isDragging() { return dragging; }

        private boolean seekTo(float x) {
            final int usable = getWidth() - getPaddingLeft() - getPaddingRight();
            if (usable <= 0) return false;
            float t = (x - getPaddingLeft()) / usable;
            t = Math.max(0f, Math.min(1f, t));
            setProgress(Math.round(t * getMax()));
            return true;
        }
    }
