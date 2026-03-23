# Ashigaru GUI — Whirlpool Coinjoin Flow Plan

## Current state

- 4 account tabs (Deposit, Premix, Postmix, Badbank) — tabs exist and are functional
- UTXO table with Mixes column visible for Premix/Postmix
- Mix Selected → Tx0 flow for Deposit and Badbank (via `AshigaruTx0Controller`)
- Start/Stop Mixing for Premix/Postmix; Mix To / Mix Selected for Postmix
- Badbank is accessible and shows "Mix Selected UTXOs" (correct — re-enter into smaller pool)
- NO receive address UI in the Ashigaru wallet panel
- NO transaction history view in the Ashigaru wallet panel
- After Tx0 broadcast, a success dialog appears but no auto-navigation to Premix

## What to build (4 features)

---

### Feature 1 — Receive Address

**Goal:** Users can get a deposit address directly from the GUI (Step 1 in the guide).

**Approach:** Simple modal dialog — no QR code complexity needed for MVP. Show the address as
copyable text with a "Next Address" button.

**Files:**
1. `ashigaru-wallet.fxml` — Add `<Button fx:id="receiveBtn" text="⬇  Receive" onAction="#onReceive">` to the wallet header HBox, next to the Refresh button.
2. `AshigaruWalletController.java`:
   - Add `@FXML Button receiveBtn` field
   - In `configureMixButtons()` / `activateAccountForm()`: show `receiveBtn` only when on the Deposit account (master wallet)
   - Add `@FXML private void onReceive()` that calls `AshigaruReceiveController.show(activeAccountForm)`
3. `ashigaru-receive.fxml` (new) — VBox dialog with:
   - Title label "RECEIVE BITCOIN"
   - Address field (copyable Label or TextField, read-only)
   - Derivation path label (e.g. `m/0'/0/0`)
   - "Copy" and "Next Address" buttons
   - Close button
4. `AshigaruReceiveController.java` (new) — loads the FXML, gets the wallet's next fresh receive node via `wallet.getFreshNode(KeyPurpose.RECEIVE)`, formats the address from `wallet.getAddress(node)`.

---

### Feature 2 — Badbank Usability Polish

**Goal:** Make the Badbank tab clearly explain doxxic change and what to do with it.

**Current behavior (already correct):**
- Tab shows UTXO list
- "Mix Selected UTXOs" button appears when UTXOs are selected → opens Tx0 to re-enter a smaller pool
- Mixes column is hidden (correct — Badbank UTXOs are unmixed)

**What to add:**
1. `ashigaru-wallet.fxml` — Add a `Label fx:id="badbankInfoLabel"` info bar below the balance row (hidden by default), shown only for the Badbank tab with text: "Doxxic Change — these UTXOs were not mixed. Select them and use 'Mix Selected UTXOs' to re-enter a smaller pool."
2. `AshigaruWalletController.java` — In `activateAccountForm()`, show/hide the info label based on whether the active account is WHIRLPOOL_BADBANK.

---

### Feature 3 — Coinjoin Flow Polish

**Goal:** After a Tx0 is broadcast, auto-navigate to Premix tab; also improve the success dialog.

**Changes:**
1. `AshigaruWalletController.java` — In `broadcastPremix()` `onSucceeded` handler:
   - After the success dialog, auto-select the Premix tab (`accountTabs.getTabs()` filter by "Premix")
   - The TXID should be shown as copyable text in the success dialog (currently it's just a plain string)

2. `AshigaruWalletController.java` — When Premix tab becomes active and there are UTXOs but mixing is not running, update `startMixBtn` text to "Start Mixing ▶" (already the default) — no change needed beyond the auto-switch.

3. Minor: Fix the misleading comment in `configureMixButtons()` that says "Premix / Postmix / Badbank" for the `isWhirlpoolMixWallet()` branch — Badbank is NOT handled there, it goes through `canWalletMix()`. The comment should be corrected.

---

### Feature 4 — Transaction History

**Goal:** Users can see their transaction history per account (not just UTXOs).

**Approach:** Add a [UTXOs] / [Transactions] toggle above the table. When Transactions is selected, show a transaction table; when UTXOs is selected (default), show the current UTXO table.

**Files:**
1. `ashigaru-wallet.fxml`:
   - Add a `ToggleGroup` with two `ToggleButton` controls ([UTXOs] [Transactions]) in a toolbar row above the UTXO table
   - Add a second `TableView fx:id="txnTable"` (initially `visible="false"`) with columns: Date, TXID, Amount
   - Wrap both tables in a `StackPane` or use `managed` property binding so only one is shown at a time

2. `AshigaruWalletController.java`:
   - Add `@FXML TableView<TxnRow> txnTable`, column fields, and `ObservableList<TxnRow> txnRows`
   - Add `record TxnRow(String date, String txid, String amount, TransactionEntry entry)`
   - Add `refreshTransactionTable()` method that calls `activeAccountForm.getWalletTransactionsEntry()`, iterates children (`TransactionEntry`), and populates `txnRows`
   - Toggle visibility of `utxoTable` vs `txnTable` based on selected toggle button
   - Update `walletHistoryChanged()` to also refresh transactions when txn view is active

---

## Implementation order

1. Feature 3 (coinjoin flow polish) — smallest change, highest impact for the core flow
2. Feature 2 (Badbank info label) — one small UI addition
3. Feature 4 (transaction history) — self-contained, additive only
4. Feature 1 (receive dialog) — new dialog, slightly more involved

## Files to create/modify

| File | Action |
|------|--------|
| `src/main/resources/.../gui/ashigaru-wallet.fxml` | Modify — add receive button, badbank info label, view toggle, txn table |
| `src/main/java/.../gui/AshigaruWalletController.java` | Modify — all new handlers |
| `src/main/resources/.../gui/ashigaru-receive.fxml` | Create |
| `src/main/java/.../gui/AshigaruReceiveController.java` | Create |
