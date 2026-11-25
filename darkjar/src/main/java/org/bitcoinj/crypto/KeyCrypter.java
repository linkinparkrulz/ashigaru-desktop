package org.bitcoinj.crypto;

import org.bouncycastle.crypto.params.KeyParameter;

import java.io.Serializable;

public interface KeyCrypter extends Serializable {
    KeyParameter deriveKey(CharSequence password) throws KeyCrypterException;

    byte[] decrypt(EncryptedData encryptedBytesToDecode, KeyParameter aesKey) throws KeyCrypterException;

    EncryptedData encrypt(byte[] plainBytes, KeyParameter aesKey) throws KeyCrypterException;
}
