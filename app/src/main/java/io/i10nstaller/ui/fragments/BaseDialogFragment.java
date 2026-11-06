package io.i10nstaller.ui.fragments;

import android.app.AlertDialog;
import android.app.UiModeManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;

import io.i10nstaller.model.InstallStage;
import io.i10nstaller.viewmodel.InstallViewModel;

public class BaseDialogFragment extends DialogFragment {

    public final InstallStage mDialogData;
    InstallViewModel mViewModel;

    public BaseDialogFragment(InstallStage dialogData) {
        mDialogData = dialogData;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mViewModel = new ViewModelProvider(requireActivity())
            .get(InstallViewModel.class);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() instanceof AlertDialog alertDialog) {
            boolean isTvDevice = isTvDevice();
            for (int which : new int[] {
                AlertDialog.BUTTON_POSITIVE,
                AlertDialog.BUTTON_NEGATIVE,
                AlertDialog.BUTTON_NEUTRAL
            }) {
                var button = alertDialog.getButton(which);
                if (button != null) {
                    button.setFocusable(true);
                    button.setFocusableInTouchMode(
                        isTvDevice && which == AlertDialog.BUTTON_POSITIVE);
                    button.setClickable(true);
                }
            }
        }
    }

    private boolean isTvDevice() {
        var context = requireContext();
        var uiModeManager =
            (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
        return (uiModeManager != null
                && uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION)
            || !context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
    }

    protected void focusPositiveButtonForDpad() {
        if (getDialog() instanceof AlertDialog alertDialog) {
            var positiveButton = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positiveButton != null) {
                boolean isTvDevice = isTvDevice();
                positiveButton.post(() -> {
                    if (isTvDevice || !positiveButton.isInTouchMode()) {
                        positiveButton.requestFocus();
                    }
                });
            }
        }
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        cleanAndFinish();
    }

    void cleanAndFinish() {
        mViewModel.cleanupInstall();
        requireActivity().finish();
    }
}
