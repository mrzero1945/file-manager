package com.mrzero.filemanager;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Shared plumbing for the list screens: a RecyclerView configured the iOS way
 * (no item animations, no scrollbar, no overscroll glow) plus the inset-grouped
 * look that the row layout produces.
 */
public class ListSurface extends FrameLayout {

    public final RecyclerView list;
    public final LinearLayoutManager lm;
    public final FileAdapter adapter;

    public ListSurface(Context ctx) {
        super(ctx);
        setBackgroundColor(0xFF000000);

        adapter = new FileAdapter(ctx, new FileAdapter.Listener() {
            @Override
            public void onOpen(FileEntry e, int position) { }

            @Override
            public void onMenu(FileEntry e, int position, View anchor) { }

            @Override
            public void onSelectionChanged() { }
        });

        list = new RecyclerView(ctx);
        lm = new LinearLayoutManager(ctx);
        list.setLayoutManager(lm);
        list.setAdapter(adapter);
        list.setBackgroundColor(0x00000000);
        list.setClipToPadding(false);
        list.setHorizontalScrollBarEnabled(false);
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(OVER_SCROLL_NEVER);
        list.setItemAnimator(null);
        list.setItemViewCacheSize(24);
        addView(list, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    public void setListener(FileAdapter.Listener l) {
        adapter.setListener(l);
    }

    public void setTopInset(int px) {
        list.setPadding(list.getPaddingLeft(), px, list.getPaddingRight(),
                list.getPaddingBottom());
    }

    public void setBottomInset(int px) {
        list.setPadding(list.getPaddingLeft(), list.getPaddingTop(),
                list.getPaddingRight(), px);
    }

    /**
     * Pixels the content has moved up from its resting position, i.e. how far it
     * has travelled under the nav bar. computeVerticalScrollOffset() is exact
     * here and needs no row-height or dp/px assumptions; deriving it from the
     * first visible row misfires as soon as that row is only partly on screen.
     */
    public float scrolledDistance() {
        return Math.max(0, list.computeVerticalScrollOffset());
    }
}
