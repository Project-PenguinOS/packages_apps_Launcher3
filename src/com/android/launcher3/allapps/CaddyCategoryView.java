/*
 * Copyright (C) 2026 The PenguinOS Project
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
 * limitations under the License.
 */

package com.android.launcher3.allapps;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.BubbleTextView;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.R;
import com.android.launcher3.folder.FolderIcon;
import com.android.launcher3.model.data.FolderInfo;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.views.ActivityContext;
import com.android.launcher3.views.BaseDragLayer;

/**
 * The in-drawer "Caddy" category page: tapping a category tile in the app drawer opens this over the
 * drawer, showing the category's apps in a grid. It expands from the tile's bounds (a container
 * transform: the panel scales + translates + fades from the tile to full size) and closes back the
 * same way, so it never animates against / drops to the home screen the way the workspace Folder
 * does. It reuses the tile's frosted rounded-square panel look so the open surface matches the
 * closed tile.
 *
 * <p>First cut of the OnePlus-style categorized drawer open; sizes/colors are tuned on-device.
 */
public class CaddyCategoryView extends AbstractFloatingView {

    private static final int MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final int PANEL_MARGIN_DP = 16;
    private static final int PANEL_RADIUS_DP = 28;
    private static final int SCRIM_ALPHA = 0xB3; // ~70% black scrim behind the panel
    private static final int OPEN_DURATION = 300;

    private final ActivityContext mActivityContext;
    private final Rect mTileRect = new Rect();

    private LinearLayout mPanel;

    public CaddyCategoryView(Context context) {
        this(context, null);
    }

    public CaddyCategoryView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mActivityContext = ActivityContext.lookupContext(context);
    }

    /** Opens the category page for a drawer category tile. */
    public static CaddyCategoryView show(FolderIcon tile) {
        ActivityContext activityContext = ActivityContext.lookupContext(tile.getContext());
        BaseDragLayer<?> dragLayer = activityContext.getDragLayer();

        CaddyCategoryView view = new CaddyCategoryView(tile.getContext());
        view.populate(tile);
        dragLayer.addView(view, new BaseDragLayer.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        view.mIsOpen = true;
        view.animateOpenFrom(tile);
        return view;
    }

    private void populate(FolderIcon tile) {
        // The FolderInfo is stored as the tile's tag (FolderIcon.inflateIcon -> setTag(folderInfo)).
        FolderInfo info = (tile.getTag() instanceof FolderInfo) ? (FolderInfo) tile.getTag() : null;
        Context context = getContext();

        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setScrimAlpha(0);

        // The category panel (frosted rounded square, matching the tile).
        mPanel = new LinearLayout(context);
        mPanel.setOrientation(VERTICAL);
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setCornerRadius(dp(PANEL_RADIUS_DP));
        panelBg.setColor(Color.argb(0xF2, 0x1c, 0x1c, 0x1e)); // opaque dark panel; themable later
        mPanel.setBackground(panelBg);
        int pad = dp(16);
        mPanel.setPadding(pad, pad, pad, pad);
        mPanel.setClipToOutline(true);

        // Title.
        TextView title = new TextView(context);
        title.setText(info != null ? info.title : "");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setPadding(dp(4), dp(4), dp(4), dp(12));
        mPanel.addView(title, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        // App grid.
        DeviceProfile dp = mActivityContext.getDeviceProfile();
        int columns = Math.max(4, dp.getAllAppsProfile().getNumShownAllAppsColumns());
        int cellH = dp.getAllAppsProfile().getCellHeightPx();
        ScrollView scroller = new ScrollView(context);
        scroller.setFillViewport(true);
        GridLayout grid = new GridLayout(context);
        grid.setColumnCount(columns);
        if (info != null) {
            for (ItemInfo item : info.getContents()) {
                if (!(item instanceof WorkspaceItemInfo)) {
                    continue;
                }
                BubbleTextView icon = (BubbleTextView) LayoutInflater.from(context)
                        .inflate(R.layout.all_apps_icon, grid, false);
                icon.reset();
                icon.applyFromWorkspaceItem((WorkspaceItemInfo) item);
                icon.setOnClickListener(mActivityContext.getItemOnClickListener());
                GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
                glp.width = 0;
                glp.height = cellH;
                glp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
                icon.setLayoutParams(glp);
                grid.addView(icon);
            }
        }
        scroller.addView(grid, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        mPanel.addView(scroller, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));

        int margin = dp(PANEL_MARGIN_DP);
        LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT);
        panelLp.setMargins(margin, margin * 4, margin, margin * 4);
        addView(mPanel, panelLp);
    }

    private void animateOpenFrom(FolderIcon tile) {
        mActivityContext.getDragLayer().getDescendantRectRelativeToSelf(tile, mTileRect);
        // Run the container transform once the panel has been laid out and has real bounds.
        getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        startContainerTransform(true /* opening */, null);
                    }
                });
    }

    private void startContainerTransform(boolean opening, @Nullable Runnable onEnd) {
        if (mPanel.getWidth() == 0 || mPanel.getHeight() == 0) {
            if (onEnd != null) onEnd.run();
            return;
        }
        Rect panelRect = new Rect();
        mActivityContext.getDragLayer().getDescendantRectRelativeToSelf(mPanel, panelRect);

        float sx = (float) mTileRect.width() / mPanel.getWidth();
        float sy = (float) mTileRect.height() / mPanel.getHeight();
        float tx = mTileRect.left - panelRect.left;
        float ty = mTileRect.top - panelRect.top;

        mPanel.setPivotX(0f);
        mPanel.setPivotY(0f);
        mPanel.setScaleX(opening ? sx : 1f);
        mPanel.setScaleY(opening ? sy : 1f);
        mPanel.setTranslationX(opening ? tx : 0f);
        mPanel.setTranslationY(opening ? ty : 0f);
        mPanel.setAlpha(opening ? 0f : 1f);

        mPanel.animate()
                .scaleX(opening ? 1f : sx).scaleY(opening ? 1f : sy)
                .translationX(opening ? 0f : tx).translationY(opening ? 0f : ty)
                .alpha(opening ? 1f : 0f)
                .setDuration(OPEN_DURATION)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (onEnd != null) onEnd.run();
                    }
                })
                .start();

        ValueAnimator scrim = ValueAnimator.ofInt(
                opening ? 0 : SCRIM_ALPHA, opening ? SCRIM_ALPHA : 0);
        scrim.setDuration(OPEN_DURATION);
        scrim.addUpdateListener(a -> setScrimAlpha((int) a.getAnimatedValue()));
        scrim.start();
    }

    private void setScrimAlpha(int alpha) {
        setBackgroundColor(Color.argb(Math.max(0, Math.min(255, alpha)), 0, 0, 0));
    }

    @Override
    protected void handleClose(boolean animate) {
        if (!mIsOpen) {
            return;
        }
        if (!animate) {
            closeComplete();
            return;
        }
        startContainerTransform(false /* closing */, this::closeComplete);
    }

    private void closeComplete() {
        mIsOpen = false;
        if (getParent() instanceof ViewGroup parent) {
            parent.removeView(this);
        }
    }

    @Override
    public boolean onControllerInterceptTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN
                && !mActivityContext.getDragLayer().isEventOverView(mPanel, ev)) {
            // Tap outside the panel closes the category page.
            close(true);
            return true;
        }
        return false;
    }

    @Override
    public boolean onControllerTouchEvent(MotionEvent ev) {
        return false;
    }

    @Override
    protected boolean isOfType(int type) {
        return (type & TYPE_CADDY_CATEGORY) != 0;
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
