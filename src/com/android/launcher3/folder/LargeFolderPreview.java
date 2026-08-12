/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.launcher3.folder;

import static com.android.launcher3.LauncherSettings.Favorites.DESKTOP_ICON_FLAG;
import static com.android.launcher3.folder.ClippedFolderIconLayoutRule.MAX_NUM_ITEMS_IN_PREVIEW;
import static com.android.launcher3.icons.BitmapInfo.FLAG_THEMED;
import static com.android.launcher3.model.data.FolderInfo.BIG_FOLDER_LARGE_ICON_COUNT;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.allapps.AllAppsGridAdapter;
import com.android.launcher3.graphics.ThemeManager;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.ItemInfoWithIcon;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.views.ActivityContext;

import java.util.ArrayList;
import java.util.List;

public class LargeFolderPreview {

    private static final float PREVIEW_SIZE_FRACTION = 0.94f;
    private static final float MAX_PREVIEW_SIZE_DP = 136f;
    private static final float QUADRANT_GAP_FRACTION = 0.06f;
    private static final float LARGE_ICON_FRACTION = 0.96f;
    private static final float CLUSTER_ICON_FRACTION = 0.33f;
    private static final float CLUSTER_SPACING_FRACTION = 0.485f;

    private static final int ACCENT = 0xFFC8783E;

    private final FolderIcon mIcon;
    private final Paint mPanelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPanelStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mAcceptFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mAcceptStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mDensity;

    private final List<ItemInfo> mLargeItems = new ArrayList<>();
    private final List<Drawable> mLargeDrawables = new ArrayList<>();
    private final List<ItemInfo> mClusterItems = new ArrayList<>();
    private final List<Drawable> mClusterDrawables = new ArrayList<>();

    private final Rect[] mLargeRects = new Rect[BIG_FOLDER_LARGE_ICON_COUNT];
    private final Rect mClusterRect = new Rect();
    private final Rect mTmpRect = new Rect();
    private final RectF mPanelRect = new RectF();

    private int mPreviewBottom;
    private boolean mAccepting;

    public static final float PANEL_RADIUS_FRACTION = 0.16f;

    public static int getPanelColor(Context context) {
        return isNight(context) ? 0x30FFFFFF : 0x3AFFFFFF;
    }

    public static int getPanelStrokeColor(Context context) {
        return isNight(context) ? 0x22FFFFFF : 0x26FFFFFF;
    }

    private static boolean isNight(Context context) {
        return (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    public LargeFolderPreview(FolderIcon icon) {
        mIcon = icon;
        mDensity = icon.getResources().getDisplayMetrics().density;
        for (int i = 0; i < mLargeRects.length; i++) {
            mLargeRects[i] = new Rect();
        }
        mPanelPaint.setColor(getPanelColor(icon.getContext()));
        mPanelStrokePaint.setStyle(Paint.Style.STROKE);
        mPanelStrokePaint.setStrokeWidth(mDensity);
        mPanelStrokePaint.setColor(getPanelStrokeColor(icon.getContext()));
        mAcceptFillPaint.setColor(0x33FFFFFF);
        mAcceptStrokePaint.setStyle(Paint.Style.STROKE);
        mAcceptStrokePaint.setStrokeWidth(2f * mDensity);
        mAcceptStrokePaint.setColor(ACCENT);
    }

    public void onItemsChanged(List<ItemInfo> orderedContents) {
        mLargeItems.clear();
        mLargeDrawables.clear();
        mClusterItems.clear();
        mClusterDrawables.clear();

        for (int i = 0; i < orderedContents.size(); i++) {
            ItemInfo item = orderedContents.get(i);
            if (i < BIG_FOLDER_LARGE_ICON_COUNT) {
                mLargeItems.add(item);
                mLargeDrawables.add(newIcon(item));
                requestHighResIfNeeded(item);
            } else if (mClusterItems.size() < MAX_NUM_ITEMS_IN_PREVIEW) {
                Drawable d = newIcon(item);
                if (d != null) {
                    mClusterItems.add(item);
                    mClusterDrawables.add(d);
                    requestHighResIfNeeded(item);
                }
            }
        }
    }

    private int drawerTileContentWidth() {
        DeviceProfile dp = ActivityContext.lookupContext(mIcon.getContext()).getDeviceProfile();
        if (dp == null) {
            return 0;
        }
        int margin = dp.getAllAppsProfile().getLeftRightMargin();
        int recyclerWidth = dp.getDeviceProperties().getAvailableWidthPx() - (2 * margin);
        return Math.max(0, (recyclerWidth / AllAppsGridAdapter.FOLDERS_PER_ROW) - margin);
    }

    @Nullable
    private Drawable newIcon(ItemInfo item) {
        if (item instanceof WorkspaceItemInfo wii) {
            int flags = ThemeManager.INSTANCE.get(mIcon.getContext()).isIconThemeEnabled()
                    ? FLAG_THEMED : 0;
            return wii.newIcon(mIcon.getContext(), flags);
        }
        return null;
    }

    private void requestHighResIfNeeded(ItemInfo item) {
        if (item instanceof ItemInfoWithIcon info
                && info.getMatchingLookupFlag().isVisuallyLessThan(DESKTOP_ICON_FLAG)) {
            LauncherAppState.getInstance(mIcon.getContext()).getIconCache().updateIconInBackground(
                    mIcon.getContext().getMainExecutor(), this::onHighResIconLoaded,
                    info, DESKTOP_ICON_FLAG);
        }
    }

    private void onHighResIconLoaded(ItemInfoWithIcon newInfo) {
        boolean changed = false;
        for (int i = 0; i < mLargeItems.size(); i++) {
            if (mLargeItems.get(i) == newInfo) {
                mLargeDrawables.set(i, newIcon(newInfo));
                changed = true;
            }
        }
        for (int i = 0; i < mClusterItems.size(); i++) {
            if (mClusterItems.get(i) == newInfo) {
                mClusterDrawables.set(i, newIcon(newInfo));
                changed = true;
            }
        }
        if (changed) {
            mIcon.invalidate();
        }
    }

    public int updateGeometry(int width, int height, int top, int labelHeight) {
        int availableHeight = height - top - labelHeight;
        int side = Math.round(Math.min(width, availableHeight) * PREVIEW_SIZE_FRACTION);
        if (!mIcon.mInfo.forceBigPreview) {
            int drawerSide = drawerTileContentWidth();
            int cap = drawerSide > 0
                    ? Math.round(drawerSide * PREVIEW_SIZE_FRACTION)
                    : Math.round(MAX_PREVIEW_SIZE_DP * mDensity);
            side = Math.min(side, cap);
        }
        if (side <= 0 || width <= 0) {
            mPreviewBottom = top;
            return mPreviewBottom;
        }

        int left = (width - side) / 2;
        int previewTop = top + Math.max(0, (availableHeight - side) / 2);
        int gap = Math.round(side * QUADRANT_GAP_FRACTION);
        int cell = (side - gap) / 2;

        int[] qx = {left, left + cell + gap, left, left + cell + gap};
        int[] qy = {previewTop, previewTop, previewTop + cell + gap, previewTop + cell + gap};

        for (int i = 0; i < BIG_FOLDER_LARGE_ICON_COUNT; i++) {
            mLargeRects[i].set(qx[i], qy[i], qx[i] + cell, qy[i] + cell);
        }
        int cq = BIG_FOLDER_LARGE_ICON_COUNT;
        mClusterRect.set(qx[cq], qy[cq], qx[cq] + cell, qy[cq] + cell);

        float pad = gap * 0.5f;
        mPanelRect.set(left - pad, previewTop - pad, left + side + pad, previewTop + side + pad);

        mPreviewBottom = previewTop + side;
        return mPreviewBottom;
    }

    public void draw(Canvas canvas) {
        updateGeometry(mIcon.getWidth(), mIcon.getHeight(), mIcon.getPaddingTop(),
                mIcon.getBigFolderLabelHeight());
        if (mPreviewBottom <= mIcon.getPaddingTop()) {
            return;
        }

        float radius = mPanelRect.width() * 0.16f;
        canvas.drawRoundRect(mPanelRect, radius, radius, mPanelPaint);
        canvas.drawRoundRect(mPanelRect, radius, radius, mPanelStrokePaint);
        if (mAccepting) {
            canvas.drawRoundRect(mPanelRect, radius, radius, mAcceptFillPaint);
            canvas.drawRoundRect(mPanelRect, radius, radius, mAcceptStrokePaint);
        }

        for (int i = 0; i < BIG_FOLDER_LARGE_ICON_COUNT && i < mLargeDrawables.size(); i++) {
            drawCentered(canvas, mLargeDrawables.get(i), mLargeRects[i], LARGE_ICON_FRACTION);
        }
        drawCluster(canvas, mClusterRect);
    }

    public boolean setAccepting(boolean accepting) {
        if (mAccepting == accepting) {
            return false;
        }
        mAccepting = accepting;
        return true;
    }

    public void getPreviewRect(Rect out) {
        updateGeometry(mIcon.getWidth(), mIcon.getHeight(), mIcon.getPaddingTop(),
                mIcon.getBigFolderLabelHeight());
        mPanelRect.roundOut(out);
    }

    private void drawCluster(Canvas canvas, Rect quadrant) {
        if (mClusterDrawables.isEmpty()) {
            return;
        }
        int side = quadrant.width();
        int spacing = Math.round(side * CLUSTER_SPACING_FRACTION);
        int mini = Math.round(side * CLUSTER_ICON_FRACTION);
        int cx = quadrant.centerX();
        int cy = quadrant.centerY();
        int[] centreX = {cx - spacing / 2, cx + spacing / 2, cx - spacing / 2, cx + spacing / 2};
        int[] centreY = {cy - spacing / 2, cy - spacing / 2, cy + spacing / 2, cy + spacing / 2};
        int count = Math.min(mClusterDrawables.size(), 4);
        for (int i = 0; i < count; i++) {
            mTmpRect.set(centreX[i] - mini / 2, centreY[i] - mini / 2,
                    centreX[i] + mini / 2, centreY[i] + mini / 2);
            drawCentered(canvas, mClusterDrawables.get(i), mTmpRect, 1f);
        }
    }

    private void drawCentered(Canvas canvas, @Nullable Drawable d, Rect bounds, float fraction) {
        if (d == null) {
            return;
        }
        int size = Math.round(Math.min(bounds.width(), bounds.height()) * fraction);
        int cx = bounds.centerX();
        int cy = bounds.centerY();
        d.setBounds(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
        d.draw(canvas);
    }

    @Nullable
    public WorkspaceItemInfo getLaunchTargetForPoint(float x, float y) {
        int ix = Math.round(x);
        int iy = Math.round(y);
        for (int i = 0; i < mLargeRects.length && i < mLargeItems.size(); i++) {
            if (mLargeRects[i].contains(ix, iy)
                    && mLargeItems.get(i) instanceof WorkspaceItemInfo wii) {
                return wii;
            }
        }
        return null;
    }
}

