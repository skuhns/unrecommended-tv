package com.gathin.tvhome;

import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** A row or grid of tiles (apps or inputs), all one size. */
public class TileAdapter extends RecyclerView.Adapter<TileAdapter.Holder> {

    static final class Tile {
        final String label;
        final Drawable banner;
        final Drawable icon;
        final Runnable onClick;
        final Runnable onLongClick;
        /** The app's package; null for non-app tiles (inputs). */
        final String pkg;

        Tile(String label, Drawable banner, Drawable icon, Runnable onClick, Runnable onLongClick) {
            this(null, label, banner, icon, onClick, onLongClick);
        }

        Tile(String pkg, String label, Drawable banner, Drawable icon, Runnable onClick, Runnable onLongClick) {
            this.pkg = pkg;
            this.label = label;
            this.banner = banner;
            this.icon = icon;
            this.onClick = onClick;
            this.onLongClick = onLongClick;
        }
    }

    interface FocusListener {
        void onTileFocused(Tile tile, View card);
    }

    static final float FOCUS_SCALE = 1.08f;
    private FocusListener focusListener;
    /** Left/Right run through the tiles in order and wrap around (used for the big 3×2 grid). */
    private boolean wrap;
    private RecyclerView attached;

    private final int widthPx, heightPx;
    /** Horizontal rows trap Left/Right at their ends so focus doesn't jump to another row. */
    private final boolean horizontal;
    private final List<Tile> tiles = new ArrayList<>();
    /** The tile being moved (shown with a distinct outline), or -1. */
    private int movingPos = -1;

    TileAdapter(int widthPx, int heightPx, boolean horizontal) {
        this.widthPx = widthPx;
        this.heightPx = heightPx;
        this.horizontal = horizontal;
    }

    void setWrap(boolean wrap) {
        this.wrap = wrap;
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView rv) {
        attached = rv;
    }

    void setFocusListener(FocusListener l) {
        focusListener = l;
    }

    void setMoving(int pos) {
        int old = movingPos;
        movingPos = pos;
        if (old >= 0) notifyItemChanged(old);
        if (pos >= 0) notifyItemChanged(pos);
    }

    /** Slide the tile at {@code from} to {@code to}; the moving outline follows it. */
    void move(int from, int to) {
        tiles.add(to, tiles.remove(from));
        if (movingPos == from) movingPos = to;
        notifyItemMoved(from, to);
        notifyItemChanged(to);
    }

    Tile tileAt(int pos) {
        return tiles.get(pos);
    }

    void setTiles(List<Tile> fresh) {
        tiles.clear();
        tiles.addAll(fresh);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_tile, parent, false);
        ViewGroup.LayoutParams lp = v.findViewById(R.id.card).getLayoutParams();
        lp.width = widthPx;
        lp.height = heightPx;
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Tile t = tiles.get(position);
        h.name.setText(t.label);
        h.name.setAlpha(0f);
        h.card.setScaleX(1f);
        h.card.setScaleY(1f);
        if (t.banner != null) {
            h.banner.setImageDrawable(t.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(t.icon);
            h.icon.setVisibility(t.icon != null ? View.VISIBLE : View.GONE);
            h.label.setText(t.label);
            h.label.setVisibility(View.VISIBLE);
        }
        h.card.setForeground(h.card.getContext().getDrawable(
                position == movingPos ? R.drawable.tile_moving : R.drawable.tile_focus));
        h.card.setOnClickListener(v -> t.onClick.run());
        h.card.setOnLongClickListener(t.onLongClick == null ? null : v -> {
            t.onLongClick.run();
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return tiles.size();
    }

    final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;

        Holder(View v) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);

            final float radius = v.getResources().getDisplayMetrics().density * 12f;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int pos = getBindingAdapterPosition();
                if (wrap && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                    int n = getItemCount();
                    int target = (pos + (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : n - 1)) % n;
                    RecyclerView.ViewHolder vh = attached != null ? attached.findViewHolderForAdapterPosition(target) : null;
                    if (vh != null) vh.itemView.findViewById(R.id.card).requestFocus();
                    return true;
                }
                if (!horizontal) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return pos == 0;
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return pos == getItemCount() - 1;
                return false;
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 220 : 160)
                        .setInterpolator(new OvershootInterpolator(focused ? 1.4f : 0f))
                        .start();
                // Tiles without a banner already show their label inside the card.
                boolean hasBanner = banner.getVisibility() == View.VISIBLE;
                name.animate().alpha(focused && hasBanner ? 1f : 0f).setDuration(180).start();
                int pos = getBindingAdapterPosition();
                if (focused && focusListener != null && pos != RecyclerView.NO_POSITION) {
                    focusListener.onTileFocused(tiles.get(pos), view);
                }
            });
        }
    }
}
