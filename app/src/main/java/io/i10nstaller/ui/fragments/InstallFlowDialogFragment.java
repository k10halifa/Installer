package io.i10nstaller.ui.fragments;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.Space;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.i10nstaller.R;
import io.i10nstaller.model.InstallAborted;
import io.i10nstaller.model.InstallFailed;
import io.i10nstaller.model.InstallInstalling;
import io.i10nstaller.model.InstallOptions;
import io.i10nstaller.model.InstallStage;
import io.i10nstaller.model.InstallSuccess;
import io.i10nstaller.model.InstallUserAction;
import io.i10nstaller.model.PackageUserAction;
import rikka.shizuku.Shizuku;

public class InstallFlowDialogFragment extends BaseDialogFragment {

    private InstallStage mCurrentStage;
    private AlertDialog mDialog;
    private View mDialogView;
    private TextView mMessage;
    private ProgressBar mProgressBar;
    private CheckBox mArchiveCheckbox;
    private boolean mWindowSizeLocked;

    public InstallFlowDialogFragment(InstallStage initialStage) {
        super(initialStage);
        mCurrentStage = initialStage;
    }

    @Override
    protected boolean shouldHandleConfirmationDpadKeys() {
        return true;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        mDialogView = getLayoutInflater().inflate(R.layout.install_content_view, null);
        mMessage = mDialogView.requireViewById(R.id.message);
        mProgressBar = mDialogView.requireViewById(R.id.progress);
        mArchiveCheckbox = mDialogView.requireViewById(R.id.checkbox);

        mDialog = new AlertDialog.Builder(requireContext())
            .setView(mDialogView)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(android.R.string.cancel, null)
            .create();
        renderStage();
        return mDialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        mViewModel.getStagingProgress().observe(this, progress -> {
            if (mCurrentStage instanceof InstallInstalling) {
                mMessage.setText(progress <= 100 ? R.string.copying : R.string.installing);
                mProgressBar.setIndeterminate(progress < 0 || progress > 100);
                mProgressBar.setProgress(progress);
            }
        });
        configureButtons();
        renderStage();
        lockWindowSizeAfterFirstLayout();
    }

    public void updateStage(InstallStage stage) {
        mCurrentStage = stage;
        if (mDialog != null) {
            renderStage();
        }
    }

    private void renderStage() {
        Context context = requireContext();
        var title = context.getString(R.string.app_name);
        var icon = context.getDrawable(R.drawable.ic_app_icon);
        mMessage.setVisibility(View.VISIBLE);
        mMessage.setText("");
        mProgressBar.setVisibility(View.INVISIBLE);
        mArchiveCheckbox.setVisibility(View.GONE);
        mDialogView.requireViewById(R.id.grant_permissions_checkbox).setVisibility(View.GONE);
        mDialogView.requireViewById(R.id.delete_apk_checkbox).setVisibility(View.GONE);
        mDialogView.requireViewById(R.id.silent_mode_checkbox).setVisibility(View.GONE);

        if (mCurrentStage instanceof InstallUserAction action) {
            title = action.getApkLite().getLabel().toString();
            icon = action.getApkLite().getIcon();
            mMessage.setText(R.string.install_confirm_question);
            showButton(AlertDialog.BUTTON_POSITIVE, R.string.install, true, true);
            showButton(AlertDialog.BUTTON_NEGATIVE, android.R.string.cancel, true, true);
            hideButton(AlertDialog.BUTTON_NEUTRAL);
        } else if (mCurrentStage instanceof PackageUserAction action) {
            title = action.getApkLite().getLabel().toString();
            icon = action.getApkLite().getIcon();
            mMessage.setText(R.string.archive_confirm_question);
            mArchiveCheckbox.setVisibility(View.VISIBLE);
            mArchiveCheckbox.setText(R.string.uninstall_keep_data);
            showButton(AlertDialog.BUTTON_POSITIVE, R.string.archive, true, true);
            showButton(AlertDialog.BUTTON_NEGATIVE, android.R.string.cancel, true, true);
            var info = action.getOldApk().applicationInfo;
            if (info != null) {
                showButton(AlertDialog.BUTTON_NEUTRAL,
                    info.enabled ? R.string.disable : R.string.enable, true, true);
            } else {
                hideButton(AlertDialog.BUTTON_NEUTRAL);
            }
        } else if (mCurrentStage instanceof InstallInstalling installing) {
            title = installing.getApkLite().getLabel().toString();
            icon = installing.getApkLite().getIcon();
            mProgressBar.setVisibility(View.VISIBLE);
            mProgressBar.setIndeterminate(true);
            mMessage.setText(R.string.installing);
            showButton(AlertDialog.BUTTON_NEGATIVE, android.R.string.cancel, true, false);
            hideButton(AlertDialog.BUTTON_POSITIVE);
            hideButton(AlertDialog.BUTTON_NEUTRAL);
        } else if (mCurrentStage instanceof InstallSuccess success) {
            title = success.getApkLite().getLabel().toString();
            icon = success.getApkLite().getIcon();
            var message = success.getPath() != null
                ? context.getString(R.string.archive_done) + success.getPath()
                : context.getString(R.string.install_done);
            mMessage.setText(message);
            showButton(AlertDialog.BUTTON_NEGATIVE, R.string.done, true, true);
            if (success.getStartIntent() != null) {
                showButton(AlertDialog.BUTTON_POSITIVE, R.string.launch, true, true);
            } else {
                hideButton(AlertDialog.BUTTON_POSITIVE);
            }
            hideButton(AlertDialog.BUTTON_NEUTRAL);
        } else if (mCurrentStage instanceof InstallFailed failed) {
            title = failed.getApkLite().getLabel().toString();
            icon = failed.getApkLite().getIcon();
            mMessage.setText(getFailureMessage(failed));
            showButton(AlertDialog.BUTTON_POSITIVE, R.string.done, true, true);
            hideButton(AlertDialog.BUTTON_NEGATIVE);
            hideButton(AlertDialog.BUTTON_NEUTRAL);
        } else if (mCurrentStage instanceof InstallAborted aborted) {
            if (aborted.getAbortReason() == InstallAborted.ABORT_INFO) {
                mMessage.setVisibility(View.GONE);
                mDialogView.requireViewById(R.id.grant_permissions_checkbox)
                    .setVisibility(View.VISIBLE);
                mDialogView.requireViewById(R.id.delete_apk_checkbox)
                    .setVisibility(View.VISIBLE);
                mDialogView.requireViewById(R.id.silent_mode_checkbox)
                    .setVisibility(View.VISIBLE);
                showButton(AlertDialog.BUTTON_POSITIVE, android.R.string.ok, true, true);
            } else {
                title = context.getString(R.string.error_title);
                mMessage.setText(getAbortMessage(context, aborted));
                showButton(AlertDialog.BUTTON_POSITIVE, android.R.string.ok, true, true);
            }
            if (aborted.getAbortReason() == InstallAborted.ABORT_INFO) {
                configureOptionCheckBox(
                    R.id.grant_permissions_checkbox, InstallOptions.KEY_GRANT_PERMISSIONS);
                configureOptionCheckBox(
                    R.id.delete_apk_checkbox, InstallOptions.KEY_DELETE_APK);
                configureOptionCheckBox(
                    R.id.silent_mode_checkbox, InstallOptions.KEY_SILENT_MODE);
            }
            hideButton(AlertDialog.BUTTON_NEGATIVE);
            hideButton(AlertDialog.BUTTON_NEUTRAL);
        } else {
            mMessage.setText(R.string.parsing);
            mProgressBar.setVisibility(View.VISIBLE);
            mProgressBar.setIndeterminate(true);
            showButton(AlertDialog.BUTTON_NEGATIVE, android.R.string.cancel, true, true);
            hideButton(AlertDialog.BUTTON_POSITIVE);
            hideButton(AlertDialog.BUTTON_NEUTRAL);
        }

        mDialog.setTitle(title);
        mDialog.setIcon(icon);
        boolean active = mCurrentStage instanceof InstallInstalling
            || mCurrentStage.getStageCode() == InstallStage.STAGE_PARSE;
        setCancelable(!active);
        mDialog.setCanceledOnTouchOutside(!active);
        updateButtonBarDividers();
        if (mDialog.isShowing()) {
            focusPositiveButtonForDpad();
        }
    }

    private void configureButtons() {
        var positive = mDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        var negative = mDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        var neutral = mDialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        for (var button : new android.widget.Button[] { positive, negative, neutral }) {
            button.setFocusable(true);
            button.setFocusableInTouchMode(false);
            button.setClickable(true);
        }
        positive.setOnClickListener(view -> onPositiveClick());
        negative.setOnClickListener(view -> onNegativeClick());
        neutral.setOnClickListener(view -> onNeutralClick());
        positive.setFilterTouchesWhenObscured(true);
    }

    private void showButton(int which, int label, boolean visible, boolean enabled) {
        var button = mDialog.getButton(which);
        if (button == null) {
            return;
        }
        button.setText(label);
        button.setVisibility(visible ? View.VISIBLE : View.GONE);
        button.setEnabled(enabled);
    }

    private void hideButton(int which) {
        var button = mDialog.getButton(which);
        if (button != null) {
            button.setVisibility(View.GONE);
            button.setEnabled(false);
        }
    }

    private void updateButtonBarDividers() {
        var positiveButton = mDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positiveButton == null || !(positiveButton.getParent() instanceof ViewGroup buttonBar)) {
            return;
        }

        int dividerMaxWidth = (int) (4 * getResources().getDisplayMetrics().density + 0.5f);
        for (int i = 0; i < buttonBar.getChildCount(); i++) {
            View child = buttonBar.getChildAt(i);
            if (child instanceof android.widget.Button || child instanceof Space
                || !isButtonBarDivider(child, dividerMaxWidth)) {
                continue;
            }

            boolean visibleButtonBefore = false;
            boolean visibleButtonAfter = false;
            for (int j = 0; j < i; j++) {
                if (isVisibleButton(buttonBar.getChildAt(j))) {
                    visibleButtonBefore = true;
                    break;
                }
            }
            for (int j = i + 1; j < buttonBar.getChildCount(); j++) {
                if (isVisibleButton(buttonBar.getChildAt(j))) {
                    visibleButtonAfter = true;
                    break;
                }
            }
            child.setVisibility(visibleButtonBefore && visibleButtonAfter
                ? View.VISIBLE : View.GONE);
        }

        buttonBar.requestLayout();
        buttonBar.invalidate();
    }

    private boolean isButtonBarDivider(View child, int maxWidth) {
        int id = child.getId();
        if (id != View.NO_ID) {
            try {
                if (getResources().getResourceEntryName(id).startsWith("sem_divider")) {
                    return true;
                }
            } catch (Resources.NotFoundException ignored) {
                // Views without a named resource ID can still be detected by their width.
            }
        }
        ViewGroup.LayoutParams layoutParams = child.getLayoutParams();
        int width = child.getWidth() > 0 ? child.getWidth()
            : layoutParams != null ? layoutParams.width : ViewGroup.LayoutParams.MATCH_PARENT;
        return width >= 0 && width <= maxWidth;
    }

    private boolean isVisibleButton(View child) {
        return child instanceof android.widget.Button && child.getVisibility() == View.VISIBLE;
    }

    private void lockWindowSizeAfterFirstLayout() {
        var window = mDialog.getWindow();
        if (window == null) {
            return;
        }
        var decorView = window.getDecorView();
        decorView.post(() -> {
            if (mWindowSizeLocked || decorView.getWidth() == 0 || decorView.getHeight() == 0) {
                return;
            }
            mWindowSizeLocked = true;
            window.setLayout(decorView.getWidth(), decorView.getHeight());
        });
    }

    private void onPositiveClick() {
        if (mCurrentStage instanceof InstallUserAction action) {
            mDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            try {
                Context context = requireContext();
                mViewModel.initiateInstall(
                    InstallOptions.getBoolean(
                        context, InstallOptions.KEY_SET_INSTALLER, false),
                    InstallOptions.getBoolean(
                        context, InstallOptions.KEY_GRANT_PERMISSIONS, false),
                    InstallOptions.getBoolean(
                        context, InstallOptions.KEY_DELETE_APK, false),
                    true,
                    action.getFullInstall(),
                    shouldRemoveSplit(action));
            } catch (Exception e) {
                mViewModel.reportUnexpectedFailure(e);
            }
        } else if (mCurrentStage instanceof PackageUserAction action) {
            mDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            mViewModel.archivePackage(action.getOldApk(), mArchiveCheckbox.isChecked());
        } else if (mCurrentStage instanceof InstallSuccess success) {
            finishAndOpen(success.getStartIntent());
        } else if (mCurrentStage instanceof InstallFailed) {
            cleanAndFinish();
        } else if (mCurrentStage instanceof InstallAborted aborted) {
            cleanAndFinish();
            if (aborted.getAbortReason() == InstallAborted.ABORT_SHIZUKU) {
                checkShizuku(aborted);
            }
        }
    }

    private void onNegativeClick() {
        cleanAndFinish();
    }

    private void onNeutralClick() {
        if (mCurrentStage instanceof PackageUserAction action) {
            var info = action.getOldApk().applicationInfo;
            if (info != null) {
                cleanAndFinish();
                mViewModel.setPackageEnabled(info.packageName, !info.enabled);
            }
        }
    }

    private boolean shouldRemoveSplit(InstallUserAction action) {
        if (action.getFullInstall()) {
            return false;
        }
        for (String splitName : action.getOldApk().splitNames) {
            if (splitName.equals(action.getApkLite().getSplitName())) {
                return true;
            }
        }
        return false;
    }

    private String getFailureMessage(InstallFailed failed) {
        int resource = switch (failed.getStatusCode()) {
            case PackageInstaller.STATUS_FAILURE_BLOCKED -> R.string.install_failed_blocked;
            case PackageInstaller.STATUS_FAILURE_INVALID -> R.string.install_failed_invalid_apk;
            case PackageInstaller.STATUS_FAILURE_CONFLICT -> R.string.install_failed_conflict;
            case PackageInstaller.STATUS_FAILURE_STORAGE -> R.string.install_failed_storage;
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                R.string.install_failed_incompatible;
            default -> R.string.install_failed;
        };
        var message = new StringBuilder(requireContext().getString(resource))
            .append(" (").append(failed.getLegacyCode()).append(")");
        if (failed.getMessage() != null) {
            message.append("\n\n").append(failed.getMessage());
        }
        return message.toString();
    }

    private String getAbortMessage(Context context, InstallAborted aborted) {
        if (aborted.getMessage() != null && !aborted.getMessage().isBlank()) {
            return aborted.getMessage();
        }
        return switch (aborted.getAbortReason()) {
            case InstallAborted.ABORT_SHIZUKU -> {
                if (!Shizuku.pingBinder()) {
                    yield context.getString(aborted.getIntent() == null
                        ? R.string.error_shizuku_notfound : R.string.error_shizuku_notrunning);
                }
                yield context.getString(Shizuku.checkSelfPermission()
                    != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ? R.string.error_shizuku_notpermitted : R.string.error_shizuku_unavailable);
            }
            case InstallAborted.ABORT_PARSE -> context.getString(R.string.error_parse);
            case InstallAborted.ABORT_SPLIT -> context.getString(R.string.error_split);
            case InstallAborted.ABORT_NOTFOUND -> context.getString(R.string.error_notfound);
            case InstallAborted.ABORT_CREATE -> context.getString(R.string.error_create);
            case InstallAborted.ABORT_WRITE -> context.getString(R.string.error_write);
            default -> context.getString(R.string.error_title);
        };
    }

    private void finishAndOpen(Intent intent) {
        cleanAndFinish();
        if (intent != null) {
            requireActivity().startActivity(intent);
        }
    }

    private void checkShizuku(InstallAborted aborted) {
        Context context = requireContext();
        if (Shizuku.pingBinder()) {
            Shizuku.requestPermission(1);
            return;
        }
        Intent intent = aborted.getIntent();
        if (intent == null) {
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(R.string.shizuku_url)));
        }
        requireActivity().startActivity(intent);
    }

    private void configureOptionCheckBox(int viewId, String optionKey) {
        var checkBox = (CheckBox) mDialogView.requireViewById(viewId);
        checkBox.setChecked(InstallOptions.getBoolean(requireContext(), optionKey, false));
        checkBox.setOnCheckedChangeListener((button, checked) ->
            InstallOptions.setBoolean(requireContext(), optionKey, checked));
    }
}
