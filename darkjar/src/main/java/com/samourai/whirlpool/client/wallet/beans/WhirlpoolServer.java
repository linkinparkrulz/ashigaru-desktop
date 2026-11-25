package com.samourai.whirlpool.client.wallet.beans;

import java.util.Optional;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.TestNet3Params;

public enum WhirlpoolServer {
    TESTNET(
            "https://pool.whirl.mx:8081",
            "http://ymzccyqgnk4vyt5fpwszdhslno7ld6g4pwwhgo7i2mfao7fnug476zqd.onion",
            "http://d4zyv3ypcl6vc2fpaxxud5kmfzvb6dzvx2vonuami37jyb2wqctewoad.onion",
            TestNet3Params.get()),
    INTEGRATION(
            "https://pool.whirl.mx:8082",
            "http://yuvewbfkftftcbzn54lfx3i5s4jxr4sfgpsbkvcflgzcvumyxrkopmyd.onion",
            "http://yuvewbfkftftcbzn54lfx3i5s4jxr4sfgpsbkvcflgzcvumyxrkopmyd.onion",
            TestNet3Params.get()),
    MAINNET(
            "https://pool.whirl.mx:8080",
            "http://vtv4xpahijw5sok332gqbbdkqdxshzfwbgb5t2hfcrkalq3laj35udid.onion",
            "http://qzspyqbctti64b3eh5l7ir3n5pemx3und7ggxyg7wueusgnp4hqqwkid.onion",
            MainNetParams.get()),
    LOCAL_TESTNET(
            "clear URL",
            "http://127.0.0.1:8080",
            "http://127.0.0.1:8080",
            TestNet3Params.get());

    private String serverUrlClear;
    private String serverUrlOnion;
    private String serverUrlOutputReg;
    private NetworkParameters params;

    WhirlpoolServer(String serverUrlClear, String serverUrlOnion, String serverUrlOutputReg, NetworkParameters params) {
        this.serverUrlClear = serverUrlClear;
        this.serverUrlOnion = serverUrlOnion;
        this.serverUrlOutputReg = serverUrlOutputReg;
        this.params = params;
    }

    public String getServerUrlClear() {
        return serverUrlClear;
    }

    public String getServerUrlOnion() {
        return serverUrlOnion;
    }

    public String getServerUrlOutputReg() {
        return serverUrlOutputReg;
    }

    public String getServerUrl(boolean onion) {
        String serverUrl = onion ? getServerUrlOnion() : getServerUrlClear();
        return serverUrl;
    }

    public NetworkParameters getParams() {
        return params;
    }

    public static Optional<WhirlpoolServer> find(String value) {
        try {
            return Optional.of(valueOf(value));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
