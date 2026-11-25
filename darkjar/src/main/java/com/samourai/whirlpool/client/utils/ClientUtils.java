package com.samourai.whirlpool.client.utils;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.Files;
import com.samourai.wallet.api.backend.beans.HttpException;
import com.samourai.wallet.api.backend.beans.UnspentOutput;
import com.samourai.wallet.client.indexHandler.IIndexHandler;
import com.samourai.wallet.hd.AddressType;
import com.samourai.wallet.segwit.bech32.Bech32UtilGeneric;
import com.samourai.wallet.util.CallbackWithArg;
import com.samourai.wallet.util.FeeUtil;
import com.samourai.wallet.util.FormatsUtilGeneric;
import com.samourai.wallet.util.RandomUtil;
import com.samourai.whirlpool.client.exception.NotifiableException;
import com.samourai.whirlpool.client.wallet.beans.IndexRange;
import com.samourai.whirlpool.client.wallet.beans.WhirlpoolUtxo;
import com.samourai.whirlpool.client.wallet.beans.WhirlpoolUtxoState;
import com.samourai.whirlpool.protocol.WhirlpoolProtocol;
import com.samourai.whirlpool.protocol.beans.Utxo;
import com.samourai.whirlpool.protocol.rest.RestErrorResponse;
import io.reactivex.Observable;
import io.reactivex.subjects.BehaviorSubject;
import io.reactivex.subjects.Subject;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.*;

import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.bitcoinj.core.*;
import org.bouncycastle.crypto.params.RSAKeyParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientUtils {
    private static final Logger log = LoggerFactory.getLogger(ClientUtils.class);
    private static final SecureRandom secureRandom = new SecureRandom();

    private static final int SLEEP_REFRESH_UTXOS_TESTNET = 15000;
    private static final int SLEEP_REFRESH_UTXOS_MAINNET = 5000;
    public static final String USER_AGENT = "whirlpool-client/" + WhirlpoolProtocol.PROTOCOL_VERSION;

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final FeeUtil feeUtil = FeeUtil.getInstance();
    private static final Bech32UtilGeneric bech32Util = Bech32UtilGeneric.getInstance();

    public static void setupEnv() {
        // prevent user-agent tracking
        System.setProperty("http.agent", USER_AGENT);
    }

    public static Integer findTxOutputIndex(
            String outputAddressBech32, Transaction tx, NetworkParameters params) {
        try {
            byte[] expectedScriptBytes =
                    Bech32UtilGeneric.getInstance().computeScriptPubKey(outputAddressBech32, params);
            for (TransactionOutput output : tx.getOutputs()) {
                if (Arrays.equals(output.getScriptBytes(), expectedScriptBytes)) {
                    return output.getIndex();
                }
            }
        } catch (Exception e) {
            log.error("findTxOutput failed", e);
        }
        return null;
    }

    public static String[] witnessSerialize64(TransactionWitness witness) {
        String[] serialized = new String[witness.getPushCount()];
        for (int i = 0; i < witness.getPushCount(); i++) {
            serialized[i] = WhirlpoolProtocol.encodeBytes(witness.getPush(i));
        }
        return serialized;
    }

    public static RSAKeyParameters publicKeyUnserialize(byte[] publicKeySerialized) throws Exception {
        RSAPublicKey rsaPublicKey =
                (RSAPublicKey)
                        KeyFactory.getInstance("RSA")
                                .generatePublic(new X509EncodedKeySpec(publicKeySerialized));
        return new RSAKeyParameters(false, rsaPublicKey.getModulus(), rsaPublicKey.getPublicExponent());
    }

    public static String toJsonString(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            log.error("", e);
        }
        return null;
    }

    public static <T> T fromJson(String json, Class<T> type) throws Exception {
        return objectMapper.readValue(json, type);
    }

    public static Logger prefixLogger(Logger log, String logPrefix) {
        Level level = ((ch.qos.logback.classic.Logger) log).getEffectiveLevel();
        Logger newLog = LoggerFactory.getLogger(log.getName() + "[" + logPrefix + "]");
        ((ch.qos.logback.classic.Logger) newLog).setLevel(level);
        return newLog;
    }

    private static String parseRestErrorMessage(String responseBody) {
        try {
            RestErrorResponse restErrorResponse =
                    ClientUtils.fromJson(responseBody, RestErrorResponse.class);
            return restErrorResponse.message;
        } catch (Exception e) {
            log.error(
                    "parseRestErrorMessage failed: responseBody="
                            + (responseBody != null ? responseBody : "null"));
            return null;
        }
    }

    public static String getHttpResponseBody(Throwable e) {
        if (!(e instanceof HttpException) && e.getCause() != null) {
            e = e.getCause(); // blockingGet from CLI wraps errors in RuntimeException
        }
        if (e instanceof HttpException) {
            return ((HttpException) e).getResponseBody();
        }
        return null;
    }

    public static String parseRestErrorMessage(Throwable e) {
        String responseBody = getHttpResponseBody(e);
        if (responseBody == null) {
            return null;
        }
        return parseRestErrorMessage(responseBody);
    }

    public static Exception wrapRestError(Exception e) {
        String restErrorResponseMessage = ClientUtils.parseRestErrorMessage(e);
        if (restErrorResponseMessage != null) {
            return new NotifiableException(restErrorResponseMessage);
        }
        return e;
    }

    public static Throwable wrapRestError(Throwable e) {
        String restErrorResponseMessage = ClientUtils.parseRestErrorMessage(e);
        if (restErrorResponseMessage != null) {
            return new NotifiableException(restErrorResponseMessage);
        }
        return e;
    }

    public static int computeNextReceiveAddressIndex(
            IIndexHandler postmixIndexHandler, IndexRange indexRange) {
        // full range
        if (indexRange == IndexRange.FULL) {
            return postmixIndexHandler.getAndIncrementUnconfirmed();
        }

        int modulo = indexRange == IndexRange.ODD ? 1 : 0;
        int index;
        do {
            index = postmixIndexHandler.getAndIncrementUnconfirmed();
        } while (index % 2 != modulo);
        return index;
    }

    public static void logUtxos(
            Collection<UnspentOutput> utxos, int purpose, int accountIndex, NetworkParameters params) {
        String lineFormat = "| %10s | %7s | %68s | %45s | %18s |\n";
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(lineFormat, "BALANCE", "CONFIRM", "UTXO", "ADDRESS", "TYPE", "PATH"));
        sb.append(String.format(lineFormat, "(btc)", "", "", "", "", ""));
        for (UnspentOutput o : utxos) {
            String utxo = o.tx_hash + ":" + o.tx_output_n;
            sb.append(
                    String.format(
                            lineFormat,
                            satToBtc(o.value),
                            o.confirmations,
                            utxo,
                            o.addr,
                            AddressType.findByAddress(o.addr, params),
                            o.getPathFull(purpose, accountIndex)));
        }
        log.info("\n" + sb.toString());
    }

    public static void logWhirlpoolUtxos(Collection<WhirlpoolUtxo> utxos, int latestBlockHeight) {
        String lineFormat = "| %10s | %7s | %68s | %45s | %13s | %27s | %14s | %8s | %8s | %4s |\n";
        StringBuilder sb = new StringBuilder();
        sb.append(
                String.format(
                        lineFormat,
                        "BALANCE",
                        "CONFIRM",
                        "UTXO",
                        "ADDRESS",
                        "TYPE",
                        "PATH",
                        "STATUS",
                        "MIXABLE",
                        "POOL",
                        "MIXS"));
        sb.append(String.format(lineFormat, "(btc)", "", "", "", "", "", "", "", "", ""));
        Iterator var3 = utxos.iterator();

        while (var3.hasNext()) {
            WhirlpoolUtxo whirlpoolUtxo = (WhirlpoolUtxo) var3.next();
            WhirlpoolUtxoState utxoState = whirlpoolUtxo.getUtxoState();
            UnspentOutput o = whirlpoolUtxo.getUtxo();
            String utxo = o.tx_hash + ":" + o.tx_output_n;
            String mixableStatusName =
                    utxoState.getMixableStatus() != null ? utxoState.getMixableStatus().name() : "-";
            sb.append(
                    String.format(
                            lineFormat,
                            ClientUtils.satToBtc(o.value),
                            whirlpoolUtxo.computeConfirmations(latestBlockHeight),
                            utxo,
                            o.addr,
                            whirlpoolUtxo.getAddressType(),
                            whirlpoolUtxo.getPathFull(),
                            utxoState.getStatus().name(),
                            mixableStatusName,
                            whirlpoolUtxo.getUtxoState().getPoolId() != null
                                    ? whirlpoolUtxo.getUtxoState().getPoolId()
                                    : "-",
                            whirlpoolUtxo.getMixsDone()));
        }
        log.info("\n" + sb.toString());
    }

    public static double satToBtc(long sat) {
        return sat / 100000000.0;
    }

    public static String utxoToKey(UnspentOutput unspentOutput) {
        return unspentOutput.tx_hash + ':' + unspentOutput.tx_output_n;
    }

    public static String utxoToKey(Utxo utxo) {
        return utxo.getHash() + ':' + utxo.getIndex();
    }

    public static String utxoToKey(String utxoHash, int utxoIndex) {
        return utxoHash + ':' + utxoIndex;
    }

    public static String getTxHex(Transaction tx) {
        String txHex = org.bitcoinj.core.Utils.HEX.encode(tx.bitcoinSerialize());
        return txHex;
    }

    public static Observable<Optional<Void>> sleepUtxosDelay(final NetworkParameters params) {
        return sleepUtxosDelay(params, null);
    }

    public static Observable<Optional<Void>> sleepUtxosDelay(
            final NetworkParameters params, final Runnable runnable) {
        final Subject<Optional<Void>> observable = BehaviorSubject.create();
        // delayed refresh utxos
        new Thread(
                new Runnable() {
                    @Override
                    public void run() {
                        // wait for delay
                        boolean isTestnet = FormatsUtilGeneric.getInstance().isTestNet(params);
                        int sleepDelay =
                                isTestnet ? SLEEP_REFRESH_UTXOS_TESTNET : SLEEP_REFRESH_UTXOS_MAINNET;
                        try {
                            Thread.sleep(sleepDelay);
                        } catch (InterruptedException e) {
                        }

                        // run callback
                        if (runnable != null) {
                            runnable.run();
                        }

                        // notify
                        observable.onNext(Optional.<Void>empty());
                        observable.onComplete();
                    }
                },
                "refreshUtxos")
                .start();
        return observable;
    }

    public static String sha256Hash(String str) {
        return sha256Hash(str.getBytes());
    }

    public static String sha256Hash(byte[] bytes) {
        return Sha256Hash.wrap(Sha256Hash.hash(bytes)).toString();
    }

    public static String maskString(String value) {
        return maskString(value, 3);
    }

    private static String maskString(String value, int startEnd) {
        if (value == null) {
            return "null";
        }
        if (value.length() <= startEnd) {
            return value;
        }
        return value.substring(0, Math.min(startEnd, value.length()))
                + "..."
                + value.substring(Math.max(0, value.length() - startEnd), value.length());
    }

    public static void safeWrite(File file, CallbackWithArg<File> callback) throws Exception {
        if (!file.exists()) {
            file.createNewFile();
        }
        FileLock fileLock = lockFile(file);

        File tempFile = null;
        try {

            try {
                // write to temp file (in same directory)
                tempFile = new File(file.getParent(), file.getName() + ".tmp");
                callback.apply(tempFile);
            } finally {
                // unlock before rename
                unlockFile(fileLock);
            }

            // rename
            Files.move(tempFile, file);
        } catch (Exception e) {
            log.error(
                    "safeWrite failed for "
                            + (tempFile != null ? tempFile.getAbsolutePath() : "null")
                            + " ->"
                            + file.getAbsolutePath());
            throw e;
        }
    }

    public static void safeWriteValue(final ObjectMapper mapper, final Object value, final File file)
            throws Exception {
        CallbackWithArg<File> callback =
                new CallbackWithArg<File>() {
                    @Override
                    public void apply(File tempFile) throws Exception {
                        mapper.writeValue(tempFile, value);
                    }
                };
        safeWrite(file, callback);
    }

    public static FileLock lockFile(File f) throws Exception {
        return lockFile(
                f,
                "Cannot lock file "
                        + f.getAbsolutePath()
                        + ". Make sure no other Whirlpool instance is running in same directory.");
    }

    public static FileLock lockFile(File f, String errorMsg) throws Exception {
        FileChannel channel = new RandomAccessFile(f, "rw").getChannel();
        FileLock fileLock = channel.tryLock(); // exclusive lock
        if (fileLock == null) {
            throw new NotifiableException(errorMsg);
        }
        return fileLock; // success
    }

    public static void unlockFile(FileLock fileLock) throws Exception {
        fileLock.release();
        fileLock.channel().close();
    }

    public static void setLogLevel(Level mainLevel, Level subLevel) {
        LogbackUtils.setLogLevel("com.samourai", mainLevel.toString());

        LogbackUtils.setLogLevel("com.samourai.whirlpool.client", subLevel.toString());
        LogbackUtils.setLogLevel("com.samourai.stomp.client", subLevel.toString());
        LogbackUtils.setLogLevel("com.samourai.wallet.util.FeeUtil", subLevel.toString());

        LogbackUtils.setLogLevel("com.samourai.whirlpool.client.wallet", mainLevel.toString());
        LogbackUtils.setLogLevel(
                "com.samourai.whirlpool.client.wallet.orchestrator", mainLevel.toString());

        // skip noisy logs
        LogbackUtils.setLogLevel("org.bitcoinj", org.slf4j.event.Level.ERROR.toString());
        LogbackUtils.setLogLevel(
                "org.bitcoin", org.slf4j.event.Level.WARN.toString()); // "no wallycore"
    }

    public static long computeTx0MinerFee(
            int nbPremix,
            long feeTx0,
            Collection<? extends UnspentOutput> spendFroms,
            NetworkParameters params) {
        int nbOutputsNonOpReturn = nbPremix + 2; // outputs + change + fee

        int nbP2PKH = 0;
        int nbP2SH = 0;
        int nbP2WPKH = 0;
        if (spendFroms != null) { // spendFroms can be NULL (for fee simulation)
            for (UnspentOutput uo : spendFroms) {

                if (bech32Util.isP2WPKHScript(uo.script)) {
                    nbP2WPKH++;
                } else {
                    String address = uo.computeScript().getToAddress(params).toString();
                    if (Address.fromBase58(params, address).isP2SHAddress()) {
                        nbP2SH++;
                    } else {
                        nbP2PKH++;
                    }
                }
            }
        }
        long tx0MinerFee =
                feeUtil.estimatedFeeSegwit(nbP2PKH, nbP2SH, nbP2WPKH, nbOutputsNonOpReturn, 1, feeTx0);

        if (log.isTraceEnabled()) {
            log.trace(
                    "tx0 minerFee: "
                            + tx0MinerFee
                            + "sats, totalBytes="
                            + "b for nbPremix="
                            + nbPremix
                            + ", feeTx0="
                            + feeTx0);
        }
        return tx0MinerFee;
    }

    public static long computeTx0SpendValue(
            long premixValue, int nbPremix, long feeValueOrFeeChange, long tx0MinerFee) {
        long spendValue = (premixValue * nbPremix) + feeValueOrFeeChange + tx0MinerFee;
        return spendValue;
    }

    public static void createFile(File f) throws NotifiableException {
        if (!f.exists()) {
            if (log.isDebugEnabled()) {
                log.debug("Creating file " + f.getAbsolutePath());
            }
            try {
                f.createNewFile();
            } catch (Exception e) {
                throw new NotifiableException("Unable to write file " + f.getAbsolutePath());
            }
        }
    }

    public static File createFile(String fileName) throws NotifiableException {
        File f = new File(fileName); // use current directory
        ClientUtils.createFile(f);
        return f;
    }

    public static <T> Collection<T> filterByAssignableType(Collection items, Class<T> type) {
        List<T> list = new LinkedList<T>();
        for (Object item : items) {
            if (item.getClass().isAssignableFrom(type)) {
                list.add((T) item);
            }
        }
        return list;
    }

    public static Collection<Integer> getOutputIndexs(Collection<TransactionOutput> outputs) {
        return (outputs).stream()
                .map(
                        new Function<TransactionOutput, Integer>() {
                            @Override
                            public Integer apply(TransactionOutput output) {
                                return output.getIndex();
                            }
                        })
                .collect(Collectors.<Integer>toList());
    }

    public static <B> Collection<B> intersect(Collection<B> a1, Collection<B> a2) {
        Set<B> s1 = new HashSet<B>(a1);
        Set<B> s2 = new HashSet<B>(a2);
        s1.retainAll(s2);
        return s1;
    }

    public static String dateToString(long timestamp) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(timestamp);
    }

    public static long bytesToMB(long bytes) {
        return Math.round(bytes / (1024L * 1024L));
    }

    public static byte[] generateBordereau() {
        return RandomUtil.getInstance().nextBytes(30);
    }
}
