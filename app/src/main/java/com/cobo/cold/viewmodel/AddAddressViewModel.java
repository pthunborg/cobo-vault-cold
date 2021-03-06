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

package com.cobo.cold.viewmodel;

import android.app.Application;
import android.os.AsyncTask;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.databinding.ObservableField;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.cobo.coinlib.coins.AbsDeriver;
import com.cobo.coinlib.utils.Coins;
import com.cobo.cold.DataRepository;
import com.cobo.cold.MainApplication;
import com.cobo.cold.callables.GetExtendedPublicKeyCallable;
import com.cobo.cold.db.entity.AccountEntity;
import com.cobo.cold.db.entity.AddressEntity;
import com.cobo.cold.db.entity.CoinEntity;

import java.util.ArrayList;
import java.util.List;

public class AddAddressViewModel extends AndroidViewModel {

    private final DataRepository mRepo;
    public CoinEntity coin;
    private final ObservableField<Boolean> loading = new ObservableField<>();
    private final MutableLiveData<Boolean> addComplete = new MutableLiveData<>();

    private AddAddressViewModel(@NonNull Application application, DataRepository repository,
                                final long id) {
        super(application);
        mRepo = repository;
    }

    public ObservableField<Boolean> getLoading() {
        return loading;
    }

    public void addAddress(List<String> addrs) {
        loading.set(true);
        new AddAddressTask(coin, mRepo, () -> {
            loading.set(false);
            addComplete.setValue(Boolean.TRUE);
        }).execute(addrs.toArray(new String[0]));
    }

    public void addAddress(CoinEntity coinEntity, DataRepository repo, String addrName) {
        new AddAddressTask(coinEntity, repo, null).execute(addrName);
    }

    public CoinEntity getCoin(String coinId) {
        coin = mRepo.loadCoinSync(coinId);
        return coin;
    }

    public LiveData<Boolean> getObservableAddState() {
        return addComplete;
    }

    public static class Factory extends ViewModelProvider.NewInstanceFactory {
        @NonNull
        private final Application mApplication;

        private final long mId;

        private final DataRepository mRepository;

        public Factory(@NonNull Application application, long id) {
            mApplication = application;
            mId = id;
            mRepository = ((MainApplication) application).getRepository();
        }

        @NonNull
        @Override
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            //noinspection unchecked
            return (T) new AddAddressViewModel(mApplication, mRepository, mId);
        }
    }

    static class AddAddressTask extends AsyncTask<String, Void, Void> {
        private final CoinEntity coinEntity;
        private final DataRepository repo;
        private final Runnable onComplete;

        AddAddressTask(CoinEntity coinEntity, DataRepository repo, Runnable onComplete) {
            this.coinEntity = coinEntity;
            this.repo = repo;
            this.onComplete = onComplete;
        }

        @Override
        protected Void doInBackground(String... strings) {

            AccountEntity defaultAccount = repo.loadAccountsForCoin(coinEntity).get(0);
            String path = defaultAccount.getHdPath();
            int addressCount = coinEntity.getAddressCount();

            String exPub = defaultAccount.getExPub();
            if (TextUtils.isEmpty(exPub)) {
                exPub = new GetExtendedPublicKeyCallable(path).call();
                defaultAccount.setExPub(exPub);
            }

            List<AddressEntity> entities = new ArrayList<>();
            AbsDeriver deriver = AbsDeriver.newInstance(coinEntity.getCoinCode());
            for (int i = 0; i < strings.length; i++) {
                AddressEntity addressEntity = new AddressEntity();
                int change = 0;
                int index = i + addressCount;
                if (Coins.isPolkadotFamily(coinEntity.getCoinCode())) {
                    addressEntity.setPath(defaultAccount.getHdPath());
                } else {
                    addressEntity.setPath(String.format(path + "/%s/%s", change, index));
                }

                if (deriver != null) {
                    String addr = deriver.derive(exPub, change, index);
                    addressEntity.setAddressString(addr);
                    addressEntity.setCoinId(coinEntity.getCoinId());
                    addressEntity.setIndex(i + addressCount);
                    addressEntity.setName(strings[i]);
                    addressEntity.setBelongTo(coinEntity.getBelongTo());
                    entities.add(addressEntity);
                }
            }

            coinEntity.setAddressCount(coinEntity.getAddressCount() + strings.length);
            defaultAccount.setAddressLength(addressCount + strings.length);
            repo.updateAccount(defaultAccount);
            repo.updateCoin(coinEntity);
            repo.insertAddress(entities);
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);
            if (onComplete != null) {
                onComplete.run();
            }
        }

    }
}
