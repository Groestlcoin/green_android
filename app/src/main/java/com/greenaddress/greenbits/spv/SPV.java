package com.greenaddress.greenbits.spv;

import android.annotation.TargetApi;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationCompat.Builder;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.FutureCallback;
import com.greenaddress.greenapi.data.EventData;
import com.greenaddress.greenapi.data.SubaccountData;
import com.greenaddress.greenapi.data.TransactionData;
import com.greenaddress.greenapi.model.Model;
import com.greenaddress.greenapi.model.TransactionDataObservable;
import com.greenaddress.greenbits.GaService;
import com.greenaddress.greenbits.ui.CB;
import com.greenaddress.greenbits.ui.R;
import com.greenaddress.greenbits.ui.TabbedMainActivity;
import com.greenaddress.greenbits.ui.preferences.PrefKeys;

import org.bitcoinj.core.Block;
import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.BloomFilter;
import org.bitcoinj.core.CheckpointManager;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.FilteredBlock;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Peer;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.core.listeners.DownloadProgressTracker;
import org.bitcoinj.core.listeners.TransactionReceivedInBlockListener;
import org.bitcoinj.net.BlockingClientManager;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.SPVBlockStore;
import org.bitcoinj.wallet.Wallet;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SPV {

    private final static String TAG = SPV.class.getSimpleName();

    private final Map<TransactionOutPoint, Coin> mCountedUtxoValues = new HashMap<>();


    static class AccountInfo extends Pair<Integer, Integer> {
        AccountInfo(final Integer subAccount, final Integer pointer) { super(subAccount, pointer); }
        Integer getSubAccount() { return first; }
        public Integer getPointer() { return second; }
    }

    // We use a single threaded executor to serialise config changes
    // without forcing callers to block.
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final SparseArray<Coin> mVerifiedCoinBalances = new SparseArray<>();
    private final Map<Sha256Hash, List<Integer>> mUnspentOutpoints = new HashMap<>();
    private final Map<TransactionOutPoint, AccountInfo> mUnspentDetails = new HashMap<>();
    private final GaService mService;
    private BlockStore mBlockStore;
    private BlockChain mBlockChain;
    private PeerGroup mPeerGroup;
    private final PeerFilterProvider mPeerFilter = new PeerFilterProvider(this);
    private NotificationManager mNotifyManager;
    private Builder mNotificationBuilder;
    private final static int mNotificationId = 1;
    private int mNetWorkType;
    private final Object mStateLock = new Object();

    public SPV(final GaService service) {
        mService = service;
        mNetWorkType = ConnectivityManager.TYPE_DUMMY;
    }

    public GaService getService() {
        return mService;
    }

    private < T > String Var(final String name, final T value) {
        return name + " => " + value.toString() + ' ';
    }

    public boolean isEnabled() {
        return !mService.isWatchOnly() && mService.cfg().getBoolean(PrefKeys.SPV_ENABLED, false) &&
               !mService.isLiquid();
    }

    public void setEnabledAsync(final boolean enabled) {
        mExecutor.execute(() -> setEnabled(enabled));
    }

    private void setEnabled(final boolean enabled) {
        synchronized (mStateLock) {
            final boolean current = isEnabled();
            Log.d(TAG, "setEnabled: " + Var("enabled", enabled) + Var("current", current));
            if (enabled == current)
                return;
            mService.cfgEdit().putBoolean(PrefKeys.SPV_ENABLED, enabled).apply();
            // FIXME: Should we delete unspent here?
            reset(false /* deleteAllData */, false /* deleteUnspent */);
        }
    }

    public boolean isSyncOnMobileEnabled() {
        return mService.cfg().getBoolean(PrefKeys.SPV_MOBILE_SYNC_ENABLED, false);
    }

    public void setSyncOnMobileEnabledAsync(final boolean enabled) {
        mExecutor.execute(() -> setSyncOnMobileEnabled(enabled));
    }

    private void setSyncOnMobileEnabled(final boolean enabled) {
        synchronized (mStateLock) {
            final boolean current = isSyncOnMobileEnabled();
            final boolean currentlyEnabled = isEnabled();
            Log.d(TAG, "setSyncOnMobileEnabled: " + Var("enabled", enabled) + Var("current", current));
            if (enabled == current)
                return; // Setting hasn't changed

            mService.cfgEdit().putBoolean(PrefKeys.SPV_MOBILE_SYNC_ENABLED, enabled).apply();

            if (getNetworkType() != ConnectivityManager.TYPE_MOBILE)
                return; // Any change doesn't affect us since we aren't currently on mobile

            if (enabled && currentlyEnabled) {
                if (mPeerGroup == null)
                    setup();
                startSync();
            }else
                stopSync();
        }
    }

    public String getTrustedPeers() {
        return mService.cfg().getString(PrefKeys.TRUSTED_ADDRESS, mService.cfg().getString("trusted_peer", "")).trim();
    }

    public void setTrustedPeersAsync(final String peers) {
        mExecutor.execute(() -> setTrustedPeers(peers));
    }

    private void setTrustedPeers(final String peers) {
        synchronized (mStateLock) {
            // FIXME: We should check if the peers differ here, instead of in the caller
            final String current = getTrustedPeers();
            Log.d(TAG, "setTrustedPeers: " + Var("peers", peers) + Var("current", current));
            mService.cfgEdit().putString(PrefKeys.TRUSTED_ADDRESS, peers).apply();
            reset(false /* deleteAllData */, false /* deleteUnspent */);
        }
    }

    public PeerGroup getPeerGroup(){
        return mPeerGroup;
    }

    public boolean isVerified(final Sha256Hash txHash) {
        return mService.cfg().getBoolean(PrefKeys.VERIFIED_HASH_ + txHash.toString(), false);
    }

    public void startAsync() {
        mExecutor.execute(() -> start());
    }

    private void start() {
        synchronized (mStateLock) {
            Log.d(TAG, "start");
            reset(false /* deleteAllData */, true /* deleteUnspent */);
        }
    }

    public Coin getVerifiedBalance(final int subAccount) {
        return mVerifiedCoinBalances.get(subAccount);
    }

    private boolean isUnspentOutpoint(final Sha256Hash txHash) {
        return mUnspentOutpoints.containsKey(txHash);
    }

    private TransactionOutPoint createOutPoint(final Integer index, final Sha256Hash txHash,
                                               final NetworkParameters params) {
        return new TransactionOutPoint(params, index, txHash);
    }

    void updateUnspentOutputs() {
        final boolean currentlyEnabled = isEnabled();
        Log.d(TAG, "updateUnspentOutputs: " + Var("currentlyEnabled", currentlyEnabled));

        final List<TransactionData> utxos = new ArrayList<>();
        final Model model = mService.getModel();
        final List<SubaccountData> subaccountDataList = model.getSubaccountDataObservable().getSubaccountDataList();

        for (final SubaccountData subaccountData : subaccountDataList) {
            final TransactionDataObservable utxoDataObservable =
                model.getUTXODataObservable(subaccountData.getPointer());
            List<TransactionData> transactionDataList = utxoDataObservable.getTransactionDataList();
            if (!utxoDataObservable.isExecutedOnce()) {
                utxoDataObservable.refreshSync();
                transactionDataList = utxoDataObservable.getTransactionDataList();
            }
            utxos.addAll(transactionDataList);
        }

        final Set<TransactionOutPoint> newUtxos = new HashSet<>();
        boolean recalculateBloom = false;

        Log.d(TAG, Var("number of utxos", utxos.size()));
        for (final TransactionData utxo : utxos) {
            final Integer prevIndex = utxo.getPtIdx();
            final Integer subaccount = utxo.getSubaccount();
            final Integer pointer = utxo.getPointer();
            final Sha256Hash txHash = utxo.getTxhashAsSha256Hash();

            if (isVerified(txHash)) {
                addToUtxo(txHash, prevIndex, subaccount, pointer);
                addUtxoToValues(txHash, false /* updateVerified */);
            } else {
                recalculateBloom = true;
                addToBloomFilter(utxo.getBlockHeight(), txHash, prevIndex, subaccount, pointer);
            }
            newUtxos.add(createOutPoint(prevIndex, txHash, mService.getNetworkParameters()));
        }

        mPeerGroup.setFastCatchupTimeSecs(1393545600);  // GA inception

        final List<Integer> changedSubaccounts = new ArrayList<>();
        for (final TransactionOutPoint oldUtxo : new HashSet<>(mCountedUtxoValues.keySet())) {
            if (!newUtxos.contains(oldUtxo)) {
                recalculateBloom = true;

                final int subAccount = mUnspentDetails.get(oldUtxo).getSubAccount();
                final Coin verifiedBalance = getVerifiedBalance(subAccount);
                mVerifiedCoinBalances.put(subAccount,
                                          verifiedBalance.subtract(mCountedUtxoValues.get(oldUtxo)));
                changedSubaccounts.add(subAccount);
                mCountedUtxoValues.remove(oldUtxo);
                mUnspentDetails.remove(oldUtxo);
                mUnspentOutpoints.get(oldUtxo.getHash()).remove(((int) oldUtxo.getIndex()));
            }
        }

        if (recalculateBloom && mPeerGroup != null)
            mPeerGroup.recalculateFastCatchupAndFilter(PeerGroup.FilterRecalculateMode.SEND_IF_CHANGED);
    }

    private void addUtxoToValues(final Sha256Hash txHash, final boolean updateVerified) {
        final String txHashHex = txHash.toString();

        if (updateVerified)
            mService.cfgEdit().putBoolean(PrefKeys.VERIFIED_HASH_ + txHashHex, true).apply();

        final Model model = mService.getModel();
        final List<SubaccountData> subaccountDataList = model.getSubaccountDataObservable().getSubaccountDataList();

        for (final SubaccountData subaccountData : subaccountDataList) {
            final Integer pointer = subaccountData.getPointer();
            final TransactionDataObservable transactionDataObservable = model.getTransactionDataObservable(pointer);
            if (transactionDataObservable.getTransactionDataList() == null)
                transactionDataObservable.refreshSync();

            //TODO called too many times
            for (TransactionData tx : transactionDataObservable.getTransactionDataList()) {
                if (txHashHex.equals(tx.getTxhash())) {
                    transactionDataObservable.fire();
                }
            }
        }
    }

    int getBloomFilterElementCount() {
        final int count = mUnspentOutpoints.size();
        return count == 0 ? 1 : count;
    }

    BloomFilter getBloomFilter(final int size, final double falsePositiveRate, final long nTweak) {
        final Set<Sha256Hash> keys = mUnspentOutpoints.keySet();
        Log.d(TAG, "getBloomFilter returning " + keys.size() + " items");
        final BloomFilter filter = new BloomFilter(size, falsePositiveRate, nTweak);
        for (final Sha256Hash hash : keys)
            filter.insert(hash.getReversedBytes());

        if (keys.isEmpty()) {
            // Add a fake entry to avoid downloading blocks when filter is empty,
            // as empty bloom filters are ignored by bitcoinj.
            // FIXME: This results in a constant filter that peers can use to identify
            //        us as a Green client. That is undesirable.
            filter.insert(new byte[] {(byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef});
        }
        return filter;
    }

    private void addToBloomFilter(final Integer blockHeight, final Sha256Hash txHash, final int prevIndex,
                                  final int subAccount, final int pointer) {
        if (mBlockChain == null)
            return; // can happen before login (onNewBlock)
        if (txHash != null)
            addToUtxo(txHash, prevIndex, subAccount, pointer);

        if (blockHeight != null && blockHeight <= mBlockChain.getBestChainHeight() &&
            (txHash == null || !mUnspentOutpoints.containsKey(txHash))) {
            // new tx or block notification with blockHeight <= current blockHeight means we might've [1]
            // synced the height already while we haven't seen the tx, so we need to re-sync to be able
            // to verify it.
            // [1] - "might've" in case of txHash == null (block height notification),
            //       because it depends on the order of notifications
            //     - "must've" in case of txHash != null, because this means the tx arrived only after
            //       requesting it manually and we already had higher blockHeight
            //
            // We do it using the special case in bitcoinj for VM crashed because of
            // a transaction received.
            try {
                Log.d(TAG, "Creating fake wallet for re-sync");
                final Wallet fakeWallet = new Wallet(mService.getNetworkParameters()) {
                    @Override
                    public int getLastBlockSeenHeight() {
                        return blockHeight - 1;
                    }
                };
                mBlockChain.addWallet(fakeWallet);
                mBlockChain.removeWallet(fakeWallet);  // can be removed, because the call above
                // should rollback already
            } catch (final Exception e) {
                e.printStackTrace();
                Log.w(TAG, "fakeWallet exception: " + e.toString());
            }
        }
    }

    private void addToUtxo(final Sha256Hash txHash, final Integer prevIndex, final int subAccount, final int pointer) {
        mUnspentDetails.put(createOutPoint(prevIndex, txHash, mService.getNetworkParameters()),
                            new AccountInfo(subAccount, pointer));
        if (mUnspentOutpoints.get(txHash) == null)
            mUnspentOutpoints.put(txHash, Lists.newArrayList(prevIndex));
        else
            mUnspentOutpoints.get(txHash).add(prevIndex);
    }

    public int getSPVHeight() {
        if (mBlockChain != null && isEnabled())
            return mBlockChain.getBestChainHeight();
        return 0;
    }

    @TargetApi(Build.VERSION_CODES.M)
    private PendingIntent getNotificationIntent() {
        final Context service = getService();
        final Intent intent = new Intent(service, TabbedMainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return PendingIntent.getActivity(service, 0, intent, PendingIntent.FLAG_IMMUTABLE);
    }

    private void startSync() {
        synchronized (mStateLock) {
            final boolean isRunning = mPeerGroup != null && mPeerGroup.isRunning();
            Log.d(TAG, "startSync: " + Var("isRunning", isRunning));

            if (isRunning)
                return; // Already started to sync

            if (mPeerGroup == null) {
                // FIXME: Thi should not be possible but it happens in the wild.
                Log.d(TAG, "startSync: mPeerGroup is null");
                return;
            }

            if (mNotifyManager == null) {
                mNotifyManager = (NotificationManager) mService.getSystemService(Context.NOTIFICATION_SERVICE);
                mNotificationBuilder = new NotificationCompat.Builder(mService, "spv_channel");
                mNotificationBuilder.setContentTitle("Green SPV_SYNCRONIZATION Sync")
                .setSmallIcon(R.drawable.ic_refresh);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    mNotificationBuilder.setContentIntent(getNotificationIntent());
            }

            mNotificationBuilder.setContentText("Connecting to peer(s)...");
            updateNotification(0, 0);

            CB.after(mPeerGroup.startAsync(), new FutureCallback<Object>() {
                @Override
                public void onSuccess(final Object result) {
                    mPeerGroup.startBlockChainDownload(new DownloadProgressTracker() {
                        @Override
                        public void onChainDownloadStarted(final Peer peer, final int blocksLeft) {
                            // Note that this method may be called multiple times if syncing
                            // switches peers while downloading.
                            Log.d(TAG, "onChainDownloadStarted: " + Var("blocksLeft", blocksLeft));
                            if (blocksLeft > 0) {
                                mService.getModel().getEventDataObservable().pushEvent(
                                    new EventData(R.string.id_spv_notifications,
                                                  R.string.id_s_blocks_left,
                                                  blocksLeft));
                            }
                            super.onChainDownloadStarted(peer, blocksLeft);
                        }

                        @Override
                        public void onBlocksDownloaded(final Peer peer, final Block block,
                                                       final FilteredBlock filteredBlock, final int blocksLeft) {
                            //Log.d(TAG, "onBlocksDownloaded: " + Var("blocksLeft", blocksLeft));
                            super.onBlocksDownloaded(peer, block, filteredBlock, blocksLeft);
                        }

                        @Override
                        protected void startDownload(final int blocks) {
                            Log.d(TAG, "startDownload");
                            updateNotification(100, 0);
                        }

                        @Override
                        protected void progress(final double percent, final int blocksSoFar, final Date date) {
                            //Log.d(TAG, "progress: " + Var("percent", percent));
                            mNotificationBuilder.setContentText("Sync in progress...");
                            updateNotification(100, (int) percent);
                        }

                        @Override
                        protected void doneDownload() {
                            Log.d(TAG, "doneDownLoad");
                            mService.getModel().getEventDataObservable().pushEvent(
                                new EventData(R.string.id_spv_notifications, R.string.id_download_finished));
                            mNotifyManager.cancel(mNotificationId);
                        }
                    });
                }

                @Override
                public void onFailure(final Throwable t) {
                    t.printStackTrace();
                    mNotifyManager.cancel(mNotificationId);
                }
            });
        }
    }

    private void updateNotification(final int total, final int soFar) {
        mNotificationBuilder.setProgress(total, soFar, false);
        mNotifyManager.notify(mNotificationId, mNotificationBuilder.build());
    }

    private PeerAddress getPeerAddress(final String address) throws URISyntaxException, UnknownHostException {
        final URI uri = new URI("btc://" + address);
        final String host = uri.getHost();

        if (host == null)
            throw new UnknownHostException(address);

        final int port = uri.getPort() == -1 ? mService.getNetworkParameters().getPort() : uri.getPort();

        if (!mService.isProxyEnabled())
            return new PeerAddress(mService.getNetworkParameters(), InetAddress.getByName(host), port);

        return new PeerAddress(mService.getNetworkParameters(), host, port) {
                   @Override
                   public InetSocketAddress toSocketAddress() {
                       return InetSocketAddress.createUnresolved(host, port);
                   }

                   @Override
                   public String toString() {
                       return String.format("%s:%s", host, port);
                   }

                   @Override
                   public int hashCode() {
                       return uri.hashCode();
                   }
        };
    }

    private void addPeer(final String address, final NetworkParameters params) throws URISyntaxException {

        try {
            mPeerGroup.addAddress(getPeerAddress(address));
        } catch (final UnknownHostException e) {
            // FIXME: Should report this error: one the host here couldn't be resolved
            e.printStackTrace();
        }
    }


    private void setPingInterval(final long interval) {
        synchronized (mStateLock) {
            if (mPeerGroup != null)
                mPeerGroup.setPingIntervalMsec(interval);
        }
    }

    public void enablePingMonitoring() { setPingInterval(PeerGroup.DEFAULT_PING_INTERVAL_MSEC); }
    public void disablePingMonitoring() { setPingInterval(-1); }

    private void setup(){
        synchronized (mStateLock) {
            Log.d(TAG, "setup: " + Var("mPeerGroup != null", mPeerGroup != null));

            if (mPeerGroup != null) {
                // FIXME: Make sure this can never happen
                Log.e(TAG, "Must stop and tear down SPV_SYNCRONIZATION before setting up again!");
                return;
            }

            try {
                Log.d(TAG, "Creating block store");
                mBlockStore = new SPVBlockStore(mService.getNetworkParameters(), mService.getSPVChainFile());
                final StoredBlock storedBlock = mBlockStore.getChainHead(); // detect corruptions as early as possible
                if (storedBlock.getHeight() == 0 && mService.isRegtest()) {
                    InputStream is = null;
                    try {
                        is = mService.getAssets().open(
                            mService.isMainnet() ? "production/checkpoints" : "btctestnet/checkpoints");
                        final int keyTime = 0; //mService.getLoginData().get("earliest_key_creation_time"); // TODO gdk
                        CheckpointManager.checkpoint(mService.getNetworkParameters(), is,
                                                     mBlockStore, keyTime);
                        Log.d(TAG, "checkpoints loaded");
                    } catch (final Exception e) {
                        // couldn't load checkpoints, log & skip
                        Log.d(TAG, "couldn't load checkpoints, log & skip");
                        e.printStackTrace();
                    } finally {
                        try {
                            if (is != null)
                                is.close();
                        } catch (final IOException e) {
                            // do nothing
                        }
                    }
                }
                Log.d(TAG, "Creating block chain");
                org.bitcoinj.core.Context context = new org.bitcoinj.core.Context(mService.getNetworkParameters());
                mBlockChain = new BlockChain(context, mBlockStore);
                mBlockChain.addTransactionReceivedListener(mTxListner);

                System.setProperty("user.home", mService.getFilesDir().toString());

                Log.d(TAG, "Creating peer group");
                if (!mService.isProxyEnabled())
                    mPeerGroup = new PeerGroup(mService.getNetworkParameters(), mBlockChain);
                else {
                    final String proxyHost = mService.getProxyHost();
                    final String proxyPort = mService.getProxyPort();
                    final Socks5SocketFactory sf = new Socks5SocketFactory(proxyHost, proxyPort);
                    final BlockingClientManager bcm = new BlockingClientManager(sf);
                    bcm.setConnectTimeoutMillis(60000);
                    mPeerGroup = new PeerGroup(mService.getNetworkParameters(), mBlockChain, bcm);
                    mPeerGroup.setConnectTimeoutMillis(60000);
                }

                disablePingMonitoring();

                updateUnspentOutputs();
                mPeerGroup.addPeerFilterProvider(mPeerFilter);

                Log.d(TAG, "Adding peers");
                final String peers = getTrustedPeers();
                final List<String> addresses;
                if (peers.isEmpty()) {
                    // DEFAULT_PEER is only set for regtest. For other networks
                    // it is empty and so will cause us to use DNS discovery.
                    addresses = mService.getNetwork().getDefaultPeers();
                }else
                    addresses = new ArrayList<>(Arrays.asList(peers.split(",")));

                for (final String address: addresses)
                    addPeer(address, mService.getNetworkParameters());

                if (addresses.isEmpty() && !mService.isProxyEnabled() &&
                    mService.getNetworkParameters().getDnsSeeds() != null) {
                    // Blank w/o proxy: Use the built in resolving via DNS
                    mPeerGroup.addPeerDiscovery(new DnsDiscovery(mService.getNetworkParameters()));
                }

            } catch (final BlockStoreException | UnknownHostException | URISyntaxException e) {
                e.printStackTrace();
            }
        }
    }

    private final TransactionReceivedInBlockListener mTxListner = new TransactionReceivedInBlockListener() {
        @Override
        public void receiveFromBlock(final Transaction tx, final StoredBlock block,
                                     final BlockChain.NewBlockType blockType,
                                     final int relativityOffset) throws VerificationException {
            if (tx == null)
                throw new RuntimeException("receiveFromBlock got null tx");
            Log.d(TAG, "receiveFromBlock " + tx.getHash().toString());
            addUtxoToValues(tx.getHash(), true);
        }

        @Override
        public boolean notifyTransactionIsInBlock(final Sha256Hash txHash, final StoredBlock block,
                                                  final BlockChain.NewBlockType blockType,
                                                  final int relativityOffset) throws VerificationException {
            Log.d(TAG, "notifyTransactionIsInBlock " + txHash.toString());
            return isUnspentOutpoint(txHash);
        }
    };

    private void stopSync() {
        synchronized (mStateLock) {
            Log.d(TAG, "stopSync: " + Var("isEnabled", isEnabled()));

            if (mPeerGroup != null && mPeerGroup.isRunning()) {
                Log.d(TAG, "Stopping peer group");
                final Intent i = new Intent("PEERGROUP_UPDATED");
                i.putExtra("peergroup", "stopSPVSync");
                mService.sendBroadcast(i);
                mPeerGroup.stop();
            }

            if (mNotifyManager != null)
                mNotifyManager.cancel(mNotificationId);

            if (mBlockChain != null) {
                Log.d(TAG, "Disposing of block chain");
                //mBlockChain.removeTransactionReceivedListener(mTxListner);
                mBlockChain = null;
            }

            if (mPeerGroup != null) {
                Log.d(TAG, "Deleting peer group");
                mPeerGroup.removePeerFilterProvider(mPeerFilter);
                mPeerGroup = null;
            }

            if (mBlockStore != null) {
                Log.d(TAG, "Closing block store");
                try {
                    mBlockStore.close();
                    mBlockStore = null;
                } catch (final BlockStoreException x) {
                    throw new RuntimeException(x);
                }
            }
        }
    }

    // We only care about mobile vs non-mobile so treat others as ethernet
    private int getNetworkType(final NetworkInfo info) {
        if (info == null)
            return ConnectivityManager.TYPE_DUMMY;
        final int type = info.getType();
        return type == ConnectivityManager.TYPE_MOBILE ? type : ConnectivityManager.TYPE_ETHERNET;
    }

    private int getNetworkType() { return getNetworkType(mService.getNetworkInfo()); }

    // Handle changes to network connectivity.
    // Note that this only handles mobile/non-mobile transitions
    public void onNetConnectivityChangedAsync(final NetworkInfo info) {
        mExecutor.execute(() -> onNetConnectivityChanged(info));
    }

    private void onNetConnectivityChanged(final NetworkInfo info) {
        synchronized (mStateLock) {
            final int oldType = mNetWorkType;
            final int newType = getNetworkType(info);
            mNetWorkType = newType;

            if (!isEnabled() || newType == oldType)
                return; // No change

            Log.d(TAG, "onNetConnectivityChanged: " + Var("newType", newType) +
                  Var("oldType", oldType) + Var("isSyncOnMobileEnabled", isSyncOnMobileEnabled()));

            // FIXME: - It seems network connectivity changes can happen when
            //          mPeerGroup is null (i.e. setup hasn't been called),
            //          but its not clear what path leads to this happening.
            if (newType == ConnectivityManager.TYPE_MOBILE) {
                if (!isSyncOnMobileEnabled())
                    stopSync(); // Mobile network and we have sync mobile disabled
            } else if (oldType == ConnectivityManager.TYPE_MOBILE) {
                if (isSyncOnMobileEnabled())
                    startSync(); // Non-Mobile network and we have sync mobile enabled
            }
        }
    }

    public void resetAsync() {
        mExecutor.execute(() -> reset(true /* deleteAllData */, true /* deleteUnspent */));
    }

    private void reset(final boolean deleteAllData, final boolean deleteUnspent) {
        synchronized (mStateLock) {
            Log.d(TAG, "reset: " + Var("deleteAllData", deleteAllData) +
                  Var("deleteUnspent", deleteUnspent));
            stopSync();

            if (deleteAllData) {
                Log.d(TAG, "Deleting chain file");
                mService.getSPVChainFile().delete();

                try {
                    Log.d(TAG, "Clearing verified and spendable transactions");
                    // TODO not a namespace anymore, delete all preferences starting with?
                    //mService.cfgInEdit(SPENDABLE).clear().commit();
                    //mService.cfgInEdit(VERIFIED).clear().commit();
                } catch (final NullPointerException e) {
                    // ignore
                }
            }

            if (deleteUnspent) {
                Log.d(TAG, "Resetting unspent outputs");
                mUnspentDetails.clear();
                mUnspentOutpoints.clear();
                mCountedUtxoValues.clear();
                mVerifiedCoinBalances.clear();
            }

            if (isEnabled()) {
                setup();

                // We might race with our network callbacks, so fetch the network type
                // if its unknown.
                if (mNetWorkType == ConnectivityManager.TYPE_DUMMY)
                    mNetWorkType = getNetworkType();

                if (isSyncOnMobileEnabled() || mNetWorkType != ConnectivityManager.TYPE_MOBILE)
                    startSync();
            }
            Log.d(TAG, "Finished reset");
        }
    }
}
