package io.i10nstaller.ui.fragments;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.i10nstaller.R;
import io.i10nstaller.model.InstallAborted;
import io.i10nstaller.model.InstallOptions;
import rikka.shizuku.Shizuku;

public class InstallErrorFragment extends BaseDialogFragment {

    private final InstallAborted mAborted;

    public InstallErrorFragment(InstallAborted aborted) {
        super(aborted);
        mAborted = aborted;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        var code = mAborted.getAbortReason();
        var context = requireContext();
        var builder = new AlertDialog.Builder(context)
            .setTitle(code == InstallAborted.ABORT_INFO ? R.string.app_name : R.string.error_title)
            .setIcon(R.drawable.ic_app_icon);
        if (code == InstallAborted.ABORT_INFO) {
            builder.setView(createOptionsView(context));
        } else {
            builder.setMessage(getErrorMessage(context, code));
        }
        return builder.setPositiveButton(android.R.string.ok,
                (dialog, which) -> {
                    cleanAndFinish();
                    if (code == InstallAborted.ABORT_SHIZUKU) {
                        checkShizuku(context);
                    }
                })
            .create();
    }

    private void checkShizuku(Context context) {
        if (Shizuku.pingBinder()) {
            Shizuku.requestPermission(1);
            return;
        }
        var intent = mAborted.getIntent();
        if (intent == null) {
            Uri web = Uri.parse(context.getString(R.string.shizuku_url));
            intent = new Intent(Intent.ACTION_VIEW, web);
        }
        requireActivity().startActivity(intent);
    }

    private String getErrorMessage(Context context, int code) {
        switch (code) {
            case InstallAborted.ABORT_SHIZUKU -> {
                if (!Shizuku.pingBinder()) {
                    if (mAborted.getIntent() == null) {
                        return context.getString(R.string.error_shizuku_notfound);
                    } else {
                        return context.getString(R.string.error_shizuku_notrunning);
                    }
                }
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    return context.getString(R.string.error_shizuku_notpermitted);
                } else {
                    return context.getString(R.string.error_shizuku_unavailable);
                }
            }
        }
        var id = switch (code) {
            case InstallAborted.ABORT_PARSE -> R.string.error_parse;
            case InstallAborted.ABORT_SPLIT -> R.string.error_split;
            case InstallAborted.ABORT_NOTFOUND -> R.string.error_notfound;
            case InstallAborted.ABORT_CREATE -> R.string.error_create;
            case InstallAborted.ABORT_WRITE -> R.string.error_write;
            default -> R.string.error_title;
        };
        return context.getString(id);
    }

    private LinearLayout createOptionsView(Context context) {
        int padding = (int) (20 * context.getResources().getDisplayMetrics().density);
        var content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding / 2, padding, 0);
        addOption(context, content, R.string.set_installer, InstallOptions.KEY_SET_INSTALLER);
        addOption(context, content, R.string.grant_permissions_battery,
            InstallOptions.KEY_GRANT_PERMISSIONS);
        addOption(context, content, R.string.delete_apk_after_install, InstallOptions.KEY_DELETE_APK);
        addOption(context, content, R.string.silent_mode, InstallOptions.KEY_SILENT_MODE);
        return content;
    }

    private void addOption(Context context, LinearLayout parent, int label, String key) {
        var checkbox = new CheckBox(context);
        checkbox.setText(label);
        checkbox.setChecked(InstallOptions.getBoolean(context, key, false));
        checkbox.setOnCheckedChangeListener((button, checked) ->
            InstallOptions.setBoolean(context, key, checked));
        parent.addView(checkbox);
    }
}
