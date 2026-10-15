package io.i10nstaller.ui.fragments;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.i10nstaller.R;
import io.i10nstaller.model.InstallInstalling;

public class InstallInstallingFragment extends BaseDialogFragment {

    private final InstallInstalling mDialogData;
    private AlertDialog mDialog;

    public InstallInstallingFragment(InstallInstalling dialogData) {
        super(dialogData);
        mDialogData = dialogData;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View dialogView = getLayoutInflater().inflate(R.layout.install_content_view, null);
        dialogView.requireViewById(R.id.grant_permissions_checkbox).setVisibility(View.GONE);
        dialogView.requireViewById(R.id.delete_apk_checkbox).setVisibility(View.GONE);
        dialogView.requireViewById(R.id.silent_mode_checkbox).setVisibility(View.GONE);
        TextView textView = dialogView.requireViewById(R.id.message);
        ProgressBar progressBar = dialogView.requireViewById(R.id.progress);
        progressBar.setVisibility(View.VISIBLE);
        mViewModel.getStagingProgress().observe(this, progress -> {
            textView.setText(progress <= 100 ? R.string.copying : R.string.installing);
            progressBar.setIndeterminate(progress < 0 || progress > 100);
            progressBar.setProgress(progress);
        });
        setCancelable(false);
        mDialog = new AlertDialog.Builder(requireContext())
            .setTitle(mDialogData.getApkLite().getLabel())
            .setIcon(mDialogData.getApkLite().getIcon())
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        return mDialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        mDialog.getButton(DialogInterface.BUTTON_NEGATIVE).setEnabled(false);
    }
}
