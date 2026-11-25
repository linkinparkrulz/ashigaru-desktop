package com.samourai.whirlpool.client.wallet.beans;

import java.util.Arrays;

import com.samourai.wallet.hd.AddressType;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public enum WhirlpoolAccount {
  DEPOSIT(0, true, new AddressType[]{AddressType.LEGACY, AddressType.SEGWIT_COMPAT, AddressType.SEGWIT_NATIVE}),
  PREMIX(Integer.MAX_VALUE - 2, true, new AddressType[]{AddressType.SEGWIT_NATIVE}),
  POSTMIX(Integer.MAX_VALUE - 1, true, new AddressType[]{AddressType.SEGWIT_NATIVE}),
  BADBANK(Integer.MAX_VALUE - 3, false, new AddressType[]{AddressType.SEGWIT_NATIVE});

  private int accountIndex;
  private boolean active;
  private AddressType[] addressTypes;

  WhirlpoolAccount(int accountIndex, boolean active, AddressType[] addressTypes) {
    this.accountIndex = accountIndex;
    this.active = active;
    this.addressTypes = addressTypes;
  }

  public int getAccountIndex() {
    return accountIndex;
  }

  public boolean isActive() {
    return active;
  }

  public AddressType[] getAddressTypes() {
    return addressTypes;
  }

  public static Optional<WhirlpoolAccount> find(int index) {
    for (WhirlpoolAccount whirlpoolAccount : values()) {
      if (whirlpoolAccount.getAccountIndex() == index) {
        return Optional.of(whirlpoolAccount);
      }
    }
    return Optional.empty();
  }

  public static WhirlpoolAccount[] getListByActive(final boolean active) {
    return (Arrays.asList(values())).stream()
        .filter(
            new Predicate<WhirlpoolAccount>() {
              @Override
              public boolean test(WhirlpoolAccount account) {
                return account.isActive() == active;
              }
            })
        .collect(Collectors.<WhirlpoolAccount>toList())
        .toArray(new WhirlpoolAccount[] {});
  }
}
