package com.muxover.oriel.home;

import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The Oriel keyboard for every app on the TV. It also carries typing from the phone remote: each field it is
 * attached to is reported to InputHub, and text from the phone goes in through the field's own connection, so it
 * works in any app that takes text. While the phone remote is open, a slim bar replaces the keyboard; OK brings
 * the keyboard back for that field.
 */
public class KeyboardService extends InputMethodService implements KeyboardView.Listener, InputHub.Listener {
    private static final int LONGEST = 4000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private KeyboardView view;
    private LinearLayout phoneBar;
    private int fieldId;
    private boolean tvKeyboard;

    @Override
    public void onCreate() {
        super.onCreate();
        InputHub.listen(this);
    }

    @Override
    public void onDestroy() {
        InputHub.unlisten(this);
        InputHub.keyboardGone(this);
        super.onDestroy();
    }

    @Override
    public View onCreateInputView() {
        Ui.init(this);
        root = new FrameLayout(this);
        view = new KeyboardView(this);
        view.setListener(this);
        root.addView(view, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        phoneBar = new LinearLayout(this);
        phoneBar.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 24);
        phoneBar.setPadding(p, Ui.dp(this, 16), p, Ui.dp(this, 16));
        phoneBar.setBackground(Ui.round(Ui.GLASS, Ui.dp(this, Ui.R_L)));
        phoneBar.addView(Ui.text(this, "Type on your phone", 20, Ui.semibold, Ui.PAPER));
        TextView hint = Ui.text(this, "Press OK for the TV keyboard", 15, Ui.regular, Ui.TEXT2);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2);
        hp.topMargin = Ui.dp(this, 2);
        phoneBar.addView(hint, hp);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bp.bottomMargin = Ui.dp(this, 32);
        root.addView(phoneBar, bp);
        showMode();
        return root;
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    /** The TV remote counts as a hardware keyboard to Android; the keyboard is shown anyway. */
    @Override
    public boolean onEvaluateInputViewShown() {
        return true;
    }

    @Override
    public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info, restarting);
        if (restarting && fieldId != 0) {
            handler.removeCallbacks(report);
            handler.post(report);
            return;
        }
        if (info == null || info.inputType == InputType.TYPE_NULL) {
            InputHub.detach(this);
            fieldId = 0;
            return;
        }
        tvKeyboard = false;
        int variation = info.inputType & InputType.TYPE_MASK_VARIATION;
        int cls = info.inputType & InputType.TYPE_MASK_CLASS;
        boolean secret = (cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
            || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
            || (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        String hint = info.hintText != null ? info.hintText.toString() : "";
        fieldId = InputHub.attach(this, current(), hint, actionLabel(info), info.packageName != null ? info.packageName : "", secret);
    }

    @Override
    public void onFinishInput() {
        super.onFinishInput();
        handler.removeCallbacks(report);
        InputHub.detach(this);
        fieldId = 0;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        if (view == null) return;
        int variation = info.inputType & InputType.TYPE_MASK_VARIATION;
        boolean emailOrLink = (info.inputType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
            && (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || variation == InputType.TYPE_TEXT_VARIATION_URI
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS);
        view.reset(actionLabel(info), emailOrLink);
        showMode();
    }

    @Override
    public void onUpdateSelection(int oldStart, int oldEnd, int newStart, int newEnd, int candStart, int candEnd) {
        super.onUpdateSelection(oldStart, oldEnd, newStart, newEnd, candStart, candEnd);
        handler.removeCallbacks(report);
        handler.postDelayed(report, 120);
    }

    /** Tells InputHub the field's text after a change, for the phone's text box. */
    private final Runnable report = () -> {
        if (fieldId != 0) InputHub.changed(fieldId, current());
    };

    @Override
    public void onRemoteChanged(boolean online) {
        showMode();
    }

    /** The phone bar while the phone remote is open, else the keyboard. */
    private void showMode() {
        if (root == null) return;
        boolean phone = InputHub.remoteOnline() && !tvKeyboard;
        view.setVisibility(phone ? View.GONE : View.VISIBLE);
        phoneBar.setVisibility(phone ? View.VISIBLE : View.GONE);
    }

    private static String actionLabel(EditorInfo info) {
        switch (info.imeOptions & EditorInfo.IME_MASK_ACTION) {
            case EditorInfo.IME_ACTION_GO: return "Go";
            case EditorInfo.IME_ACTION_SEARCH: return "Search";
            case EditorInfo.IME_ACTION_SEND: return "Send";
            case EditorInfo.IME_ACTION_NEXT: return "Next";
            default: return "Done";
        }
    }

    /** The field's whole text, or "" when the app does not share it (password fields still give their text). */
    private String current() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return "";
        ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
        if (et != null && et.text != null) return et.text.toString();
        CharSequence before = ic.getTextBeforeCursor(LONGEST, 0), after = ic.getTextAfterCursor(LONGEST, 0);
        return (before != null ? before.toString() : "") + (after != null ? after.toString() : "");
    }

    /**
     * Makes the field read text, changing only what differs (so search-as-you-type fields see a normal edit).
     * Main thread, called by InputHub.
     */
    void replaceText(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        String now = current();
        if (now.equals(text)) return;
        int same = 0, max = Math.min(now.length(), text.length());
        while (same < max && now.charAt(same) == text.charAt(same)) same++;
        ic.beginBatchEdit();
        ic.finishComposingText();
        ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
        if (et != null) {
            ic.setSelection(same, now.length());
            ic.commitText(text.substring(same), 1);
        } else {
            CharSequence before = ic.getTextBeforeCursor(LONGEST, 0), after = ic.getTextAfterCursor(LONGEST, 0);
            ic.deleteSurroundingText(before != null ? before.length() : 0, after != null ? after.length() : 0);
            ic.commitText(text, 1);
        }
        ic.endBatchEdit();
        if (fieldId != 0) InputHub.changed(fieldId, text);
    }

    /** The field's action (Search, Go, Send), or Enter when it has none. Main thread, called by InputHub. */
    void submit() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        EditorInfo info = getCurrentInputEditorInfo();
        int action = info != null ? info.imeOptions & EditorInfo.IME_MASK_ACTION : EditorInfo.IME_ACTION_NONE;
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
            && (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0) {
            ic.performEditorAction(action);
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
        }
        if (action == EditorInfo.IME_ACTION_DONE || action == EditorInfo.IME_ACTION_NONE
            || action == EditorInfo.IME_ACTION_UNSPECIFIED) requestHideSelf(0);
    }

    @Override
    public void onKey(KeyboardView.Key k) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        switch (k.kind) {
            case KeyboardView.DEL:
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL));
                return;
            case KeyboardView.DONE:
                submit();
                return;
            default:
                String s = k.text;
                if (view.shifted() && s.length() == 1) {
                    s = s.toUpperCase();
                    view.unshift();
                }
                ic.commitText(s, 1);
        }
    }

    private boolean phoneBarShown() {
        return phoneBar != null && phoneBar.getVisibility() == View.VISIBLE && isInputViewShown();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (phoneBarShown()) {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                if (e.getRepeatCount() == 0) {
                    tvKeyboard = true;
                    showMode();
                }
                return true;
            }
            return super.onKeyDown(code, e);
        }
        if (view == null || !isInputViewShown()) return super.onKeyDown(code, e);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (!view.move(-1, 0)) requestHideSelf(0);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                view.move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                view.move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                view.move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (e.getRepeatCount() == 0) view.press();
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (phoneBarShown()) {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) return true;
            return super.onKeyUp(code, e);
        }
        if (view != null && isInputViewShown() && view.getVisibility() == View.VISIBLE && (code == KeyEvent.KEYCODE_DPAD_UP
            || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
            || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) return true;
        return super.onKeyUp(code, e);
    }
}
