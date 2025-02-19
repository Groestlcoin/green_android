package com.greenaddress.greenbits.ui.preferences;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.greenaddress.greenbits.GaService;
import com.greenaddress.greenbits.GreenAddressApplication;
import com.greenaddress.greenbits.ui.LoggedActivity;

public class GAPreferenceFragment extends PreferenceFragmentCompat {
    private static final String TAG = GAPreferenceFragment.class.getSimpleName();

    protected GaService mService;
    private GreenAddressApplication mApp;

    @Override
    public void onAttach(final Context context) {
        super.onAttach(context);
        mApp = (GreenAddressApplication) getActivity().getApplication();
        mService = mApp.mService;
    }

    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {}

    private static final Preference.OnPreferenceChangeListener onPreferenceChanged =
        new Preference.OnPreferenceChangeListener() {
        @Override
        public boolean onPreferenceChange(final Preference preference, final Object value) {
            preference.setSummary(value.toString());
            return true;
        }
    };

    protected void bindPreferenceSummaryToValue(final Preference preference) {
        preference.setOnPreferenceChangeListener(onPreferenceChanged);
        // Trigger the listener immediately with the preference's
        // current value.
        final String currentVal = mService.cfg().getString(preference.getKey(), "");
        onPreferenceChanged.onPreferenceChange(preference, currentVal);
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            startActivity(new Intent(getActivity(), SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    protected < T > T find(final String preferenceName) {
        return (T) findPreference(preferenceName);
    }

    protected void logout() {
        if (getActivity() instanceof LoggedActivity)
            ((LoggedActivity) getActivity()).logout();
        else if (getActivity() instanceof GaPreferenceActivity)
            ((GaPreferenceActivity) getActivity()).logout();
    }

    protected boolean openURI(final String uri) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)));
        return false;
    }
}
