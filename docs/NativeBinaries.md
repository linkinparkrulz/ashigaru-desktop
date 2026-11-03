# Bundled native binaries

The repository commits prebuilt native libraries and executables. None are built from source in this
repository. This file records where each one came from and its SHA-256, so a changed binary is visible
in review: any pull request that touches a file below must update its hash here and say where the new
file came from.

Every file below is byte-identical (same git blob) to the file at the same path in the upstream
project it was inherited from. "Upstream match" names the latest upstream release (Sparrow) or commit
(drongo) that ships the identical file. Upstream does not publish build recipes for these files either,
so this records continuity with upstream, not a reproducible build.

To re-check a file against upstream:

```bash
git hash-object <file>
```

and compare with `git ls-tree <upstream tag> -- <path>` in a clone of
https://github.com/sparrowwallet/sparrow (or https://github.com/sparrowwallet/drongo for `drongo/`).

| File | Component | Upstream match | SHA-256 |
|---|---|---|---|
| `drongo/src/main/resources/native/linux/aarch64/libsecp256k1.so` | libsecp256k1 | sparrowwallet/drongo `13059eb` | `bebbd3129cad960743fcf13877716b04542fe552259dd7403aef5b6a4b7121d5` |
| `drongo/src/main/resources/native/linux/x64/libsecp256k1.so` | libsecp256k1 | sparrowwallet/drongo `13059eb` | `61ca7ca3090552417b946ddc537135c9b18b01cea6f4cae9883b0fb6f501146f` |
| `drongo/src/main/resources/native/osx/aarch64/libsecp256k1.dylib` | libsecp256k1 | sparrowwallet/drongo `13059eb` | `41f7df9a64982c0b6ecb9568e11c32c3cd989690687852f853e53614853b1a86` |
| `drongo/src/main/resources/native/osx/x64/libsecp256k1.dylib` | libsecp256k1 | sparrowwallet/drongo `13059eb` | `9d7538a3eda0823a37d056beeeb45a221d608dafbca1eaee2f60661e89c87cf6` |
| `drongo/src/main/resources/native/windows/x64/libsecp256k1-0.dll` | libsecp256k1 | sparrowwallet/drongo `13059eb` | `440644944b12373a07954eeb032bbe8e3cae34c78aeda7bdc9ed3650f59a657d` |
| `src/main/resources/native/linux/aarch64/hwi` | HWI 2.4.0 | Sparrow 1.8.4 | `408145187bd500882cd89ffd92d63799b484e22ec2883401d6acf052e9463ae3` |
| `src/main/resources/native/linux/aarch64/libbwt_jni.so` | BWT JNI 0.1.8 | Sparrow 2.5.6 | `9075fed80a1002eb770b4500d5ee4a098c52ded4582733490b726c6235d0e3af` |
| `src/main/resources/native/linux/aarch64/libzbar.so` | zbar | Sparrow 1.9.1 | `7410569872b586c662d4ac047b7dcb8c5c1a21e5d609fc4ea94618d405b31be1` |
| `src/main/resources/native/linux/x64/hwi` | HWI 2.4.0 | Sparrow 1.8.4 | `15631dcc3020aa8d8a9b773744b61fd18f08ae862072068b795038d79f5b36b3` |
| `src/main/resources/native/linux/x64/libbwt_jni.so` | BWT JNI 0.1.8 | Sparrow 2.5.6 | `49ea61c9cde78fe7de322cba3b5e0d0b06bbc6c41fb4c40fbc59e986479f0e16` |
| `src/main/resources/native/linux/x64/libzbar.so` | zbar | Sparrow 1.9.1 | `6342cedd3170a88c03b45bba08a0b5a67bac7d4ec47c377ab7ee01fee86158d8` |
| `src/main/resources/native/osx/aarch64/hwi-2.4.0-mac-aarch64-signed.tar.bz2` | HWI 2.4.0 | Sparrow 1.8.4 | `3496e4d9bf2f5dc69a103dd2fd6de31f7fad2c3429a0c5a8c49b39ec0333b10d` |
| `src/main/resources/native/osx/aarch64/libbwt_jni.dylib` | BWT JNI 0.1.8 | Sparrow 2.5.6 | `ebe60c708f3be03aa4f4848993131da3a09dee9e883463ca112846eff5564aab` |
| `src/main/resources/native/osx/aarch64/libzbar.dylib` | zbar | Sparrow 1.9.1 | `2216a1514741f86c151cc64502dc3db3e2a799c13ff90f0d03724ca351070112` |
| `src/main/resources/native/osx/x64/hwi-2.4.0-mac-amd64-signed.tar.bz2` | HWI 2.4.0 | Sparrow 1.8.4 | `b627d9d7feb67190bcb52637669acf0fe7ea6a941fe591271768e4fc9244b553` |
| `src/main/resources/native/osx/x64/libbwt_jni.dylib` | BWT JNI 0.1.8 | Sparrow 2.5.6 | `6e56671dea1faaa379c24c660292c825391045426206404da23d741e2084118c` |
| `src/main/resources/native/osx/x64/libzbar.dylib` | zbar | Sparrow 1.9.1 | `76f90182195827663a8459d46888aa30cfa10eb6745a99a28a4dd83e9b025f51` |
| `src/main/resources/native/windows/x64/bwt_jni.dll` | BWT JNI 0.1.8 | Sparrow 2.5.6 | `3747aaec6a4ecc5cab7fb7ba35fd452b198de748d6765c2562845fd728631478` |
| `src/main/resources/native/windows/x64/hwi.exe` | HWI 2.4.0 | Sparrow 1.8.4 | `2541b1e7ada2640e26ef7ce3491585e1788ad9be68d4ac5e818d38d23a4a9cab` |
| `src/main/resources/native/windows/x64/iconv-2.dll` | libiconv (zbar dependency) | Sparrow 2.2.3 | `c33c49d95e5800f47d34e0da7e32a7ae583f9e3e39b3030c5dc320c950642ff6` |
| `src/main/resources/native/windows/x64/zbar.dll` | zbar | Sparrow 1.9.1 | `2a21cfaafa283d9e75683e14b3cc75fe0f032af8406f6e5b9650df06776e6bc3` |

`iconv-2.dll` is not at this path in Sparrow releases after 2.2.3; check whether the bundled `zbar.dll`
still needs it before the next upgrade.
