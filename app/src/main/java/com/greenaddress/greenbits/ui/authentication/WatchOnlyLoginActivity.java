package com.greenaddress.greenbits.ui.authentication;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

import com.blockstream.libgreenaddress.GDK;
import com.greenaddress.greenapi.ConnectionManager;
import com.greenaddress.greenbits.ui.LoginActivity;
import com.greenaddress.greenbits.ui.R;
import com.greenaddress.greenbits.ui.UI;
import com.greenaddress.greenbits.ui.components.CircularButton;
import com.greenaddress.greenbits.ui.preferences.PrefKeys;

import java.util.Observer;

public class WatchOnlyLoginActivity extends LoginActivity implements View.OnClickListener, Observer {

    private EditText mUsernameText;
    private EditText mPasswordText;
    private CircularButton mLoginButton;
    private SwitchCompat mRememberSwitch;

    @Override
    protected void onCreateWithService(final Bundle savedInstanceState) {
        setAppNameTitle();
        setContentView(R.layout.activity_watchonly);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        setTitleBackTransparent();
        setAppNameTitle();

        mUsernameText = UI.find(this, R.id.input_user);
        mPasswordText = UI.find(this, R.id.input_password);
        mLoginButton = UI.find(this, R.id.btn_login);
        mRememberSwitch = UI.find(this, R.id.remember_watch_only);

        mLoginButton.setOnClickListener(this);

        final TextView.OnEditorActionListener listener;
        listener = UI.getListenerRunOnEnter(this::onLoginButtonClicked);
        mPasswordText.setOnEditorActionListener(listener);

        final String username = mService.cfg().getString(PrefKeys.WATCH_ONLY_USERNAME, "");
        final String password =  mService.cfg().getString(PrefKeys.WATCH_ONLY_PASSWORD, "");
        final boolean hasCredentials = !username.isEmpty();
        mUsernameText.setText(username);
        mPasswordText.setText(password);
        mRememberSwitch.setChecked(hasCredentials);

        mRememberSwitch.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            if (!isChecked) {
                mService.cfgEdit().putString(PrefKeys.WATCH_ONLY_USERNAME, "")
                .putString(PrefKeys.WATCH_ONLY_PASSWORD, "").apply();
                mUsernameText.setText("");
                mPasswordText.setText("");
                return;
            }

            UI.popup(WatchOnlyLoginActivity.this, R.string.id_warning_watchonly_credentials)
            .content(R.string.id_your_watchonly_username_and)
            .canceledOnTouchOutside(false)
            .onNegative((dlg, which) -> mRememberSwitch.setChecked(false))
            .onPositive((dlg, which) -> mRememberSwitch.setChecked(true)).build().show();
        });
    }

    @Override
    public void onClick(final View v) {
        if (v == mLoginButton)
            onLoginButtonClicked();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        UI.unmapClick(mLoginButton);
        mService.getConnectionManager().deleteObserver(this);
    }

    private void onLoginButtonClicked() {
        if (mLoginButton.isLoading())
            return;

        if (!validate()) {
            onLoginStop();
            return;
        }

        final ConnectionManager connectionManager = mService.getConnectionManager();
        final String username = UI.getText(mUsernameText);
        final String password = UI.getText(mPasswordText);

        onLoginBegin();

        mService.getExecutor().execute(() -> {
            mService.resetSession();
            connectionManager.loginWatchOnly(username, password);
        });

    }

    private void onLoginBegin() {
        mLoginButton.startLoading();
        mUsernameText.setEnabled(false);
        mPasswordText.setEnabled(false);
        mRememberSwitch.setEnabled(false);
        final String usr = mRememberSwitch.isChecked() ? UI.getText(mUsernameText) : "";
        final String pswd = mRememberSwitch.isChecked() ? UI.getText(mPasswordText) : "";
        mService.cfgEdit().putString(PrefKeys.WATCH_ONLY_USERNAME, usr)
        .putString(PrefKeys.WATCH_ONLY_PASSWORD, pswd).apply();
    }

    private void onLoginStop() {
        mLoginButton.stopLoading();
        mUsernameText.setEnabled(true);
        mPasswordText.setEnabled(true);
        mRememberSwitch.setEnabled(true);
    }

    @Override
    protected void onLoginFailure() {
        final Exception lastLoginException = mService.getConnectionManager().getLastLoginException();
        mService.getConnectionManager().clearPreviousLoginError();
        final int code = getCode(lastLoginException);
        onLoginStop();
        if (code == GDK.GA_RECONNECT) {
            mPasswordText.setError(getString(R.string.id_you_are_not_connected_to_the));
        } else {
            mPasswordText.setError(getString(R.string.id_user_not_found_or_invalid));
        }
    }

    @Override
    protected void onLoginSuccess() {
        super.onLoggedIn();
    }

    private boolean validate() {

        if (mUsernameText.getText().length() == 0) {
            mUsernameText.setError(getString(R.string.id_enter_a_valid_username));
            return false;
        }

        mUsernameText.setError(null);

        if (mPasswordText.getText().length() == 0) {
            mPasswordText.setError(getString(R.string.id_the_password_cant_be_empty));
            return false;
        }

        mPasswordText.setError(null);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
        case android.R.id.home:
            onBackPressed();
            return true;
        default:
            return super.onOptionsItemSelected(item);
        }
    }
}
