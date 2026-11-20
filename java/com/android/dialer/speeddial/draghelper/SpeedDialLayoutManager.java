/*
 * Copyright (C) 2017 The Android Open Source Project
 * Copyright (C) 2023 The LineageOS Project
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

package com.android.dialer.speeddial.draghelper;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/** {@link GridLayoutManager} that allows disabling scrolling. */
public class SpeedDialLayoutManager extends GridLayoutManager {

  private boolean isScrollEnabled = true;

  public SpeedDialLayoutManager(Context context, int spanCount) {
    super(context, spanCount);
  }

  public void setScrollEnabled(boolean flag) {
    this.isScrollEnabled = flag;
  }

  @Override
  public boolean requestChildRectangleOnScreen(
      RecyclerView parent, View child, Rect rect, boolean immediate, boolean focusedChildVisible) {
    Rect visibleBounds = new Rect();
    if (parent.getLocalVisibleRect(visibleBounds)) {
      // The negative top margin reserves a drag-to-remove area behind the toolbar.
      // Focus scrolling must reveal the child below that clipped area, not inside it.
      int hiddenTop = Math.max(0, visibleBounds.top - getPaddingTop());
      if (hiddenTop > 0) {
        Rect revealRect = new Rect(rect);
        revealRect.top -= hiddenTop;
        // Reveal focus immediately rather than racing an existing touch-scroll animation.
        parent.stopScroll();
        return super.requestChildRectangleOnScreen(
            parent, child, revealRect, true, focusedChildVisible);
      }
    }
    return super.requestChildRectangleOnScreen(
        parent, child, rect, immediate, focusedChildVisible);
  }

  @Override
  public boolean canScrollVertically() {
    // Similarly you can customize "canScrollHorizontally()" for managing horizontal scroll
    return isScrollEnabled && super.canScrollVertically();
  }
}
