package com.magstic.mldplayer;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.content.res.ColorStateList;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;

public final class PlaylistAdapter extends RecyclerView.Adapter<PlaylistAdapter.ViewHolder> {
    public interface Listener {
        void onPlaylistEntryClicked(int position);
    }

    private final Listener listener;
    private final ArrayList<PlaylistEntry> items;
    private int currentIndex;

    public PlaylistAdapter(Listener listener) {
        this.listener = listener;
        this.items = new ArrayList<PlaylistEntry>();
        this.currentIndex = -1;
    }

    public void submit(List<PlaylistEntry> entries, int currentIndex) {
        if (isSameDataset(entries, currentIndex)) {
            return;
        }
        items.clear();
        if (entries != null) {
            items.addAll(entries);
        }
        this.currentIndex = currentIndex;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_playlist, parent, false);
        return new ViewHolder(itemView);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        PlaylistEntry entry = items.get(position);
        boolean active = position == currentIndex;
        int surfaceColor = MaterialColors.getColor(holder.root, com.google.android.material.R.attr.colorSurface, 0);
        int onSurfaceColor = MaterialColors.getColor(holder.root, com.google.android.material.R.attr.colorOnSurface, 0);
        int onSurfaceVariantColor = MaterialColors.getColor(holder.root, com.google.android.material.R.attr.colorOnSurfaceVariant, 0);
        int primaryColor = MaterialColors.getColor(holder.root, com.google.android.material.R.attr.colorPrimary, 0);
        int secondaryContainerColor = MaterialColors.getColor(holder.root, com.google.android.material.R.attr.colorSecondaryContainer, surfaceColor);
        int onSecondaryContainerColor = MaterialColors.getColor(holder.root, com.google.android.material.R.attr.colorOnSecondaryContainer, onSurfaceColor);

        holder.titleView.setText(entry.displayTitle);
        holder.subtitleView.setText(entry.fileName);
        holder.root.setCardBackgroundColor(active ? secondaryContainerColor : surfaceColor);
        holder.root.setStrokeColor(active ? primaryColor : MaterialColors.getColor(holder.root, com.google.android.material.R.attr.colorOutlineVariant, surfaceColor));
        holder.titleView.setTextColor(active ? onSecondaryContainerColor : onSurfaceColor);
        holder.subtitleView.setTextColor(active ? onSecondaryContainerColor : onSurfaceVariantColor);
        holder.durationView.setVisibility(active ? View.GONE : View.VISIBLE);
        holder.durationView.setTextColor(active ? onSecondaryContainerColor : onSurfaceVariantColor);
        holder.durationView.setText(formatDuration(entry.durationMs));
        holder.stateIconView.setVisibility(active ? View.VISIBLE : View.GONE);
        holder.stateIconView.setImageTintList(ColorStateList.valueOf(primaryColor));
        holder.root.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int bindingPosition = holder.getBindingAdapterPosition();
                if (listener != null && bindingPosition != RecyclerView.NO_POSITION) {
                    listener.onPlaylistEntryClicked(bindingPosition);
                }
            }
        });
        holder.root.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                return true;
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        final MaterialCardView root;
        final TextView titleView;
        final TextView subtitleView;
        final TextView durationView;
        final ImageView stateIconView;

        ViewHolder(View itemView) {
            super(itemView);
            root = (MaterialCardView) itemView.findViewById(R.id.item_root);
            titleView = (TextView) itemView.findViewById(R.id.item_title);
            subtitleView = (TextView) itemView.findViewById(R.id.item_subtitle);
            durationView = (TextView) itemView.findViewById(R.id.item_duration);
            stateIconView = (ImageView) itemView.findViewById(R.id.item_state_icon);
        }
    }

    private static String formatDuration(int millis) {
        if (millis <= 0) {
            return "--:--";
        }
        int totalSeconds = millis / 1000;
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        StringBuilder builder = new StringBuilder();

        if (hours > 0) {
            builder.append(hours).append(':');
            if (minutes < 10) {
                builder.append('0');
            }
        } else if (minutes < 10) {
            builder.append('0');
        }
        builder.append(minutes).append(':');
        if (seconds < 10) {
            builder.append('0');
        }
        builder.append(seconds);
        return builder.toString();
    }

    private boolean isSameDataset(List<PlaylistEntry> entries, int newCurrentIndex) {
        int newSize = entries == null ? 0 : entries.size();
        int index;

        if (currentIndex != newCurrentIndex || items.size() != newSize) {
            return false;
        }
        for (index = 0; index < newSize; index++) {
            if (!sameEntry(items.get(index), entries.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameEntry(PlaylistEntry left, PlaylistEntry right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.durationMs == right.durationMs
                && sameText(left.fileName, right.fileName)
                && sameText(left.displayTitle, right.displayTitle)
                && sameText(left.uriString(), right.uriString());
    }

    private static boolean sameText(String left, String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }
}
