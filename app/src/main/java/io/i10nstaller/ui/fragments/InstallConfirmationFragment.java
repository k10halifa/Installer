package io.i10nstaller.ui.fragments;

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.i10nstaller.R;
import io.i10nstaller.model.InstallOptions;
import io.i10nstaller.model.InstallUserAction;

public class InstallConfirmationFragment extends BaseDialogFragment {

    private final InstallUserAction mDialogData;
    private AlertDialog mDialog;

    public InstallConfirmationFragment(InstallUserAction dialogData) {
        super(dialogData);
        mDialogData = dialogData;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        var context = requireContext();
        var dialogView = getLayoutInflater().inflate(R.layout.install_content_view, null);
        var message = (TextView) dialogView.requireViewById(R.id.message);
        message.setVisibility(View.VISIBLE);
        message.setText(R.string.install_confirm_question);
        dialogView.requireViewById(R.id.checkbox).setVisibility(View.GONE);
        dialogView.requireViewById(R.id.grant_permissions_checkbox).setVisibility(View.GONE);
        dialogView.requireViewById(R.id.delete_apk_checkbox).setVisibility(View.GONE);
        dialogView.requireViewById(R.id.silent_mode_checkbox).setVisibility(View.GONE);
        var full = mDialogData.getFullInstall();
        var removeSplit = false;
        if (!full) {
            for (String splitName : mDialogData.getOldApk().splitNames) {
                if (splitName.equals(mDialogData.getApkLite().getSplitName())) {
                    removeSplit = true;
                    break;
                }
            }
        }
        var remove = removeSplit;

        mDialog = new AlertDialog.Builder(context)
            .setIcon(mDialogData.getApkLite().getIcon())
            .setTitle(mDialogData.getApkLite().getLabel())
            .setView(dialogView)
            .setPositiveButton(R.string.install,
                (dialog, which) -> mViewModel.initiateInstall(
                    InstallOptions.getBoolean(
                        context, InstallOptions.KEY_SET_INSTALLER, false),
                    InstallOptions.getBoolean(
                        context, InstallOptions.KEY_GRANT_PERMISSIONS, false),
                    InstallOptions.getBoolean(
                        context, InstallOptions.KEY_DELETE_APK, false),
                    true,
                    full,
                    remove))
            .setNegativeButton(android.R.string.cancel,
                (dialog, which) -> cleanAndFinish())
            .create();
        mDialog.setCanceledOnTouchOutside(false);
        return mDialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        mDialog.getButton(AlertDialog.BUTTON_POSITIVE).setFilterTouchesWhenObscured(true);
    }

    @Override
    public void onPause() {
        super.onPause();
        mDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
    }

    @Override
    public void onResume() {
        super.onResume();
        mDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
    }
}
