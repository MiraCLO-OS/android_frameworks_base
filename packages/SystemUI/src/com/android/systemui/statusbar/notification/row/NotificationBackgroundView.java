/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row;

import static com.android.systemui.Flags.lockscreenBlurForNotifications;
import static com.android.systemui.Flags.notificationRowTransparency;
import static com.android.systemui.util.ColorUtilKt.hexColorString;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.VisibleForTesting;

import com.android.internal.graphics.drawable.BackgroundBlurDrawable;
import com.android.internal.util.ContrastColorUtil;
import com.android.systemui.Dumpable;
import com.android.systemui.common.shared.colors.SurfaceEffectColors;
import com.android.systemui.miraclo.MiraCLOLiquidGlass;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.shared.NotificationAddXOnHoverToDismiss;
import com.android.systemui.util.Assert;
import com.android.systemui.util.DrawableDumpKt;

import java.io.PrintWriter;
import java.util.Arrays;

/**
 * A view that can be used for both the dimmed and normal background of an notification.
 */
public class NotificationBackgroundView extends View implements Dumpable,
        ExpandableNotificationRow.DismissButtonTargetVisibilityListener {

    private final boolean mDontModifyCorners;
    private Drawable mBackground;
    private int mClipTopAmount;
    private int mTopOverlap;
    private int mBottomOverlap;
    private int mClipBottomAmount;
    private int mTintColor;
    @Nullable private Integer mRippleColor;
    private final float[] mCornerRadii = new float[8];
    private final float[] mFocusOverlayCornerRadii = new float[8];
    private float mFocusOverlayStroke = 0;
    private boolean mBottomIsRounded;
    private boolean mBottomAmountClips = true;
    private int mActualHeight = -1;
    private int mActualWidth = -1;
    private boolean mExpandAnimationRunning;
    private int mExpandAnimationWidth = -1;
    private int mExpandAnimationHeight = -1;
    private int mDrawableAlpha = 255;
    private final ColorStateList mLightColoredStatefulColors;
    private final ColorStateList mDarkColoredStatefulColors;
    private int mNormalColor;
    private final int convexR = 9;
    private final int concaveR = 22;
    private BackgroundBlurDrawable mBackgroundBlurDrawable;
    @VisibleForTesting
    protected View.OnAttachStateChangeListener mOnAttachStateChangeListener;

    // True only if the dismiss button is visible.
    private boolean mDrawDismissButtonCutout = false;
    private boolean mOnKeyguard = true;

    // MiraCLO Liquid Glass: the card fill turns see-through and gets the same gloss and
    // light rim as the Liquid Glass QS and island. Only in the open shade, never on the
    // lock screen or on a heads-up floating over an app.
    private static final float GLASS_FILL = 0.45f;
    private final Paint mGlassGloss = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlassRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mGlassPath = new Path();
    private final RectF mGlassRect = new RectF();
    private final float[] mGlassRadii = new float[8];
    private final float mGlassStroke;
    private int mGlassShaderW = -1;
    private int mGlassShaderH = -1;

    public NotificationBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDontModifyCorners = getResources().getBoolean(R.bool.config_clipNotificationsToOutline);
        mLightColoredStatefulColors = getResources().getColorStateList(
                R.color.notification_state_color_light);
        mDarkColoredStatefulColors = getResources().getColorStateList(
                R.color.notification_state_color_dark);
        if (notificationRowTransparency()) {
            mNormalColor = SurfaceEffectColors.surfaceEffect1(getContext());
        } else  {
            mNormalColor = mContext.getColor(
                    com.android.internal.R.color.materialColorSurfaceContainerHigh);
        }
        mFocusOverlayStroke = getResources().getDimension(R.dimen.notification_focus_stroke_width);
        mGlassStroke = 1.3f * getResources().getDisplayMetrics().density;
        mGlassRim.setStyle(Paint.Style.STROKE);
        mGlassRim.setStrokeWidth(mGlassStroke);
        mGlassGloss.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        MiraCLOLiquidGlass.attach(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        MiraCLOLiquidGlass.detach(this);
        super.onDetachedFromWindow();
    }

    private boolean useLiquidGlass() {
        if (mBackground == null || mBackgroundBlurDrawable != null || mExpandAnimationRunning) {
            return false;
        }
        if (!MiraCLOLiquidGlass.isEnabled(mContext)) {
            return false;
        }
        ViewParent parent = getParent();
        if (!(parent instanceof ActivatableNotificationView view) || view.isOnKeyguard()) {
            return false;
        }
        if (view instanceof ExpandableNotificationRow row
                && (row.isPinned() || row.isHeadsUpAnimatingAway())) {
            return false;
        }
        return true;
    }

    private boolean isGroupChild() {
        return getParent() instanceof ExpandableNotificationRow row && row.isChildInGroup();
    }

    /** Draws the card as glass: a see-through fill, then the gloss and the light rim. */
    private void drawLiquidGlass(Canvas canvas, Drawable drawable, int left, int top, int right,
            int bottom) {
        final Drawable.Callback callback = drawable.getCallback();
        // No callback while the alpha is changed for this one draw, so it does not ask for
        // another frame.
        drawable.setCallback(null);
        drawable.setAlpha(Math.round(mDrawableAlpha * GLASS_FILL));
        drawable.setBounds(left, top, right, bottom);
        drawable.draw(canvas);
        drawable.setAlpha(mDrawableAlpha);
        drawable.setCallback(callback);

        final int w = right - left;
        final int h = bottom - top;
        if (w <= 0 || h <= 0) {
            return;
        }
        if (w != mGlassShaderW || h != mGlassShaderH) {
            mGlassShaderW = w;
            mGlassShaderH = h;
            mGlassGloss.setShader(cssGradient(w, h, 155f,
                    new int[] {0x33FFFFFF, 0x0AFFFFFF, 0x05FFFFFF, 0x1AFFFFFF},
                    new float[] {0f, 0.42f, 0.60f, 1f}));
            mGlassRim.setShader(cssGradient(w, h, 135f,
                    new int[] {0xF2FFFFFF, 0x40FFFFFF, 0x0AFFFFFF, 0x59BED7FF, 0xD9FFFFFF},
                    new float[] {0f, 0.22f, 0.50f, 0.78f, 1f}));
        }
        final int alpha = mDrawableAlpha;
        canvas.save();
        canvas.translate(left, top);
        mGlassRect.set(0, 0, w, h);
        mGlassPath.reset();
        mGlassPath.addRoundRect(mGlassRect, mCornerRadii, Path.Direction.CW);
        mGlassGloss.setAlpha(alpha);
        canvas.drawPath(mGlassPath, mGlassGloss);
        // Group children sit inside one card, so only the card itself gets the rim.
        if (!isGroupChild()) {
            final float inset = mGlassStroke / 2f;
            for (int i = 0; i < 8; i++) {
                mGlassRadii[i] = Math.max(0f, mCornerRadii[i] - inset);
            }
            mGlassRect.set(inset, inset, w - inset, h - inset);
            mGlassPath.reset();
            mGlassPath.addRoundRect(mGlassRect, mGlassRadii, Path.Direction.CW);
            mGlassRim.setAlpha(alpha);
            canvas.drawPath(mGlassPath, mGlassRim);
        }
        canvas.restore();
    }

    /** A linear gradient laid out like CSS linear-gradient(angle) over a w x h box. */
    private static LinearGradient cssGradient(int w, int h, float angle, int[] colors,
            float[] stops) {
        final double rad = Math.toRadians(angle);
        final float dx = (float) Math.sin(rad);
        final float dy = (float) -Math.cos(rad);
        final float half = (Math.abs(w * dx) + Math.abs(h * dy)) / 2f;
        final float cx = w / 2f;
        final float cy = h / 2f;
        return new LinearGradient(cx - dx * half, cy - dy * half, cx + dx * half,
                cy + dy * half, colors, stops, Shader.TileMode.CLAMP);
    }

    @Override
    public void onTargetVisibilityChanged(boolean targetVisible) {
        if (NotificationAddXOnHoverToDismiss.isUnexpectedlyInLegacyMode()) {
            return;
        }

        if (mDrawDismissButtonCutout != targetVisible) {
            mDrawDismissButtonCutout = targetVisible;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float clipTop = Math.max(mClipTopAmount, mTopOverlap);
        int clipBottomAmount = Math.max(mClipBottomAmount, mBottomOverlap);
        if (clipTop + clipBottomAmount < getActualHeight() || mExpandAnimationRunning) {
            canvas.save();
            if (!mExpandAnimationRunning) {
                canvas.clipRect(0, clipTop, getWidth(),
                        getActualHeight() - clipBottomAmount);
            }

            if (!NotificationAddXOnHoverToDismiss.isEnabled()) {
                if (mBackgroundBlurDrawable != null) {
                    draw(canvas, mBackgroundBlurDrawable);
                } else {
                    draw(canvas, mBackground);
                }
                canvas.restore();
                return;
            }

            Rect backgroundBounds = null;
            if (mBackground != null || mBackgroundBlurDrawable != null
                    || mDrawDismissButtonCutout) {
                backgroundBounds = calculateBackgroundBounds();
            }

            if (mDrawDismissButtonCutout) {
                canvas.clipPath(calculateDismissButtonCutoutPath(backgroundBounds));
            }
            if (mBackgroundBlurDrawable != null) {
                mBackgroundBlurDrawable.setBounds(backgroundBounds);
                mBackgroundBlurDrawable.draw(canvas);
            } else if (mBackground != null) {
                if (useLiquidGlass()) {
                    drawLiquidGlass(canvas, mBackground, backgroundBounds.left,
                            backgroundBounds.top, backgroundBounds.right,
                            backgroundBounds.bottom);
                } else {
                    mBackground.setBounds(backgroundBounds);
                    mBackground.draw(canvas);
                }
            }

            canvas.restore();
        }
    }

    private Path calculateDismissButtonCutoutPath(Rect backgroundBounds) {
        // TODO(b/365585705): Adapt to RTL after the UX design is finalized.

        NotificationAddXOnHoverToDismiss.isUnexpectedlyInLegacyMode();

        Path path = new Path();

        final int left = backgroundBounds.left;
        final int right = backgroundBounds.right;
        final int top = backgroundBounds.top;
        final int bottom = backgroundBounds.bottom;

        // Generate the path clockwise from the left-top corner.
        path.moveTo(left, top);
        path.lineTo(right - 2 * convexR - concaveR, top);
        path.quadTo(right - convexR - concaveR, top, right - convexR - concaveR,
                top + convexR);
        path.quadTo(right - convexR - concaveR, top + convexR + concaveR, right - convexR,
                top + convexR + concaveR);
        path.quadTo(right, top + convexR + concaveR, right, top + 2 * convexR + concaveR);
        path.lineTo(right, bottom);
        path.lineTo(left, bottom);
        path.lineTo(left, top);

        return path;
    }

    private Rect calculateBackgroundBounds() {
        NotificationAddXOnHoverToDismiss.isUnexpectedlyInLegacyMode();

        int top = 0;
        int bottom = getActualHeight();
        if (mBottomIsRounded
                && mBottomAmountClips
                && !mExpandAnimationRunning) {
            bottom -= Math.max(mClipBottomAmount, mBottomOverlap);
        }
        final boolean alignedToRight = isAlignedToRight();
        final int width = getWidth();
        final int actualWidth = getActualWidth();

        int left = alignedToRight ? width - actualWidth : 0;
        int right = alignedToRight ? width : actualWidth;

        if (mExpandAnimationRunning) {
            // Horizontally center this background view inside of the container
            left = (int) ((width - actualWidth) / 2.0f);
            right = (int) (left + actualWidth);
        }

        return new Rect(left, top, right, bottom);
    }

    /**
     * @return Whether the background view should be right-aligned. This only matters if the
     * actualWidth is different than the full (measured) width. In other words, this is used to
     * define the short-shelf alignment.
     */
    protected boolean isAlignedToRight() {
        return isLayoutRtl();
    }

    private void draw(Canvas canvas, Drawable drawable) {
        NotificationAddXOnHoverToDismiss.assertInLegacyMode();

        if (drawable != null) {
            int top = 0;
            int bottom = getActualHeight();
            if (mBottomIsRounded
                    && mBottomAmountClips
                    && !mExpandAnimationRunning) {
                bottom -= Math.max(mClipBottomAmount, mBottomOverlap);
            }

            final boolean alignedToRight = isAlignedToRight();
            final int width = getWidth();
            final int actualWidth = getActualWidth();

            int left = alignedToRight ? width - actualWidth : 0;
            int right = alignedToRight ? width : actualWidth;

            if (mExpandAnimationRunning) {
                // Horizontally center this background view inside of the container
                left = (int) ((width - actualWidth) / 2.0f);
                right = (int) (left + actualWidth);
            }
            if (drawable == mBackground && useLiquidGlass()) {
                drawLiquidGlass(canvas, drawable, left, top, right, bottom);
                return;
            }
            drawable.setBounds(left, top, right, bottom);
            drawable.draw(canvas);
        }
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return super.verifyDrawable(who) || who == mBackground;
    }

    @Override
    protected void drawableStateChanged() {
        setState(getDrawableState());
    }

    @Override
    public void drawableHotspotChanged(float x, float y) {
        if (mBackground != null) {
            mBackground.setHotspot(x, y);
        }
    }

    /**
     * Update the view on whether the device is currently on the keyguard.
     */
    public void setOnKeyguard(boolean onKeyguard) {
        if (onKeyguard == mOnKeyguard) {
            return;
        }

        // TODO: (b/445495701) - Determine why this value is opposite of expected, then use in #draw
        // to restrict the blurred background to the keyguard only.
        mOnKeyguard = onKeyguard;
    }

    /**
     * Stateful colors are colors that will overlay on the notification original color when one of
     * hover states, pressed states or other similar states is activated.
     */
    private void setStatefulColors() {
        Drawable statefulLayer = getStatefulBackgroundLayer();
        if (statefulLayer == null) {
            return;
        }
        if (mTintColor != mNormalColor) {
            ColorStateList newColor = ContrastColorUtil.isColorDark(mTintColor)
                    ? mDarkColoredStatefulColors : mLightColoredStatefulColors;
            ((GradientDrawable) statefulLayer.mutate()).setColor(newColor);
        }
    }

    /**
     * Sets a background drawable. As we need to change our bounds independently of layout, we need
     * the notion of a background independently of the regular View background..
     */
    public void setCustomBackground(Drawable background) {
        if (mBackground != null) {
            mBackground.setCallback(null);
            unscheduleDrawable(mBackground);
        }
        mBackground = background;
        mRippleColor = null;
        mBackground.mutate();
        if (mBackground != null) {
            mBackground.setCallback(this);
            setTint(mTintColor);
        }
        if (mBackground instanceof RippleDrawable) {
            ((RippleDrawable) mBackground).setForceSoftware(true);
        }
        updateBackgroundRadii();
        invalidate();
    }

    public void setCustomBackground(int drawableResId) {
        final Drawable d = mContext.getDrawable(drawableResId);
        setCustomBackground(d);
    }

    /**
     * Update whether this view should allow a blurred background or not.
     *
     * @param enabled - If true, queues creation of a {BackgroundBlurDrawable}. If false, removes
     *     any reference to a blurred drawable.
     */
    @UiThread
    public void setBlurBackgroundEnabled(boolean enabled) {
        if (!lockscreenBlurForNotifications()) {
            return;
        }
        Assert.isMainThread();

        if (enabled && mBackgroundBlurDrawable == null) {
            if (mOnAttachStateChangeListener != null) {
                removeOnAttachStateChangeListener(mOnAttachStateChangeListener);
            }
            mOnAttachStateChangeListener =
                    new OnAttachStateChangeListener() {
                        @Override
                        public void onViewAttachedToWindow(View view) {
                            if (mBackgroundBlurDrawable == null) {
                                mBackgroundBlurDrawable =
                                        view.getViewRootImpl().createBackgroundBlurDrawable();
                                mBackgroundBlurDrawable.setBlurRadius(
                                        getResources().getDimensionPixelSize(
                                                R.dimen.notification_background_blur_radius));
                                mBackgroundBlurDrawable.setXfermode(null);
                                mBackgroundBlurDrawable.setCallback(
                                        NotificationBackgroundView.this);
                                mBackgroundBlurDrawable.setColor(mNormalColor);

                                updateBackgroundRadii();
                                invalidate();
                            }
                            NotificationBackgroundView.this.removeOnAttachStateChangeListener(this);
                            mOnAttachStateChangeListener = null;
                        }

                        @Override
                        public void onViewDetachedFromWindow(View view) {}
                    };
            addOnAttachStateChangeListener(mOnAttachStateChangeListener);

            if (isAttachedToWindow()) {
                mOnAttachStateChangeListener.onViewAttachedToWindow(this);
            }
        } else if (!enabled) {
            if (mOnAttachStateChangeListener != null) {
                removeOnAttachStateChangeListener(mOnAttachStateChangeListener);
                mOnAttachStateChangeListener = null;
            }
            if (mBackgroundBlurDrawable != null) {
                mBackgroundBlurDrawable.setCallback(null);
            }
            mBackgroundBlurDrawable = null;
        }
    }

    public Drawable getBaseBackgroundLayer() {
        return ((LayerDrawable) mBackground).getDrawable(0);
    }

    private Drawable getStatefulBackgroundLayer() {
        return ((LayerDrawable) mBackground).getDrawable(1);
    }

    public void setTint(int tintColor) {
        Drawable baseLayer = getBaseBackgroundLayer();
        if (notificationRowTransparency()) {
            ((GradientDrawable) baseLayer.mutate()).setColor(tintColor);
        } else {
            baseLayer.mutate().setTintMode(PorterDuff.Mode.SRC_ATOP);
            baseLayer.setTint(tintColor);
        }
        if (mBackgroundBlurDrawable != null) {
            mBackgroundBlurDrawable.setColor(tintColor);
        }
        mTintColor = tintColor;
        setStatefulColors();
        invalidate();
    }

    public void setActualHeight(int actualHeight) {
        if (mExpandAnimationRunning) {
            return;
        }
        mActualHeight = actualHeight;
        invalidate();
    }

    private int getActualHeight() {
        if (mExpandAnimationRunning && mExpandAnimationHeight > -1) {
            return mExpandAnimationHeight;
        } else if (mActualHeight > -1) {
            return mActualHeight;
        }
        return getHeight();
    }

    public void setActualWidth(int actualWidth) {
        mActualWidth = actualWidth;
    }

    private int getActualWidth() {
        if (mExpandAnimationRunning && mExpandAnimationWidth > -1) {
            return mExpandAnimationWidth;
        } else if (mActualWidth > -1) {
            return mActualWidth;
        }
        return getWidth();
    }

    public void setClipTopAmount(int clipTopAmount) {
        mClipTopAmount = clipTopAmount;
        invalidate();
    }

    /**
     * Sets the overlap on the top of the view with other views. As a result we should clip the
     * background and content such that no overlap is visible anymore.
     * This is related to setClipTopAmount, however it is a separate way to clip which is usually
     * then combined with the clipTopAmount to take the maximum.
     */
    public void setTopOverlap(int topOverlap) {
        mTopOverlap = topOverlap;
        invalidate();
    }

    /**
     * Sets the overlap on the bottom of the view with other views. As a result we should clip the
     * background and content such that no overlap is visible anymore.
     * This is related to setClipBottomAmount, however it is a separate way to clip which is usually
     * then combined with the clipBottomAmount to take the maximum.
     */
    public void setBottomOverlap(int bottomOverlap) {
        mBottomOverlap = bottomOverlap;
        invalidate();
    }

    public void setClipBottomAmount(int clipBottomAmount) {
        mClipBottomAmount = clipBottomAmount;
        invalidate();
    }

    @Override
    public boolean hasOverlappingRendering() {

        // Prevents this view from creating a layer when alpha is animating.
        return false;
    }

    public void setState(int[] drawableState) {
        if (mBackground != null && mBackground.isStateful()) {
            mBackground.setState(drawableState);
        }
    }

    public void setRippleColor(int color) {
        if (mBackground instanceof RippleDrawable) {
            RippleDrawable ripple = (RippleDrawable) mBackground;
            ripple.setColor(ColorStateList.valueOf(color));
            mRippleColor = color;
        } else {
            mRippleColor = null;
        }
    }

    public void setDrawableAlpha(int drawableAlpha) {
        mDrawableAlpha = drawableAlpha;
        if (mExpandAnimationRunning) {
            return;
        }
        mBackground.setAlpha(drawableAlpha);
        if (mBackgroundBlurDrawable != null) {
            mBackgroundBlurDrawable.setAlpha(drawableAlpha);
        }
    }

    /**
     * Sets the current top and bottom radius for this background.
     */
    public void setRadius(float topRoundness, float bottomRoundness) {
        if (topRoundness == mCornerRadii[0] && bottomRoundness == mCornerRadii[4]) {
            return;
        }
        mBottomIsRounded = bottomRoundness != 0.0f;
        mCornerRadii[0] = topRoundness;
        mCornerRadii[1] = topRoundness;
        mCornerRadii[2] = topRoundness;
        mCornerRadii[3] = topRoundness;
        mCornerRadii[4] = bottomRoundness;
        mCornerRadii[5] = bottomRoundness;
        mCornerRadii[6] = bottomRoundness;
        mCornerRadii[7] = bottomRoundness;
        updateBackgroundRadii();
    }

    public void setBottomAmountClips(boolean clips) {
        if (clips != mBottomAmountClips) {
            mBottomAmountClips = clips;
            invalidate();
        }
    }

    @VisibleForTesting
    protected boolean isBlurEnabled() {
        return mBackgroundBlurDrawable != null;
    }

    private void updateBackgroundRadii() {
        if (mDontModifyCorners) {
            return;
        }
        if (mBackground instanceof LayerDrawable layerDrawable) {
            int numberOfLayers = layerDrawable.getNumberOfLayers();
            for (int i = 0; i < numberOfLayers; i++) {
                GradientDrawable gradientDrawable = (GradientDrawable) layerDrawable.getDrawable(i);
                gradientDrawable.setCornerRadii(mCornerRadii);
            }
            updateFocusOverlayRadii(layerDrawable);
        }
        if (mBackgroundBlurDrawable != null) {
            mBackgroundBlurDrawable.setCornerRadius(mCornerRadii[0], mCornerRadii[1],
                    mCornerRadii[2], mCornerRadii[3], mCornerRadii[4], mCornerRadii[5],
                    mCornerRadii[6], mCornerRadii[7]);
        }
    }

    private void updateFocusOverlayRadii(LayerDrawable background) {
        GradientDrawable overlay =
                (GradientDrawable) background.findDrawableByLayerId(
                        R.id.notification_focus_overlay);
        if (overlay == null) {
            return;
        }
        for (int i = 0; i < mCornerRadii.length; i++) {
            // in theory subtracting mFocusOverlayStroke/2 should be enough but notification
            // background is still peeking a bit from below - probably due to antialiasing or
            // overlay uneven scaling. So let's subtract full mFocusOverlayStroke to make sure the
            // radius is a bit smaller and covers background corners fully
            mFocusOverlayCornerRadii[i] = Math.max(0, mCornerRadii[i] - mFocusOverlayStroke);
        }
        overlay.setCornerRadii(mFocusOverlayCornerRadii);
    }

    /** Set the current expand animation size. */
    public void setExpandAnimationSize(int width, int height) {
        mExpandAnimationHeight = height;
        mExpandAnimationWidth = width;
        invalidate();
    }

    public void setExpandAnimationRunning(boolean running) {
        mExpandAnimationRunning = running;
        if (mBackground instanceof LayerDrawable) {
            GradientDrawable gradientDrawable =
                    (GradientDrawable) ((LayerDrawable) mBackground).getDrawable(0);
            // Speed optimization: disable AA if transfer mode is not SRC_OVER. AA is not easy to
            // spot during animation anyways.
            gradientDrawable.setAntiAlias(!running);
        }
        if (!mExpandAnimationRunning) {
            setDrawableAlpha(mDrawableAlpha);
        }
        invalidate();
    }

    @Override
    public void dump(PrintWriter pw, @NonNull String[] args) {
        pw.println("mDontModifyCorners: " + mDontModifyCorners);
        pw.println("mClipTopAmount: " + mClipTopAmount);
        pw.println("mClipBottomAmount: " + mClipBottomAmount);
        pw.println("mTopOverlap: " + mTopOverlap);
        pw.println("mBottomOverlap: " + mBottomOverlap);
        pw.println("mCornerRadii: " + Arrays.toString(mCornerRadii));
        pw.println("mBottomIsRounded: " + mBottomIsRounded);
        pw.println("mBottomAmountClips: " + mBottomAmountClips);
        pw.println("mActualWidth: " + mActualWidth);
        pw.println("mActualHeight: " + mActualHeight);
        pw.println("mTintColor: " + hexColorString(mTintColor));
        pw.println("mRippleColor: " + hexColorString(mRippleColor));
        pw.println("mBackground: " + DrawableDumpKt.dumpToString(mBackground));
        pw.println("mBackgroundBlurDrawable: "
                + DrawableDumpKt.dumpToString(mBackgroundBlurDrawable));
    }

    /** create a concise dump of this view's colors */
    public String toDumpString() {
        return "<NotificationBackgroundView"
                + " tintColor=" + hexColorString(mTintColor)
                + " rippleColor=" + hexColorString(mRippleColor)
                + " bgColor=" + DrawableDumpKt.getSolidColor(mBackground)
                + " blurEnabled=" + (mBackgroundBlurDrawable != null)
                + ">";

    }
}
