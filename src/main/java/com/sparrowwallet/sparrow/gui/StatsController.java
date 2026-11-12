package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.StandardAccount;
import com.sparrowwallet.sparrow.wallet.WalletForm;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.io.Config;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public class StatsController implements Initializable {
    private static final Logger log = LoggerFactory.getLogger(StatsController.class);

    // whirlpoolstats serves both clearnet and an onion mirror; prefer the onion when a Tor
    // proxy is active (clearnet HTTPS over Tor is unreliable in packaged builds, whereas
    // HTTP-to-onion over the proxy is the same path the avatar/fee-rate calls use).
    private static final String ONION_BASE = "http://25jmfjjzm24mjslbub27xnqdbf3dy2oafc7ydawdkz56nlb252hpenyd.onion/api";
    private static final String CLEARNET_BASE = "https://whirlpoolstats.xyz/api";
    private static final String SOURCE_URL = "https://whirlpoolstats.xyz";

    private static String apiBase() {
        return AppServices.getProxy() != null ? ONION_BASE : CLEARNET_BASE;
    }

    @FXML private Button refreshBtn;
    @FXML private Label statusLabel;
    @FXML private Label totalBtcLabel;
    @FXML private VBox totalCard;
    @FXML private VBox yourPoolCard;
    @FXML private Label yourPoolTitle;
    @FXML private Label yourPoolValue;
    @FXML private Label yourPoolSub;
    @FXML private Label totalsSubLabel;
    @FXML private Label blockRangeLabel;
    @FXML private Label syncLabel;
    @FXML private Label lastUpdatedLabel;
    @FXML private Label nextUpdateLabel;
    @FXML private Label pool025SizeBadge;
    @FXML private Label pool025EnteredLabel;
    @FXML private Label pool025CyclesLabel;
    @FXML private Label pool025Tx0CountLabel;
    @FXML private Label pool25SizeBadge;
    @FXML private Label pool25EnteredLabel;
    @FXML private Label pool25CyclesLabel;
    @FXML private Label pool25Tx0CountLabel;
    @FXML private Label chartTitleLabel;
    @FXML private ToggleGroup chartMetricGroup;
    @FXML private ToggleButton capacityToggle;
    @FXML private ToggleButton enteredToggle;
    @FXML private ToggleButton utxosToggle;
    @FXML private LineChart<Number, Number> poolChart;
    @FXML private NumberAxis xAxis;
    @FXML private NumberAxis yAxis;
    @FXML private Hyperlink sourceLink;
    @FXML private ListView<TxRow> txListView;

    private StatsData currentData;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        poolChart.setCreateSymbols(false);
        poolChart.setAnimated(false);
        xAxis.setForceZeroInRange(false);
        xAxis.setTickUnit(10000);
        xAxis.setAutoRanging(true);
        yAxis.setAutoRanging(true);

        capacityToggle.setUserData("Capacity");
        enteredToggle.setUserData("Entered");
        utxosToggle.setUserData("UTXOs");
        chartMetricGroup.selectedToggleProperty().addListener((obs, old, neu) -> {
            if(neu == null) {
                // never allow an empty selection
                if(old != null) old.setSelected(true);
                return;
            }
            renderSelectedChart();
        });

        txListView.setCellFactory(lv -> new TxCell());
        fetchStats();
    }

    @FXML
    private void onRefresh() {
        fetchStats();
    }

    @FXML
    private void onOpenSource() {
        openUrl(SOURCE_URL);
    }

    private static void openUrl(String url) {
        try {
            AppServices.openExternalLink(url);
        } catch(Exception e) {
            log.warn("Could not open browser", e);
        }
    }

    private void fetchStats() {
        statusLabel.setVisible(true);
        statusLabel.setManaged(true);
        statusLabel.setText("Fetching statistics from whirlpoolstats.xyz…");
        refreshBtn.setDisable(true);

        String base = apiBase();
        Task<StatsData> task = new Task<>() {
            @Override
            protected StatsData call() throws Exception {
                String summaryJson = httpGet(base + "/summary");
                String chartsJson = httpGet(base + "/charts");
                String txsJson = httpGet(base + "/txs");
                return parseStats(summaryJson, chartsJson, txsJson);
            }
        };

        task.setOnSucceeded(e -> Platform.runLater(() -> {
            currentData = task.getValue();
            renderStats(currentData);
            statusLabel.setVisible(false);
            statusLabel.setManaged(false);
            refreshBtn.setDisable(false);
        }));

        task.setOnFailed(e -> Platform.runLater(() -> {
            Throwable ex = task.getException();
            log.error("Failed to fetch whirlpool stats", ex);
            statusLabel.setText("Could not fetch stats: " + (ex == null ? "unknown error" : ex.getMessage()));
            refreshBtn.setDisable(false);
        }));

        Thread t = new Thread(task, "whirlpool-stats-fetch");
        t.setDaemon(true);
        t.start();
    }

    private void renderStats(StatsData data) {
        totalBtcLabel.setText(String.format(Locale.ROOT, "%.3f BTC", data.totalBtc));
        totalsSubLabel.setText(String.format(Locale.ROOT, "%,d cycles  ·  %,d Tx0s", data.totalCycles, data.totalTx0));

        blockRangeLabel.setText(String.format(Locale.ROOT, "%,d – %,d", data.startBlock, data.tipBlock));
        syncLabel.setText(data.synced
                ? String.format(Locale.ROOT, "Synced (%.0f%%)", data.progressPct)
                : String.format(Locale.ROOT, "Syncing… %.0f%%", data.progressPct));

        if(data.lastUpdatedTs > 0) {
            lastUpdatedLabel.setText(new SimpleDateFormat("HH:mm 'UTC', MMM d")
                    .format(new Date(data.lastUpdatedTs * 1000)));
        } else {
            lastUpdatedLabel.setText("—");
        }
        nextUpdateLabel.setText(data.nextUpdateSeconds > 0
                ? "Next update ~" + formatDuration(data.nextUpdateSeconds)
                : "—");

        renderPoolCard(data, "0.025_BTC_Pool", pool025SizeBadge, pool025EnteredLabel, pool025CyclesLabel, pool025Tx0CountLabel);
        renderPoolCard(data, "0.25_BTC_Pool", pool25SizeBadge, pool25EnteredLabel, pool25CyclesLabel, pool25Tx0CountLabel);

        renderYourPool(data);
        renderSelectedChart();
        txListView.getItems().setAll(data.recentTxs);
    }

    /**
     * Leads with the crowd the open wallets' own coins sit in: how many coins are unspent in that
     * pool and how many of them are yours. A mix count means little without the crowd behind it.
     * Both numbers already exist, one in the stats feed and one in the wallet.
     */
    private void renderYourPool(StatsData data) {
        long small = 0, large = 0;
        for(WalletForm master : AshigaruGui.get().getWalletForms().values()) {
            for(WalletForm nested : master.getNestedWalletForms()) {
                if(nested.getWallet().getStandardAccountType() != StandardAccount.WHIRLPOOL_POSTMIX) {
                    continue;
                }
                for(BlockTransactionHashIndex utxo : nested.getWallet().getWalletUtxos().keySet()) {
                    if(utxo.getValue() == 2_500_000L) small++;
                    else if(utxo.getValue() == 25_000_000L) large++;
                }
            }
        }
        boolean has = small + large > 0;
        yourPoolCard.setVisible(has);
        yourPoolCard.setManaged(has);
        totalCard.getStyleClass().remove("primary");
        if(!has) {
            totalCard.getStyleClass().add("primary");
            return;
        }
        boolean smallPool = small >= large;
        long yours = smallPool ? small : large;
        String poolName = smallPool ? "0.025_BTC_Pool" : "0.25_BTC_Pool";
        PoolSummary pool = data.pools.stream().filter(ps -> ps.poolName().equals(poolName)).findFirst().orElse(null);
        yourPoolTitle.setText("YOUR POOL \u00b7 " + (smallPool ? "0.025" : "0.25") + " BTC");
        yourPoolValue.setText(pool == null ? "\u2014" : String.format(Locale.ROOT, "%,d coins unspent", pool.unspentUtxos()));
        yourPoolSub.setText(String.format(Locale.ROOT, "%,d of them %s yours", yours, yours == 1 ? "is" : "are")
                + (pool == null ? "" : String.format(Locale.ROOT, "  \u00b7  %,d cycles so far", pool.cycles())));
    }

    private void renderPoolCard(StatsData data, String poolName, Label sizeBadge, Label enteredLabel,
                                Label cyclesLabel, Label tx0Label) {
        PoolSummary p = data.pools.stream().filter(ps -> ps.poolName().equals(poolName)).findFirst().orElse(null);
        if(p == null) {
            return;
        }
        sizeBadge.setText(String.format(Locale.ROOT, "%.3f BTC", p.unspentBtc()));
        enteredLabel.setText(String.format(Locale.ROOT, "Total entered: %.3f BTC", p.enteredBtc()));
        cyclesLabel.setText(String.format(Locale.ROOT, "Cycles: %,d  ·  UTXOs: %,d", p.cycles(), p.unspentUtxos()));
        tx0Label.setText(String.format(Locale.ROOT, "Tx0 count: %,d  ·  Avg fee efficiency: %.1f%%", p.tx0Count(), p.avgFeeEfficiencyPct()));
    }

    private void renderSelectedChart() {
        if(currentData == null) {
            return;
        }
        Toggle selected = chartMetricGroup.getSelectedToggle();
        String key = selected != null && selected.getUserData() != null ? selected.getUserData().toString() : "Capacity";
        renderChart(currentData, key);
    }

    private void renderChart(StatsData data, String metricKey) {
        poolChart.getData().clear();
        ChartMetric metric = data.metrics.get(metricKey);
        if(metric == null || metric.blocks.isEmpty()) {
            return;
        }

        chartTitleLabel.setText(metric.title);
        yAxis.setLabel(metric.yLabel);

        // Series colours (orange / yellow per pool) are applied by index via the
        // .stats-chart CSS rules, so both the lines and legend symbols stay in sync.
        for(Map.Entry<String, List<Double>> entry : metric.series.entrySet()) {
            XYChart.Series<Number, Number> series = new XYChart.Series<>();
            series.setName(entry.getKey().replace("_BTC_Pool", " BTC Pool"));
            List<Double> values = entry.getValue();
            for(int i = 0; i < metric.blocks.size() && i < values.size(); i++) {
                series.getData().add(new XYChart.Data<>(metric.blocks.get(i), values.get(i)));
            }
            poolChart.getData().add(series);
        }
    }

    private static String formatDuration(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        if(h > 0) {
            return h + "h " + m + "m";
        }
        return Math.max(1, m) + "m";
    }

    private static String httpGet(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        Proxy proxy = AppServices.getProxy();
        HttpURLConnection conn = (HttpURLConnection) (proxy != null
                ? url.openConnection(proxy)
                : url.openConnection());
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "Ashigaru-Desktop");
        conn.setRequestProperty("Accept", "application/json");

        int code = conn.getResponseCode();
        if(code != 200) {
            throw new IOException("HTTP " + code + " for " + urlStr);
        }

        StringBuilder sb = new StringBuilder();
        try(BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static StatsData parseStats(String summaryJson, String chartsJson, String txsJson) {
        StatsData data = new StatsData();
        JsonObject summary = JsonParser.parseString(summaryJson).getAsJsonObject();

        data.startBlock = summary.get("start_block_height").getAsInt();
        data.tipBlock = summary.get("tip_height").getAsInt();
        data.lastUpdatedTs = summary.has("last_report_refresh_ts")
                ? summary.get("last_report_refresh_ts").getAsLong() : 0;
        data.synced = summary.has("is_synced") && summary.get("is_synced").getAsBoolean();
        data.progressPct = summary.has("progress_pct") ? summary.get("progress_pct").getAsDouble() : 0;
        data.nextUpdateSeconds = summary.has("next_update_seconds") ? summary.get("next_update_seconds").getAsLong() : 0;

        double total = 0;
        JsonArray poolsArr = summary.getAsJsonArray("pools");
        for(JsonElement el : poolsArr) {
            JsonObject pObj = el.getAsJsonObject();
            PoolSummary pool = new PoolSummary(
                    pObj.get("label").getAsString(),
                    pObj.get("pool").getAsString(),
                    pObj.get("unspent_btc").getAsDouble(),
                    pObj.get("entered_btc").getAsDouble(),
                    pObj.get("cycles").getAsInt(),
                    pObj.has("tx0_count") ? pObj.get("tx0_count").getAsInt() : 0,
                    pObj.has("unspent_utxos") ? pObj.get("unspent_utxos").getAsInt() : 0,
                    pObj.has("avg_fee_efficiency_pct") ? pObj.get("avg_fee_efficiency_pct").getAsDouble() : 0,
                    pObj.has("color") ? pObj.get("color").getAsString() : "#8e8e93"
            );
            data.pools.add(pool);
            total += pool.unspentBtc();
            data.totalCycles += pool.cycles();
            data.totalTx0 += pool.tx0Count();
        }
        data.totalBtc = total;

        // Chart metrics
        JsonObject charts = JsonParser.parseString(chartsJson).getAsJsonObject();
        data.metrics.put("Capacity", parsePerPoolMetric(charts, "capacity", "Pool Capacity Over Block Height", "Pool Size (BTC)"));
        data.metrics.put("Entered", parsePerPoolMetric(charts, "entered", "Cumulative BTC Entered", "Entered (BTC)"));
        data.metrics.put("UTXOs", parseTotalMetric(charts, "utxos", "Unspent UTXOs Over Block Height", "UTXOs", "Unspent UTXOs"));

        // Recent txs
        if(txsJson != null && !txsJson.isEmpty()) {
            JsonObject txsObj = JsonParser.parseString(txsJson).getAsJsonObject();
            if(txsObj.has("items")) {
                for(JsonElement el : txsObj.getAsJsonArray("items")) {
                    JsonObject tx = el.getAsJsonObject();
                    String txid = tx.get("txid").getAsString();
                    int blockHeight = tx.get("block_height").getAsInt();
                    String poolLabel = tx.has("pool_label") ? tx.get("pool_label").getAsString() : "—";
                    String poolColor = tx.has("pool_color") ? tx.get("pool_color").getAsString() : "#8e8e93";
                    if(!poolColor.matches("#[0-9a-fA-F]{6}")) {
                        //Inserted into a JavaFX style, so anything other than a plain colour could inject other CSS properties
                        poolColor = "#8e8e93";
                    }

                    List<TxInput> inputs = new ArrayList<>();
                    if(tx.has("tx0_inputs")) {
                        for(JsonElement in : tx.getAsJsonArray("tx0_inputs")) {
                            JsonObject inObj = in.getAsJsonObject();
                            inputs.add(new TxInput(
                                    inObj.get("txid").getAsString(),
                                    inObj.has("fee_efficiency_pct") ? inObj.get("fee_efficiency_pct").getAsString() : "—"
                            ));
                        }
                    }
                    String amIExposed = tx.has("am_i_exposed_url") ? tx.get("am_i_exposed_url").getAsString() : null;
                    data.recentTxs.add(new TxRow(txid, blockHeight, poolLabel, poolColor, inputs, amIExposed));
                }
            }
        }

        return data;
    }

    private static ChartMetric parsePerPoolMetric(JsonObject charts, String key, String title, String yLabel) {
        ChartMetric metric = new ChartMetric(title, yLabel);
        if(!charts.has(key)) {
            return metric;
        }
        JsonObject obj = charts.getAsJsonObject(key);
        for(JsonElement b : obj.getAsJsonArray("blocks")) {
            metric.blocks.add(b.getAsInt());
        }
        if(obj.has("series")) {
            for(Map.Entry<String, JsonElement> entry : obj.getAsJsonObject("series").entrySet()) {
                List<Double> values = new ArrayList<>();
                for(JsonElement v : entry.getValue().getAsJsonArray()) {
                    values.add(v.getAsDouble());
                }
                metric.series.put(entry.getKey(), values);
            }
        }
        return metric;
    }

    private static ChartMetric parseTotalMetric(JsonObject charts, String key, String title, String yLabel, String seriesName) {
        ChartMetric metric = new ChartMetric(title, yLabel);
        if(!charts.has(key)) {
            return metric;
        }
        JsonObject obj = charts.getAsJsonObject(key);
        for(JsonElement b : obj.getAsJsonArray("blocks")) {
            metric.blocks.add(b.getAsInt());
        }
        if(obj.has("total_utxos")) {
            List<Double> values = new ArrayList<>();
            for(JsonElement v : obj.getAsJsonArray("total_utxos")) {
                values.add(v.getAsDouble());
            }
            metric.series.put(seriesName, values);
        }
        return metric;
    }

    // -------------------------------------------------------------------------
    // Data models
    // -------------------------------------------------------------------------

    private static class StatsData {
        double totalBtc;
        int totalCycles;
        int totalTx0;
        int startBlock;
        int tipBlock;
        long lastUpdatedTs;
        boolean synced;
        double progressPct;
        long nextUpdateSeconds;
        List<PoolSummary> pools = new ArrayList<>();
        Map<String, ChartMetric> metrics = new LinkedHashMap<>();
        List<TxRow> recentTxs = new ArrayList<>();
    }

    private static class ChartMetric {
        final String title;
        final String yLabel;
        final List<Integer> blocks = new ArrayList<>();
        final Map<String, List<Double>> series = new LinkedHashMap<>();

        ChartMetric(String title, String yLabel) {
            this.title = title;
            this.yLabel = yLabel;
        }
    }

    private record PoolSummary(String label, String poolName, double unspentBtc, double enteredBtc,
                               int cycles, int tx0Count, int unspentUtxos, double avgFeeEfficiencyPct,
                               String color) {}

    private record TxInput(String txid, String feeEfficiencyPct) {}

    private record TxRow(String txid, int blockHeight, String poolLabel, String poolColor,
                         List<TxInput> inputs, String amIExposedUrl) {}

    // -------------------------------------------------------------------------
    // Transaction cell
    // -------------------------------------------------------------------------

    private static class TxCell extends ListCell<TxRow> {
        private final Label poolBadge = new Label();
        private final Label blockLabel = new Label();
        private final Label txidLabel = new Label();
        private final Label feeLabel = new Label();
        private final Hyperlink explorerLink = new Hyperlink("explorer ↗");
        private final Hyperlink exposureLink = new Hyperlink("exposure ↗");
        private final HBox topLine = new HBox(8);
        private final HBox midLine = new HBox(8);
        private final VBox root = new VBox(6, topLine, midLine, feeLabel);

        TxCell() {
            blockLabel.getStyleClass().add("card-faint");
            txidLabel.getStyleClass().add("card-secondary");
            feeLabel.getStyleClass().add("card-faint");
            explorerLink.getStyleClass().add("card-link");
            exposureLink.getStyleClass().add("card-link");
            topLine.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            midLine.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            root.setFillWidth(true);
        }

        @Override
        protected void updateItem(TxRow row, boolean empty) {
            super.updateItem(row, empty);
            if(empty || row == null) {
                setText(null);
                setGraphic(null);
                return;
            }

            // Row 1: pool badge (left) — block height (right)
            poolBadge.setText(row.poolLabel());
            //The feed's own colour is not used: pills take the same two token colours as the chart.
            poolBadge.getStyleClass().removeAll("pool-pill", "second");
            poolBadge.getStyleClass().add("pool-pill");
            if(row.poolLabel() != null && row.poolLabel().startsWith("0.25")) {
                poolBadge.getStyleClass().add("second");
            }
            blockLabel.setText("Block " + String.format(Locale.ROOT, "%,d", row.blockHeight()));
            Region topSpacer = new Region();
            HBox.setHgrow(topSpacer, Priority.ALWAYS);
            topLine.getChildren().setAll(poolBadge, topSpacer, blockLabel);

            // Row 2: txid + copy (left) — explorer / exposure links (right)
            String shortTxid = row.txid().length() > 22
                    ? row.txid().substring(0, 12) + "…" + row.txid().substring(row.txid().length() - 8)
                    : row.txid();
            txidLabel.setText(shortTxid);
            Region midSpacer = new Region();
            HBox.setHgrow(midSpacer, Priority.ALWAYS);
            midLine.getChildren().setAll(txidLabel, AshigaruWalletController.makeCopyButton(row.txid()), midSpacer);
            if(!Config.get().isBlockExplorerDisabled()) {
                explorerLink.setOnAction(e -> AppServices.openBlockExplorer(row.txid()));
                midLine.getChildren().add(explorerLink);
            }
            if(!Config.get().isAmIExposedDisabled()) {
                exposureLink.setOnAction(e -> AppServices.openAmIExposed(row.txid()));
                midLine.getChildren().add(exposureLink);
            }

            // Row 3: Tx0 inputs + fee efficiency
            if(!row.inputs().isEmpty()) {
                String fees = row.inputs().stream()
                        .map(i -> i.feeEfficiencyPct() + "%")
                        .reduce((a, b) -> a + " · " + b)
                        .orElse("—");
                feeLabel.setText(row.inputs().size() + " Tx0 input" + (row.inputs().size() == 1 ? "" : "s")
                        + "  ·  fee efficiency: " + fees);
                feeLabel.setVisible(true);
                feeLabel.setManaged(true);
            } else {
                feeLabel.setVisible(false);
                feeLabel.setManaged(false);
            }

            setText(null);
            setGraphic(root);
        }
    }
}
