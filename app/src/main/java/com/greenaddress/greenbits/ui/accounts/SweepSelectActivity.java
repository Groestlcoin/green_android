package com.greenaddress.greenbits.ui.accounts;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;

import com.greenaddress.greenapi.data.SubaccountData;
import com.greenaddress.greenbits.ui.GaActivity;
import com.greenaddress.greenbits.ui.R;
import com.greenaddress.greenbits.ui.preferences.PrefKeys;
import com.greenaddress.greenbits.ui.send.ScanActivity;

import java.util.List;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class SweepSelectActivity extends GaActivity implements SweepAdapter.OnAccountSelected {

    private RecyclerView mRecyclerView;
    private List<SubaccountData> mSubaccountData;

    @Override
    protected void onCreateWithService(Bundle savedInstanceState) {
        setContentView(R.layout.activity_sweep_selection);
        setTitleBackTransparent();

        mSubaccountData = getModel().getSubaccountDataObservable().getSubaccountDataList();
        mRecyclerView = findViewById(R.id.accountsList);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        mRecyclerView.setAdapter(new SweepAdapter(mSubaccountData, this));
    }

    @Override
    public void onAccountSelected(int account) {
        Intent intent = new Intent(this, ScanActivity.class);
        intent.putExtra(PrefKeys.SWEEP, true);
        mService.getModel().getActiveAccountObservable().setActiveAccount(mSubaccountData.get(account).getPointer());
        startActivity(intent);
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
