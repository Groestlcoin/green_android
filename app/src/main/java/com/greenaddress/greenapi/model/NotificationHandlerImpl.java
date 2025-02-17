package com.greenaddress.greenapi.model;

import android.util.Log;

import com.blockstream.libgreenaddress.GDK;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.greenaddress.greenapi.data.EstimatesData;
import com.greenaddress.greenapi.data.EventData;
import com.greenaddress.greenapi.data.SettingsData;
import com.greenaddress.greenapi.data.TransactionData;
import com.greenaddress.greenbits.GaService;
import com.greenaddress.greenbits.ui.R;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class NotificationHandlerImpl implements GDK.NotificationHandler {
    private Model mModel;
    private GaService mService;
    private Queue<Object> mTemp = new LinkedList<>();
    private static final ObjectMapper mObjectMapper = new ObjectMapper();
    private ScheduledFuture<?> mScheduledFuture;
    private Long mTryingAt;

    public NotificationHandlerImpl() {
        mObjectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public synchronized void setModel(final GaService service) {
        this.mService = service;
        this.mModel = service.getModel();
        for (Object element : mTemp) {
            process(element);
        }
        mTemp.clear();
    }

    @Override
    public synchronized void onNewNotification(final Object session, final Object jsonObject) {
        if (mModel == null) {
            mTemp.add(jsonObject);
        } else {
            process(jsonObject);
        }
    }

    private void cancelTimer() {
        if (mScheduledFuture != null && !mScheduledFuture.isCancelled())
            mScheduledFuture.cancel(false);
    }

//{"reset_2fa_active":true,"reset_2fa_days_remaining":90,"reset_2fa_disputed":false}
    private void process(final Object jsonObject) {
        Log.d("OBSNTF", "notification " + jsonObject);
        if (jsonObject == null)
            return;
        ObjectNode objectNode = (ObjectNode) jsonObject;
        try {
            switch (objectNode.get("event").asText()) {
            case "network": {
                //{"event":"network","network":{"connected":false,"elapsed":1091312175736,"limit":true,"waiting":0}}
                final JsonNode networkNode = objectNode.get("network");
                final boolean connected = networkNode.get("connected").asBoolean();

                cancelTimer();
                Log.d("OBSNTF", "NETWORKEVENT connected:" + connected);

                if (connected) {
                    final boolean loginRequired = networkNode.get("login_required").asBoolean(false);
                    mModel.getConnMsgObservable().setOnline();
                    if (loginRequired) {
                        mService.getConnectionManager().goLoginRequired();
                    } else {
                        mService.getConnectionManager().goPostLogin();
                    }
                } else {
                    long waitingMs = networkNode.get("waiting").asLong() * 1000;
                    if (waitingMs > 3000) {
                        mTryingAt = System.currentTimeMillis() + waitingMs;
                        mScheduledFuture = mService.getTimerExecutor().scheduleAtFixedRate(() -> {
                                final int remainingSec = (int) ((mTryingAt - System.currentTimeMillis()) / 1000);
                                if (remainingSec >= 0)
                                    mModel.getConnMsgObservable().setMessage(R.string.id_not_connected_connecting_in_ds_,
                                                                             new Object[] {remainingSec});
                                else
                                    cancelTimer();
                            }, 0, 100, TimeUnit.MILLISECONDS);
                    }
                    mService.getConnectionManager().goOffline();
                }

                break;
            }
            case "block": {
                //{"block":{"block_hash":"0000000000003c640a577923dd385428edcfa570ee3bb46d435efca1efbb71a5","block_height":1435025},"event":"block"}
                final JsonNode blockHeight = objectNode.get("block").get("block_height");
                Log.d("OBSNTF", "blockHeight " + blockHeight);
                mModel.getBlockchainHeightObservable().setHeight(blockHeight.asInt());
                break;
            }
            case "fees": {
                //{"fees":[1000,86602,86602,23249,23249,23249,23249,23249,23249,23249,23249,23249,23249,23249,23249,23249,23249,23249,23249,23249,23249,23249,23249,23249,23249]}
                final EstimatesData estimatesData = mObjectMapper.treeToValue(objectNode, EstimatesData.class);
                mModel.getFeeObservable().setFees(estimatesData.getFees());
                Log.d("OBSNTF", "estimatesData " + estimatesData);
                break;
            }
            case "transaction": {
                //{"event":"transaction","transaction":{"satoshi":7895722,"subaccounts":[0],"txhash":"eab1e3aaa357a78f83c7a7d009fe8d2c8acbe9e1c5071398694bbeed7f812f2f","type":"incoming"}}
                final JsonNode transaction = objectNode.get("transaction");
                final ArrayNode arrayNode = (ArrayNode) transaction.get("subaccounts");
                final List<Integer> subaccounts = mObjectMapper.readValue(
                    arrayNode.toString(), new TypeReference<List<Integer>>(){});
                if (transaction.get("type") != null && "incoming".equals(transaction.get("type").asText())) {
                    mModel.getToastObservable().setMessage(R.string.id_a_new_transaction_has_just);
                }

                boolean eventPushed = false;
                for (JsonNode jsonNode : arrayNode) {
                    final int subaccount = jsonNode.asInt();
                    Log.d("OBSNTF", "subaccount involved " + subaccount);
                    mModel.getBalanceDataObservable(subaccount).refresh();
                    mModel.getReceiveAddressObservable(subaccount).refresh();
                    mModel.getTransactionDataObservable(subaccount).refresh();
                    try {
                        final TransactionData transactionData = mObjectMapper.convertValue(transaction,
                                                                                           TransactionData.class);

                        transactionData.setSubaccount(subaccount);
                        transactionData.setSubaccounts(subaccounts);
                        Log.d("OBSNTF", "transactionData " + transactionData);
                        Integer description = null;
                        if (subaccounts.size() > 1) {
                            description = R.string.id_new_transaction_involving;
                        } else if (transaction.get("type") != null) {
                            if ("incoming".equals(transaction.get("type").asText())) {
                                description = R.string.id_new_incoming_transaction_in;
                            } else {
                                description = R.string.id_new_outgoing_transaction_from;
                            }
                        }

                        if (!eventPushed && description != null) {
                            mModel.getEventDataObservable().pushEvent(new EventData(
                                                                          R.string.id_new_transaction, description,
                                                                          transactionData));
                            eventPushed = true;
                        }
                    } catch (final Exception e) {
                        Log.e("NOTIF", e.getMessage());
                    }
                }
                break;
            }
            case "settings": {
                //{"event":"settings","settings":{"altimeout":5,"notifications":{"email_incoming":true,"email_outgoing":true},"pricing":{"currency":"MYR","exchange":"LUNO"},"required_num_blocks":24,"sound":false,"unit":"bits"}}
                final SettingsData settings =
                    mObjectMapper.convertValue(objectNode.get("settings"), SettingsData.class);
                Log.d("OBSNTF", "SettingsData " + settings);
                mModel.getSettingsObservable().setSettings(settings);
                break;
            }
            case "subaccount": {
                //{"event":"subaccount","subaccount":{"bits":"701144.66","btc":"0.70114466","fiat":"0.7712591260000000622741556099981585311432","fiat_currency":"EUR","fiat_rate":"1.10000000000000008881784197001252","has_transactions":true,"mbtc":"701.14466","name":"","pointer":0,"receiving_id":"GA3MQKVp6pP7royXDuZcw55F2TXTgg","recovery_chain_code":"","recovery_pub_key":"","satoshi":70114466,"type":"2of2","ubtc":"701144.66"}}
                // server-side subaccount setting is ignored because we use the locally-saved one
                break;
            }
            case "twofactor_reset": {
                //{"event":"twofactor_reset","twofactor_reset":{"days_remaining":90,"is_active":true,"is_disputed":false}}
                final JsonNode resetData = objectNode.get("twofactor_reset");
                if (resetData.get("is_active").asBoolean()) {
                    mModel.setTwoFAReset(true);
                    final EventData ev;
                    if (resetData.get("is_disputed").asBoolean()) {
                        ev = new EventData(R.string.id_twofactor_authentication,
                                           R.string.id_warning_wallet_locked_by);
                    } else{
                        final Integer days = resetData.get("days_remaining").asInt();
                        ev = new EventData(R.string.id_twofactor_authentication,
                                           R.string.id_warning_wallet_locked_for, days);
                    }
                    mModel.getEventDataObservable().pushEvent(ev);
                }
                break;
            }
            }
        } catch (final Exception e) {
            Log.e("NOTIF", e.getMessage());
        }

    }
}
