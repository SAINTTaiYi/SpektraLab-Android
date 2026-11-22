package app.spektralab;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

/**
 * 「corner」—— 设置页的进与出。
 *
 * <p>三块从**左上角**长满屏幕：先是克莱因蓝，晚一拍是深色，再晚一拍是设置页
 * 本身（它住在一个从同一个角长出来的窗口里，页面在里面从左上稍稍滑到位）。
 * 退出时同一段片子倒放：页面先缩回角里，然后是深色，最后是蓝色。
 *
 * <p>逐项对着 Interlude 的 {@code src/transitions/corner.ts}：每块都是
 * {@code clip-path: inset(0 100% 100% 0)} 长到 {@code inset(0 0 0 0)}，时长
 * 0.9 s、缓动 {@code expo.inOut}、三块错开 0.12 s、窗口里的页面偏 10%。在
 * Android 上那条 inset 就是「一个从 (0,0) 开始的矩形」：
 * {@code (0, 0, w·p, h·p)}，所以只需要一个 clipRect 和一个 drawRect。
 *
 * <p>**窗口里放的是页面的真身，不是截图。** 设置页换页时有一层 180 ms 的
 * 进场（`fillSettings`），截图会把它定在半路；真身搬进来则一路都是活的。
 * 搬进来的代价只有一个：转场期间必须挡住点击（页面正在飞，点中哪一行都是
 * 意外）。
 */
final class CornerSwitch extends FrameLayout {

    /** 每块长满屏幕的时间。参考实现是 900 ms —— 按下按钮时那太长了。 */
    private static final long GROW = 620L;
    /** 三块依次晚一拍。 */
    private static final long STAGGER = 90L;
    /** 窗口里的页面从左上多出来多少（占屏宽 / 屏高）。0 就是不动。 */
    private static final float SHIFT = 0.10f;

    private final Paint paint = new Paint();
    private final int blue, dark, pageBg;

    /** 三块的生长量 0..1：蓝、深色、窗口。 */
    private float pb, pd, pw;

    private ValueAnimator anim;

    CornerSwitch(Context context, int blue, int dark, int pageBg) {
        super(context);
        this.blue = blue;
        this.dark = dark;
        this.pageBg = pageBg;
        setClickable(true);
    }

    /**
     * 播一遍。`open` = 设置页进入（0 → 1），否则退出（1 → 0）。
     * `done` 在**自然结束**时回调；被 {@link #stop()} 取消的不回调 —— 取消
     * 意味着有人接手了，那时再收尾会把页面从窗口里搬进一个已经不用的 stage。
     */
    void play(final boolean open, final Runnable done) {
        stop();
        pb = pd = pw = open ? 0f : 1f;
        applyShift();
        final float total = GROW + 2f * STAGGER;
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration((long) total);
        // 每块自己带 expo.inOut，所以总进度必须是线性的。
        a.setInterpolator(new LinearInterpolator());
        a.addUpdateListener(an -> {
            final float ms = (float) an.getAnimatedValue() * total;
            final float s0 = ease(seg(ms, 0f));
            final float s1 = ease(seg(ms, STAGGER));
            final float s2 = ease(seg(ms, STAGGER * 2f));
            if (open) {
                pb = s0; pd = s1; pw = s2;              // 蓝 → 橙 → 页面
            } else {
                // **倒放不是把三个数一起反过来，而是把它们换个位置。**
                // 网页的 `close()` 里第一个动的是旧页面本身（delay 0），黑色是
                // STAGGER，红色是 2×STAGGER。照 delay 顺序取反（蓝最先缩）时，
                // 蓝在页面底下、又永远比页面小 ⇒ **橙和蓝一个像素都露不出来**，
                // 只剩页面自己往左上角缩 —— 看起来就是「返回没有动画」。
                pw = 1f - s0; pd = 1f - s1; pb = 1f - s2;
            }
            applyShift();
            invalidate();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator which) {
                if (anim != which) return;
                anim = null;
                pb = pd = pw = open ? 1f : 0f;
                applyShift();
                invalidate();
                if (done != null) done.run();
            }
        });
        anim = a;
        a.start();
    }

    /** 立刻停下（不回调 `done`）。 */
    void stop() {
        final ValueAnimator a = anim;
        anim = null;
        if (a != null) a.cancel();
    }

    // ---------------------------------------------------------------- 画

    @Override
    protected void dispatchDraw(Canvas canvas) {
        final int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) { super.dispatchDraw(canvas); return; }
        // 三块叠着的顺序就是网页里的 DOM 顺序：蓝在最下、深色压着它、页面最上。
        if (pb > 0f) {
            paint.setColor(blue);
            canvas.drawRect(0f, 0f, w * pb, h * pb, paint);
        }
        if (pd > 0f) {
            paint.setColor(dark);
            canvas.drawRect(0f, 0f, w * pd, h * pd, paint);
        }
        if (pw <= 0f) return;                 // 窗口还没有面积：页面一个像素都不露
        final int save = canvas.save();
        canvas.clipRect(0f, 0f, w * pw, h * pw);
        // 窗口自己的底色。页面只盖住内容区，上下两条系统栏的空格里要露出纸，
        // 不然最后一刻会是一条深色的缝 —— 网页那边这一层就是 `pageColor()`。
        paint.setColor(pageBg);
        canvas.drawRect(0f, 0f, w, h, paint);
        super.dispatchDraw(canvas);
        canvas.restoreToCount(save);
    }

    /** 页面在窗口里的位移。窗口越小，页面越偏左上 —— 两者不同步才看得出「窗口在开」。 */
    private void applyShift() {
        final float dx = -getWidth() * SHIFT * (1f - pw);
        final float dy = -getHeight() * SHIFT * (1f - pw);
        for (int i = 0; i < getChildCount(); ++i) {
            final View c = getChildAt(i);
            if (c.getTranslationX() != dx) c.setTranslationX(dx);
            if (c.getTranslationY() != dy) c.setTranslationY(dy);
        }
    }

    // ---------------------------------------------------------------- 手感

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return true;                          // 转场期间页面正在飞：所有点击都拦在这里
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        return true;                          // 吃掉，不再传给下面的编辑器
    }

    // ---------------------------------------------------------------- 数

    /** 这一段（从 `delay` 起）跑到了哪里，0..1。 */
    private static float seg(float ms, float delay) {
        final float t = (ms - delay) / GROW;
        return t <= 0f ? 0f : (t >= 1f ? 1f : t);
    }

    /**
     * **起步就走、尾巴收得住。**
     *
     * <p>参考实现用的是 {@code expo.inOut}，它在前 150 ms 里几乎没有位移
     * （0.9 s 的 10 % 处只有 0.2 %）。在网页上没人觉得慢 —— 那会儿页面本来
     * 就在加载；但按下按钮时，那就是「点了没反应」。而且它在结尾同样慢，三块
     * 的收尾会糊在一起。
     *
     * <p>换成 {@code 1-(1-t)^2.2}：第一帧（16 ms）就有 5.6 % 的位移，后面是一条
     * 平滑的减速尾巴，三块错开的那一拍仍然分得清。
     */
    private static float ease(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        return 1f - (float) Math.pow(1d - t, 2.2d);
    }
}
