/*
 * Copyright (c) 2020 Cobo
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * in the file COPYING.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.cobo.cold.ui.fragment.main;

import android.os.Bundle;
import android.os.Handler;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;

import androidx.databinding.DataBindingUtil;
import androidx.lifecycle.ViewModelProviders;

import com.cobo.coinlib.coins.BCH.Bch;
import com.cobo.coinlib.coins.LTC.Ltc;
import com.cobo.coinlib.utils.Arith;
import com.cobo.coinlib.utils.Coins;
import com.cobo.cold.R;
import com.cobo.cold.Utilities;
import com.cobo.cold.callables.FingerprintPolicyCallable;
import com.cobo.cold.config.FeatureFlags;
import com.cobo.cold.databinding.ProgressModalBinding;
import com.cobo.cold.databinding.TxConfirmFragmentBinding;
import com.cobo.cold.db.entity.TxEntity;
import com.cobo.cold.encryptioncore.utils.ByteFormatter;
import com.cobo.cold.ui.BindingAdapters;
import com.cobo.cold.ui.fragment.BaseFragment;
import com.cobo.cold.ui.fragment.setup.PreImportFragment;
import com.cobo.cold.ui.modal.ModalDialog;
import com.cobo.cold.ui.modal.SigningDialog;
import com.cobo.cold.ui.views.AuthenticateModal;
import com.cobo.cold.util.KeyStoreUtil;
import com.cobo.cold.viewmodel.TxConfirmViewModel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.cobo.cold.callables.FingerprintPolicyCallable.READ;
import static com.cobo.cold.callables.FingerprintPolicyCallable.TYPE_SIGN_TX;
import static com.cobo.cold.ui.fragment.main.BroadcastTxFragment.KEY_TXID;
import static com.cobo.cold.ui.fragment.main.FeeAttackChecking.FeeAttackCheckingResult.NORMAL;
import static com.cobo.cold.ui.fragment.main.FeeAttackChecking.FeeAttackCheckingResult.SAME_OUTPUTS;
import static com.cobo.cold.ui.fragment.setup.PreImportFragment.ACTION;

public class TxConfirmFragment extends BaseFragment<TxConfirmFragmentBinding> {

    public static final String KEY_TX_DATA = "tx_data";
    private final Runnable forgetPassword = () -> {
        Bundle bundle = new Bundle();
        bundle.putString(ACTION, PreImportFragment.ACTION_RESET_PWD);
        navigate(R.id.action_to_preImportFragment, bundle);
    };
    private String data;
    private TxConfirmViewModel viewModel;
    private SigningDialog signingDialog;
    private TxEntity txEntity;
    private ModalDialog addingAddressDialog;
    private int feeAttackCheckingState;
    private FeeAttackChecking feeAttackChecking;

    @Override
    protected int setView() {
        return R.layout.tx_confirm_fragment;
    }

    @Override
    protected void init(View view) {
        Bundle bundle = Objects.requireNonNull(getArguments());
        mBinding.toolbar.setNavigationOnClickListener(v -> navigateUp());
        mBinding.txDetail.txIdInfo.setVisibility(View.GONE);
        data = bundle.getString(KEY_TX_DATA);
        viewModel = ViewModelProviders.of(this).get(TxConfirmViewModel.class);
        mBinding.setViewModel(viewModel);
        subscribeTxEntityState();

        mBinding.sign.setOnClickListener(v -> handleSign());

    }

    private void handleSign() {
        if (feeAttackCheckingState == SAME_OUTPUTS) {
            feeAttackChecking.showFeeAttackWarning();
            return;
        }
        boolean fingerprintSignEnable = new FingerprintPolicyCallable(READ, TYPE_SIGN_TX).call();
        if (txEntity != null) {
            if (FeatureFlags.ENABLE_WHITE_LIST) {
                if (isAddressInWhiteList()) {
                    AuthenticateModal.show(mActivity,
                            getString(R.string.password_modal_title),
                            "",
                            fingerprintSignEnable,
                            token -> {
                                viewModel.setToken(token);
                                viewModel.handleSign();
                                subscribeSignState();
                            }, forgetPassword);
                } else {
                    Utilities.alert(mActivity, getString(R.string.hint),
                            getString(R.string.not_in_whitelist_reject),
                            getString(R.string.confirm),
                            () -> navigate(R.id.action_to_home));
                }

            } else {
                AuthenticateModal.show(mActivity,
                        getString(R.string.password_modal_title),
                        "",
                        fingerprintSignEnable,
                        token -> {
                            viewModel.setToken(token);
                            viewModel.handleSign();
                            subscribeSignState();
                        }, forgetPassword);
            }
        } else {
            navigate(R.id.action_to_home);
        }
    }

    private void subscribeTxEntityState() {
        viewModel.parseTxData(data);
        viewModel.getObservableTx().observe(this, txEntity -> {
            if (txEntity != null) {
                this.txEntity = txEntity;
                mBinding.setTx(txEntity);
                refreshAmount();
                refreshFromList();
                refreshReceiveList();
                refreshTokenUI();
                refreshFeeDisplay();
                refreshMemoDisplay();
            }
        });

        viewModel.getAddingAddressState().observe(this, b -> {
            if (b) {
                addingAddressDialog = ModalDialog.newInstance();
                ProgressModalBinding binding = DataBindingUtil.inflate(LayoutInflater.from(mActivity),
                        R.layout.progress_modal, null, false);
                binding.text.setText(R.string.sync_in_progress);
                binding.text.setVisibility(View.VISIBLE);
                addingAddressDialog.setBinding(binding);
                addingAddressDialog.show(mActivity.getSupportFragmentManager(), "");
            } else {
                if (addingAddressDialog != null) {
                    addingAddressDialog.dismiss();
                }
            }
        });

        viewModel.parseTxException().observe(this, ex -> {
            if (ex != null) {
                ex.printStackTrace();
                ModalDialog.showCommonModal(mActivity,
                        getString(R.string.scan_failed),
                        getString(R.string.incorrect_tx_data),
                        getString(R.string.confirm),
                        null);
                navigateUp();
            }
        });

        viewModel.feeAttackChecking().observe(this, state -> {
            feeAttackCheckingState = state;
            if (state != NORMAL) {
                feeAttackChecking = new FeeAttackChecking(this);
            }
        });
    }

    private void refreshMemoDisplay() {
        if (txEntity.getCoinCode().equals(Coins.EOS.coinCode())
                || txEntity.getCoinCode().equals(Coins.IOST.coinCode())) {
            mBinding.txDetail.memoLabel.setText(R.string.tag);
        } else if (Coins.isPolkadotFamily(txEntity.getCoinCode()) || txEntity.getCoinCode().equals(Coins.TCFX.coinCode())) {
            mBinding.txDetail.memoInfo.setVisibility(View.GONE);
        }
    }

    private void refreshFeeDisplay() {
        if (txEntity.getCoinCode().equals(Coins.EOS.coinCode())
                || txEntity.getCoinCode().equals(Coins.IOST.coinCode())) {
            mBinding.txDetail.feeInfo.setVisibility(View.GONE);
        } else if (Coins.isPolkadotFamily(txEntity.getCoinCode())) {
            mBinding.txDetail.feeLabel.setText(R.string.dot_tip);
        }
    }

    private void refreshAmount() {
        SpannableStringBuilder style = new SpannableStringBuilder(txEntity.getAmount());
        style.setSpan(new ForegroundColorSpan(mActivity.getColor(R.color.colorAccent)),
                0, txEntity.getAmount().indexOf(" "), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        mBinding.txDetail.amount.setText(style);
    }

    private void refreshTokenUI() {
        String assetCode = null;
        try {
            assetCode = txEntity.getAmount().split(" ")[1];
        } catch (Exception ignore) {
        }
        if (TextUtils.isEmpty(assetCode)) {
            assetCode = txEntity.getCoinCode();
        }
        BindingAdapters.setIcon(mBinding.txDetail.icon,
                txEntity.getCoinCode(),
                assetCode);
        if (!assetCode.equals(txEntity.getCoinCode())) {
            mBinding.txDetail.coinId.setText(assetCode);
        } else {
            mBinding.txDetail.coinId.setText(Coins.coinNameOfCoinId(txEntity.getCoinId()));
        }
    }

    private void refreshReceiveList() {
        String to = txEntity.getTo();
        if (Coins.isPolkadotFamily(txEntity.getCoinCode())) {
            double amount = Double.parseDouble(txEntity.getAmount().split(" ")[0]);
            double tip = Double.parseDouble(txEntity.getFee().split(" ")[0]);
            double value = Arith.sub(amount, tip);
            mBinding.txDetail.info.setText(value + " " +txEntity.getCoinCode() +"\n" + to);
            return;
        } else {
            mBinding.txDetail.info.setText(to.replace(",","\n\n"));
        }
        List<TransactionItem> items = new ArrayList<>();
        try {
            JSONArray outputs = new JSONArray(to);
            for (int i = 0; i < outputs.length(); i++) {
                JSONObject output = outputs.getJSONObject(i);
                if (output.optBoolean("isChange")) {
                    continue;
                }
                items.add(new TransactionItem(i,
                        output.getLong("value"),
                        output.getString("address"),
                        txEntity.getCoinCode()
                ));
            }
        } catch (JSONException e) {
            return;
        }
        TransactionItemAdapter adapter = new TransactionItemAdapter(mActivity,
                TransactionItem.ItemType.TO);
        adapter.setItems(items);
        mBinding.txDetail.toList.setVisibility(View.VISIBLE);
        mBinding.txDetail.toInfo.setVisibility(View.GONE);
        mBinding.txDetail.toList.setAdapter(adapter);
    }

    private void refreshFromList() {
        String from = txEntity.getFrom();
        from = convertLegacyAddress(txEntity,from);
        mBinding.txDetail.from.setText(from);
        List<TransactionItem> items = new ArrayList<>();
        try {
            JSONArray inputs = new JSONArray(from);
            for (int i = 0; i < inputs.length(); i++) {
                items.add(new TransactionItem(i,
                        inputs.getJSONObject(i).getLong("value"),
                        inputs.getJSONObject(i).getString("address"),
                        txEntity.getCoinCode()
                ));
            }
            String fromAddress = inputs.getJSONObject(0).getString("address");
            fromAddress = convertLegacyAddress(txEntity, fromAddress);
            mBinding.txDetail.from.setText(fromAddress);
        } catch (JSONException ignore) {
        }
    }

    public static String convertLegacyAddress(TxEntity tx, String fromAddress) {
        String coinCode = tx.getCoinCode();
        if (coinCode.equals(Coins.BCH.coinCode())) {
            fromAddress = Bch.toCashAddress(fromAddress);
        } else if (coinCode.equals(Coins.LTC.coinCode())) {
            fromAddress = Ltc.convertAddress(fromAddress);
        }
        return fromAddress;
    }

    private void subscribeSignState() {
        viewModel.getSignState().observe(this, s -> {
            if (TxConfirmViewModel.STATE_SIGNING.equals(s)) {
                signingDialog = SigningDialog.newInstance();
                signingDialog.show(mActivity.getSupportFragmentManager(), "");
            } else if (TxConfirmViewModel.STATE_SIGN_SUCCESS.equals(s)) {
                if (signingDialog != null) {
                    signingDialog.setState(SigningDialog.STATE_SUCCESS);
                }
                new Handler().postDelayed(() -> {
                    if (signingDialog != null) {
                        signingDialog.dismiss();
                    }
                    signingDialog = null;
                    onSignSuccess();
                }, 500);
            } else if (TxConfirmViewModel.STATE_SIGN_FAIL.equals(s)) {
                if (signingDialog == null) {
                    signingDialog = SigningDialog.newInstance();
                    signingDialog.show(mActivity.getSupportFragmentManager(), "");
                }
                new Handler().postDelayed(() -> signingDialog.setState(SigningDialog.STATE_FAIL), 1000);
                new Handler().postDelayed(() -> {
                    if (signingDialog != null) {
                        signingDialog.dismiss();
                    }
                    signingDialog = null;
                    viewModel.getSignState().removeObservers(this);
                }, 2000);
            }
        });
    }

    private void onSignSuccess() {
        String txId = viewModel.getTxId();
        Bundle data = new Bundle();
        data.putString(KEY_TXID, txId);
        navigate(R.id.action_to_broadcastTxFragment, data);
        viewModel.getSignState().removeObservers(this);
    }

    private boolean isAddressInWhiteList() {
        String to = txEntity.getTo();
        String encryptedAddress = ByteFormatter.bytes2hex(
                new KeyStoreUtil().encrypt(to.getBytes(StandardCharsets.UTF_8)));
        return viewModel.isAddressInWhiteList(encryptedAddress);
    }

    @Override
    protected void initData(Bundle savedInstanceState) {

    }

}



