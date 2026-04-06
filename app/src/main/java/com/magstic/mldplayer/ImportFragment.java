package com.magstic.mldplayer;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;

public final class ImportFragment extends Fragment implements PlayerController.UiListener {
    private TextView recentEmptyView;
    private RecyclerView recentImportsList;
    private MaterialButton openFolderButton;
    private RecentImportAdapter recentImportAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_import, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        final MainActivity activity = (MainActivity) requireActivity();

        openFolderButton = (MaterialButton) view.findViewById(R.id.open_folder_button);
        recentEmptyView = (TextView) view.findViewById(R.id.recent_empty_text);
        recentImportsList = (RecyclerView) view.findViewById(R.id.recent_imports_list);

        recentImportAdapter = new RecentImportAdapter(new RecentImportAdapter.Listener() {
            @Override
            public void onRecentImportClicked(RecentImportEntry entry) {
                activity.getController().onRecentImportSelected(entry);
                activity.navigateToPlayer();
            }
        });
        recentImportsList.setLayoutManager(new LinearLayoutManager(view.getContext()));
        recentImportsList.setAdapter(recentImportAdapter);
        applyTopInset(view);

        openFolderButton.setOnClickListener(v -> activity.getController().onOpenFolderRequested());
    }

    @Override
    public void onStart() {
        super.onStart();
        ((MainActivity) requireActivity()).getController().addListener(this);
    }

    @Override
    public void onStop() {
        ((MainActivity) requireActivity()).getController().removeListener(this);
        super.onStop();
    }

    @Override
    public void onImportUiStateChanged(PlayerController.ImportUiState state) {
        ArrayList<RecentImportEntry> folders;

        if (getView() == null) {
            return;
        }
        folders = filterFolderImports(state.recentImports);
        recentEmptyView.setVisibility(folders.isEmpty() ? View.VISIBLE : View.GONE);
        recentImportsList.setVisibility(folders.isEmpty() ? View.GONE : View.VISIBLE);
        recentImportAdapter.submit(folders);

        recentImportsList.setAlpha(state.scanning ? 0.72f : 1f);
        recentEmptyView.setAlpha(state.scanning ? 0.72f : 1f);
        openFolderButton.setEnabled(!state.scanning);
        openFolderButton.setAlpha(state.scanning ? 0.55f : 1f);
    }

    private void applyTopInset(View view) {
        final int baseTopPadding = view.getPaddingTop();

        ViewCompat.setOnApplyWindowInsetsListener(view, (target, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());

            target.setPadding(
                    target.getPaddingLeft(),
                    baseTopPadding + systemBars.top,
                    target.getPaddingRight(),
                    target.getPaddingBottom());
            return insets;
        });
        ViewCompat.requestApplyInsets(view);
    }

    private ArrayList<RecentImportEntry> filterFolderImports(java.util.List<RecentImportEntry> entries) {
        ArrayList<RecentImportEntry> folders = new ArrayList<RecentImportEntry>();
        int index;

        if (entries == null) {
            return folders;
        }
        for (index = 0; index < entries.size(); index++) {
            RecentImportEntry entry = entries.get(index);
            if (entry != null && RecentImportEntry.KIND_FOLDER.equals(entry.kind)) {
                folders.add(new RecentImportEntry(entry.kind, entry.uri, entry.title, entry.subtitle, entry.itemCount));
            }
        }
        return folders;
    }
}
