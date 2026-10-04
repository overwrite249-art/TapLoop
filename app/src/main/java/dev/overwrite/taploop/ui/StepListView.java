package dev.overwrite.taploop.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ListView;

/**
 * ListView with drag-to-reorder (grab the view with the handle id) and
 * swipe-left-to-delete. The list data is owned by the listener.
 */
public class StepListView extends ListView {
    public interface Listener {
        void onDragStart(int pos);

        /** move one item, the list will redraw after */
        void onDragMove(int from, int to);

        void onDragEnd(int startPos, int endPos);

        boolean canSwipe(int pos);

        void onSwipe(int pos);
    }

    private Listener listener;
    private int handleId;

    private final int slop, minFling;
    private final Paint floatPaint = new Paint();
    private final Rect tmp = new Rect();

    // drag state
    private boolean dragging;
    private int dragPos = INVALID_POSITION, dragStart;
    private Bitmap dragBmp;
    private int dragLeft, dragTop, grabOffset, lastY;

    // swipe state
    private int downX, downY, swipePos = INVALID_POSITION;
    private View swipeView;
    private boolean swiping;
    private VelocityTracker vt;

    private final Runnable autoScroll = new Runnable() {
        @Override
        public void run() {
            if (!dragging) return;
            int edge = getHeight() / 6;
            int step = 0;
            if (lastY < edge) step = -Math.max(4, (edge - lastY) / 3);
            else if (lastY > getHeight() - edge) step = Math.max(4, (lastY - getHeight() + edge) / 3);
            if (step != 0 && canScrollList(step)) {
                scrollListBy(step);
                updateTarget();
                invalidate();
            }
            postOnAnimation(this);
        }
    };

    public StepListView(Context c, AttributeSet attrs) {
        super(c, attrs);
        ViewConfiguration vc = ViewConfiguration.get(c);
        slop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity() * 4;
        floatPaint.setAlpha(225);
    }

    public void setListener(Listener l, int handleId) {
        listener = l;
        this.handleId = handleId;
    }

    /** position currently being dragged, the adapter should hide that row */
    public int getDragPosition() {
        return dragging ? dragPos : INVALID_POSITION;
    }

    private View rowAt(int pos) {
        int i = pos - getFirstVisiblePosition();
        return i >= 0 && i < getChildCount() ? getChildAt(i) : null;
    }

    private boolean onHandle(View row, int x, int y) {
        if (handleId == 0) return false;
        View h = row.findViewById(handleId);
        if (h == null || h.getVisibility() != VISIBLE) return false;
        h.getDrawingRect(tmp);
        offsetDescendantRectToMyCoords(h, tmp);
        // be generous, the handle is small
        tmp.inset(-slop, -slop / 2);
        return tmp.contains(x, y);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (listener == null) return super.onTouchEvent(e);
        int x = (int) e.getX(), y = (int) e.getY();

        if (dragging) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    lastY = y;
                    updateTarget();
                    invalidate();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    stopDrag();
                    break;
            }
            return true;
        }
        if (swiping) {
            handleSwipe(e, x);
            return true;
        }

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = x;
                downY = y;
                swipePos = pointToPosition(x, y);
                swipeView = swipePos == INVALID_POSITION ? null : rowAt(swipePos);
                if (swipeView != null && onHandle(swipeView, x, y)) {
                    startDrag(swipePos, swipeView, y);
                    swipePos = INVALID_POSITION;
                    return true;
                }
                if (vt != null) vt.clear();
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (swipePos == INVALID_POSITION || swipeView == null) break;
                int dx = x - downX, dy = Math.abs(y - downY);
                if (dy > slop) {
                    swipePos = INVALID_POSITION;
                } else if (-dx > slop && -dx > dy * 2 && listener.canSwipe(swipePos)) {
                    swiping = true;
                    downX = x;
                    MotionEvent cancel = MotionEvent.obtain(e);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.onTouchEvent(cancel);
                    cancel.recycle();
                    getParent().requestDisallowInterceptTouchEvent(true);
                    if (vt == null) vt = VelocityTracker.obtain();
                    vt.clear();
                    vt.addMovement(e);
                    return true;
                }
                break;
            }
        }
        return super.onTouchEvent(e);
    }

    private void handleSwipe(MotionEvent e, int x) {
        View v = swipeView;
        int w = Math.max(1, getWidth());
        float dx = Math.min(0, x - downX);
        vt.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                v.setTranslationX(dx);
                v.setAlpha(1f - 0.7f * Math.min(1f, -dx / w));
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                swiping = false;
                vt.computeCurrentVelocity(1000);
                boolean gone = e.getActionMasked() == MotionEvent.ACTION_UP
                        && (-dx > w * 0.4f || (vt.getXVelocity() < -minFling && -dx > slop * 2));
                int pos = swipePos;
                swipePos = INVALID_POSITION;
                if (gone) {
                    v.animate().translationX(-w).alpha(0f).setDuration(150).withEndAction(() -> {
                        v.setTranslationX(0);
                        v.setAlpha(1f);
                        listener.onSwipe(pos);
                    }).start();
                } else {
                    v.animate().translationX(0).alpha(1f).setDuration(150).start();
                }
                break;
            }
        }
    }

    private void startDrag(int pos, View row, int y) {
        // stop any fling first
        smoothScrollBy(0, 0);
        dragging = true;
        dragPos = dragStart = pos;
        dragLeft = row.getLeft();
        dragTop = row.getTop();
        grabOffset = y - row.getTop();
        lastY = y;
        dragBmp = Bitmap.createBitmap(Math.max(1, row.getWidth()), Math.max(1, row.getHeight()),
                Bitmap.Config.ARGB_8888);
        row.draw(new Canvas(dragBmp));
        getParent().requestDisallowInterceptTouchEvent(true);
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        listener.onDragStart(pos);
        postOnAnimation(autoScroll);
        invalidate();
    }

    private void updateTarget() {
        int h = dragBmp == null ? 0 : dragBmp.getHeight();
        dragTop = Math.max(-h / 2, Math.min(getHeight() - h / 2, lastY - grabOffset));
        int y = Math.max(0, Math.min(getHeight() - 1, dragTop + h / 2));
        int target = pointToPosition(getWidth() / 2, y);
        if (target == INVALID_POSITION && getChildCount() > 0) {
            if (y < getChildAt(0).getTop()) target = getFirstVisiblePosition();
            else if (y > getChildAt(getChildCount() - 1).getBottom()) target = getLastVisiblePosition();
        }
        if (target != INVALID_POSITION && target != dragPos && target < getCount()) {
            listener.onDragMove(dragPos, target);
            dragPos = target;
        }
    }

    private void stopDrag() {
        dragging = false;
        removeCallbacks(autoScroll);
        if (dragBmp != null) {
            dragBmp.recycle();
            dragBmp = null;
        }
        int start = dragStart, end = dragPos;
        dragPos = INVALID_POSITION;
        listener.onDragEnd(start, end);
        invalidate();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (dragging && dragBmp != null) {
            canvas.drawBitmap(dragBmp, dragLeft, dragTop, floatPaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(autoScroll);
        if (vt != null) {
            vt.recycle();
            vt = null;
        }
        super.onDetachedFromWindow();
    }
}
