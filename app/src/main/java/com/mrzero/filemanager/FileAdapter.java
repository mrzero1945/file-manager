package com.mrzero.filemanager;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Row renderer for both list and grid modes.
 *
 * A row is: [colour tile + white glyph] [name / meta] [chevron].
 * In grid mode the tile becomes a square and the name moves beneath it,
 * matching iOS Files.
 */
public class FileAdapter extends RecyclerView.Adapter<FileAdapter.VH> {

    private static final int TYPE_ROW = 0;
    private static final int TYPE_GRID = 1;

    public interface Listener {
        void onOpen(FileEntry e, int position);

        void onMenu(FileEntry e, int position, View anchor);

        void onSelectionChanged();
    }

    private final android.content.Context ctx;
    private FileAdapter.Listener listener;
    private final List<FileEntry> items = new ArrayList<>();
    private final List<FileEntry> selected = new ArrayList<>();

    private boolean gridMode;
    private boolean selectionMode;
    private boolean selectionEnabled = true;
    private boolean showHidden;
    private int sort = FileEntry.SORT_NAME;
    private boolean ascending = true;

    public FileAdapter(android.content.Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
    }

    public void setListener(Listener l) { this.listener = l; }

    public void submit(List<FileEntry> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        pruneSelection();
        notifyDataSetChanged();
    }

    public void setGridMode(boolean grid) {
        if (gridMode == grid) return;
        gridMode = grid;
        notifyDataSetChanged();
    }

    public boolean isGridMode() { return gridMode; }

    // ---- selection -----------------------------------------------------
    /** Recents has no bulk-edit affordance, so long-press should not select there. */
    public void setSelectionEnabled(boolean on) {
        selectionEnabled = on;
        if (!on && selectionMode) clearSelection();
    }

    public boolean isSelectionEnabled() { return selectionEnabled; }

    public void setSelectionMode(boolean on) {
        if (!selectionEnabled && on) return;
        if (selectionMode == on) return;
        selectionMode = on;
        if (!on) selected.clear();
        notifyDataSetChanged();
        if (listener != null) listener.onSelectionChanged();
    }

    public boolean isSelectionMode() { return selectionMode; }

    public void toggleSelection(FileEntry e) {
        if (selected.contains(e)) selected.remove(e);
        else selected.add(e);
        if (selected.isEmpty()) selectionMode = false;
        notifyDataSetChanged();
        if (listener != null) listener.onSelectionChanged();
    }

    public void selectAll() {
        if (!selectionEnabled) return;
        selected.clear();
        // ".." is a navigation row, not something you can act on.
        for (FileEntry e : items) {
            if (!e.isParentRow) selected.add(e);
        }
        if (selected.isEmpty()) return;
        selectionMode = true;
        notifyDataSetChanged();
        if (listener != null) listener.onSelectionChanged();
    }

    public void clearSelection() {
        if (selected.isEmpty() && !selectionMode) return;
        selected.clear();
        selectionMode = false;
        notifyDataSetChanged();
        if (listener != null) listener.onSelectionChanged();
    }

    public List<FileEntry> getSelected() { return new ArrayList<>(selected); }

    public int selectedCount() { return selected.size(); }

    public boolean isSelected(FileEntry e) { return selected.contains(e); }

    /** How many rows can actually be selected, i.e. everything except "..". */
    public int selectableCount() {
        int n = 0;
        for (FileEntry e : items) {
            if (!e.isParentRow) n++;
        }
        return n;
    }

    /** True when every selectable row is selected, so "Select All" means undo. */
    public boolean isAllSelected() {
        int total = selectableCount();
        return total > 0 && selected.size() == total;
    }

    private void pruneSelection() {
        if (selected.isEmpty()) return;
        selected.retainAll(items);
        if (selected.isEmpty()) selectionMode = false;
    }

    // ---- sort / hidden --------------------------------------------------
    public int getSort() { return sort; }

    public boolean isAscending() { return ascending; }

    public boolean isShowHidden() { return showHidden; }

    public void setSort(int sort, boolean ascending) {
        this.sort = sort;
        this.ascending = ascending;
    }

    public void setShowHidden(boolean v) { showHidden = v; }

    public List<FileEntry> items() { return items; }

    public FileEntry at(int pos) {
        return pos >= 0 && pos < items.size() ? items.get(pos) : null;
    }

    // ------------------------------------------------------------------
    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout;
        if (viewType == TYPE_GRID) layout = R.layout.item_file_grid;
        else layout = R.layout.item_file_row;
        View v = android.view.LayoutInflater.from(ctx).inflate(layout, parent, false);
        return new VH(v);
    }

    @Override
    public int getItemViewType(int position) { return gridMode ? TYPE_GRID : TYPE_ROW; }

    @Override
    public int getItemCount() { return items.size(); }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        final FileEntry e = items.get(position);
        h.bind(e);
        h.itemView.setOnClickListener(v -> {
            if (selectionMode) {
                // ".." is navigation, not content. Letting it into the selection
                // would aim a bulk delete/copy/move at the parent folder.
                if (!e.isParentRow) toggleSelection(e);
            } else if (listener != null) {
                listener.onOpen(e, h.getBindingAdapterPosition());
            }
        });
        h.itemView.setOnLongClickListener(v -> {
            if (e.isParentRow) return false;
            if (!selectionEnabled) return false;
            // Long-press opens the iOS context menu anchored to the row.
            if (listener != null) {
                listener.onMenu(e, h.getBindingAdapterPosition(), h.itemView);
            }
            return true;
        });
    }

    // ------------------------------------------------------------------
    class VH extends RecyclerView.ViewHolder {
        final ImageView tile;
        final ImageView glyph;
        final TextView title;
        final TextView meta;
        final View chevron;
        final View selector;

        VH(View v) {
            super(v);
            tile = v.findViewById(R.id.tile);
            glyph = v.findViewById(R.id.glyph);
            title = v.findViewById(R.id.title);
            meta = v.findViewById(R.id.meta);
            chevron = v.findViewById(R.id.chevron);
            selector = v.findViewById(R.id.selector);
        }

        void bind(FileEntry e) {
            title.setText(e.name);
            meta.setText(gridMode ? gridMeta(e) : rowMeta(e));

            if (e.kind == FileEntry.Kind.IMAGE) {
                // Images show the real photo in both layouts, not a type icon.
                tile.setBackgroundResource(gridMode
                        ? R.drawable.bg_grid_photo : R.drawable.bg_row_photo);
                glyph.setVisibility(View.GONE);
                final String finalKey = e.file.getAbsolutePath() + "@"
                        + Apple.dp(ctx, gridMode ? 78 : 38);
                Thumbs.load(tile, e, Apple.dp(ctx, gridMode ? 78 : 38), () -> {
                    // Undecodable file: fall back to the green image tile.
                    if (!finalKey.equals(tile.getTag())) return;
                    tile.setBackgroundResource(R.drawable.tile_image);
                    glyph.setImageResource(R.drawable.ic_file_image);
                    glyph.setColorFilter(0xFFFFFFFF,
                            android.graphics.PorterDuff.Mode.SRC_IN);
                    glyph.setVisibility(View.VISIBLE);
                });
            } else {
                tile.setBackgroundResource(tileResFor(e));
                glyph.setVisibility(View.VISIBLE);
                // The tile is a container here; make sure no photo is left behind.
                Thumbs.clear(tile);
                glyph.setImageResource(iconFor(e));
                glyph.setColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
            }

            Draw.setVisible(selector, selected.contains(e));
            Draw.setVisible(chevron, !gridMode && e.isFolder() && !selectionMode);
            if (e.isParentRow) {
                // ".." has no metadata of its own: no count, no chevron.
                meta.setText("");
                tile.setBackgroundResource(R.drawable.tile_folder);
                glyph.setImageResource(R.drawable.ic_chevron_left);
                glyph.setColorFilter(0xFF0A84FF, android.graphics.PorterDuff.Mode.SRC_IN);
                itemView.setBackgroundColor(0xFF000000);
                Draw.setVisible(selector, false);
                return;
            }
            itemView.setBackgroundColor(selectionMode
                    ? (selected.contains(e) ? 0xFF1F3A5F : 0xFF0A0A0C)
                    : 0xFF000000);
        }

        String rowMeta(FileEntry e) {
            if (e.isParentRow) return "";
            if (e.isFolder()) {
                int n = e.childCount;
                if (n < 0) return "";
                return n == 1 ? "1 item" : n + " items";
            }
            return FileOps.formatSize(e.size) + "  ·  " + kindLabel(e);
        }

        String gridMeta(FileEntry e) {
            return e.isFolder() ? "" : FileOps.formatSize(e.size);
        }
    }

    // ------------------------------------------------------------------
    public static String kindLabel(FileEntry e) {
        switch (e.kind) {
            case FOLDER: return "Folder";
            case IMAGE: return "Image";
            case VIDEO: return "Video";
            case AUDIO: return "Audio";
            case PDF: return "PDF Document";
            case ARCHIVE: return "Archive";
            case CODE: return "Source Code";
            case APK: return "Android App";
            case TEXT: return "Document";
            default:
                if (e.ext.isEmpty()) return "File";
                return e.ext.toUpperCase(Locale.US) + " File";
        }
    }

    public static int iconFor(FileEntry e) {
        switch (e.kind) {
            case FOLDER: return R.drawable.ic_folder;
            case IMAGE: return R.drawable.ic_file_image;
            case VIDEO: return R.drawable.ic_file_video;
            case AUDIO: return R.drawable.ic_file_audio;
            case PDF: return R.drawable.ic_file_pdf;
            case ARCHIVE: return R.drawable.ic_file_zip;
            case CODE: return R.drawable.ic_file_code;
            case APK: return R.drawable.ic_file_apk;
            case TEXT: return R.drawable.ic_file_text;
            default: return R.drawable.ic_file;
        }
    }

    public static int tileResFor(FileEntry e) {
        switch (e.kind) {
            case FOLDER: return R.drawable.tile_folder;
            case IMAGE: return R.drawable.tile_image;
            case VIDEO: return R.drawable.tile_video;
            case AUDIO: return R.drawable.tile_audio;
            case PDF: return R.drawable.tile_pdf;
            case ARCHIVE: return R.drawable.tile_archive;
            case CODE: return R.drawable.tile_code;
            case APK: return R.drawable.tile_apk;
            case TEXT: return R.drawable.tile_text;
            default: return R.drawable.tile_file;
        }
    }

    public static int tintForCategory(FileEntry.Kind k) {
        switch (k) {
            case FOLDER: return Apple.BLUE;
            case IMAGE: return Apple.GREEN;
            case VIDEO: return Apple.PURPLE;
            case AUDIO: return Apple.PINK;
            case PDF: return Apple.RED;
            case ARCHIVE: return Apple.ORANGE;
            case CODE: return Apple.INDIGO;
            case APK: return Apple.GREEN;
            case TEXT: return Apple.ORANGE;
            default: return Apple.GRAY_DARK;
        }
    }
}
