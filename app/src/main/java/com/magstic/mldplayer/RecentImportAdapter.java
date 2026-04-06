package com.magstic.mldplayer;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;

public final class RecentImportAdapter extends RecyclerView.Adapter<RecentImportAdapter.ViewHolder> {
    public interface Listener {
        void onRecentImportClicked(RecentImportEntry entry);
    }

    private final Listener listener;
    private final ArrayList<RecentImportEntry> items;

    public RecentImportAdapter(Listener listener) {
        this.listener = listener;
        this.items = new ArrayList<RecentImportEntry>();
    }

    public void submit(List<RecentImportEntry> entries) {
        items.clear();
        if (entries != null) {
            items.addAll(entries);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_recent_import, parent, false);
        return new ViewHolder(itemView);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        RecentImportEntry entry = items.get(position);
        int onSurfaceColor = MaterialColors.getColor(holder.root, com.google.android.material.R.attr.colorOnSurface, 0);
        int onSurfaceVariantColor = MaterialColors.getColor(holder.root, com.google.android.material.R.attr.colorOnSurfaceVariant, 0);

        holder.titleView.setText(entry.title);
        holder.subtitleView.setText(entry.subtitle);
        holder.subtitleView.setVisibility(entry.subtitle == null || entry.subtitle.length() == 0 ? View.GONE : View.VISIBLE);
        holder.kindView.setVisibility(View.VISIBLE);
        holder.kindView.setText(resolveItemCountLabel(holder, entry.itemCount));
        holder.titleView.setTextColor(onSurfaceColor);
        holder.subtitleView.setTextColor(onSurfaceVariantColor);
        holder.kindView.setTextColor(onSurfaceVariantColor);
        holder.root.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int bindingPosition = holder.getBindingAdapterPosition();
                if (listener != null && bindingPosition != RecyclerView.NO_POSITION) {
                    listener.onRecentImportClicked(items.get(bindingPosition));
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private String resolveItemCountLabel(ViewHolder holder, int itemCount) {
        if (itemCount == 1) {
            return holder.itemView.getContext().getString(R.string.folder_item_count, Integer.valueOf(1));
        }
        if (itemCount > 1) {
            return holder.itemView.getContext().getString(R.string.folder_item_count_plural, Integer.valueOf(itemCount));
        }
        return holder.itemView.getContext().getString(R.string.folder_item_count_plural, Integer.valueOf(0));
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        final View root;
        final TextView titleView;
        final TextView subtitleView;
        final TextView kindView;

        ViewHolder(View itemView) {
            super(itemView);
            root = itemView.findViewById(R.id.recent_root);
            titleView = (TextView) itemView.findViewById(R.id.recent_title);
            subtitleView = (TextView) itemView.findViewById(R.id.recent_subtitle);
            kindView = (TextView) itemView.findViewById(R.id.recent_kind);
        }
    }
}
