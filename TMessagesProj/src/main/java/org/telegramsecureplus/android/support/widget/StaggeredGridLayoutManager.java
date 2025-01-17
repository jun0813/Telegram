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
 * limitations under the License.
 */

package org.telegramsecureplus.android.support.widget;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.v4.view.ViewCompat;
import android.support.v4.view.accessibility.AccessibilityEventCompat;
import android.support.v4.view.accessibility.AccessibilityNodeInfoCompat;
import android.support.v4.view.accessibility.AccessibilityRecordCompat;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import static org.telegramsecureplus.android.support.widget.LayoutState.LAYOUT_START;
import static org.telegramsecureplus.android.support.widget.LayoutState.LAYOUT_END;
import static org.telegramsecureplus.android.support.widget.LayoutState.ITEM_DIRECTION_HEAD;
import static org.telegramsecureplus.android.support.widget.LayoutState.ITEM_DIRECTION_TAIL;
import static org.telegramsecureplus.android.support.widget.RecyclerView.NO_POSITION;

/**
 * A LayoutManager that lays out children in a staggered grid formation.
 * It supports horizontal & vertical layout as well as an ability to layout children in reverse.
 * <p>
 * Staggered grids are likely to have gaps at the edges of the layout. To avoid these gaps,
 * StaggeredGridLayoutManager can offset spans independently or move items between spans. You can
 * control this behavior via {@link #setGapStrategy(int)}.
 */
public class StaggeredGridLayoutManager extends RecyclerView.LayoutManager {

    public static final String TAG = "StaggeredGridLayoutManager";

    private static final boolean DEBUG = false;

    public static final int HORIZONTAL = OrientationHelper.HORIZONTAL;

    public static final int VERTICAL = OrientationHelper.VERTICAL;

    /**
     * Does not do anything to hide gaps.
     */
    public static final int GAP_HANDLING_NONE = 0;

    @Deprecated
    public static final int GAP_HANDLING_LAZY = 1;

    /**
     * When scroll state is changed to {@link RecyclerView#SCROLL_STATE_IDLE}, StaggeredGrid will
     * check if there are gaps in the because of full span items. If it finds, it will re-layout
     * and move items to correct positions with animations.
     * <p>
     * For example, if LayoutManager ends up with the following layout due to adapter changes:
     * <pre>
     * AAA
     * _BC
     * DDD
     * </pre>
     * <p>
     * It will animate to the following state:
     * <pre>
     * AAA
     * BC_
     * DDD
     * </pre>
     */
    public static final int GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS = 2;

    private static final int INVALID_OFFSET = Integer.MIN_VALUE;

    /**
     * Number of spans
     */
    private int mSpanCount = -1;

    private Span[] mSpans;

    /**
     * Primary orientation is the layout's orientation, secondary orientation is the orientation
     * for spans. Having both makes code much cleaner for calculations.
     */
    OrientationHelper mPrimaryOrientation;
    OrientationHelper mSecondaryOrientation;

    private int mOrientation;

    /**
     * The width or height per span, depending on the orientation.
     */
    private int mSizePerSpan;

    private LayoutState mLayoutState;

    private boolean mReverseLayout = false;

    /**
     * Aggregated reverse layout value that takes RTL into account.
     */
    boolean mShouldReverseLayout = false;

    /**
     * Temporary variable used during fill method to check which spans needs to be filled.
     */
    private BitSet mRemainingSpans;

    /**
     * When LayoutManager needs to scroll to a position, it sets this variable and requests a
     * layout which will check this variable and re-layout accordingly.
     */
    int mPendingScrollPosition = NO_POSITION;

    /**
     * Used to keep the offset value when {@link #scrollToPositionWithOffset(int, int)} is
     * called.
     */
    int mPendingScrollPositionOffset = INVALID_OFFSET;

    /**
     * Keeps the mapping between the adapter positions and spans. This is necessary to provide
     * a consistent experience when user scrolls the list.
     */
    LazySpanLookup mLazySpanLookup = new LazySpanLookup();

    /**
     * how we handle gaps in UI.
     */
    private int mGapStrategy = GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS;

    /**
     * Saved state needs this information to properly layout on restore.
     */
    private boolean mLastLayoutFromEnd;

    /**
     * Saved state and onLayout needs this information to re-layout properly
     */
    private boolean mLastLayoutRTL;

    /**
     * SavedState is not handled until a layout happens. This is where we keep it until next
     * layout.
     */
    private SavedState mPendingSavedState;

    /**
     * Re-used measurement specs. updated by onLayout.
     */
    private int mFullSizeSpec, mWidthSpec, mHeightSpec;

    /**
     * Re-used rectangle to get child decor offsets.
     */
    private final Rect mTmpRect = new Rect();

    /**
     * Re-used anchor info.
     */
    private final AnchorInfo mAnchorInfo = new AnchorInfo();

    /**
     * If a full span item is invalid / or created in reverse direction; it may create gaps in
     * the UI. While laying out, if such case is detected, we set this flag.
     * <p>
     * After scrolling stops, we check this flag and if it is set, re-layout.
     */
    private boolean mLaidOutInvalidFullSpan = false;

    /**
     * Works the same way as {@link android.widget.AbsListView#setSmoothScrollbarEnabled(boolean)}.
     * see {@link android.widget.AbsListView#setSmoothScrollbarEnabled(boolean)}
     */
    private boolean mSmoothScrollbarEnabled = true;

    private final Runnable mCheckForGapsRunnable = new Runnable() {
        @Override
        public void run() {
            checkForGaps();
        }
    };

    /**
     * Creates a StaggeredGridLayoutManager with given parameters.
     *
     * @param spanCount   If orientation is vertical, spanCount is number of columns. If
     *                    orientation is horizontal, spanCount is number of rows.
     * @param orientation {@link #VERTICAL} or {@link #HORIZONTAL}
     */
    public StaggeredGridLayoutManager(int spanCount, int orientation) {
        mOrientation = orientation;
        setSpanCount(spanCount);
    }

    /**
     * Checks for gaps in the UI that may be caused by adapter changes.
     * <p>
     * When a full span item is laid out in reverse direction, it sets a flag which we check when
     * scroll is stopped (or re-layout happens) and re-layout after first valid item.
     */
    private boolean checkForGaps() {
        if (getChildCount() == 0 || mGapStrategy == GAP_HANDLING_NONE || !isAttachedToWindow()) {
            return false;
        }
        final int minPos, maxPos;
        if (mShouldReverseLayout) {
            minPos = getLastChildPosition();
            maxPos = getFirstChildPosition();
        } else {
            minPos = getFirstChildPosition();
            maxPos = getLastChildPosition();
        }
        if (minPos == 0) {
            View gapView = hasGapsToFix();
            if (gapView != null) {
                mLazySpanLookup.clear();
                requestSimpleAnimationsInNextLayout();
                requestLayout();
                return true;
            }
        }
        if (!mLaidOutInvalidFullSpan) {
            return false;
        }
        int invalidGapDir = mShouldReverseLayout ? LAYOUT_START : LAYOUT_END;
        final LazySpanLookup.FullSpanItem invalidFsi = mLazySpanLookup
                .getFirstFullSpanItemInRange(minPos, maxPos + 1, invalidGapDir, true);
        if (invalidFsi == null) {
            mLaidOutInvalidFullSpan = false;
            mLazySpanLookup.forceInvalidateAfter(maxPos + 1);
            return false;
        }
        final LazySpanLookup.FullSpanItem validFsi = mLazySpanLookup
                .getFirstFullSpanItemInRange(minPos, invalidFsi.mPosition,
                        invalidGapDir * -1, true);
        if (validFsi == null) {
            mLazySpanLookup.forceInvalidateAfter(invalidFsi.mPosition);
        } else {
            mLazySpanLookup.forceInvalidateAfter(validFsi.mPosition + 1);
        }
        requestSimpleAnimationsInNextLayout();
        requestLayout();
        return true;
    }

    @Override
    public void onScrollStateChanged(int state) {
        if (state == RecyclerView.SCROLL_STATE_IDLE) {
            checkForGaps();
        }
    }

    @Override
    public void onDetachedFromWindow(RecyclerView view, RecyclerView.Recycler recycler) {
        removeCallbacks(mCheckForGapsRunnable);
        for (int i = 0; i < mSpanCount; i++) {
            mSpans[i].clear();
        }
    }

    /**
     * Checks for gaps if we've reached to the top of the list.
     * <p>
     * Intermediate gaps created by full span items are tracked via mLaidOutInvalidFullSpan field.
     */
    View hasGapsToFix() {
        int startChildIndex = 0;
        int endChildIndex = getChildCount() - 1;
        BitSet mSpansToCheck = new BitSet(mSpanCount);
        mSpansToCheck.set(0, mSpanCount, true);

        final int firstChildIndex, childLimit;
        final int preferredSpanDir = mOrientation == VERTICAL && isLayoutRTL() ? 1 : -1;

        if (mShouldReverseLayout) {
            firstChildIndex = endChildIndex;
            childLimit = startChildIndex - 1;
        } else {
            firstChildIndex = startChildIndex;
            childLimit = endChildIndex + 1;
        }
        final int nextChildDiff = firstChildIndex < childLimit ? 1 : -1;
        for (int i = firstChildIndex; i != childLimit; i += nextChildDiff) {
            View child = getChildAt(i);
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            if (mSpansToCheck.get(lp.mSpan.mIndex)) {
                if (checkSpanForGap(lp.mSpan)) {
                    return child;
                }
                mSpansToCheck.clear(lp.mSpan.mIndex);
            }
            if (lp.mFullSpan) {
                continue; // quick reject
            }

            if (i + nextChildDiff != childLimit) {
                View nextChild = getChildAt(i + nextChildDiff);
                boolean compareSpans = false;
                if (mShouldReverseLayout) {
                    // ensure child's end is below nextChild's end
                    int myEnd = mPrimaryOrientation.getDecoratedEnd(child);
                    int nextEnd = mPrimaryOrientation.getDecoratedEnd(nextChild);
                    if (myEnd < nextEnd) {
                        return child;//i should have a better position
                    } else if (myEnd == nextEnd) {
                        compareSpans = true;
                    }
                } else {
                    int myStart = mPrimaryOrientation.getDecoratedStart(child);
                    int nextStart = mPrimaryOrientation.getDecoratedStart(nextChild);
                    if (myStart > nextStart) {
                        return child;//i should have a better position
                    } else if (myStart == nextStart) {
                        compareSpans = true;
                    }
                }
                if (compareSpans) {
                    // equal, check span indices.
                    LayoutParams nextLp = (LayoutParams) nextChild.getLayoutParams();
                    if (lp.mSpan.mIndex - nextLp.mSpan.mIndex < 0 != preferredSpanDir < 0) {
                        return child;
                    }
                }
            }
        }
        // everything looks good
        return null;
    }

    private boolean checkSpanForGap(Span span) {
        if (mShouldReverseLayout) {
            if (span.getEndLine() < mPrimaryOrientation.getEndAfterPadding()) {
                return true;
            }
        } else if (span.getStartLine() > mPrimaryOrientation.getStartAfterPadding()) {
            return true;
        }
        return false;
    }

    /**
     * Sets the number of spans for the layout. This will invalidate all of the span assignments
     * for Views.
     * <p>
     * Calling this method will automatically result in a new layout request unless the spanCount
     * parameter is equal to current span count.
     *
     * @param spanCount Number of spans to layout
     */
    public void setSpanCount(int spanCount) {
        assertNotInLayoutOrScroll(null);
        if (spanCount != mSpanCount) {
            invalidateSpanAssignments();
            mSpanCount = spanCount;
            mRemainingSpans = new BitSet(mSpanCount);
            mSpans = new Span[mSpanCount];
            for (int i = 0; i < mSpanCount; i++) {
                mSpans[i] = new Span(i);
            }
            requestLayout();
        }
    }

    /**
     * Sets the orientation of the layout. StaggeredGridLayoutManager will do its best to keep
     * scroll position if this method is called after views are laid out.
     *
     * @param orientation {@link #HORIZONTAL} or {@link #VERTICAL}
     */
    public void setOrientation(int orientation) {
        if (orientation != HORIZONTAL && orientation != VERTICAL) {
            throw new IllegalArgumentException("invalid orientation.");
        }
        assertNotInLayoutOrScroll(null);
        if (orientation == mOrientation) {
            return;
        }
        mOrientation = orientation;
        if (mPrimaryOrientation != null && mSecondaryOrientation != null) {
            // swap
            OrientationHelper tmp = mPrimaryOrientation;
            mPrimaryOrientation = mSecondaryOrientation;
            mSecondaryOrientation = tmp;
        }
        requestLayout();
    }

    /**
     * Sets whether LayoutManager should start laying out items from the end of the UI. The order
     * items are traversed is not affected by this call.
     * <p>
     * For vertical layout, if it is set to <code>true</code>, first item will be at the bottom of
     * the list.
     * <p>
     * For horizontal layouts, it depends on the layout direction.
     * When set to true, If {@link RecyclerView} is LTR, than it will layout from RTL, if
     * {@link RecyclerView}} is RTL, it will layout from LTR.
     *
     * @param reverseLayout Whether layout should be in reverse or not
     */
    public void setReverseLayout(boolean reverseLayout) {
        assertNotInLayoutOrScroll(null);
        if (mPendingSavedState != null && mPendingSavedState.mReverseLayout != reverseLayout) {
            mPendingSavedState.mReverseLayout = reverseLayout;
        }
        mReverseLayout = reverseLayout;
        requestLayout();
    }

    /**
     * Returns the current gap handling strategy for StaggeredGridLayoutManager.
     * <p>
     * Staggered grid may have gaps in the layout due to changes in the adapter. To avoid gaps,
     * StaggeredGridLayoutManager provides 2 options. Check {@link #GAP_HANDLING_NONE} and
     * {@link #GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS} for details.
     * <p>
     * By default, StaggeredGridLayoutManager uses {@link #GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS}.
     *
     * @return Current gap handling strategy.
     * @see #setGapStrategy(int)
     * @see #GAP_HANDLING_NONE
     * @see #GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS
     */
    public int getGapStrategy() {
        return mGapStrategy;
    }

    /**
     * Sets the gap handling strategy for StaggeredGridLayoutManager. If the gapStrategy parameter
     * is different than the current strategy, calling this method will trigger a layout request.
     *
     * @param gapStrategy The new gap handling strategy. Should be
     *                    {@link #GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS} or {@link
     *                    #GAP_HANDLING_NONE}.
     * @see #getGapStrategy()
     */
    public void setGapStrategy(int gapStrategy) {
        assertNotInLayoutOrScroll(null);
        if (gapStrategy == mGapStrategy) {
            return;
        }
        if (gapStrategy != GAP_HANDLING_NONE &&
                gapStrategy != GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS) {
            throw new IllegalArgumentException("invalid gap strategy. Must be GAP_HANDLING_NONE "
                    + "or GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS");
        }
        mGapStrategy = gapStrategy;
        requestLayout();
    }

    @Override
    public void assertNotInLayoutOrScroll(String message) {
        if (mPendingSavedState == null) {
            super.assertNotInLayoutOrScroll(message);
        }
    }

    /**
     * Returns the number of spans laid out by StaggeredGridLayoutManager.
     *
     * @return Number of spans in the layout
     */
    public int getSpanCount() {
        return mSpanCount;
    }

    /**
     * For consistency, StaggeredGridLayoutManager keeps a mapping between spans and items.
     * <p>
     * If you need to cancel current assignments, you can call this method which will clear all
     * assignments and request a new layout.
     */
    public void invalidateSpanAssignments() {
        mLazySpanLookup.clear();
        requestLayout();
    }

    private void ensureOrientationHelper() {
        if (mPrimaryOrientation == null) {
            mPrimaryOrientation = OrientationHelper.createOrientationHelper(this, mOrientation);
            mSecondaryOrientation = OrientationHelper
                    .createOrientationHelper(this, 1 - mOrientation);
            mLayoutState = new LayoutState();
        }
    }

    /**
     * Calculates the views' layout order. (e.g. from end to start or start to end)
     * RTL layout support is applied automatically. So if layout is RTL and
     * {@link #getReverseLayout()} is {@code true}, elements will be laid out starting from left.
     */
    private void resolveShouldLayoutReverse() {
        // A == B is the same result, but we rather keep it readable
        if (mOrientation == VERTICAL || !isLayoutRTL()) {
            mShouldReverseLayout = mReverseLayout;
        } else {
            mShouldReverseLayout = !mReverseLayout;
        }
    }

    boolean isLayoutRTL() {
        return getLayoutDirection() == ViewCompat.LAYOUT_DIRECTION_RTL;
    }

    /**
     * Returns whether views are laid out in reverse order or not.
     * <p>
     * Not that this value is not affected by RecyclerView's layout direction.
     *
     * @return True if layout is reversed, false otherwise
     * @see #setReverseLayout(boolean)
     */
    public boolean getReverseLayout() {
        return mReverseLayout;
    }
    @Override
    public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
        ensureOrientationHelper();

        final AnchorInfo anchorInfo = mAnchorInfo;
        anchorInfo.reset();

        if (mPendingSavedState != null) {
            applyPendingSavedState(anchorInfo);
        } else {
            resolveShouldLayoutReverse();
            anchorInfo.mLayoutFromEnd = mShouldReverseLayout;
        }

        updateAnchorInfoForLayout(state, anchorInfo);

        if (mPendingSavedState == null) {
            if (anchorInfo.mLayoutFromEnd != mLastLayoutFromEnd ||
                    isLayoutRTL() != mLastLayoutRTL) {
                mLazySpanLookup.clear();
                anchorInfo.mInvalidateOffsets = true;
            }
        }

        if (getChildCount() > 0 && (mPendingSavedState == null ||
                mPendingSavedState.mSpanOffsetsSize < 1)) {
            if (anchorInfo.mInvalidateOffsets) {
                for (int i = 0; i < mSpanCount; i++) {
                    // Scroll to position is set, clear.
                    mSpans[i].clear();
                    if (anchorInfo.mOffset != INVALID_OFFSET) {
                        mSpans[i].setLine(anchorInfo.mOffset);
                    }
                }
            } else {
                for (int i = 0; i < mSpanCount; i++) {
                    mSpans[i].cacheReferenceLineAndClear(mShouldReverseLayout, anchorInfo.mOffset);
                }
            }
        }
        detachAndScrapAttachedViews(recycler);
        mLaidOutInvalidFullSpan = false;
        updateMeasureSpecs();
        if (anchorInfo.mLayoutFromEnd) {
            // Layout start.
            updateLayoutStateToFillStart(anchorInfo.mPosition, state);
            fill(recycler, mLayoutState, state);
            // Layout end.
            updateLayoutStateToFillEnd(anchorInfo.mPosition, state);
            mLayoutState.mCurrentPosition += mLayoutState.mItemDirection;
            fill(recycler, mLayoutState, state);
        } else {
            // Layout end.
            updateLayoutStateToFillEnd(anchorInfo.mPosition, state);
            fill(recycler, mLayoutState, state);
            // Layout start.
            updateLayoutStateToFillStart(anchorInfo.mPosition, state);
            mLayoutState.mCurrentPosition += mLayoutState.mItemDirection;
            fill(recycler, mLayoutState, state);
        }

        if (getChildCount() > 0) {
            if (mShouldReverseLayout) {
                fixEndGap(recycler, state, true);
                fixStartGap(recycler, state, false);
            } else {
                fixStartGap(recycler, state, true);
                fixEndGap(recycler, state, false);
            }
        }

        if (!state.isPreLayout()) {
            final boolean needToCheckForGaps = mGapStrategy != GAP_HANDLING_NONE
                    && getChildCount() > 0
                    && (mLaidOutInvalidFullSpan || hasGapsToFix() != null);
            if (needToCheckForGaps) {
                removeCallbacks(mCheckForGapsRunnable);
                postOnAnimation(mCheckForGapsRunnable);
            }
            mPendingScrollPosition = NO_POSITION;
            mPendingScrollPositionOffset = INVALID_OFFSET;
        }
        mLastLayoutFromEnd = anchorInfo.mLayoutFromEnd;
        mLastLayoutRTL = isLayoutRTL();
        mPendingSavedState = null; // we don't need this anymore
    }

    private void applyPendingSavedState(AnchorInfo anchorInfo) {
        if (DEBUG) {
            Log.d(TAG, "found saved state: " + mPendingSavedState);
        }
        if (mPendingSavedState.mSpanOffsetsSize > 0) {
            if (mPendingSavedState.mSpanOffsetsSize == mSpanCount) {
                for (int i = 0; i < mSpanCount; i++) {
                    mSpans[i].clear();
                    int line = mPendingSavedState.mSpanOffsets[i];
                    if (line != Span.INVALID_LINE) {
                        if (mPendingSavedState.mAnchorLayoutFromEnd) {
                            line += mPrimaryOrientation.getEndAfterPadding();
                        } else {
                            line += mPrimaryOrientation.getStartAfterPadding();
                        }
                    }
                    mSpans[i].setLine(line);
                }
            } else {
                mPendingSavedState.invalidateSpanInfo();
                mPendingSavedState.mAnchorPosition = mPendingSavedState.mVisibleAnchorPosition;
            }
        }
        mLastLayoutRTL = mPendingSavedState.mLastLayoutRTL;
        setReverseLayout(mPendingSavedState.mReverseLayout);
        resolveShouldLayoutReverse();

        if (mPendingSavedState.mAnchorPosition != NO_POSITION) {
            mPendingScrollPosition = mPendingSavedState.mAnchorPosition;
            anchorInfo.mLayoutFromEnd = mPendingSavedState.mAnchorLayoutFromEnd;
        } else {
            anchorInfo.mLayoutFromEnd = mShouldReverseLayout;
        }
        if (mPendingSavedState.mSpanLookupSize > 1) {
            mLazySpanLookup.mData = mPendingSavedState.mSpanLookup;
            mLazySpanLookup.mFullSpanItems = mPendingSavedState.mFullSpanItems;
        }
    }

    void updateAnchorInfoForLayout(RecyclerView.State state, AnchorInfo anchorInfo) {
        if (updateAnchorFromPendingData(state, anchorInfo)) {
            return;
        }
        if (updateAnchorFromChildren(state, anchorInfo)) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "Deciding anchor info from fresh state");
        }
        anchorInfo.assignCoordinateFromPadding();
        anchorInfo.mPosition = 0;
    }

    private boolean updateAnchorFromChildren(RecyclerView.State state, AnchorInfo anchorInfo) {
        // We don't recycle views out of adapter order. This way, we can rely on the first or
        // last child as the anchor position.
        // Layout direction may change but we should select the child depending on the latest
        // layout direction. Otherwise, we'll choose the wrong child.
        anchorInfo.mPosition = mLastLayoutFromEnd
                ? findLastReferenceChildPosition(state.getItemCount())
                : findFirstReferenceChildPosition(state.getItemCount());
        anchorInfo.mOffset = INVALID_OFFSET;
        return true;
    }

    boolean updateAnchorFromPendingData(RecyclerView.State state, AnchorInfo anchorInfo) {
        // Validate scroll position if exists.
        if (state.isPreLayout() || mPendingScrollPosition == NO_POSITION) {
            return false;
        }
        // Validate it.
        if (mPendingScrollPosition < 0 || mPendingScrollPosition >= state.getItemCount()) {
            mPendingScrollPosition = NO_POSITION;
            mPendingScrollPositionOffset = INVALID_OFFSET;
            return false;
        }

        if (mPendingSavedState == null || mPendingSavedState.mAnchorPosition == NO_POSITION
                || mPendingSavedState.mSpanOffsetsSize < 1) {
            // If item is visible, make it fully visible.
            final View child = findViewByPosition(mPendingScrollPosition);
            if (child != null) {
                // Use regular anchor position, offset according to pending offset and target
                // child
                anchorInfo.mPosition = mShouldReverseLayout ? getLastChildPosition()
                        : getFirstChildPosition();

                if (mPendingScrollPositionOffset != INVALID_OFFSET) {
                    if (anchorInfo.mLayoutFromEnd) {
                        final int target = mPrimaryOrientation.getEndAfterPadding() -
                                mPendingScrollPositionOffset;
                        anchorInfo.mOffset = target - mPrimaryOrientation.getDecoratedEnd(child);
                    } else {
                        final int target = mPrimaryOrientation.getStartAfterPadding() +
                                mPendingScrollPositionOffset;
                        anchorInfo.mOffset = target - mPrimaryOrientation.getDecoratedStart(child);
                    }
                    return true;
                }

                // no offset provided. Decide according to the child location
                final int childSize = mPrimaryOrientation.getDecoratedMeasurement(child);
                if (childSize > mPrimaryOrientation.getTotalSpace()) {
                    // Item does not fit. Fix depending on layout direction.
                    anchorInfo.mOffset = anchorInfo.mLayoutFromEnd
                            ? mPrimaryOrientation.getEndAfterPadding()
                            : mPrimaryOrientation.getStartAfterPadding();
                    return true;
                }

                final int startGap = mPrimaryOrientation.getDecoratedStart(child)
                        - mPrimaryOrientation.getStartAfterPadding();
                if (startGap < 0) {
                    anchorInfo.mOffset = -startGap;
                    return true;
                }
                final int endGap = mPrimaryOrientation.getEndAfterPadding() -
                        mPrimaryOrientation.getDecoratedEnd(child);
                if (endGap < 0) {
                    anchorInfo.mOffset = endGap;
                    return true;
                }
                // child already visible. just layout as usual
                anchorInfo.mOffset = INVALID_OFFSET;
            } else {
                // Child is not visible. Set anchor coordinate depending on in which direction
                // child will be visible.
                anchorInfo.mPosition = mPendingScrollPosition;
                if (mPendingScrollPositionOffset == INVALID_OFFSET) {
                    final int position = calculateScrollDirectionForPosition(
                            anchorInfo.mPosition);
                    anchorInfo.mLayoutFromEnd = position == LAYOUT_END;
                    anchorInfo.assignCoordinateFromPadding();
                } else {
                    anchorInfo.assignCoordinateFromPadding(mPendingScrollPositionOffset);
                }
                anchorInfo.mInvalidateOffsets = true;
            }
        } else {
            anchorInfo.mOffset = INVALID_OFFSET;
            anchorInfo.mPosition = mPendingScrollPosition;
        }
        return true;
    }

    void updateMeasureSpecs() {
        mSizePerSpan = mSecondaryOrientation.getTotalSpace() / mSpanCount;
        mFullSizeSpec = View.MeasureSpec.makeMeasureSpec(
                mSecondaryOrientation.getTotalSpace(), View.MeasureSpec.EXACTLY);
        if (mOrientation == VERTICAL) {
            mWidthSpec = View.MeasureSpec.makeMeasureSpec(mSizePerSpan, View.MeasureSpec.EXACTLY);
            mHeightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        } else {
            mHeightSpec = View.MeasureSpec.makeMeasureSpec(mSizePerSpan, View.MeasureSpec.EXACTLY);
            mWidthSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        }
    }

    @Override
    public boolean supportsPredictiveItemAnimations() {
        return mPendingSavedState == null;
    }

    /**
     * Returns the adapter position of the first visible view for each span.
     * <p>
     * Note that, this value is not affected by layout orientation or item order traversal.
     * ({@link #setReverseLayout(boolean)}). Views are sorted by their positions in the adapter,
     * not in the layout.
     * <p>
     * If RecyclerView has item decorators, they will be considered in calculations as well.
     * <p>
     * StaggeredGridLayoutManager may pre-cache some views that are not necessarily visible. Those
     * views are ignored in this method.
     *
     * @param into An array to put the results into. If you don't provide any, LayoutManager will
     *             create a new one.
     * @return The adapter position of the first visible item in each span. If a span does not have
     * any items, {@link RecyclerView#NO_POSITION} is returned for that span.
     * @see #findFirstCompletelyVisibleItemPositions(int[])
     * @see #findLastVisibleItemPositions(int[])
     */
    public int[] findFirstVisibleItemPositions(int[] into) {
        if (into == null) {
            into = new int[mSpanCount];
        } else if (into.length < mSpanCount) {
            throw new IllegalArgumentException("Provided int[]'s size must be more than or equal"
                    + " to span count. Expected:" + mSpanCount + ", array size:" + into.length);
        }
        for (int i = 0; i < mSpanCount; i++) {
            into[i] = mSpans[i].findFirstVisibleItemPosition();
        }
        return into;
    }

    /**
     * Returns the adapter position of the first completely visible view for each span.
     * <p>
     * Note that, this value is not affected by layout orientation or item order traversal.
     * ({@link #setReverseLayout(boolean)}). Views are sorted by their positions in the adapter,
     * not in the layout.
     * <p>
     * If RecyclerView has item decorators, they will be considered in calculations as well.
     * <p>
     * StaggeredGridLayoutManager may pre-cache some views that are not necessarily visible. Those
     * views are ignored in this method.
     *
     * @param into An array to put the results into. If you don't provide any, LayoutManager will
     *             create a new one.
     * @return The adapter position of the first fully visible item in each span. If a span does
     * not have any items, {@link RecyclerView#NO_POSITION} is returned for that span.
     * @see #findFirstVisibleItemPositions(int[])
     * @see #findLastCompletelyVisibleItemPositions(int[])
     */
    public int[] findFirstCompletelyVisibleItemPositions(int[] into) {
        if (into == null) {
            into = new int[mSpanCount];
        } else if (into.length < mSpanCount) {
            throw new IllegalArgumentException("Provided int[]'s size must be more than or equal"
                    + " to span count. Expected:" + mSpanCount + ", array size:" + into.length);
        }
        for (int i = 0; i < mSpanCount; i++) {
            into[i] = mSpans[i].findFirstCompletelyVisibleItemPosition();
        }
        return into;
    }

    /**
     * Returns the adapter position of the last visible view for each span.
     * <p>
     * Note that, this value is not affected by layout orientation or item order traversal.
     * ({@link #setReverseLayout(boolean)}). Views are sorted by their positions in the adapter,
     * not in the layout.
     * <p>
     * If RecyclerView has item decorators, they will be considered in calculations as well.
     * <p>
     * StaggeredGridLayoutManager may pre-cache some views that are not necessarily visible. Those
     * views are ignored in this method.
     *
     * @param into An array to put the results into. If you don't provide any, LayoutManager will
     *             create a new one.
     * @return The adapter position of the last visible item in each span. If a span does not have
     * any items, {@link RecyclerView#NO_POSITION} is returned for that span.
     * @see #findLastCompletelyVisibleItemPositions(int[])
     * @see #findFirstVisibleItemPositions(int[])
     */
    public int[] findLastVisibleItemPositions(int[] into) {
        if (into == null) {
            into = new int[mSpanCount];
        } else if (into.length < mSpanCount) {
            throw new IllegalArgumentException("Provided int[]'s size must be more than or equal"
                    + " to span count. Expected:" + mSpanCount + ", array size:" + into.length);
        }
        for (int i = 0; i < mSpanCount; i++) {
            into[i] = mSpans[i].findLastVisibleItemPosition();
        }
        return into;
    }

    /**
     * Returns the adapter position of the last completely visible view for each span.
     * <p>
     * Note that, this value is not affected by layout orientation or item order traversal.
     * ({@link #setReverseLayout(boolean)}). Views are sorted by their positions in the adapter,
     * not in the layout.
     * <p>
     * If RecyclerView has item decorators, they will be considered in calculations as well.
     * <p>
     * StaggeredGridLayoutManager may pre-cache some views that are not necessarily visible. Those
     * views are ignored in this method.
     *
     * @param into An array to put the results into. If you don't provide any, LayoutManager will
     *             create a new one.
     * @return The adapter position of the last fully visible item in each span. If a span does not
     * have any items, {@link RecyclerView#NO_POSITION} is returned for that span.
     * @see #findFirstCompletelyVisibleItemPositions(int[])
     * @see #findLastVisibleItemPositions(int[])
     */
    public int[] findLastCompletelyVisibleItemPositions(int[] into) {
        if (into == null) {
            into = new int[mSpanCount];
        } else if (into.length < mSpanCount) {
            throw new IllegalArgumentException("Provided int[]'s size must be more than or equal"
                    + " to span count. Expected:" + mSpanCount + ", array size:" + into.length);
        }
        for (int i = 0; i < mSpanCount; i++) {
            into[i] = mSpans[i].findLastCompletelyVisibleItemPosition();
        }
        return into;
    }

    @Override
    public int computeHorizontalScrollOffset(RecyclerView.State state) {
        return computeScrollOffset(state);
    }

    private int computeScrollOffset(RecyclerView.State state) {
        if (getChildCount() == 0) {
            return 0;
        }
        ensureOrientationHelper();
        return ScrollbarHelper.computeScrollOffset(state, mPrimaryOrientation,
                findFirstVisibleItemClosestToStart(!mSmoothScrollbarEnabled, true)
                , findFirstVisibleItemClosestToEnd(!mSmoothScrollbarEnabled, true),
                this, mSmoothScrollbarEnabled, mShouldReverseLayout);
    }

    @Override
    public int computeVerticalScrollOffset(RecyclerView.State state) {
        return computeScrollOffset(state);
    }

    @Override
    public int computeHorizontalScrollExtent(RecyclerView.State state) {
        return computeScrollExtent(state);
    }

    private int computeScrollExtent(RecyclerView.State state) {
        if (getChildCount() == 0) {
            return 0;
        }
        ensureOrientationHelper();
        return ScrollbarHelper.computeScrollExtent(state, mPrimaryOrientation,
                findFirstVisibleItemClosestToStart(!mSmoothScrollbarEnabled, true)
                , findFirstVisibleItemClosestToEnd(!mSmoothScrollbarEnabled, true),
                this, mSmoothScrollbarEnabled);
    }

    @Override
    public int computeVerticalScrollExtent(RecyclerView.State state) {
        return computeScrollExtent(state);
    }

    @Override
    public int computeHorizontalScrollRange(RecyclerView.State state) {
        return computeScrollRange(state);
    }

    private int computeScrollRange(RecyclerView.State state) {
        if (getChildCount() == 0) {
            return 0;
        }
        ensureOrientationHelper();
        return ScrollbarHelper.computeScrollRange(state, mPrimaryOrientation,
                findFirstVisibleItemClosestToStart(!mSmoothScrollbarEnabled, true)
                , findFirstVisibleItemClosestToEnd(!mSmoothScrollbarEnabled, true),
                this, mSmoothScrollbarEnabled);
    }

    @Override
    public int computeVerticalScrollRange(RecyclerView.State state) {
        return computeScrollRange(state);
    }

    private void measureChildWithDecorationsAndMargin(View child, LayoutParams lp) {
        if (lp.mFullSpan) {
            if (mOrientation == VERTICAL) {
                measureChildWithDecorationsAndMargin(child, mFullSizeSpec,
                        getSpecForDimension(lp.height, mHeightSpec));
            } else {
                measureChildWithDecorationsAndMargin(child,
                        getSpecForDimension(lp.width, mWidthSpec), mFullSizeSpec);
            }
        } else {
            if (mOrientation == VERTICAL) {
                measureChildWithDecorationsAndMargin(child, mWidthSpec,
                        getSpecForDimension(lp.height, mHeightSpec));
            } else {
                measureChildWithDecorationsAndMargin(child,
                        getSpecForDimension(lp.width, mWidthSpec), mHeightSpec);
            }
        }
    }

    private int getSpecForDimension(int dim, int defaultSpec) {
        if (dim < 0) {
            return defaultSpec;
        } else {
            return View.MeasureSpec.makeMeasureSpec(dim, View.MeasureSpec.EXACTLY);
        }
    }

    private void measureChildWithDecorationsAndMargin(View child, int widthSpec,
            int heightSpec) {
        calculateItemDecorationsForChild(child, mTmpRect);
        LayoutParams lp = (LayoutParams) child.getLayoutParams();
        widthSpec = updateSpecWithExtra(widthSpec, lp.leftMargin + mTmpRect.left,
                lp.rightMargin + mTmpRect.right);
        heightSpec = updateSpecWithExtra(heightSpec, lp.topMargin + mTmpRect.top,
                lp.bottomMargin + mTmpRect.bottom);
        child.measure(widthSpec, heightSpec);
    }

    private int updateSpecWithExtra(int spec, int startInset, int endInset) {
        if (startInset == 0 && endInset == 0) {
            return spec;
        }
        final int mode = View.MeasureSpec.getMode(spec);
        if (mode == View.MeasureSpec.AT_MOST || mode == View.MeasureSpec.EXACTLY) {
            return View.MeasureSpec.makeMeasureSpec(
                    View.MeasureSpec.getSize(spec) - startInset - endInset, mode);
        }
        return spec;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if (state instanceof SavedState) {
            mPendingSavedState = (SavedState) state;
            requestLayout();
        } else if (DEBUG) {
            Log.d(TAG, "invalid saved state class");
        }
    }

    @Override
    public Parcelable onSaveInstanceState() {
        if (mPendingSavedState != null) {
            return new SavedState(mPendingSavedState);
        }
        SavedState state = new SavedState();
        state.mReverseLayout = mReverseLayout;
        state.mAnchorLayoutFromEnd = mLastLayoutFromEnd;
        state.mLastLayoutRTL = mLastLayoutRTL;

        if (mLazySpanLookup != null && mLazySpanLookup.mData != null) {
            state.mSpanLookup = mLazySpanLookup.mData;
            state.mSpanLookupSize = state.mSpanLookup.length;
            state.mFullSpanItems = mLazySpanLookup.mFullSpanItems;
        } else {
            state.mSpanLookupSize = 0;
        }

        if (getChildCount() > 0) {
            ensureOrientationHelper();
            state.mAnchorPosition = mLastLayoutFromEnd ? getLastChildPosition()
                    : getFirstChildPosition();
            state.mVisibleAnchorPosition = findFirstVisibleItemPositionInt();
            state.mSpanOffsetsSize = mSpanCount;
            state.mSpanOffsets = new int[mSpanCount];
            for (int i = 0; i < mSpanCount; i++) {
                int line;
                if (mLastLayoutFromEnd) {
                    line = mSpans[i].getEndLine(Span.INVALID_LINE);
                    if (line != Span.INVALID_LINE) {
                        line -= mPrimaryOrientation.getEndAfterPadding();
                    }
                } else {
                    line = mSpans[i].getStartLine(Span.INVALID_LINE);
                    if (line != Span.INVALID_LINE) {
                        line -= mPrimaryOrientation.getStartAfterPadding();
                    }
                }
                state.mSpanOffsets[i] = line;
            }
        } else {
            state.mAnchorPosition = NO_POSITION;
            state.mVisibleAnchorPosition = NO_POSITION;
            state.mSpanOffsetsSize = 0;
        }
        if (DEBUG) {
            Log.d(TAG, "saved state:\n" + state);
        }
        return state;
    }

    @Override
    public void onInitializeAccessibilityNodeInfoForItem(RecyclerView.Recycler recycler,
            RecyclerView.State state, View host, AccessibilityNodeInfoCompat info) {
        ViewGroup.LayoutParams lp = host.getLayoutParams();
        if (!(lp instanceof LayoutParams)) {
            super.onInitializeAccessibilityNodeInfoForItem(host, info);
            return;
        }
        LayoutParams sglp = (LayoutParams) lp;
        if (mOrientation == HORIZONTAL) {
            info.setCollectionItemInfo(AccessibilityNodeInfoCompat.CollectionItemInfoCompat.obtain(
                    sglp.getSpanIndex(), sglp.mFullSpan ? mSpanCount : 1,
                    -1, -1,
                    sglp.mFullSpan, false));
        } else { // VERTICAL
            info.setCollectionItemInfo(AccessibilityNodeInfoCompat.CollectionItemInfoCompat.obtain(
                    -1, -1,
                    sglp.getSpanIndex(), sglp.mFullSpan ? mSpanCount : 1,
                    sglp.mFullSpan, false));
        }
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        if (getChildCount() > 0) {
            final AccessibilityRecordCompat record = AccessibilityEventCompat
                    .asRecord(event);
            final View start = findFirstVisibleItemClosestToStart(false, true);
            final View end = findFirstVisibleItemClosestToEnd(false, true);
            if (start == null || end == null) {
                return;
            }
            final int startPos = getPosition(start);
            final int endPos = getPosition(end);
            if (startPos < endPos) {
                record.setFromIndex(startPos);
                record.setToIndex(endPos);
            } else {
                record.setFromIndex(endPos);
                record.setToIndex(startPos);
            }
        }
    }

    /**
     * Finds the first fully visible child to be used as an anchor child if span count changes when
     * state is restored. If no children is fully visible, returns a partially visible child instead
     * of returning null.
     */
    int findFirstVisibleItemPositionInt() {
        final View first = mShouldReverseLayout ? findFirstVisibleItemClosestToEnd(true, true) :
                findFirstVisibleItemClosestToStart(true, true);
        return first == null ? NO_POSITION : getPosition(first);
    }

    @Override
    public int getRowCountForAccessibility(RecyclerView.Recycler recycler,
            RecyclerView.State state) {
        if (mOrientation == HORIZONTAL) {
            return mSpanCount;
        }
        return super.getRowCountForAccessibility(recycler, state);
    }

    @Override
    public int getColumnCountForAccessibility(RecyclerView.Recycler recycler,
            RecyclerView.State state) {
        if (mOrientation == VERTICAL) {
            return mSpanCount;
        }
        return super.getColumnCountForAccessibility(recycler, state);
    }

    /**
     * This is for internal use. Not necessarily the child closest to start but the first child
     * we find that matches the criteria.
     * This method does not do any sorting based on child's start coordinate, instead, it uses
     * children order.
     */
    View findFirstVisibleItemClosestToStart(boolean fullyVisible, boolean acceptPartiallyVisible) {
        ensureOrientationHelper();
        final int boundsStart = mPrimaryOrientation.getStartAfterPadding();
        final int boundsEnd = mPrimaryOrientation.getEndAfterPadding();
        final int limit = getChildCount();
        View partiallyVisible = null;
        for (int i = 0; i < limit; i++) {
            final View child = getChildAt(i);
            final int childStart = mPrimaryOrientation.getDecoratedStart(child);
            final int childEnd = mPrimaryOrientation.getDecoratedEnd(child);
            if(childEnd <= boundsStart || childStart >= boundsEnd) {
                continue; // not visible at all
            }
            if (childStart >= boundsStart || !fullyVisible) {
                // when checking for start, it is enough even if part of the child's top is visible
                // as long as fully visible is not requested.
                return child;
            }
            if (acceptPartiallyVisible && partiallyVisible == null) {
                partiallyVisible = child;
            }
        }
        return partiallyVisible;
    }

    /**
     * This is for internal use. Not necessarily the child closest to bottom but the first child
     * we find that matches the criteria.
     * This method does not do any sorting based on child's end coordinate, instead, it uses
     * children order.
     */
    View findFirstVisibleItemClosestToEnd(boolean fullyVisible, boolean acceptPartiallyVisible) {
        ensureOrientationHelper();
        final int boundsStart = mPrimaryOrientation.getStartAfterPadding();
        final int boundsEnd = mPrimaryOrientation.getEndAfterPadding();
        View partiallyVisible = null;
        for (int i = getChildCount() - 1; i >= 0; i--) {
            final View child = getChildAt(i);
            final int childStart = mPrimaryOrientation.getDecoratedStart(child);
            final int childEnd = mPrimaryOrientation.getDecoratedEnd(child);
            if(childEnd <= boundsStart || childStart >= boundsEnd) {
                continue; // not visible at all
            }
            if (childEnd <= boundsEnd || !fullyVisible) {
                // when checking for end, it is enough even if part of the child's bottom is visible
                // as long as fully visible is not requested.
                return child;
            }
            if (acceptPartiallyVisible && partiallyVisible == null) {
                partiallyVisible = child;
            }
        }
        return partiallyVisible;
    }

    private void fixEndGap(RecyclerView.Recycler recycler, RecyclerView.State state,
            boolean canOffsetChildren) {
        final int maxEndLine = getMaxEnd(mPrimaryOrientation.getEndAfterPadding());
        int gap = mPrimaryOrientation.getEndAfterPadding() - maxEndLine;
        int fixOffset;
        if (gap > 0) {
            fixOffset = -scrollBy(-gap, recycler, state);
        } else {
            return; // nothing to fix
        }
        gap -= fixOffset;
        if (canOffsetChildren && gap > 0) {
            mPrimaryOrientation.offsetChildren(gap);
        }
    }

    private void fixStartGap(RecyclerView.Recycler recycler, RecyclerView.State state,
            boolean canOffsetChildren) {
        final int minStartLine = getMinStart(mPrimaryOrientation.getStartAfterPadding());
        int gap = minStartLine - mPrimaryOrientation.getStartAfterPadding();
        int fixOffset;
        if (gap > 0) {
            fixOffset = scrollBy(gap, recycler, state);
        } else {
            return; // nothing to fix
        }
        gap -= fixOffset;
        if (canOffsetChildren && gap > 0) {
            mPrimaryOrientation.offsetChildren(-gap);
        }
    }

    private void updateLayoutStateToFillStart(int anchorPosition, RecyclerView.State state) {
        mLayoutState.mAvailable = 0;
        mLayoutState.mCurrentPosition = anchorPosition;
        if (isSmoothScrolling()) {
            final int targetPos = state.getTargetScrollPosition();
            if (mShouldReverseLayout == targetPos < anchorPosition) {
                mLayoutState.mExtra = 0;
            } else {
                mLayoutState.mExtra = mPrimaryOrientation.getTotalSpace();
            }
        } else {
            mLayoutState.mExtra = 0;
        }
        mLayoutState.mLayoutDirection = LAYOUT_START;
        mLayoutState.mItemDirection = mShouldReverseLayout ? ITEM_DIRECTION_TAIL
                : ITEM_DIRECTION_HEAD;
    }

    private void updateLayoutStateToFillEnd(int anchorPosition, RecyclerView.State state) {
        mLayoutState.mAvailable = 0;
        mLayoutState.mCurrentPosition = anchorPosition;
        if (isSmoothScrolling()) {
            final int targetPos = state.getTargetScrollPosition();
            if (mShouldReverseLayout == targetPos > anchorPosition) {
                mLayoutState.mExtra = 0;
            } else {
                mLayoutState.mExtra = mPrimaryOrientation.getTotalSpace();
            }
        } else {
            mLayoutState.mExtra = 0;
        }
        mLayoutState.mLayoutDirection = LAYOUT_END;
        mLayoutState.mItemDirection = mShouldReverseLayout ? ITEM_DIRECTION_HEAD
                : ITEM_DIRECTION_TAIL;
    }

    @Override
    public void offsetChildrenHorizontal(int dx) {
        super.offsetChildrenHorizontal(dx);
        for (int i = 0; i < mSpanCount; i++) {
            mSpans[i].onOffset(dx);
        }
    }

    @Override
    public void offsetChildrenVertical(int dy) {
        super.offsetChildrenVertical(dy);
        for (int i = 0; i < mSpanCount; i++) {
            mSpans[i].onOffset(dy);
        }
    }

    @Override
    public void onItemsRemoved(RecyclerView recyclerView, int positionStart, int itemCount) {
        handleUpdate(positionStart, itemCount, AdapterHelper.UpdateOp.REMOVE);
    }

    @Override
    public void onItemsAdded(RecyclerView recyclerView, int positionStart, int itemCount) {
        handleUpdate(positionStart, itemCount, AdapterHelper.UpdateOp.ADD);
    }

    @Override
    public void onItemsChanged(RecyclerView recyclerView) {
        mLazySpanLookup.clear();
        requestLayout();
    }

    @Override
    public void onItemsMoved(RecyclerView recyclerView, int from, int to, int itemCount) {
        handleUpdate(from, to, AdapterHelper.UpdateOp.MOVE);
    }

    @Override
    public void onItemsUpdated(RecyclerView recyclerView, int positionStart, int itemCount) {
        handleUpdate(positionStart, itemCount, AdapterHelper.UpdateOp.UPDATE);
    }

    /**
     * Checks whether it should invalidate span assignments in response to an adapter change.
     */
    private void handleUpdate(int positionStart, int itemCountOrToPosition, int cmd) {
        int minPosition = mShouldReverseLayout ? getLastChildPosition() : getFirstChildPosition();
        final int affectedRangeEnd;// exclusive
        final int affectedRangeStart;// inclusive

        if (cmd == AdapterHelper.UpdateOp.MOVE) {
            if (positionStart < itemCountOrToPosition) {
                affectedRangeEnd = itemCountOrToPosition + 1;
                affectedRangeStart = positionStart;
            } else {
                affectedRangeEnd = positionStart + 1;
                affectedRangeStart = itemCountOrToPosition;
            }
        } else {
            affectedRangeStart = positionStart;
            affectedRangeEnd = positionStart + itemCountOrToPosition;
        }

        mLazySpanLookup.invalidateAfter(affectedRangeStart);
        switch (cmd) {
            case AdapterHelper.UpdateOp.ADD:
                mLazySpanLookup.offsetForAddition(positionStart, itemCountOrToPosition);
                break;
            case AdapterHelper.UpdateOp.REMOVE:
                mLazySpanLookup.offsetForRemoval(positionStart, itemCountOrToPosition);
                break;
            case AdapterHelper.UpdateOp.MOVE:
                // TODO optimize
                mLazySpanLookup.offsetForRemoval(positionStart, 1);
                mLazySpanLookup.offsetForAddition(itemCountOrToPosition, 1);
                break;
        }

        if (affectedRangeEnd <= minPosition) {
            return;
        }

        int maxPosition = mShouldReverseLayout ? getFirstChildPosition() : getLastChildPosition();
        if (affectedRangeStart <= maxPosition) {
            requestLayout();
        }
    }

    private int fill(RecyclerView.Recycler recycler, LayoutState layoutState,
            RecyclerView.State state) {
        mRemainingSpans.set(0, mSpanCount, true);
        // The target position we are trying to reach.
        final int targetLine;
        /*
        * The line until which we can recycle, as long as we add views.
        * Keep in mind, it is still the line in layout direction which means; to calculate the
        * actual recycle line, we should subtract/add the size in orientation.
        */
        final int recycleLine;
        // Line of the furthest row.
        if (layoutState.mLayoutDirection == LAYOUT_END) {
            // ignore padding for recycler
            recycleLine = mPrimaryOrientation.getEndAfterPadding() + mLayoutState.mAvailable;
            targetLine = recycleLine + mLayoutState.mExtra + mPrimaryOrientation.getEndPadding();

        } else { // LAYOUT_START
            // ignore padding for recycler
            recycleLine = mPrimaryOrientation.getStartAfterPadding() - mLayoutState.mAvailable;
            targetLine = recycleLine - mLayoutState.mExtra -
                    mPrimaryOrientation.getStartAfterPadding();
        }
        updateAllRemainingSpans(layoutState.mLayoutDirection, targetLine);

        // the default coordinate to add new view.
        final int defaultNewViewLine = mShouldReverseLayout
                ? mPrimaryOrientation.getEndAfterPadding()
                : mPrimaryOrientation.getStartAfterPadding();

        while (layoutState.hasMore(state) && !mRemainingSpans.isEmpty()) {
            View view = layoutState.next(recycler);
            LayoutParams lp = ((LayoutParams) view.getLayoutParams());
            final int position = lp.getViewLayoutPosition();
            final int spanIndex = mLazySpanLookup.getSpan(position);
            Span currentSpan;
            final boolean assignSpan = spanIndex == LayoutParams.INVALID_SPAN_ID;
            if (assignSpan) {
                currentSpan = lp.mFullSpan ? mSpans[0] : getNextSpan(layoutState);
                mLazySpanLookup.setSpan(position, currentSpan);
                if (DEBUG) {
                    Log.d(TAG, "assigned " + currentSpan.mIndex + " for " + position);
                }
            } else {
                if (DEBUG) {
                    Log.d(TAG, "using " + spanIndex + " for pos " + position);
                }
                currentSpan = mSpans[spanIndex];
            }
            // assign span before measuring so that item decorators can get updated span index
            lp.mSpan = currentSpan;
            if (layoutState.mLayoutDirection == LAYOUT_END) {
                addView(view);
            } else {
                addView(view, 0);
            }
            measureChildWithDecorationsAndMargin(view, lp);

            final int start;
            final int end;
            if (layoutState.mLayoutDirection == LAYOUT_END) {
                start = lp.mFullSpan ? getMaxEnd(defaultNewViewLine)
                        : currentSpan.getEndLine(defaultNewViewLine);
                end = start + mPrimaryOrientation.getDecoratedMeasurement(view);
                if (assignSpan && lp.mFullSpan) {
                    LazySpanLookup.FullSpanItem fullSpanItem;
                    fullSpanItem = createFullSpanItemFromEnd(start);
                    fullSpanItem.mGapDir = LAYOUT_START;
                    fullSpanItem.mPosition = position;
                    mLazySpanLookup.addFullSpanItem(fullSpanItem);
                }
            } else {
                end = lp.mFullSpan ? getMinStart(defaultNewViewLine)
                        : currentSpan.getStartLine(defaultNewViewLine);
                start = end - mPrimaryOrientation.getDecoratedMeasurement(view);
                if (assignSpan && lp.mFullSpan) {
                    LazySpanLookup.FullSpanItem fullSpanItem;
                    fullSpanItem = createFullSpanItemFromStart(end);
                    fullSpanItem.mGapDir = LAYOUT_END;
                    fullSpanItem.mPosition = position;
                    mLazySpanLookup.addFullSpanItem(fullSpanItem);
                }
            }

            // check if this item may create gaps in the future
            if (lp.mFullSpan && layoutState.mItemDirection == ITEM_DIRECTION_HEAD) {
                if (assignSpan) {
                    mLaidOutInvalidFullSpan = true;
                } else {
                    final boolean hasInvalidGap;
                    if (layoutState.mLayoutDirection == LAYOUT_END) {
                        hasInvalidGap = !areAllEndsEqual();
                    } else { // layoutState.mLayoutDirection == LAYOUT_START
                        hasInvalidGap = !areAllStartsEqual();
                    }
                    if (hasInvalidGap) {
                        final LazySpanLookup.FullSpanItem fullSpanItem = mLazySpanLookup
                                .getFullSpanItem(position);
                        if (fullSpanItem != null) {
                            fullSpanItem.mHasUnwantedGapAfter = true;
                        }
                        mLaidOutInvalidFullSpan = true;
                    }
                }

            }
            attachViewToSpans(view, lp, layoutState);
            final int otherStart = lp.mFullSpan ? mSecondaryOrientation.getStartAfterPadding()
                    : currentSpan.mIndex * mSizePerSpan +
                            mSecondaryOrientation.getStartAfterPadding();
            final int otherEnd = otherStart + mSecondaryOrientation.getDecoratedMeasurement(view);
            if (mOrientation == VERTICAL) {
                layoutDecoratedWithMargins(view, otherStart, start, otherEnd, end);
            } else {
                layoutDecoratedWithMargins(view, start, otherStart, end, otherEnd);
            }

            if (lp.mFullSpan) {
                updateAllRemainingSpans(mLayoutState.mLayoutDirection, targetLine);
            } else {
                updateRemainingSpans(currentSpan, mLayoutState.mLayoutDirection, targetLine);
            }
            recycle(recycler, mLayoutState, currentSpan, recycleLine);
        }
        if (DEBUG) {
            Log.d(TAG, "fill, " + getChildCount());
        }
        if (mLayoutState.mLayoutDirection == LAYOUT_START) {
            final int minStart = getMinStart(mPrimaryOrientation.getStartAfterPadding());
            return Math.max(0, mLayoutState.mAvailable + (recycleLine - minStart));
        } else {
            final int max = getMaxEnd(mPrimaryOrientation.getEndAfterPadding());
            return Math.max(0, mLayoutState.mAvailable + (max - recycleLine));
        }
    }

    private LazySpanLookup.FullSpanItem createFullSpanItemFromEnd(int newItemTop) {
        LazySpanLookup.FullSpanItem fsi = new LazySpanLookup.FullSpanItem();
        fsi.mGapPerSpan = new int[mSpanCount];
        for (int i = 0; i < mSpanCount; i++) {
            fsi.mGapPerSpan[i] = newItemTop - mSpans[i].getEndLine(newItemTop);
        }
        return fsi;
    }

    private LazySpanLookup.FullSpanItem createFullSpanItemFromStart(int newItemBottom) {
        LazySpanLookup.FullSpanItem fsi = new LazySpanLookup.FullSpanItem();
        fsi.mGapPerSpan = new int[mSpanCount];
        for (int i = 0; i < mSpanCount; i++) {
            fsi.mGapPerSpan[i] = mSpans[i].getStartLine(newItemBottom) - newItemBottom;
        }
        return fsi;
    }

    private void attachViewToSpans(View view, LayoutParams lp, LayoutState layoutState) {
        if (layoutState.mLayoutDirection == LayoutState.LAYOUT_END) {
            if (lp.mFullSpan) {
                appendViewToAllSpans(view);
            } else {
                lp.mSpan.appendToSpan(view);
            }
        } else {
            if (lp.mFullSpan) {
                prependViewToAllSpans(view);
            } else {
                lp.mSpan.prependToSpan(view);
            }
        }
    }

    private void recycle(RecyclerView.Recycler recycler, LayoutState layoutState,
            Span updatedSpan, int recycleLine) {
        if (layoutState.mLayoutDirection == LAYOUT_START) {
            // calculate recycle line
            int maxStart = getMaxStart(updatedSpan.getStartLine());
            recycleFromEnd(recycler, Math.max(recycleLine, maxStart) +
                    (mPrimaryOrientation.getEnd() - mPrimaryOrientation.getStartAfterPadding()));
        } else {
            // calculate recycle line
            int minEnd = getMinEnd(updatedSpan.getEndLine());
            recycleFromStart(recycler, Math.min(recycleLine, minEnd) -
                    (mPrimaryOrientation.getEnd() - mPrimaryOrientation.getStartAfterPadding()));
        }
    }

    private void appendViewToAllSpans(View view) {
        // traverse in reverse so that we end up assigning full span items to 0
        for (int i = mSpanCount - 1; i >= 0; i--) {
            mSpans[i].appendToSpan(view);
        }
    }

    private void prependViewToAllSpans(View view) {
        // traverse in reverse so that we end up assigning full span items to 0
        for (int i = mSpanCount - 1; i >= 0; i--) {
            mSpans[i].prependToSpan(view);
        }
    }

    private void layoutDecoratedWithMargins(View child, int left, int top, int right, int bottom) {
        LayoutParams lp = (LayoutParams) child.getLayoutParams();
        if (DEBUG) {
            Log.d(TAG, "layout decorated pos: " + lp.getViewLayoutPosition() + ", span:"
                    + lp.getSpanIndex() + ", fullspan:" + lp.mFullSpan
                    + ". l:" + left + ",t:" + top
                    + ", r:" + right + ", b:" + bottom);
        }
        layoutDecorated(child, left + lp.leftMargin, top + lp.topMargin, right - lp.rightMargin
                , bottom - lp.bottomMargin);
    }

    private void updateAllRemainingSpans(int layoutDir, int targetLine) {
        for (int i = 0; i < mSpanCount; i++) {
            if (mSpans[i].mViews.isEmpty()) {
                continue;
            }
            updateRemainingSpans(mSpans[i], layoutDir, targetLine);
        }
    }

    private void updateRemainingSpans(Span span, int layoutDir, int targetLine) {
        final int deletedSize = span.getDeletedSize();
        if (layoutDir == LAYOUT_START) {
            final int line = span.getStartLine();
            if (line + deletedSize < targetLine) {
                mRemainingSpans.set(span.mIndex, false);
            }
        } else {
            final int line = span.getEndLine();
            if (line - deletedSize > targetLine) {
                mRemainingSpans.set(span.mIndex, false);
            }
        }
    }

    private int getMaxStart(int def) {
        int maxStart = mSpans[0].getStartLine(def);
        for (int i = 1; i < mSpanCount; i++) {
            final int spanStart = mSpans[i].getStartLine(def);
            if (spanStart > maxStart) {
                maxStart = spanStart;
            }
        }
        return maxStart;
    }

    private int getMinStart(int def) {
        int minStart = mSpans[0].getStartLine(def);
        for (int i = 1; i < mSpanCount; i++) {
            final int spanStart = mSpans[i].getStartLine(def);
            if (spanStart < minStart) {
                minStart = spanStart;
            }
        }
        return minStart;
    }

    boolean areAllEndsEqual() {
        int end = mSpans[0].getEndLine(Span.INVALID_LINE);
        for (int i = 1; i < mSpanCount; i++) {
            if (mSpans[i].getEndLine(Span.INVALID_LINE) != end) {
                return false;
            }
        }
        return true;
    }

    boolean areAllStartsEqual() {
        int start = mSpans[0].getStartLine(Span.INVALID_LINE);
        for (int i = 1; i < mSpanCount; i++) {
            if (mSpans[i].getStartLine(Span.INVALID_LINE) != start) {
                return false;
            }
        }
        return true;
    }

    private int getMaxEnd(int def) {
        int maxEnd = mSpans[0].getEndLine(def);
        for (int i = 1; i < mSpanCount; i++) {
            final int spanEnd = mSpans[i].getEndLine(def);
            if (spanEnd > maxEnd) {
                maxEnd = spanEnd;
            }
        }
        return maxEnd;
    }

    private int getMinEnd(int def) {
        int minEnd = mSpans[0].getEndLine(def);
        for (int i = 1; i < mSpanCount; i++) {
            final int spanEnd = mSpans[i].getEndLine(def);
            if (spanEnd < minEnd) {
                minEnd = spanEnd;
            }
        }
        return minEnd;
    }

    private void recycleFromStart(RecyclerView.Recycler recycler, int line) {
        if (DEBUG) {
            Log.d(TAG, "recycling from start for line " + line);
        }
        while (getChildCount() > 0) {
            View child = getChildAt(0);
            if (mPrimaryOrientation.getDecoratedEnd(child) < line) {
                LayoutParams lp = (LayoutParams) child.getLayoutParams();
                if (lp.mFullSpan) {
                    for (int j = 0; j < mSpanCount; j++) {
                        mSpans[j].popStart();
                    }
                } else {
                    lp.mSpan.popStart();
                }
                removeAndRecycleView(child, recycler);
            } else {
                return;// done
            }
        }
    }

    private void recycleFromEnd(RecyclerView.Recycler recycler, int line) {
        final int childCount = getChildCount();
        int i;
        for (i = childCount - 1; i >= 0; i--) {
            View child = getChildAt(i);
            if (mPrimaryOrientation.getDecoratedStart(child) > line) {
                LayoutParams lp = (LayoutParams) child.getLayoutParams();
                if (lp.mFullSpan) {
                    for (int j = 0; j < mSpanCount; j++) {
                        mSpans[j].popEnd();
                    }
                } else {
                    lp.mSpan.popEnd();
                }
                removeAndRecycleView(child, recycler);
            } else {
                return;// done
            }
        }
    }

    /**
     * @return True if last span is the first one we want to fill
     */
    private boolean preferLastSpan(int layoutDir) {
        if (mOrientation == HORIZONTAL) {
            return (layoutDir == LAYOUT_START) != mShouldReverseLayout;
        }
        return ((layoutDir == LAYOUT_START) == mShouldReverseLayout) == isLayoutRTL();
    }

    /**
     * Finds the span for the next view.
     */
    private Span getNextSpan(LayoutState layoutState) {
        final boolean preferLastSpan = preferLastSpan(layoutState.mLayoutDirection);
        final int startIndex, endIndex, diff;
        if (preferLastSpan) {
            startIndex = mSpanCount - 1;
            endIndex = -1;
            diff = -1;
        } else {
            startIndex = 0;
            endIndex = mSpanCount;
            diff = 1;
        }
        if (layoutState.mLayoutDirection == LAYOUT_END) {
            Span min = null;
            int minLine = Integer.MAX_VALUE;
            final int defaultLine = mPrimaryOrientation.getStartAfterPadding();
            for (int i = startIndex; i != endIndex; i += diff) {
                final Span other = mSpans[i];
                int otherLine = other.getEndLine(defaultLine);
                if (otherLine < minLine) {
                    min = other;
                    minLine = otherLine;
                }
            }
            return min;
        } else {
            Span max = null;
            int maxLine = Integer.MIN_VALUE;
            final int defaultLine = mPrimaryOrientation.getEndAfterPadding();
            for (int i = startIndex; i != endIndex; i += diff) {
                final Span other = mSpans[i];
                int otherLine = other.getStartLine(defaultLine);
                if (otherLine > maxLine) {
                    max = other;
                    maxLine = otherLine;
                }
            }
            return max;
        }
    }

    @Override
    public boolean canScrollVertically() {
        return mOrientation == VERTICAL;
    }

    @Override
    public boolean canScrollHorizontally() {
        return mOrientation == HORIZONTAL;
    }

    @Override
    public int scrollHorizontallyBy(int dx, RecyclerView.Recycler recycler,
            RecyclerView.State state) {
        return scrollBy(dx, recycler, state);
    }

    @Override
    public int scrollVerticallyBy(int dy, RecyclerView.Recycler recycler,
            RecyclerView.State state) {
        return scrollBy(dy, recycler, state);
    }

    private int calculateScrollDirectionForPosition(int position) {
        if (getChildCount() == 0) {
            return mShouldReverseLayout ? LAYOUT_END : LAYOUT_START;
        }
        final int firstChildPos = getFirstChildPosition();
        return position < firstChildPos != mShouldReverseLayout ? LAYOUT_START : LAYOUT_END;
    }

    @Override
    public void smoothScrollToPosition(RecyclerView recyclerView, RecyclerView.State state,
            int position) {
        LinearSmoothScroller scroller = new LinearSmoothScroller(recyclerView.getContext()) {
            @Override
            public PointF computeScrollVectorForPosition(int targetPosition) {
                final int direction = calculateScrollDirectionForPosition(targetPosition);
                if (direction == 0) {
                    return null;
                }
                if (mOrientation == HORIZONTAL) {
                    return new PointF(direction, 0);
                } else {
                    return new PointF(0, direction);
                }
            }
        };
        scroller.setTargetPosition(position);
        startSmoothScroll(scroller);
    }

    @Override
    public void scrollToPosition(int position) {
        if (mPendingSavedState != null && mPendingSavedState.mAnchorPosition != position) {
            mPendingSavedState.invalidateAnchorPositionInfo();
        }
        mPendingScrollPosition = position;
        mPendingScrollPositionOffset = INVALID_OFFSET;
        requestLayout();
    }

    /**
     * Scroll to the specified adapter position with the given offset from layout start.
     * <p>
     * Note that scroll position change will not be reflected until the next layout call.
     * <p>
     * If you are just trying to make a position visible, use {@link #scrollToPosition(int)}.
     *
     * @param position Index (starting at 0) of the reference item.
     * @param offset   The distance (in pixels) between the start edge of the item view and
     *                 start edge of the RecyclerView.
     * @see #setReverseLayout(boolean)
     * @see #scrollToPosition(int)
     */
    public void scrollToPositionWithOffset(int position, int offset) {
        if (mPendingSavedState != null) {
            mPendingSavedState.invalidateAnchorPositionInfo();
        }
        mPendingScrollPosition = position;
        mPendingScrollPositionOffset = offset;
        requestLayout();
    }

    int scrollBy(int dt, RecyclerView.Recycler recycler, RecyclerView.State state) {
        ensureOrientationHelper();
        final int referenceChildPosition;
        if (dt > 0) { // layout towards end
            mLayoutState.mLayoutDirection = LAYOUT_END;
            mLayoutState.mItemDirection = mShouldReverseLayout ? ITEM_DIRECTION_HEAD
                    : ITEM_DIRECTION_TAIL;
            referenceChildPosition = getLastChildPosition();
        } else {
            mLayoutState.mLayoutDirection = LAYOUT_START;
            mLayoutState.mItemDirection = mShouldReverseLayout ? ITEM_DIRECTION_TAIL
                    : ITEM_DIRECTION_HEAD;
            referenceChildPosition = getFirstChildPosition();
        }
        mLayoutState.mCurrentPosition = referenceChildPosition + mLayoutState.mItemDirection;
        final int absDt = Math.abs(dt);
        mLayoutState.mAvailable = absDt;
        mLayoutState.mExtra = isSmoothScrolling() ? mPrimaryOrientation.getTotalSpace() : 0;
        int consumed = fill(recycler, mLayoutState, state);
        final int totalScroll;
        if (absDt < consumed) {
            totalScroll = dt;
        } else if (dt < 0) {
            totalScroll = -consumed;
        } else { // dt > 0
            totalScroll = consumed;
        }
        if (DEBUG) {
            Log.d(TAG, "asked " + dt + " scrolled" + totalScroll);
        }

        mPrimaryOrientation.offsetChildren(-totalScroll);
        // always reset this if we scroll for a proper save instance state
        mLastLayoutFromEnd = mShouldReverseLayout;
        return totalScroll;
    }

    private int getLastChildPosition() {
        final int childCount = getChildCount();
        return childCount == 0 ? 0 : getPosition(getChildAt(childCount - 1));
    }

    private int getFirstChildPosition() {
        final int childCount = getChildCount();
        return childCount == 0 ? 0 : getPosition(getChildAt(0));
    }

    /**
     * Finds the first View that can be used as an anchor View.
     *
     * @return Position of the View or 0 if it cannot find any such View.
     */
    private int findFirstReferenceChildPosition(int itemCount) {
        final int limit = getChildCount();
        for (int i = 0; i < limit; i++) {
            final View view = getChildAt(i);
            final int position = getPosition(view);
            if (position >= 0 && position < itemCount) {
                return position;
            }
        }
        return 0;
    }

    /**
     * Finds the last View that can be used as an anchor View.
     *
     * @return Position of the View or 0 if it cannot find any such View.
     */
    private int findLastReferenceChildPosition(int itemCount) {
        for (int i = getChildCount() - 1; i >= 0; i--) {
            final View view = getChildAt(i);
            final int position = getPosition(view);
            if (position >= 0 && position < itemCount) {
                return position;
            }
        }
        return 0;
    }

    @Override
    public RecyclerView.LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    @Override
    public RecyclerView.LayoutParams generateLayoutParams(Context c, AttributeSet attrs) {
        return new LayoutParams(c, attrs);
    }

    @Override
    public RecyclerView.LayoutParams generateLayoutParams(ViewGroup.LayoutParams lp) {
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            return new LayoutParams((ViewGroup.MarginLayoutParams) lp);
        } else {
            return new LayoutParams(lp);
        }
    }

    @Override
    public boolean checkLayoutParams(RecyclerView.LayoutParams lp) {
        return lp instanceof LayoutParams;
    }

    public int getOrientation() {
        return mOrientation;
    }


    /**
     * LayoutParams used by StaggeredGridLayoutManager.
     * <p>
     * Note that if the orientation is {@link #VERTICAL}, the width parameter is ignored and if the
     * orientation is {@link #HORIZONTAL} the height parameter is ignored because child view is
     * expected to fill all of the space given to it.
     */
    public static class LayoutParams extends RecyclerView.LayoutParams {

        /**
         * Span Id for Views that are not laid out yet.
         */
        public static final int INVALID_SPAN_ID = -1;

        // Package scope to be able to access from tests.
        Span mSpan;

        boolean mFullSpan;

        public LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);
        }

        public LayoutParams(int width, int height) {
            super(width, height);
        }

        public LayoutParams(ViewGroup.MarginLayoutParams source) {
            super(source);
        }

        public LayoutParams(ViewGroup.LayoutParams source) {
            super(source);
        }

        public LayoutParams(RecyclerView.LayoutParams source) {
            super(source);
        }

        /**
         * When set to true, the item will layout using all span area. That means, if orientation
         * is vertical, the view will have full width; if orientation is horizontal, the view will
         * have full height.
         *
         * @param fullSpan True if this item should traverse all spans.
         * @see #isFullSpan()
         */
        public void setFullSpan(boolean fullSpan) {
            mFullSpan = fullSpan;
        }

        /**
         * Returns whether this View occupies all available spans or just one.
         *
         * @return True if the View occupies all spans or false otherwise.
         * @see #setFullSpan(boolean)
         */
        public boolean isFullSpan() {
            return mFullSpan;
        }

        /**
         * Returns the Span index to which this View is assigned.
         *
         * @return The Span index of the View. If View is not yet assigned to any span, returns
         * {@link #INVALID_SPAN_ID}.
         */
        public final int getSpanIndex() {
            if (mSpan == null) {
                return INVALID_SPAN_ID;
            }
            return mSpan.mIndex;
        }
    }

    // Package scoped to access from tests.
    class Span {

        static final int INVALID_LINE = Integer.MIN_VALUE;
        private ArrayList<View> mViews = new ArrayList<View>();
        int mCachedStart = INVALID_LINE;
        int mCachedEnd = INVALID_LINE;
        int mDeletedSize = 0;
        final int mIndex;

        private Span(int index) {
            mIndex = index;
        }

        int getStartLine(int def) {
            if (mCachedStart != INVALID_LINE) {
                return mCachedStart;
            }
            if (mViews.size() == 0) {
                return def;
            }
            calculateCachedStart();
            return mCachedStart;
        }

        void calculateCachedStart() {
            final View startView = mViews.get(0);
            final LayoutParams lp = getLayoutParams(startView);
            mCachedStart = mPrimaryOrientation.getDecoratedStart(startView);
            if (lp.mFullSpan) {
                LazySpanLookup.FullSpanItem fsi = mLazySpanLookup
                        .getFullSpanItem(lp.getViewLayoutPosition());
                if (fsi != null && fsi.mGapDir == LAYOUT_START) {
                    mCachedStart -= fsi.getGapForSpan(mIndex);
                }
            }
        }

        // Use this one when default value does not make sense and not having a value means a bug.
        int getStartLine() {
            if (mCachedStart != INVALID_LINE) {
                return mCachedStart;
            }
            calculateCachedStart();
            return mCachedStart;
        }

        int getEndLine(int def) {
            if (mCachedEnd != INVALID_LINE) {
                return mCachedEnd;
            }
            final int size = mViews.size();
            if (size == 0) {
                return def;
            }
            calculateCachedEnd();
            return mCachedEnd;
        }

        void calculateCachedEnd() {
            final View endView = mViews.get(mViews.size() - 1);
            final LayoutParams lp = getLayoutParams(endView);
            mCachedEnd = mPrimaryOrientation.getDecoratedEnd(endView);
            if (lp.mFullSpan) {
                LazySpanLookup.FullSpanItem fsi = mLazySpanLookup
                        .getFullSpanItem(lp.getViewLayoutPosition());
                if (fsi != null && fsi.mGapDir == LAYOUT_END) {
                    mCachedEnd += fsi.getGapForSpan(mIndex);
                }
            }
        }

        // Use this one when default value does not make sense and not having a value means a bug.
        int getEndLine() {
            if (mCachedEnd != INVALID_LINE) {
                return mCachedEnd;
            }
            calculateCachedEnd();
            return mCachedEnd;
        }

        void prependToSpan(View view) {
            LayoutParams lp = getLayoutParams(view);
            lp.mSpan = this;
            mViews.add(0, view);
            mCachedStart = INVALID_LINE;
            if (mViews.size() == 1) {
                mCachedEnd = INVALID_LINE;
            }
            if (lp.isItemRemoved() || lp.isItemChanged()) {
                mDeletedSize += mPrimaryOrientation.getDecoratedMeasurement(view);
            }
        }

        void appendToSpan(View view) {
            LayoutParams lp = getLayoutParams(view);
            lp.mSpan = this;
            mViews.add(view);
            mCachedEnd = INVALID_LINE;
            if (mViews.size() == 1) {
                mCachedStart = INVALID_LINE;
            }
            if (lp.isItemRemoved() || lp.isItemChanged()) {
                mDeletedSize += mPrimaryOrientation.getDecoratedMeasurement(view);
            }
        }

        // Useful method to preserve positions on a re-layout.
        void cacheReferenceLineAndClear(boolean reverseLayout, int offset) {
            int reference;
            if (reverseLayout) {
                reference = getEndLine(INVALID_LINE);
            } else {
                reference = getStartLine(INVALID_LINE);
            }
            clear();
            if (reference == INVALID_LINE) {
                return;
            }
            if ((reverseLayout && reference < mPrimaryOrientation.getEndAfterPadding()) ||
                    (!reverseLayout && reference > mPrimaryOrientation.getStartAfterPadding())) {
                return;
            }
            if (offset != INVALID_OFFSET) {
                reference += offset;
            }
            mCachedStart = mCachedEnd = reference;
        }

        void clear() {
            mViews.clear();
            invalidateCache();
            mDeletedSize = 0;
        }

        void invalidateCache() {
            mCachedStart = INVALID_LINE;
            mCachedEnd = INVALID_LINE;
        }

        void setLine(int line) {
            mCachedEnd = mCachedStart = line;
        }

        void popEnd() {
            final int size = mViews.size();
            View end = mViews.remove(size - 1);
            final LayoutParams lp = getLayoutParams(end);
            lp.mSpan = null;
            if (lp.isItemRemoved() || lp.isItemChanged()) {
                mDeletedSize -= mPrimaryOrientation.getDecoratedMeasurement(end);
            }
            if (size == 1) {
                mCachedStart = INVALID_LINE;
            }
            mCachedEnd = INVALID_LINE;
        }

        void popStart() {
            View start = mViews.remove(0);
            final LayoutParams lp = getLayoutParams(start);
            lp.mSpan = null;
            if (mViews.size() == 0) {
                mCachedEnd = INVALID_LINE;
            }
            if (lp.isItemRemoved() || lp.isItemChanged()) {
                mDeletedSize -= mPrimaryOrientation.getDecoratedMeasurement(start);
            }
            mCachedStart = INVALID_LINE;
        }

        public int getDeletedSize() {
            return mDeletedSize;
        }

        LayoutParams getLayoutParams(View view) {
            return (LayoutParams) view.getLayoutParams();
        }

        void onOffset(int dt) {
            if (mCachedStart != INVALID_LINE) {
                mCachedStart += dt;
            }
            if (mCachedEnd != INVALID_LINE) {
                mCachedEnd += dt;
            }
        }

        // normalized offset is how much this span can scroll
        int getNormalizedOffset(int dt, int targetStart, int targetEnd) {
            if (mViews.size() == 0) {
                return 0;
            }
            if (dt < 0) {
                final int endSpace = getEndLine() - targetEnd;
                if (endSpace <= 0) {
                    return 0;
                }
                return -dt > endSpace ? -endSpace : dt;
            } else {
                final int startSpace = targetStart - getStartLine();
                if (startSpace <= 0) {
                    return 0;
                }
                return startSpace < dt ? startSpace : dt;
            }
        }

        /**
         * Returns if there is no child between start-end lines
         *
         * @param start The start line
         * @param end   The end line
         * @return true if a new child can be added between start and end
         */
        boolean isEmpty(int start, int end) {
            final int count = mViews.size();
            for (int i = 0; i < count; i++) {
                final View view = mViews.get(i);
                if (mPrimaryOrientation.getDecoratedStart(view) < end &&
                        mPrimaryOrientation.getDecoratedEnd(view) > start) {
                    return false;
                }
            }
            return true;
        }

        public int findFirstVisibleItemPosition() {
            return mReverseLayout
                    ? findOneVisibleChild(mViews.size() - 1, -1, false)
                    : findOneVisibleChild(0, mViews.size(), false);
        }

        public int findFirstCompletelyVisibleItemPosition() {
            return mReverseLayout
                    ? findOneVisibleChild(mViews.size() - 1, -1, true)
                    : findOneVisibleChild(0, mViews.size(), true);
        }

        public int findLastVisibleItemPosition() {
            return mReverseLayout
                    ? findOneVisibleChild(0, mViews.size(), false)
                    : findOneVisibleChild(mViews.size() - 1, -1, false);
        }

        public int findLastCompletelyVisibleItemPosition() {
            return mReverseLayout
                    ? findOneVisibleChild(0, mViews.size(), true)
                    : findOneVisibleChild(mViews.size() - 1, -1, true);
        }

        int findOneVisibleChild(int fromIndex, int toIndex, boolean completelyVisible) {
            final int start = mPrimaryOrientation.getStartAfterPadding();
            final int end = mPrimaryOrientation.getEndAfterPadding();
            final int next = toIndex > fromIndex ? 1 : -1;
            for (int i = fromIndex; i != toIndex; i += next) {
                final View child = mViews.get(i);
                final int childStart = mPrimaryOrientation.getDecoratedStart(child);
                final int childEnd = mPrimaryOrientation.getDecoratedEnd(child);
                if (childStart < end && childEnd > start) {
                    if (completelyVisible) {
                        if (childStart >= start && childEnd <= end) {
                            return getPosition(child);
                        }
                    } else {
                        return getPosition(child);
                    }
                }
            }
            return NO_POSITION;
        }
    }

    /**
     * An array of mappings from adapter position to span.
     * This only grows when a write happens and it grows up to the size of the adapter.
     */
    static class LazySpanLookup {

        private static final int MIN_SIZE = 10;
        int[] mData;
        List<FullSpanItem> mFullSpanItems;


        /**
         * Invalidates everything after this position, including full span information
         */
        int forceInvalidateAfter(int position) {
            if (mFullSpanItems != null) {
                for (int i = mFullSpanItems.size() - 1; i >= 0; i--) {
                    FullSpanItem fsi = mFullSpanItems.get(i);
                    if (fsi.mPosition >= position) {
                        mFullSpanItems.remove(i);
                    }
                }
            }
            return invalidateAfter(position);
        }

        /**
         * returns end position for invalidation.
         */
        int invalidateAfter(int position) {
            if (mData == null) {
                return RecyclerView.NO_POSITION;
            }
            if (position >= mData.length) {
                return RecyclerView.NO_POSITION;
            }
            int endPosition = invalidateFullSpansAfter(position);
            if (endPosition == RecyclerView.NO_POSITION) {
                Arrays.fill(mData, position, mData.length, LayoutParams.INVALID_SPAN_ID);
                return mData.length;
            } else {
                // just invalidate items in between
                Arrays.fill(mData, position, endPosition + 1, LayoutParams.INVALID_SPAN_ID);
                return endPosition + 1;
            }
        }

        int getSpan(int position) {
            if (mData == null || position >= mData.length) {
                return LayoutParams.INVALID_SPAN_ID;
            } else {
                return mData[position];
            }
        }

        void setSpan(int position, Span span) {
            ensureSize(position);
            mData[position] = span.mIndex;
        }

        int sizeForPosition(int position) {
            int len = mData.length;
            while (len <= position) {
                len *= 2;
            }
            return len;
        }

        void ensureSize(int position) {
            if (mData == null) {
                mData = new int[Math.max(position, MIN_SIZE) + 1];
                Arrays.fill(mData, LayoutParams.INVALID_SPAN_ID);
            } else if (position >= mData.length) {
                int[] old = mData;
                mData = new int[sizeForPosition(position)];
                System.arraycopy(old, 0, mData, 0, old.length);
                Arrays.fill(mData, old.length, mData.length, LayoutParams.INVALID_SPAN_ID);
            }
        }

        void clear() {
            if (mData != null) {
                Arrays.fill(mData, LayoutParams.INVALID_SPAN_ID);
            }
            mFullSpanItems = null;
        }

        void offsetForRemoval(int positionStart, int itemCount) {
            if (mData == null || positionStart >= mData.length) {
                return;
            }
            ensureSize(positionStart + itemCount);
            System.arraycopy(mData, positionStart + itemCount, mData, positionStart,
                    mData.length - positionStart - itemCount);
            Arrays.fill(mData, mData.length - itemCount, mData.length,
                    LayoutParams.INVALID_SPAN_ID);
            offsetFullSpansForRemoval(positionStart, itemCount);
        }

        private void offsetFullSpansForRemoval(int positionStart, int itemCount) {
            if (mFullSpanItems == null) {
                return;
            }
            final int end = positionStart + itemCount;
            for (int i = mFullSpanItems.size() - 1; i >= 0; i--) {
                FullSpanItem fsi = mFullSpanItems.get(i);
                if (fsi.mPosition < positionStart) {
                    continue;
                }
                if (fsi.mPosition < end) {
                    mFullSpanItems.remove(i);
                } else {
                    fsi.mPosition -= itemCount;
                }
            }
        }

        void offsetForAddition(int positionStart, int itemCount) {
            if (mData == null || positionStart >= mData.length) {
                return;
            }
            ensureSize(positionStart + itemCount);
            System.arraycopy(mData, positionStart, mData, positionStart + itemCount,
                    mData.length - positionStart - itemCount);
            Arrays.fill(mData, positionStart, positionStart + itemCount,
                    LayoutParams.INVALID_SPAN_ID);
            offsetFullSpansForAddition(positionStart, itemCount);
        }

        private void offsetFullSpansForAddition(int positionStart, int itemCount) {
            if (mFullSpanItems == null) {
                return;
            }
            for (int i = mFullSpanItems.size() - 1; i >= 0; i--) {
                FullSpanItem fsi = mFullSpanItems.get(i);
                if (fsi.mPosition < positionStart) {
                    continue;
                }
                fsi.mPosition += itemCount;
            }
        }

        /**
         * Returns when invalidation should end. e.g. hitting a full span position.
         * Returned position SHOULD BE invalidated.
         */
        private int invalidateFullSpansAfter(int position) {
            if (mFullSpanItems == null) {
                return RecyclerView.NO_POSITION;
            }
            final FullSpanItem item = getFullSpanItem(position);
            // if there is an fsi at this position, get rid of it.
            if (item != null) {
                mFullSpanItems.remove(item);
            }
            int nextFsiIndex = -1;
            final int count = mFullSpanItems.size();
            for (int i = 0; i < count; i++) {
                FullSpanItem fsi = mFullSpanItems.get(i);
                if (fsi.mPosition >= position) {
                    nextFsiIndex = i;
                    break;
                }
            }
            if (nextFsiIndex != -1) {
                FullSpanItem fsi = mFullSpanItems.get(nextFsiIndex);
                mFullSpanItems.remove(nextFsiIndex);
                return fsi.mPosition;
            }
            return RecyclerView.NO_POSITION;
        }

        public void addFullSpanItem(FullSpanItem fullSpanItem) {
            if (mFullSpanItems == null) {
                mFullSpanItems = new ArrayList<FullSpanItem>();
            }
            final int size = mFullSpanItems.size();
            for (int i = 0; i < size; i++) {
                FullSpanItem other = mFullSpanItems.get(i);
                if (other.mPosition == fullSpanItem.mPosition) {
                    if (DEBUG) {
                        throw new IllegalStateException("two fsis for same position");
                    } else {
                        mFullSpanItems.remove(i);
                    }
                }
                if (other.mPosition >= fullSpanItem.mPosition) {
                    mFullSpanItems.add(i, fullSpanItem);
                    return;
                }
            }
            // if it is not added to a position.
            mFullSpanItems.add(fullSpanItem);
        }

        public FullSpanItem getFullSpanItem(int position) {
            if (mFullSpanItems == null) {
                return null;
            }
            for (int i = mFullSpanItems.size() - 1; i >= 0; i--) {
                final FullSpanItem fsi = mFullSpanItems.get(i);
                if (fsi.mPosition == position) {
                    return fsi;
                }
            }
            return null;
        }

        /**
         * @param minPos inclusive
         * @param maxPos exclusive
         * @param gapDir if not 0, returns FSIs on in that direction
         * @param hasUnwantedGapAfter If true, when full span item has unwanted gaps, it will be
         *                        returned even if its gap direction does not match.
         */
        public FullSpanItem getFirstFullSpanItemInRange(int minPos, int maxPos, int gapDir,
                boolean hasUnwantedGapAfter) {
            if (mFullSpanItems == null) {
                return null;
            }
            final int limit = mFullSpanItems.size();
            for (int i = 0; i < limit; i++) {
                FullSpanItem fsi = mFullSpanItems.get(i);
                if (fsi.mPosition >= maxPos) {
                    return null;
                }
                if (fsi.mPosition >= minPos
                        && (gapDir == 0 || fsi.mGapDir == gapDir ||
                        (hasUnwantedGapAfter && fsi.mHasUnwantedGapAfter))) {
                    return fsi;
                }
            }
            return null;
        }

        /**
         * We keep information about full span items because they may create gaps in the UI.
         */
        static class FullSpanItem implements Parcelable {

            int mPosition;
            int mGapDir;
            int[] mGapPerSpan;
            // A full span may be laid out in primary direction but may have gaps due to
            // invalidation of views after it. This is recorded during a reverse scroll and if
            // view is still on the screen after scroll stops, we have to recalculate layout
            boolean mHasUnwantedGapAfter;

            public FullSpanItem(Parcel in) {
                mPosition = in.readInt();
                mGapDir = in.readInt();
                mHasUnwantedGapAfter = in.readInt() == 1;
                int spanCount = in.readInt();
                if (spanCount > 0) {
                    mGapPerSpan = new int[spanCount];
                    in.readIntArray(mGapPerSpan);
                }
            }

            public FullSpanItem() {
            }

            int getGapForSpan(int spanIndex) {
                return mGapPerSpan == null ? 0 : mGapPerSpan[spanIndex];
            }

            public void invalidateSpanGaps() {
                mGapPerSpan = null;
            }

            @Override
            public int describeContents() {
                return 0;
            }

            @Override
            public void writeToParcel(Parcel dest, int flags) {
                dest.writeInt(mPosition);
                dest.writeInt(mGapDir);
                dest.writeInt(mHasUnwantedGapAfter ? 1 : 0);
                if (mGapPerSpan != null && mGapPerSpan.length > 0) {
                    dest.writeInt(mGapPerSpan.length);
                    dest.writeIntArray(mGapPerSpan);
                } else {
                    dest.writeInt(0);
                }
            }

            @Override
            public String toString() {
                return "FullSpanItem{" +
                        "mPosition=" + mPosition +
                        ", mGapDir=" + mGapDir +
                        ", mHasUnwantedGapAfter=" + mHasUnwantedGapAfter +
                        ", mGapPerSpan=" + Arrays.toString(mGapPerSpan) +
                        '}';
            }

            public static final Parcelable.Creator<FullSpanItem> CREATOR
                    = new Parcelable.Creator<FullSpanItem>() {
                @Override
                public FullSpanItem createFromParcel(Parcel in) {
                    return new FullSpanItem(in);
                }

                @Override
                public FullSpanItem[] newArray(int size) {
                    return new FullSpanItem[size];
                }
            };
        }
    }

    static class SavedState implements Parcelable {

        int mAnchorPosition;
        int mVisibleAnchorPosition; // Replacement for span info when spans are invalidated
        int mSpanOffsetsSize;
        int[] mSpanOffsets;
        int mSpanLookupSize;
        int[] mSpanLookup;
        List<LazySpanLookup.FullSpanItem> mFullSpanItems;
        boolean mReverseLayout;
        boolean mAnchorLayoutFromEnd;
        boolean mLastLayoutRTL;

        public SavedState() {
        }

        SavedState(Parcel in) {
            mAnchorPosition = in.readInt();
            mVisibleAnchorPosition = in.readInt();
            mSpanOffsetsSize = in.readInt();
            if (mSpanOffsetsSize > 0) {
                mSpanOffsets = new int[mSpanOffsetsSize];
                in.readIntArray(mSpanOffsets);
            }

            mSpanLookupSize = in.readInt();
            if (mSpanLookupSize > 0) {
                mSpanLookup = new int[mSpanLookupSize];
                in.readIntArray(mSpanLookup);
            }
            mReverseLayout = in.readInt() == 1;
            mAnchorLayoutFromEnd = in.readInt() == 1;
            mLastLayoutRTL = in.readInt() == 1;
            mFullSpanItems = in.readArrayList(
                    LazySpanLookup.FullSpanItem.class.getClassLoader());
        }

        public SavedState(SavedState other) {
            mSpanOffsetsSize = other.mSpanOffsetsSize;
            mAnchorPosition = other.mAnchorPosition;
            mVisibleAnchorPosition = other.mVisibleAnchorPosition;
            mSpanOffsets = other.mSpanOffsets;
            mSpanLookupSize = other.mSpanLookupSize;
            mSpanLookup = other.mSpanLookup;
            mReverseLayout = other.mReverseLayout;
            mAnchorLayoutFromEnd = other.mAnchorLayoutFromEnd;
            mLastLayoutRTL = other.mLastLayoutRTL;
            mFullSpanItems = other.mFullSpanItems;
        }

        void invalidateSpanInfo() {
            mSpanOffsets = null;
            mSpanOffsetsSize = 0;
            mSpanLookupSize = 0;
            mSpanLookup = null;
            mFullSpanItems = null;
        }

        void invalidateAnchorPositionInfo() {
            mSpanOffsets = null;
            mSpanOffsetsSize = 0;
            mAnchorPosition = NO_POSITION;
            mVisibleAnchorPosition = NO_POSITION;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mAnchorPosition);
            dest.writeInt(mVisibleAnchorPosition);
            dest.writeInt(mSpanOffsetsSize);
            if (mSpanOffsetsSize > 0) {
                dest.writeIntArray(mSpanOffsets);
            }
            dest.writeInt(mSpanLookupSize);
            if (mSpanLookupSize > 0) {
                dest.writeIntArray(mSpanLookup);
            }
            dest.writeInt(mReverseLayout ? 1 : 0);
            dest.writeInt(mAnchorLayoutFromEnd ? 1 : 0);
            dest.writeInt(mLastLayoutRTL ? 1 : 0);
            dest.writeList(mFullSpanItems);
        }

        public static final Parcelable.Creator<SavedState> CREATOR
                = new Parcelable.Creator<SavedState>() {
            @Override
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            @Override
            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
    }

    /**
     * Data class to hold the information about an anchor position which is used in onLayout call.
     */
    private class AnchorInfo {

        int mPosition;
        int mOffset;
        boolean mLayoutFromEnd;
        boolean mInvalidateOffsets;

        void reset() {
            mPosition = NO_POSITION;
            mOffset = INVALID_OFFSET;
            mLayoutFromEnd = false;
            mInvalidateOffsets = false;
        }

        void assignCoordinateFromPadding() {
            mOffset = mLayoutFromEnd ? mPrimaryOrientation.getEndAfterPadding()
                    : mPrimaryOrientation.getStartAfterPadding();
        }

        void assignCoordinateFromPadding(int addedDistance) {
            if (mLayoutFromEnd) {
                mOffset = mPrimaryOrientation.getEndAfterPadding() - addedDistance;
            } else {
                mOffset = mPrimaryOrientation.getStartAfterPadding() + addedDistance;
            }
        }
    }
}
