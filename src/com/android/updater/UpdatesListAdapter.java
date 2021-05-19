/*
 * Copyright (C) 2017 The LineageOS Project
 * Copyright (C) 2019 The PixelExperience Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.updater;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.text.method.LinkMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.appcompat.view.menu.MenuBuilder;
import androidx.appcompat.view.menu.MenuPopupHelper;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.RecyclerView;

import com.android.updater.controller.UpdaterController;
import com.android.updater.controller.UpdaterService;
import com.android.updater.misc.Constants;
import com.android.updater.misc.PermissionsUtils;
import com.android.updater.misc.StringGenerator;
import com.android.updater.misc.Utils;
import com.android.updater.model.UpdateInfo;
import com.android.updater.model.UpdateStatus;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.MarkwonConfiguration;
import io.noties.markwon.MarkwonPlugin;
import io.noties.markwon.RenderProps;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.html.HtmlPlugin;
import io.noties.markwon.html.HtmlTag;
import io.noties.markwon.html.tag.SimpleTagHandler;

public class UpdatesListAdapter extends RecyclerView.Adapter<UpdatesListAdapter.ViewHolder> {

    private static final String TAG = "UpdateListAdapter";

    private final float mAlphaDisabledValue;

    private List<String> mDownloadIds;
    private String mSelectedDownload;
    private UpdaterController mUpdaterController;
    private UpdatesListActivity mActivity;

    UpdatesListAdapter(UpdatesListActivity activity) {
        mActivity = activity;

        TypedValue tv = new TypedValue();
        mActivity.getTheme().resolveAttribute(android.R.attr.disabledAlpha, tv, true);
        mAlphaDisabledValue = tv.getFloat();
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup viewGroup, int i) {
        View view = LayoutInflater.from(viewGroup.getContext())
                .inflate(R.layout.update_item_view, viewGroup, false);
        return new ViewHolder(view);
    }

    void setUpdaterController(UpdaterController updaterController) {
        mUpdaterController = updaterController;
        notifyDataSetChanged();
    }

    @SuppressLint({"SetTextI18n", "StringFormatInvalid"})
    private void handleActiveStatus(ViewHolder viewHolder, UpdateInfo update) {
        boolean canDelete = false;
        if (update.getStatus() == UpdateStatus.PAUSED_ERROR) {
            viewHolder.mHeaderText.setText(R.string.download_paused_error_notification);
        }
        final String downloadId = update.getDownloadId();
        if (mUpdaterController.isDownloading(downloadId)) {
            viewHolder.mHeaderText.setText(R.string.downing_update);
            canDelete = false;
            String downloaded = Utils.readableFileSize(update.getFile().length());
            String total = Utils.readableFileSize(update.getFileSize());
            String percentage = NumberFormat.getPercentInstance().format(
                    update.getProgress() / 100.f);
            long eta = update.getEta();
            if (eta > 0) {
                CharSequence etaString = StringGenerator.formatETA(mActivity, eta * 1000);
                viewHolder.mProgressText.setText(mActivity.getString(
                        R.string.list_download_progress_eta_new, downloaded, total, etaString,
                        percentage));
            } else {
                viewHolder.mProgressText.setText(mActivity.getString(
                        R.string.list_download_progress_new, downloaded, total, percentage));
            }
            setButtonAction(viewHolder.mAction, Action.PAUSE, downloadId, true,viewHolder);
            viewHolder.mProgressBar.setIndeterminate(update.getStatus() == UpdateStatus.STARTING);
            viewHolder.mProgressBar.setProgress(update.getProgress());
        } else if (mUpdaterController.isInstallingUpdate(downloadId)) {
            viewHolder.mHeaderText.setText(R.string.installing_update);
            setButtonAction(viewHolder.mAction, Action.CANCEL_INSTALLATION, downloadId, true,viewHolder);
            boolean notAB = !mUpdaterController.isInstallingABUpdate();
            viewHolder.mProgressText.setText(notAB ? R.string.dialog_prepare_zip_message :
                    update.getFinalizing() ?
                            R.string.finalizing_package :
                            R.string.preparing_ota_first_boot);
            viewHolder.mProgressBar.setIndeterminate(false);
            viewHolder.mProgressBar.setProgress(update.getInstallProgress());
        } else if (mUpdaterController.isVerifyingUpdate(downloadId)) {
            viewHolder.mHeaderText.setText(R.string.downing_update);
            setButtonAction(viewHolder.mAction, Action.INSTALL, downloadId, false,viewHolder);
            viewHolder.mProgressText.setText(R.string.list_verifying_update);
            viewHolder.mProgressBar.setIndeterminate(true);
        } else {
            canDelete = true;
            viewHolder.mHeaderText.setText(R.string.download_paused_notification);
            setButtonAction(viewHolder.mAction, Action.RESUME, downloadId, !isBusy(),viewHolder);
            String downloaded = Utils.readableFileSize(update.getFile().length());
            String total = Utils.readableFileSize(update.getFileSize());
            String percentage = NumberFormat.getPercentInstance().format(
                    update.getProgress() / 100.f);
            viewHolder.mProgressText.setText(mActivity.getString(R.string.list_download_progress_new,
                    downloaded, total, percentage));
            viewHolder.mProgressBar.setIndeterminate(false);
            viewHolder.mProgressBar.setProgress(update.getProgress());
        }
        int isdownable = Integer.valueOf(Constants.isdownloadable);
        if (isdownable == 1) {
            viewHolder.mAction.setVisibility(View.VISIBLE);
            viewHolder.mBuildSize.setVisibility(View.VISIBLE);
            viewHolder.mDownTip.setVisibility(View.VISIBLE);
        } else if (isdownable == 0) {
            viewHolder.mAction.setVisibility(View.INVISIBLE);
            viewHolder.mBuildSize.setVisibility(View.GONE);
            viewHolder.mDownTip.setVisibility(View.GONE);
        }
        viewHolder.mNotificationContent.setText(String.format(mActivity.getResources()
                .getString(R.string.notification_content), Constants.maintainer));
        viewHolder.markwoncontent.setMarkdown(viewHolder.mWhatsNew, Constants.whatnew);
        viewHolder.markwoncontent.setMarkdown(viewHolder.mAdvisorycontent, Constants.advisory);
        viewHolder.mAvatar.setImageBitmap(Utils.decodeBASE64Image(Constants.avatarbitmap));

        String fileSize = Utils.readableFileSize(update.getFileSize());
        viewHolder.mBuildSize.setText(String
                .format(mActivity.getResources().getString(R.string.update_size), fileSize));

        viewHolder.mLongPressMenu.setOnLongClickListener(getLongClickListener(update, canDelete,
                viewHolder.mLongPressMenu));
        viewHolder.mProgressBar.setVisibility(View.VISIBLE);
        viewHolder.mProgressText.setVisibility(View.VISIBLE);
    }

    private void handleNotActiveStatus(ViewHolder viewHolder, UpdateInfo update) {
        final String downloadId = update.getDownloadId();
        if (mUpdaterController.isWaitingForReboot(downloadId)) {

            viewHolder.mLongPressMenu.setOnLongClickListener(
                    getLongClickListener(update, false, viewHolder.mLongPressMenu));
            setButtonAction(viewHolder.mAction, Action.REBOOT, downloadId, true,viewHolder);
        } else if (update.getPersistentStatus() == UpdateStatus.Persistent.VERIFIED) {
            viewHolder.mHeaderText.setText(R.string.download_completed_notification);
            viewHolder.mLongPressMenu.setOnLongClickListener(
                    getLongClickListener(update, true, viewHolder.mLongPressMenu));
            setButtonAction(viewHolder.mAction,
                    Utils.canInstall(update) ? Action.INSTALL : Action.DELETE,
                    downloadId, !isBusy(),viewHolder);
        } else if (!Utils.canInstall(update)) {
            viewHolder.mLongPressMenu.setOnLongClickListener(
                    getLongClickListener(update, false, viewHolder.mLongPressMenu));
            setButtonAction(viewHolder.mAction, Action.INFO, downloadId, !isBusy(),viewHolder);
        } else {
            viewHolder.mLongPressMenu.setOnLongClickListener(
                    getLongClickListener(update, false, viewHolder.mLongPressMenu));
            setButtonAction(viewHolder.mAction, Action.DOWNLOAD, downloadId, !isBusy(),viewHolder);
        }
        int isdownable = Integer.valueOf(Constants.isdownloadable);
        if (isdownable == 1) {
            viewHolder.mAction.setVisibility(View.VISIBLE);
            viewHolder.mBuildSize.setVisibility(View.VISIBLE);
            viewHolder.mDownTip.setVisibility(View.VISIBLE);
        } else if (isdownable == 0) {
            viewHolder.mAction.setVisibility(View.INVISIBLE);
            viewHolder.mBuildSize.setVisibility(View.GONE);
            viewHolder.mDownTip.setVisibility(View.GONE);
        }
        viewHolder.mNotificationContent.setText(String.format(mActivity.getResources()
                .getString(R.string.notification_content),Constants.maintainer));
        viewHolder.markwoncontent.setMarkdown(viewHolder.mWhatsNew, Constants.whatnew);
        viewHolder.markwoncontent.setMarkdown(viewHolder.mAdvisorycontent, Constants.advisory);
        viewHolder.mAvatar.setImageBitmap(Utils.decodeBASE64Image(Constants.avatarbitmap));

        String fileSize = Utils.readableFileSize(update.getFileSize());
        viewHolder.mBuildSize.setText(String
                .format(mActivity.getResources().getString(R.string.update_size), fileSize));

        viewHolder.mProgressBar.setVisibility(View.INVISIBLE);
        viewHolder.mProgressText.setVisibility(View.INVISIBLE);
    }

    @Override
    public void onBindViewHolder(final ViewHolder viewHolder, int i) {
        if (mDownloadIds == null) {
            viewHolder.mAction.setEnabled(false);
            return;
        }

        final String downloadId = mDownloadIds.get(i);
        UpdateInfo update = mUpdaterController.getUpdate(downloadId);
        if (update == null) {
            // The updater was deleted
            viewHolder.mAction.setEnabled(false);
            viewHolder.mAction.setText(R.string.action_download);
            return;
        }

        viewHolder.itemView.setSelected(downloadId.equals(mSelectedDownload));

        boolean activeLayout;
        switch (update.getPersistentStatus()) {
            case UpdateStatus.Persistent.UNKNOWN:
                activeLayout = update.getStatus() == UpdateStatus.STARTING;
                break;
            case UpdateStatus.Persistent.VERIFIED:
                activeLayout = update.getStatus() == UpdateStatus.INSTALLING;
                break;
            case UpdateStatus.Persistent.INCOMPLETE:
                activeLayout = true;
                break;
            default:
                throw new RuntimeException("Unknown updater status");
        }

        viewHolder.mNotificationContent.setText(String.format(mActivity.getResources()
                .getString(R.string.notification_content),Constants.maintainer));
        viewHolder.markwoncontent.setMarkdown(viewHolder.mWhatsNew, Constants.whatnew);
        viewHolder.markwoncontent.setMarkdown(viewHolder.mAdvisorycontent, Constants.advisory);
        viewHolder.mAvatar.setImageBitmap(Utils.decodeBASE64Image(Constants.avatarbitmap));

        String buildDate = StringGenerator.getDateLocalizedUTC(mActivity,
                DateFormat.LONG, update.getTimestamp());
        String buildVersion = update.getName();
        viewHolder.mBuildDate.setText(buildDate);

        if (activeLayout) {
            handleActiveStatus(viewHolder, update);
        } else {
            handleNotActiveStatus(viewHolder, update);
        }
    }

    @Override
    public int getItemCount() {
        return mDownloadIds == null ? 0 : mDownloadIds.size();
    }

    public void setData(List<String> downloadIds) {
        mDownloadIds = downloadIds;
    }

    void notifyItemChanged(String downloadId) {
        if (mDownloadIds == null) {
            return;
        }
        notifyItemChanged(mDownloadIds.indexOf(downloadId));
    }

    void removeItem(String downloadId) {
        if (mDownloadIds == null) {
            return;
        }
        int position = mDownloadIds.indexOf(downloadId);
        mDownloadIds.remove(downloadId);
        notifyItemRemoved(position);
        notifyItemRangeChanged(position, getItemCount());
    }

    private void startDownloadWithWarning(final String downloadId) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mActivity);
        boolean warn = preferences.getBoolean(Constants.PREF_MOBILE_DATA_WARNING, true);
        if (Utils.isOnWifiOrEthernet(mActivity) || !warn) {
            mActivity.findViewById(R.id.mobile_data_warning).setVisibility(View.GONE);
            mUpdaterController.startDownload(downloadId);
            return;
        }

        View checkboxView = LayoutInflater.from(mActivity).inflate(R.layout.checkbox_view, null);
        CheckBox checkbox = checkboxView.findViewById(R.id.checkbox);
        checkbox.setText(R.string.checkbox_mobile_data_warning);

        mActivity.findViewById(R.id.mobile_data_warning).setVisibility(View.VISIBLE);

        new AlertDialog.Builder(mActivity, R.style.AppTheme_AlertDialogStyle)
                .setTitle(R.string.update_on_mobile_data_title)
                .setMessage(R.string.update_on_mobile_data_message)
                .setView(checkboxView)
                .setPositiveButton(R.string.action_download,
                        (dialog, which) -> {
                            if (checkbox.isChecked()) {
                                preferences.edit()
                                        .putBoolean(Constants.PREF_MOBILE_DATA_WARNING, false)
                                        .apply();
                            }
                            mUpdaterController.startDownload(downloadId);
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void setButtonAction(Button button, Action action, final String downloadId, boolean enabled,ViewHolder viewHolder) {
        final View.OnClickListener clickListener;
        switch (action) {
            case DOWNLOAD:
                button.setText(R.string.action_download);
                button.setEnabled(enabled);
                clickListener = enabled ? view -> startDownloadWithWarning(downloadId) : null;
                break;
            case PAUSE:
                button.setText(R.string.action_pause);
                button.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_pause, 0, 0, 0);
                button.setEnabled(enabled);
                clickListener = enabled ? view -> mUpdaterController.pauseDownload(downloadId)
                        : null;
                break;
            case RESUME: {
                button.setText(R.string.action_resume);
                button.setEnabled(enabled);
                UpdateInfo update = mUpdaterController.getUpdate(downloadId);
                viewHolder.mHeaderText.setText(R.string.download_paused_notification);
                final boolean canInstall = Utils.canInstall(update) ||
                        update.getFile().length() == update.getFileSize();
                clickListener = enabled ? view -> {
                    if (canInstall) {
                        mUpdaterController.resumeDownload(downloadId);
                    } else {
                        mActivity.showToast(R.string.snack_update_not_installable,
                                Toast.LENGTH_LONG);
                    }
                } : null;
            }
            break;
            case INSTALL: {
                button.setText(R.string.action_install);
                button.setEnabled(enabled);
                UpdateInfo update = mUpdaterController.getUpdate(downloadId);
                final boolean canInstall = Utils.canInstall(update);
                clickListener = enabled ? view -> {
                    if (canInstall) {
                        getInstallDialog(downloadId).show();
                    } else {
                        mActivity.showToast(R.string.snack_update_not_installable,
                                Toast.LENGTH_LONG);
                    }
                } : null;
            }
            break;
            case INFO: {
                button.setText(R.string.details_button);
                button.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_updateui_new, 0, 0, 0);
                button.setEnabled(enabled);
                clickListener = enabled ? view -> showInfoDialog() : null;
            }
            break;
            case DELETE: {
                button.setText(R.string.action_delete);
                button.setEnabled(enabled);
                clickListener = enabled ? view -> getDeleteDialog(downloadId).show() : null;
            }
            break;
            case CANCEL_INSTALLATION: {
                button.setText(R.string.action_cancel);
                button.setEnabled(enabled);
                clickListener = enabled ? view -> getCancelInstallationDialog().show() : null;
            }
            break;
            case REBOOT: {
                button.setText(R.string.reboot);
                button.setEnabled(enabled);
                clickListener = enabled ? view -> {
                    PowerManager pm =
                            (PowerManager) mActivity.getSystemService(Context.POWER_SERVICE);
                    pm.reboot(null);
                } : null;
            }
            break;
            default:
                clickListener = null;
        }
        button.setAlpha(enabled ? 1.f : mAlphaDisabledValue);

        // Disable action mode when a button is clicked
        button.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onClick(v);
            }
        });
    }

    private boolean isBusy() {
        return mUpdaterController.hasActiveDownloads() || mUpdaterController.isVerifyingUpdate()
                || mUpdaterController.isInstallingUpdate();
    }

    private AlertDialog.Builder getDeleteDialog(final String downloadId) {
        return new AlertDialog.Builder(mActivity, R.style.AppTheme_AlertDialogStyle)
                .setTitle(R.string.confirm_delete_dialog_title)
                .setMessage(R.string.confirm_delete_dialog_message)
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> {
                            mUpdaterController.pauseDownload(downloadId);
                            mUpdaterController.deleteUpdate(downloadId);
                        })
                .setNegativeButton(android.R.string.cancel, null);
    }

    private View.OnLongClickListener getLongClickListener(final UpdateInfo update,final boolean canDelete, View anchor) {
        return view -> {
            startActionMode(update, canDelete, anchor);
            return true;
        };
    }

    private AlertDialog.Builder getInstallDialog(final String downloadId) {
        if (!isBatteryLevelOk()) {
            Resources resources = mActivity.getResources();
            String message = resources.getString(R.string.dialog_battery_low_message_pct,
                    resources.getInteger(R.integer.battery_ok_percentage_discharging),
                    resources.getInteger(R.integer.battery_ok_percentage_charging));
            return new AlertDialog.Builder(mActivity, R.style.AppTheme_AlertDialogStyle)
                    .setTitle(R.string.dialog_battery_low_title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null);
        }
        UpdateInfo update = mUpdaterController.getUpdate(downloadId);
        int resId;
        String extraMessage = "";
        try {
            if (Utils.isABUpdate(update.getFile())) {
                resId = R.string.apply_update_dialog_message_ab;
            } else {
                resId = R.string.apply_update_dialog_message;
                extraMessage = " (" + Constants.DOWNLOAD_PATH + ")";
            }
        } catch (IOException e) {
            Log.e(TAG, "Could not determine the type of the updater");
            return null;
        }

        return new AlertDialog.Builder(mActivity, R.style.AppTheme_AlertDialogStyle)
                .setTitle(R.string.apply_update_dialog_title)
                .setMessage(mActivity.getString(resId,update.getName(),
                        mActivity.getString(android.R.string.ok)) + extraMessage)
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> Utils.triggerUpdate(mActivity, downloadId))
                .setNegativeButton(android.R.string.cancel, null);
    }

    private AlertDialog.Builder getCancelInstallationDialog() {
        return new AlertDialog.Builder(mActivity, R.style.AppTheme_AlertDialogStyle)
                .setMessage(R.string.cancel_installation_dialog_message)
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> {
                            Intent intent = new Intent(mActivity, UpdaterService.class);
                            intent.setAction(UpdaterService.ACTION_INSTALL_STOP);
                            mActivity.startService(intent);
                        })
                .setNegativeButton(android.R.string.cancel, null);
    }

    @SuppressLint("RestrictedApi")
    private void startActionMode( final UpdateInfo update, final boolean canDelete, View anchor) {
        mSelectedDownload = update.getDownloadId();
        notifyItemChanged(update.getDownloadId());

        ContextThemeWrapper wrapper = new ContextThemeWrapper(mActivity,
                R.style.AppTheme_PopupMenuOverlapAnchor);
        PopupMenu popupMenu = new PopupMenu(wrapper, anchor, Gravity.NO_GRAVITY,
                R.attr.actionOverflowMenuStyle, 0);
        popupMenu.inflate(R.menu.menu_action_mode);

        int isdownable = Integer.valueOf(Constants.isdownloadable);

        MenuBuilder menu = (MenuBuilder) popupMenu.getMenu();
        menu.findItem(R.id.menu_delete_action).setVisible(canDelete);
        menu.findItem(R.id.menu_copy_url).setVisible(update.getAvailableOnline() && isdownable == 1);
        menu.findItem(R.id.menu_history_changes).setVisible(true);
        menu.findItem(R.id.menu_open_maintainer_uri).setVisible(true);
        menu.findItem(R.id.menu_open_post).setVisible(true);
        menu.findItem(R.id.menu_open_donation_qrcode).setVisible(true);
        menu.findItem(R.id.menu_export_update).setVisible(
                update.getPersistentStatus() == UpdateStatus.Persistent.VERIFIED);

        popupMenu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case R.id.menu_delete_action:
                    getDeleteDialog(update.getDownloadId()).show();
                    return true;
                case R.id.menu_copy_url:
                    Utils.addToClipboard(mActivity,
                            mActivity.getString(R.string.label_download_url),
                            update.getDownloadUrl(),
                            mActivity.getString(R.string.toast_download_url_copied));
                    return true;
                case R.id.menu_history_changes:
                    Intent intentl=new Intent(mActivity,LocalChangelogActivity.class);
                    mActivity.startActivity(intentl);
                    return true;
                case R.id.menu_open_maintainer_uri:
                    Uri uri=Uri.parse(Constants.maintainer_url);
                    Intent intent=new Intent(Intent.ACTION_VIEW,uri);
                    mActivity.startActivity(intent);
                    return true;
                case R.id.menu_open_post:
                    Uri uri2=Uri.parse(Constants.postlink);
                    Intent intent2=new Intent(Intent.ACTION_VIEW,uri2);
                    mActivity.startActivity(intent2);
                    return true;
                case R.id.menu_open_donation_qrcode:
                    showDonationDialog();
                    return true;
                case R.id.menu_export_update:
                    // TODO: start exporting once the permission has been granted
                    boolean hasPermission = PermissionsUtils.checkAndRequestStoragePermission(
                            mActivity, 0);
                    if (hasPermission) {
                        exportUpdate(update);
                    }
                    return true;
            }
            return false;
        });

        MenuPopupHelper helper = new MenuPopupHelper(wrapper, menu, anchor);
        helper.show();
    }

    private void exportUpdate(UpdateInfo update) {
        File dest = new File(Utils.getExportPath(mActivity), update.getName());
        if (dest.exists()) {
            dest = Utils.appendSequentialNumber(dest);
        }
        Intent intent = new Intent(mActivity, ExportUpdateService.class);
        intent.setAction(ExportUpdateService.ACTION_START_EXPORTING);
        intent.putExtra(ExportUpdateService.EXTRA_SOURCE_FILE, update.getFile());
        intent.putExtra(ExportUpdateService.EXTRA_DEST_FILE, dest);
        mActivity.startService(intent);
    }

    private void showInfoDialog() {
        String messageString = mActivity.getString(R.string.snack_update_not_installable);
        AlertDialog dialog = new AlertDialog.Builder(mActivity, R.style.AppTheme_AlertDialogStyle)
                .setTitle(R.string.blocked_update_dialog_title)
                .setPositiveButton(android.R.string.ok, null)
                .setMessage(messageString)
                .show();
        TextView textView = dialog.findViewById(android.R.id.message);
        textView.setMovementMethod(LinkMovementMethod.getInstance());
    }

    private void showDonationDialog() {
        View view = View.inflate(mActivity, R.layout.dondialog, null);
        ImageView img = view.findViewById(R.id.QRcode);
        Bitmap qrBitmap = generateBitmap(Constants.donationbitmap,800, 800);
        img.setImageBitmap(qrBitmap);
        AlertDialog dialog = new AlertDialog.Builder(mActivity, R.style.AppTheme_AlertDialogStyle)
                .setTitle(R.string.donation_title)
                .setPositiveButton(android.R.string.ok, null)
                .setMessage(R.string.donation_info)
                .setView(view)
                .show();
        TextView textView = dialog.findViewById(android.R.id.message);
        textView.setMovementMethod(LinkMovementMethod.getInstance());
    }

    private Bitmap generateBitmap( String content, int width, int height) {
        QRCodeWriter qrCodeWriter = new QRCodeWriter();
        Map<EncodeHintType, String> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "utf-8");
        try {
            BitMatrix encode = qrCodeWriter.encode(content, BarcodeFormat.QR_CODE, width, height, hints);
            int[] pixels = new int[width * height];
            for (int i = 0; i < height; i++) {
                for (int j = 0; j < width; j++) {
                    if (encode.get(j, i)) {
                        pixels[i * width + j] = 0x00000000;
                    } else {
                        pixels[i * width + j] = 0xffffffff;
                    }
                }
            }
            return Bitmap.createBitmap(pixels, 0, width, width, height, Bitmap.Config.RGB_565);
        } catch (WriterException e) {
            e.printStackTrace();
        }
        return null;
    }

    private boolean isBatteryLevelOk() {
        Intent intent = mActivity.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        assert intent != null;
        if (!intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)) {
            return true;
        }
        int percent = Math.round(100.f * intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) /
                intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
        int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int required = (plugged & BatteryManager.BATTERY_PLUGGED_AC) != 0 ?
                mActivity.getResources().getInteger(R.integer.battery_ok_percentage_charging) :
                mActivity.getResources().getInteger(R.integer.battery_ok_percentage_discharging);
        return percent >= required;
    }

    private enum Action {
        DOWNLOAD,
        PAUSE,
        RESUME,
        INSTALL,
        INFO,
        DELETE,
        CANCEL_INSTALLATION,
        REBOOT,
    }

    static class ViewHolder extends RecyclerView.ViewHolder {

        Activity itemcontext = (Activity) itemView.getContext();
        final Markwon markwoncontent;

        private Button mAction;

        private TextView mBuildDate;
        private TextView mBuildSize;
        private TextView mNotificationContent;
        private TextView mWhatsNew;
        private TextView mAdvisorycontent;
        private TextView mLongPressMenu;
        private TextView mHeaderText;
        private TextView mDownTip;

        private ImageView mAvatar;

        private ProgressBar mProgressBar;
        private TextView mProgressText;

        ViewHolder(final View view) {
            super(view);
            markwoncontent = Markwon.builder(itemcontext).usePlugin(HtmlPlugin.create())
                    .usePlugin(StrikethroughPlugin.create())
                    .usePlugin(new AbstractMarkwonPlugin() {
                        @Override
                        public void configure(@NonNull MarkwonPlugin.Registry registry) {
                            registry.require(HtmlPlugin.class, htmlPlugin -> htmlPlugin
                                    .addHandler(new ColorTagHandler()));
                        }
                    })
                    .build();

            mAction = view.findViewById(R.id.update_action);

            mBuildDate = view.findViewById(R.id.build_date);
            mLongPressMenu = view.findViewById(R.id.longpressmenu);
            mBuildSize = view.findViewById(R.id.build_size);
            mNotificationContent = view.findViewById(R.id.notification_content);
            mWhatsNew = view.findViewById(R.id.whats_new);
            mAdvisorycontent = view.findViewById(R.id.advisory_content);
            mAvatar = view.findViewById(R.id.avatar);
            mHeaderText = view.findViewById(R.id.header_text);
            mDownTip = view.findViewById(R.id.downloadtip);

            mProgressBar = view.findViewById(R.id.progress_bar);
            mProgressText = view.findViewById(R.id.progress_text);
        }
    }

    public static class ColorTagHandler extends SimpleTagHandler {

        @Nullable
        @Override
        public Object getSpans(
                @NonNull MarkwonConfiguration configuration,
                @NonNull RenderProps renderProps,
                @NonNull HtmlTag tag) {

            // we require start and end to be present
            final String color = (tag.attributes().get("color"));
            long l = Long.parseLong(color, 16);
            return new ForegroundColorSpan((int) l);
        }

        @NonNull
        @Override
        public Collection<String> supportedTags() {
            return Collections.singleton("font");
        }
    }
}
