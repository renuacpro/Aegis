package com.beemdevelopment.aegis.ui.dialogs;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.NumberPicker;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;

import com.beemdevelopment.aegis.Preferences;
import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.helpers.EditTextHelper;
import com.beemdevelopment.aegis.helpers.PasswordStrengthHelper;
import com.beemdevelopment.aegis.importers.DatabaseImporter;
import com.beemdevelopment.aegis.ui.tasks.KeyDerivationTask;
import com.beemdevelopment.aegis.vault.VaultEntry;
import com.beemdevelopment.aegis.vault.slots.PasswordSlot;
import com.beemdevelopment.aegis.vault.slots.Slot;
import com.beemdevelopment.aegis.vault.slots.SlotException;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.nulabinc.zxcvbn.Strength;
import com.nulabinc.zxcvbn.Zxcvbn;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import javax.crypto.Cipher;

public class Dialogs {
    private Dialogs() {

    }

    public static void secureDialog(Dialog dialog) {
        if (new Preferences(dialog.getContext()).isSecureScreenEnabled()) {
            dialog.getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    public static void showSecureDialog(Dialog dialog) {
        secureDialog(dialog);
        dialog.show();
    }

    public static void showDeleteEntriesDialog(Activity activity, List<VaultEntry> services, DialogInterface.OnClickListener onDelete) {
        View view = activity.getLayoutInflater().inflate(R.layout.dialog_delete_entry, null);
        TextView textMessage = view.findViewById(R.id.text_message);
        TextView textExplanation = view.findViewById(R.id.text_explanation);
        String entries = services.stream()
                .map(entry -> String.format("• %s", getVaultEntryName(activity, entry)))
                .collect(Collectors.joining("\n"));
        textExplanation.setText(activity.getString(R.string.delete_entry_explanation, entries));

        String title, message;
        if (services.size() > 1) {
            title = activity.getString(R.string.delete_entries);
            message = activity.getResources().getQuantityString(R.plurals.delete_entries_description, services.size(), services.size());
        } else {
            title = activity.getString(R.string.delete_entry);
            message = activity.getString(R.string.delete_entry_description);
        }
        textMessage.setText(message);

        showSecureDialog(new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(view)
                .setPositiveButton(android.R.string.yes, onDelete)
                .setNegativeButton(android.R.string.no, null)
                .create());
    }

    private static String getVaultEntryName(Context context, VaultEntry entry) {
        if (!entry.getIssuer().isEmpty() && !entry.getName().isEmpty()) {
            return String.format("%s (%s)", entry.getIssuer(), entry.getName());
        } else if (entry.getIssuer().isEmpty() && entry.getName().isEmpty()) {
            return context.getString(R.string.unknown_issuer);
        } else if (entry.getIssuer().isEmpty()) {
            return entry.getName();
        } else {
            return entry.getIssuer();
        }
    }

    public static void showDiscardDialog(Activity activity, DialogInterface.OnClickListener onSave, DialogInterface.OnClickListener onDiscard) {
        showSecureDialog(new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.discard_changes))
                .setMessage(activity.getString(R.string.discard_changes_description))
                .setPositiveButton(R.string.save, onSave)
                .setNegativeButton(R.string.discard, onDiscard)
                .create());
    }

    public static void showSetPasswordDialog(ComponentActivity activity, PasswordSlotListener listener) {
        Zxcvbn zxcvbn = new Zxcvbn();
        View view = activity.getLayoutInflater().inflate(R.layout.dialog_password, null);
        EditText textPassword = view.findViewById(R.id.text_password);
        EditText textPasswordConfirm = view.findViewById(R.id.text_password_confirm);
        ProgressBar barPasswordStrength = view.findViewById(R.id.progressBar);
        TextView textPasswordStrength = view.findViewById(R.id.text_password_strength);
        TextInputLayout textPasswordWrapper = view.findViewById(R.id.text_password_wrapper);
        CheckBox switchToggleVisibility = view.findViewById(R.id.check_toggle_visibility);

        switchToggleVisibility.setOnCheckedChangeListener((CompoundButton.OnCheckedChangeListener) (buttonView, isChecked) -> {
            if (isChecked) {
                textPassword.setTransformationMethod(null);
                textPasswordConfirm.setTransformationMethod(null);
                textPassword.clearFocus();
                textPasswordConfirm.clearFocus();
            } else {
                textPassword.setTransformationMethod(new PasswordTransformationMethod());
                textPasswordConfirm.setTransformationMethod(new PasswordTransformationMethod());
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.set_password)
                .setView(view)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);

        final AtomicReference<Button> buttonOK = new AtomicReference<>();
        dialog.setOnShowListener(d -> {
            Button button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            button.setEnabled(false);
            buttonOK.set(button);

            // replace the default listener
            button.setOnClickListener(v -> {
                if (!EditTextHelper.areEditTextsEqual(textPassword, textPasswordConfirm)) {
                    return;
                }

                char[] password = EditTextHelper.getEditTextChars(textPassword);
                PasswordSlot slot = new PasswordSlot();
                KeyDerivationTask task = new KeyDerivationTask(activity, (passSlot, key) -> {
                    Cipher cipher;
                    try {
                        cipher = Slot.createEncryptCipher(key);
                    } catch (SlotException e) {
                        listener.onException(e);
                        dialog.cancel();
                        return;
                    }
                    listener.onSlotResult(slot, cipher);
                    dialog.dismiss();
                });
                KeyDerivationTask.Params params = new KeyDerivationTask.Params(slot, password);
                task.execute(activity.getLifecycle(), params);
            });
        });

        TextWatcher watcher = new TextWatcher() {
            @Override
            public void onTextChanged(CharSequence c, int start, int before, int count) {
                boolean equal = EditTextHelper.areEditTextsEqual(textPassword, textPasswordConfirm);
                buttonOK.get().setEnabled(equal);

                Strength strength = zxcvbn.measure(textPassword.getText());
                barPasswordStrength.setProgress(strength.getScore());
                barPasswordStrength.setProgressTintList(ColorStateList.valueOf(Color.parseColor(PasswordStrengthHelper.getColor(strength.getScore()))));
                textPasswordStrength.setText((textPassword.getText().length() != 0) ? PasswordStrengthHelper.getString(strength.getScore(), activity) : "");
                textPasswordWrapper.setError(strength.getFeedback().getWarning());
                strength.wipe();
            }

            @Override
            public void beforeTextChanged(CharSequence c, int start, int count, int after) {
            }

            @Override
            public void afterTextChanged(Editable c) {
            }
        };
        textPassword.addTextChangedListener(watcher);
        textPasswordConfirm.addTextChangedListener(watcher);

        showSecureDialog(dialog);
    }

    private static void showTextInputDialog(Context context, @StringRes int titleId, @StringRes int messageId, @StringRes int hintId, TextInputListener listener, DialogInterface.OnCancelListener cancelListener, boolean isSecret) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_text_input, null);
        TextInputEditText input = view.findViewById(R.id.text_input);
        TextInputLayout inputLayout = view.findViewById(R.id.text_input_layout);
        if (isSecret) {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        inputLayout.setHint(hintId);

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(titleId)
                .setView(view)
                .setPositiveButton(android.R.string.ok, (dialog1, which) -> {
                    char[] text = EditTextHelper.getEditTextChars(input);
                    listener.onTextInputResult(text);
                });

        if (cancelListener != null) {
            builder.setOnCancelListener(cancelListener);
        }

        if (messageId != 0) {
            builder.setMessage(messageId);
        }

        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(true);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        showSecureDialog(dialog);
    }

    private static void showTextInputDialog(Context context, @StringRes int titleId, @StringRes int hintId, TextInputListener listener, boolean isSecret) {
        showTextInputDialog(context, titleId, 0, hintId, listener, null, isSecret);
    }

    public static void showTextInputDialog(Context context, @StringRes int titleId, @StringRes int hintId, TextInputListener listener) {
        showTextInputDialog(context, titleId, hintId, listener, false);
    }

    public static void showPasswordInputDialog(Context context, TextInputListener listener) {
        showTextInputDialog(context, R.string.set_password, R.string.password, listener, true);
    }

    public static void showPasswordInputDialog(Context context, TextInputListener listener, DialogInterface.OnCancelListener cancelListener) {
        showTextInputDialog(context, R.string.set_password, 0, R.string.password, listener, cancelListener, true);
    }

    public static void showPasswordInputDialog(Context context, @StringRes int messageId, TextInputListener listener) {
        showTextInputDialog(context, R.string.set_password, messageId, R.string.password, listener, null, true);
    }

    public static void showPasswordInputDialog(Context context, @StringRes int messageId, TextInputListener listener, DialogInterface.OnCancelListener cancelListener) {
        showTextInputDialog(context, R.string.set_password, messageId, R.string.password, listener, cancelListener, true);
    }

    public static void showPasswordInputDialog(Context context, @StringRes int titleId, @StringRes int messageId, TextInputListener listener, DialogInterface.OnCancelListener cancelListener) {
        showTextInputDialog(context, titleId, messageId, R.string.password, listener, cancelListener, true);
    }

    public static void showCheckboxDialog(Context context, @StringRes int titleId, @StringRes int messageId, @StringRes int checkboxMessageId, CheckboxInputListener listener) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_checkbox, null);
        CheckBox checkBox = view.findViewById(R.id.checkbox);
        checkBox.setText(checkboxMessageId);

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(titleId)
                .setView(view)
                .setNegativeButton(R.string.no, (dialog1, which) ->
                        listener.onCheckboxInputResult(false))
                .setPositiveButton(R.string.yes, (dialog1, which) ->
                        listener.onCheckboxInputResult(checkBox.isChecked()));

        if (messageId != 0) {
            builder.setMessage(messageId);
        }

        AlertDialog dialog = builder.create();

        final AtomicReference<Button> buttonOK = new AtomicReference<>();
        dialog.setOnShowListener(d -> {
            Button button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            button.setEnabled(false);
            buttonOK.set(button);
        });

        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> buttonOK.get().setEnabled(isChecked));

        showSecureDialog(dialog);
    }

    public static void showTapToRevealTimeoutPickerDialog(Activity activity, int currentValue, NumberInputListener listener) {
        View view = activity.getLayoutInflater().inflate(R.layout.dialog_number_picker, null);
        NumberPicker numberPicker = view.findViewById(R.id.numberPicker);
        numberPicker.setMinValue(1);
        numberPicker.setMaxValue(60);
        numberPicker.setValue(currentValue);
        numberPicker.setWrapSelectorWheel(true);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.set_number)
                .setView(view)
                .setPositiveButton(android.R.string.ok, (dialog1, which) ->
                        listener.onNumberInputResult(numberPicker.getValue()))
                .create();

        showSecureDialog(dialog);
    }

    public static void showBackupVersionsPickerDialog(Activity activity, int currentVersionCount, NumberInputListener listener) {
        final int max = 30;
        String[] numbers = new String[max / 5];
        for (int i = 0; i < numbers.length; i++) {
            numbers[i] = Integer.toString(i * 5 + 5);
        }

        View view = activity.getLayoutInflater().inflate(R.layout.dialog_number_picker, null);
        NumberPicker numberPicker = view.findViewById(R.id.numberPicker);
        numberPicker.setDisplayedValues(numbers);
        numberPicker.setMaxValue(numbers.length - 1);
        numberPicker.setMinValue(0);
        numberPicker.setValue(currentVersionCount / 5 - 1);
        numberPicker.setWrapSelectorWheel(false);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.set_number)
                .setView(view)
                .setPositiveButton(android.R.string.ok, (dialog1, which) ->
                        listener.onNumberInputResult(numberPicker.getValue()))
                .create();

        showSecureDialog(dialog);
    }

    public static void showErrorDialog(Context context, @StringRes int message, Exception e) {
        showErrorDialog(context, message, e, null);
    }

    public static void showErrorDialog(Context context, @StringRes int message, CharSequence error) {
        showErrorDialog(context, message, error, null);
    }

    public static void showErrorDialog(Context context, @StringRes int message, Exception e, DialogInterface.OnClickListener listener) {
        showErrorDialog(context, message, e.toString(), listener);
    }

    public static void showErrorDialog(Context context, @StringRes int message, CharSequence error, DialogInterface.OnClickListener listener) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_error, null);
        TextView textDetails = view.findViewById(R.id.error_details);
        textDetails.setText(error);
        TextView textMessage = view.findViewById(R.id.error_message);
        textMessage.setText(message);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.error_occurred)
                .setView(view)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, (dialog1, which) -> {
                    if (listener != null) {
                        listener.onClick(dialog1, which);
                    }
                })
                .setNeutralButton(R.string.details, (dialog1, which) -> {
                    textDetails.setVisibility(View.VISIBLE);
                })
                .create();

        dialog.setOnShowListener(d -> {
            Button button = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            button.setOnClickListener(v -> {
                if (textDetails.getVisibility() == View.GONE) {
                    textDetails.setVisibility(View.VISIBLE);
                    button.setText(R.string.copy);
                } else {
                    ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        ClipData clip = ClipData.newPlainText("text/plain", error);
                        clipboard.setPrimaryClip(clip);
                    }
                }
            });
        });

        Dialogs.showSecureDialog(dialog);
    }

    public static void showTimeSyncWarningDialog(Context context, Dialog.OnClickListener listener) {
        Preferences prefs = new Preferences(context);
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_time_sync, null);
        CheckBox checkBox = view.findViewById(R.id.check_warning_disable);

        showSecureDialog(new AlertDialog.Builder(context)
                .setTitle(R.string.time_sync_warning_title)
                .setView(view)
                .setCancelable(false)
                .setPositiveButton(R.string.yes, (dialog, which) -> {
                    if (checkBox.isChecked()) {
                        prefs.setIsTimeSyncWarningEnabled(false);
                    }
                    if (listener != null) {
                        listener.onClick(dialog, which);
                    }
                })
                .setNegativeButton(R.string.no, (dialog, which) -> {
                    if (checkBox.isChecked()) {
                        prefs.setIsTimeSyncWarningEnabled(false);
                    }
                })
                .create());
    }

    public static void showImportersDialog(Context context, boolean isDirect, ImporterListener listener) {
        List<DatabaseImporter.Definition> importers = DatabaseImporter.getImporters(isDirect);
        List<String> names = importers.stream().map(DatabaseImporter.Definition::getName).collect(Collectors.toList());

        int i = 0;
        if (!isDirect) {
            i = names.indexOf(context.getString(R.string.app_name));
        }
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_importers, null);
        TextView helpText = view.findViewById(R.id.text_importer_help);
        setImporterHelpText(helpText, importers.get(i), isDirect);
        ListView listView = view.findViewById(R.id.list_importers);
        listView.setAdapter(new ArrayAdapter<>(context, R.layout.card_importer, names));
        listView.setItemChecked(i, true);
        listView.setOnItemClickListener((parent, view1, position, id) -> {
            setImporterHelpText(helpText, importers.get(position), isDirect);
        });

        Dialogs.showSecureDialog(new AlertDialog.Builder(context)
                .setTitle(R.string.choose_application)
                .setView(view)
                .setPositiveButton(android.R.string.ok, (dialog1, which) -> {
                    listener.onImporterSelectionResult(importers.get(listView.getCheckedItemPosition()));
                })
                .create());
    }

    private static void setImporterHelpText(TextView view, DatabaseImporter.Definition definition, boolean isDirect) {
        if (isDirect) {
            view.setText(view.getResources().getString(R.string.importer_help_direct, definition.getName()));
        } else {
            view.setText(definition.getHelp());
        }
    }

    public interface CheckboxInputListener {
        void onCheckboxInputResult(boolean checkbox);
    }

    public interface NumberInputListener {
        void onNumberInputResult(int number);
    }

    public interface TextInputListener {
        void onTextInputResult(char[] text);
    }

    public interface PasswordSlotListener {
        void onSlotResult(PasswordSlot slot, Cipher cipher);
        void onException(Exception e);
    }

    public interface ImporterListener {
        void onImporterSelectionResult(DatabaseImporter.Definition definition);
    }
}
