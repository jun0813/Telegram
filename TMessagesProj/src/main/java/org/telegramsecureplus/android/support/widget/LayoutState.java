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
 * See the License for the specific languag`e governing permissions and
 * limitations under the License.
 */

package org.telegramsecureplus.android.support.widget;
import android.view.View;

/**
 * Helper class that keeps temporary state while {LayoutManager} is filling out the empty
 * space.
 */
class LayoutState {

    final static String TAG = "LayoutState";

    final static int LAYOUT_START = -1;

    final static int LAYOUT_END = 1;

    final static int INVALID_LAYOUT = Integer.MIN_VALUE;

    final static int ITEM_DIRECTION_HEAD = -1;

    final static int ITEM_DIRECTION_TAIL = 1;

    final static int SCOLLING_OFFSET_NaN = Integer.MIN_VALUE;

    /**
     * Number of pixels that we should fill, in the layout direction.
     */
    int mAvailable;

    /**
     * Current position on the adapter to get the next item.
     */
    int mCurrentPosition;

    /**
     * Defines the direction in which the data adapter is traversed.
     * Should be {@link #ITEM_DIRECTION_HEAD} or {@link #ITEM_DIRECTION_TAIL}
     */
    int mItemDirection;

    /**
     * Defines the direction in which the layout is filled.
     * Should be {@link #LAYOUT_START} or {@link #LAYOUT_END}
     */
    int mLayoutDirection;

    /**
     * Used if you want to pre-layout items that are not yet visible.
     * The difference with {@link #mAvailable} is that, when recycling, distance rendered for
     * {@link #mExtra} is not considered not to recycle visible children.
     */
    int mExtra = 0;

    /**
     * @return true if there are more items in the data adapter
     */
    boolean hasMore(RecyclerView.State state) {
        return mCurrentPosition >= 0 && mCurrentPosition < state.getItemCount();
    }

    /**
     * Gets the view for the next element that we should render.
     * Also updates current item index to the next item, based on {@link #mItemDirection}
     *
     * @return The next element that we should render.
     */
    View next(RecyclerView.Recycler recycler) {
        final View view = recycler.getViewForPosition(mCurrentPosition);
        mCurrentPosition += mItemDirection;
        return view;
    }
}
