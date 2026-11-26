package com.gathin.tvhome;

/** Physical left/right boundaries for a grid whose first column follows its reading direction. */
final class GridNavigation {
    private GridNavigation() { }

    static boolean atHorizontalEdge(int position, int count, int columns, boolean rtl, boolean left) {
        boolean towardStart = left != rtl;
        if (towardStart) return position % columns == 0;
        return position % columns == columns - 1 || position == count - 1;
    }

    /** Dim level of a non-carried tile: edit-dock dims only the other dock places, Move mode dims every tile. */
    static float idleAlpha(int pos, int columns, boolean dockEditing, int dockEditSlot, boolean reordering) {
        if (dockEditing) return dockEditSlot >= 0 && pos >= 0 && pos < columns && pos != dockEditSlot ? 0.45f : 1f;
        return reordering ? 0.45f : 1f;
    }

    /**
     * Vertical scroll delta to bring a focused grid row cleanly on screen.
     * Uses the full item bottom plus glass padding (not only the inner card rect passed by
     * RecyclerView.requestChildFocus), so Down scrolls straight to the final resting row
     * position and subsequent sideways moves in the same row produce 0 drift.
     */
    static int verticalRevealDy(int childTop, int childBottom, int rectTop, int rectHeight, int glassPad,
                                int limitTop, int limitBottom, int viewportHeight, int topTolerance,
                                boolean canScrollUp, boolean canScrollDown) {
        int bottom = Math.max(childBottom + glassPad, childTop + rectTop + rectHeight);
        int dy;
        if (childTop < limitTop - topTolerance) dy = childTop - limitTop;
        else if (bottom <= viewportHeight) dy = 0;
        else dy = Math.min(childTop - limitTop, bottom - limitBottom);
        if (dy > 0 && !canScrollDown) return 0;
        if (dy < 0 && !canScrollUp) return 0;
        return dy;
    }

    /**
     * Prevents fast/held DPAD_UP from overshooting the dock row straight into the top bar
     * while the grid is still scrolling, on key-repeat events, or within 350 ms of landing
     * on the dock from a lower grid row.
     */
    static boolean shouldHoldAtDockOnUp(boolean inDock, boolean isRepeat, boolean gridScrolling,
                                        long elapsedSinceDockLandMs) {
        if (!inDock) return false;
        return isRepeat || gridScrolling || (elapsedSinceDockLandMs >= 0L && elapsedSinceDockLandMs < 350L);
    }
}
