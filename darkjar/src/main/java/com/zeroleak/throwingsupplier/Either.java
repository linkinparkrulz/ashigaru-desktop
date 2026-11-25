package com.zeroleak.throwingsupplier;

import java.util.Optional;

public class Either<T, U> {
  private Optional<T> value;
  private Optional<U> fallback;

  public Either(T value) {
    this(Optional.of(value), Optional.<U>empty());
  }

  public Either(Optional<T> emptyValue, U fallback) {
    this(Optional.<T>empty(), Optional.of(fallback));
  }

  private Either(Optional<T> value, Optional<U> fallback) {
    this.value = value;
    this.fallback = fallback;
  }

  public Optional<T> getValue() {
    return value;
  }

  public Optional<U> getFallback() {
    return fallback;
  }
}
