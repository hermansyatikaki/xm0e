package miku.moe.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.OverScroller;

/**
 * Panel gulir dengan satu jari untuk teks panjang seperti log crash.
 * Teks dibungkus mengikuti lebar panel (word wrap); bila anaknya sengaja dibuat lebih lebar,
 * panel tetap bisa digeser ke kiri-kanan.
 */
public class BidirectionalScrollView extends FrameLayout {
    private final OverScroller scroller;
    private final int touchSlop;
    private final int minFling;
    private final int maxFling;
    private VelocityTracker velocity;
    private float lastX, lastY;
    private boolean dragging;

    public BidirectionalScrollView(Context context) { this(context, null); }

    public BidirectionalScrollView(Context context, AttributeSet attrs) { this(context, attrs, 0); }

    public BidirectionalScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        scroller = new OverScroller(context);
        ViewConfiguration vc = ViewConfiguration.get(context);
        touchSlop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity();
        maxFling = vc.getScaledMaximumFlingVelocity();
        setWillNotDraw(false);
        setClipToPadding(true);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        View child = getChildAt(0);
        if (child == null) return;
        // Lebar anak dikunci sebesar lebar panel supaya teks dibungkus (word wrap) dan tidak perlu
        // digeser ke samping; tinggi tetap bebas sehingga isi panjang bisa digulir ke bawah.
        int viewport = Math.max(0, getMeasuredWidth() - getPaddingLeft() - getPaddingRight());
        child.measure(MeasureSpec.makeMeasureSpec(viewport, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        View child = getChildAt(0);
        if (child == null) return;
        int left = getPaddingLeft();
        int top = getPaddingTop();
        child.layout(left, top, left + child.getMeasuredWidth(), top + child.getMeasuredHeight());
        clampScroll();
    }

    private int maxScrollX() {
        View child = getChildAt(0);
        if (child == null) return 0;
        int viewport = getWidth() - getPaddingLeft() - getPaddingRight();
        return Math.max(0, child.getWidth() - viewport);
    }

    private int maxScrollY() {
        View child = getChildAt(0);
        if (child == null) return 0;
        int viewport = getHeight() - getPaddingTop() - getPaddingBottom();
        return Math.max(0, child.getHeight() - viewport);
    }

    private void clampScroll() {
        int x = Math.max(0, Math.min(getScrollX(), maxScrollX()));
        int y = Math.max(0, Math.min(getScrollY(), maxScrollY()));
        if (x != getScrollX() || y != getScrollY()) scrollTo(x, y);
    }

    @Override
    public void scrollTo(int x, int y) {
        super.scrollTo(Math.max(0, Math.min(x, maxScrollX())), Math.max(0, Math.min(y, maxScrollY())));
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = ev.getX();
                lastY = ev.getY();
                dragging = !scroller.isFinished();
                if (dragging) scroller.abortAnimation();
                disallowParent(true);
                break;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(ev.getX() - lastX) > touchSlop || Math.abs(ev.getY() - lastY) > touchSlop) {
                    dragging = true;
                    lastX = ev.getX();
                    lastY = ev.getY();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                disallowParent(false);
                break;
        }
        return dragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (velocity == null) velocity = VelocityTracker.obtain();
        velocity.addMovement(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!scroller.isFinished()) scroller.abortAnimation();
                lastX = ev.getX();
                lastY = ev.getY();
                disallowParent(true);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = lastX - ev.getX();
                float dy = lastY - ev.getY();
                lastX = ev.getX();
                lastY = ev.getY();
                scrollTo(getScrollX() + Math.round(dx), getScrollY() + Math.round(dy));
                return true;
            }
            case MotionEvent.ACTION_UP: {
                velocity.computeCurrentVelocity(1000, maxFling);
                int vx = (int) velocity.getXVelocity();
                int vy = (int) velocity.getYVelocity();
                if (Math.abs(vx) > minFling || Math.abs(vy) > minFling) {
                    scroller.fling(getScrollX(), getScrollY(), -vx, -vy, 0, maxScrollX(), 0, maxScrollY());
                    postInvalidateOnAnimation();
                }
                release();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                release();
                return true;
        }
        return true;
    }

    private void release() {
        dragging = false;
        disallowParent(false);
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
    }

    private void disallowParent(boolean disallow) {
        ViewGroup parent = getParent() instanceof ViewGroup ? (ViewGroup) getParent() : null;
        if (parent != null) parent.requestDisallowInterceptTouchEvent(disallow);
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), scroller.getCurrY());
            postInvalidateOnAnimation();
        }
    }

    /** Kembali ke pojok kiri-atas, dipanggil setelah isi log diganti. */
    public void resetScroll() {
        scroller.forceFinished(true);
        scrollTo(0, 0);
    }
}
