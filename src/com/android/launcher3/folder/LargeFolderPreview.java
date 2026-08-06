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

import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;

import com.android.launcher3.LauncherAppState;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.ItemInfoWithIcon;
import com.android.launcher3.model.data.WorkspaceItemInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the preview of a "big" (2x2) {@link FolderIcon}: the first
 * {@link com.android.launcher3.model.data.FolderInfo#BIG_FOLDER_LARGE_ICON_COUNT} apps as large,
 * individually launchable icons, plus a mini 2x2 cluster of the remaining apps in the last
 * quadrant that opens the folder when tapped.
 *
 * <p>This class owns the big-folder layout math, drawing, and the per-quadrant hit rectangles used
 * to route taps. {@link FolderIcon} delegates to it only while the folder is big; normal folders
 * keep using {@link PreviewItemManager}.
 */
public class LargeFolderPreview {

    // Fraction of the available (above-label) area used by the square preview.
    private static final float PREVIEW_SIZE_FRACTION = 0.94f;
    // Gap between the four quadrants, as a fraction of the preview side.
    private static final float QUADRANT_GAP_FRACTION = 0.06f;
    // Large icon size within its quadrant.
    private static final float LARGE_ICON_FRACTION = 0.96f;
    // Mini icon size within the cluster quadrant sub-grid.
    private static final float CLUSTER_ICON_FRACTION = 0.9f;
    // Gap between mini icons in the cluster, as a fraction of the quadrant side.
    private static final float CLUSTER_GAP_FRACTION = 0.08f;

    // Aurora burnt-orange accent, used for the drag-over "drop here" ring.
    private static final int ACCENT = 0xFFC8783E;

    private final FolderIcon mIcon;
    // The frosted "fake blur" panel behind the whole preview, and its drag-over highlight.
    private final Paint mPanelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPanelStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mAcceptFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mAcceptStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mDensity;

    // The apps drawn as large, launchable icons (index-aligned with mLargeRects).
    private final List<ItemInfo> mLargeItems = new ArrayList<>();
    private final List<Drawable> mLargeDrawables = new ArrayList<>();
    // The apps collapsed into the mini-cluster quadrant (mClusterItems index-aligned with drawables).
    private final List<ItemInfo> mClusterItems = new ArrayList<>();
    private final List<Drawable> mClusterDrawables = new ArrayList<>();

    // Hit rectangles in FolderIcon-local coordinates. mLargeRects[i] launches mLargeItems.get(i);
    // mClusterRect opens the folder.
    private final Rect[] mLargeRects = new Rect[BIG_FOLDER_LARGE_ICON_COUNT];
    private final Rect mClusterRect = new Rect();
    private final Rect mTmpRect = new Rect();
    // The frosted panel rect (the whole 2x2 preview square, slightly inset).
    private final RectF mPanelRect = new RectF();

    // Bottom of the preview square (FolderIcon-local px); the label is placed just below it.
    private int mPreviewBottom;
    // True while an app is being dragged over this folder, to show the accept highlight.
    private boolean mAccepting;

    public LargeFolderPreview(FolderIcon icon) {
        mIcon = icon;
        mDensity = icon.getResources().getDisplayMetrics().density;
        for (int i = 0; i < mLargeRects.length; i++) {
            mLargeRects[i] = new Rect();
        }
        final boolean night = (icon.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        // Frosted-glass look: a translucent light panel over the aurora. Cheap (no RenderEffect),
        // so it never introduces lag.
        mPanelPaint.setColor(night ? 0x30FFFFFF : 0x3AFFFFFF);
        mPanelStrokePaint.setStyle(Paint.Style.STROKE);
        mPanelStrokePaint.setStrokeWidth(mDensity);
        mPanelStrokePaint.setColor(night ? 0x22FFFFFF : 0x26FFFFFF);
        // Drag-over highlight: brighter fill + accent ring.
        mAcceptFillPaint.setColor(0x33FFFFFF);
        mAcceptStrokePaint.setStyle(Paint.Style.STROKE);
        mAcceptStrokePaint.setStrokeWidth(2f * mDensity);
        mAcceptStrokePaint.setColor(ACCENT);
    }

    /**
     * Rebuilds the icon drawables from the folder's ordered contents. Should be called whenever the
     * folder contents change.
     */
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

    @Nullable
    private Drawable newIcon(ItemInfo item) {
        if (item instanceof WorkspaceItemInfo wii) {
            return wii.newIcon(mIcon.getContext(), FLAG_THEMED);
        }
        return null;
    }

    /**
     * The loader only fetches high-res icons for the first {@code MAX_NUM_ITEMS_IN_PREVIEW} folder
     * items, but a big folder shows more (3 large + up to 4 cluster). Any still-low-res icon renders
     * as a plain coloured circle, so request a high-res load and rebuild that icon when it arrives.
     */
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

    /**
     * Recomputes the quadrant rectangles and preview bounds for the given tile size. Returns the
     * bottom of the preview square, which the caller uses to place the folder label just below it.
     */
    public int updateGeometry(int width, int height, int top, int labelHeight) {
        int availableHeight = height - top - labelHeight;
        int side = Math.round(Math.min(width, availableHeight) * PREVIEW_SIZE_FRACTION);
        if (side <= 0 || width <= 0) {
            mPreviewBottom = top;
            return mPreviewBottom;
        }

        int left = (width - side) / 2;
        int previewTop = top + Math.max(0, (availableHeight - side) / 2);
        int gap = Math.round(side * QUADRANT_GAP_FRACTION);
        int cell = (side - gap) / 2;

        // Quadrant top-left corners: 0 1 / 2 3
        int[] qx = {left, left + cell + gap, left, left + cell + gap};
        int[] qy = {previewTop, previewTop, previewTop + cell + gap, previewTop + cell + gap};

        for (int i = 0; i < BIG_FOLDER_LARGE_ICON_COUNT; i++) {
            mLargeRects[i].set(qx[i], qy[i], qx[i] + cell, qy[i] + cell);
        }
        int cq = BIG_FOLDER_LARGE_ICON_COUNT;
        mClusterRect.set(qx[cq], qy[cq], qx[cq] + cell, qy[cq] + cell);

        // The frosted panel frames the whole preview, expanded a touch past the icons.
        float pad = gap * 0.5f;
        mPanelRect.set(left - pad, previewTop - pad, left + side + pad, previewTop + side + pad);

        mPreviewBottom = previewTop + side;
        return mPreviewBottom;
    }

    /** Draws the big-folder preview using the geometry from the icon's current size. */
    public void draw(Canvas canvas) {
        updateGeometry(mIcon.getWidth(), mIcon.getHeight(), mIcon.getPaddingTop(),
                mIcon.getBigFolderLabelHeight());
        if (mPreviewBottom <= mIcon.getPaddingTop()) {
            return;
        }

        // Frosted "fake blur" panel behind everything.
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

    /** Toggles the drag-over "drop here" highlight. Returns true if the state changed. */
    public boolean setAccepting(boolean accepting) {
        if (mAccepting == accepting) {
            return false;
        }
        mAccepting = accepting;
        return true;
    }

    /**
     * Fills {@code out} with the frosted panel rect (FolderIcon-local px) for the current size, so
     * callers (drag bounds, open/close animation) use the big footprint instead of the small
     * circular preview.
     */
    public void getPreviewRect(Rect out) {
        updateGeometry(mIcon.getWidth(), mIcon.getHeight(), mIcon.getPaddingTop(),
                mIcon.getBigFolderLabelHeight());
        mPanelRect.roundOut(out);
    }

    private void drawCluster(Canvas canvas, Rect quadrant) {
        // The mini-cluster reads as "the folder" from its grid of small icons; it needs no separate
        // dark backing on top of the frosted panel (that extra square looked out of place).
        if (mClusterDrawables.isEmpty()) {
            return;
        }
        int gap = Math.round(quadrant.width() * CLUSTER_GAP_FRACTION);
        int miniCell = (quadrant.width() - gap) / 2;
        int[] mx = {quadrant.left, quadrant.left + miniCell + gap,
                quadrant.left, quadrant.left + miniCell + gap};
        int[] my = {quadrant.top, quadrant.top,
                quadrant.top + miniCell + gap, quadrant.top + miniCell + gap};
        int count = Math.min(mClusterDrawables.size(), 4);
        for (int i = 0; i < count; i++) {
            mTmpRect.set(mx[i], my[i], mx[i] + miniCell, my[i] + miniCell);
            drawCentered(canvas, mClusterDrawables.get(i), mTmpRect, CLUSTER_ICON_FRACTION);
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

    /**
     * Returns the app to launch for a tap at ({@code x}, {@code y}) in FolderIcon-local
     * coordinates, or {@code null} if the tap should instead open the folder (the mini-cluster
     * quadrant, the label, or empty space).
     */
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
