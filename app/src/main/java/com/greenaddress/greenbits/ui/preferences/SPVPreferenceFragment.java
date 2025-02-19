package com.greenaddress.greenbits.ui.preferences;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.CheckBoxPreference;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;

import com.afollestad.materialdialogs.DialogAction;
import com.afollestad.materialdialogs.MaterialDialog;
import com.greenaddress.greenbits.ui.R;
import com.greenaddress.greenbits.ui.ScanForResultActivity;
import com.greenaddress.greenbits.ui.UI;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

public class SPVPreferenceFragment extends GAPreferenceFragment
    implements Preference.OnPreferenceChangeListener,
    Preference.OnPreferenceClickListener {

    private static final String TAG = GAPreferenceFragment.class.getSimpleName();
    private static final int QRSCANNER = 1338;
    private static final int CAMERA_PERMISSION = 150;

    private EditTextPreference mTrustedPeer;
    private Preference mResetSPV;
    private Preference mScanSPV;
    private CheckBoxPreference mSPVEnabled;
    private CheckBoxPreference mSPVSyncOnMobile;

    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);
        addPreferencesFromResource(R.xml.preference_spv);
        setHasOptionsMenu(true);

        if (mService == null || mService.getModel() == null) {
            logout();
            return;
        }

        mTrustedPeer = find("trusted_peer");
        mResetSPV = find("reset_spv");
        mSPVEnabled = find("spvEnabled");
        mSPVSyncOnMobile = find("spvSyncMobile");
        mScanSPV = find("scan_spv");

        mSPVEnabled.setSingleLineTitle(false);
        mSPVSyncOnMobile.setSingleLineTitle(false);

        if (mService.isWatchOnly()) {
            // Do not allow editing of SPV_SYNCRONIZATION prefs from watch only logins
            mTrustedPeer.setEnabled(false);
            mResetSPV.setEnabled(false);
            mSPVEnabled.setEnabled(false);
            mSPVSyncOnMobile.setEnabled(false);
            mScanSPV.setEnabled(false);
        } else {
            // Initialise values and bindings for preference changes
            bindPreferenceSummaryToValue(mTrustedPeer);

            mSPVEnabled.setChecked(mService.isSPVEnabled());
            mSPVSyncOnMobile.setChecked(mService.isSPVSyncOnMobileEnabled());

            mTrustedPeer.setEnabled(mService.isSPVEnabled());
            mScanSPV.setEnabled(mService.isSPVEnabled());
            final boolean setTextValue = true;
            setTrustedPeersPreference(mService.getSPVTrustedPeers(), setTextValue);

            mResetSPV.setOnPreferenceClickListener(this);
            mSPVEnabled.setOnPreferenceChangeListener(this);
            mSPVSyncOnMobile.setOnPreferenceChangeListener(this);
            mTrustedPeer.setOnPreferenceChangeListener(this);
            mScanSPV.setOnPreferenceClickListener(this);
        }

        getActivity().setResult(Activity.RESULT_OK, null);
    }

    private void setTrustedPeersPreference(final String peers, final boolean setTextValue) {

        if (peers.isEmpty()) {
            mTrustedPeer.setSummary(R.string.id_example_89014283334011612858333);
            return;
        }
        if (setTextValue)
            mTrustedPeer.setText(peers);
        mTrustedPeer.setSummary(peers);
    }

    private void setTrustedPeers(final String peers) {
        final boolean setTextValue = false;
        setTrustedPeersPreference(peers, setTextValue);
        mService.setSPVTrustedPeersAsync(peers);
    }

    private boolean onSPVEnabledChanged(final Boolean newValue) {
        mTrustedPeer.setEnabled(newValue);
        mService.setSPVEnabledAsync(newValue);
        mSPVSyncOnMobile.setEnabled(newValue);
        mScanSPV.setEnabled(newValue);
        if (!newValue) {
            mSPVSyncOnMobile.setChecked(false);
            mService.setSPVSyncOnMobileEnabledAsync(false);
        }
        return true;
    }

    private boolean onSPVSyncOnMobileChanged(final Boolean newValue) {
        mService.setSPVSyncOnMobileEnabledAsync(newValue);
        return true;
    }

    private boolean isBadAddress(final String s) {
        try {
            if (!s.isEmpty())
                new URI("btc://" + s);
            return false;
        } catch (final URISyntaxException e) {}
        return true;
    }

    private boolean onTrustedPeerChange(final String newValue) {

        final String peers = newValue.trim().replaceAll("\\s","");
        if (mService.getSPVTrustedPeers().equals(peers))
            return false;

        if (peers.isEmpty()) {
            setTrustedPeers(peers);
            return true;
        }

        for (final String peer: peers.split(","))
            if (isBadAddress(peer)) {
                UI.popup(getActivity(), R.string.id_invalid_address, android.R.string.ok)
                .content(R.string.id_enter_a_valid_onion_or_ip).build().show();
                return false;
            }

        if (peers.toLowerCase(Locale.US).contains(".onion")) {
            // Tor address
            if (!mService.isProxyEnabled()) {
                UI.popup(getActivity(), R.string.id_tor_connectivity_disabled, android.R.string.ok)
                .content(R.string.id_connecting_to_a_trusted_node).build().show();
                return false;
            }
            setTrustedPeers(peers);
            return true;
        }

        // Force the user to confirm that they want to use a non-Tor host
        UI.popup(getActivity(), R.string.id_warning_nononion_address)
        .content(R.string.id_connecting_to_tor_onion_nodes)
        .onPositive(new MaterialDialog.SingleButtonCallback() {
            @Override
            public void onClick(final MaterialDialog dlg, final DialogAction which) {
                setTrustedPeers(peers);
            }
        }).build().show();
        return true;
    }

    @Override
    public boolean onPreferenceChange(final Preference preference, final Object newValue) {
        if (preference == mSPVEnabled)
            return onSPVEnabledChanged((Boolean) newValue);
        if (preference == mSPVSyncOnMobile)
            return onSPVSyncOnMobileChanged((Boolean) newValue);
        if (preference == mTrustedPeer)
            return onTrustedPeerChange((String) newValue);
        return false;
    }

    @Override
    public boolean onPreferenceClick(final Preference preference) {
        if (preference == mResetSPV) {
            UI.toast(getActivity(), R.string.id_spv_reset_and_restarted, Toast.LENGTH_LONG);
            mService.resetSPVAsync();
        } else if (preference == mScanSPV) {
            onScanClicked();
        }
        return false;
    }

    private void onScanClicked() {
        final String[] perms = { "android.permission.CAMERA" };
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP_MR1 &&
            getActivity().checkSelfPermission(perms[0]) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(perms, CAMERA_PERMISSION);
        else {
            final Intent scanner = new Intent(getActivity(), ScanForResultActivity.class);
            startActivityForResult(scanner, QRSCANNER);
        }
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode, final String[] permissions, final int[] granted) {
        if (requestCode == CAMERA_PERMISSION && granted != null && granted.length > 0 &&
            granted[0] == PackageManager.PERMISSION_GRANTED)
            startActivityForResult(new Intent(getActivity(), ScanForResultActivity.class), QRSCANNER);
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == QRSCANNER && data != null &&
            data.getStringExtra(ScanForResultActivity.INTENT_EXTRA_RESULT) != null) {
            String address = data.getStringExtra(ScanForResultActivity.INTENT_EXTRA_RESULT);
            address = address.contains("://") ? address.split("://")[1] : address;
            setTrustedPeers(address);
        }
    }
}
