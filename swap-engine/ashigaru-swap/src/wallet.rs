//! The Bitcoin wallet handed to eigenwallet's taker.
//!
//! eigenwallet funds the BTC lock through the `BitcoinWallet` trait. Ashigaru implements it so the
//! lock is paid straight from Badbank and signed by Ashigaru; no coins ever sit in a wallet of the
//! engine's own. For the first milestone only quotes are fetched, so every funding and chain call
//! is refused by `QuoteOnlyWallet`; swaps use `HybridWallet`.

use std::sync::Arc;
use std::time::Duration;

use anyhow::{Context, Result, bail};
use bdk_wallet::Balance;
use bdk_wallet::export::FullyNodedExport;
use bitcoin::FeeRate;
use bitcoin::secp256k1::{PublicKey, ecdsa::Signature};
use bitcoin_wallet::{
    Address, Amount, BitcoinWallet, Network, Psbt, ScriptStatus, Subscription, Txid, Watchable,
    Weight,
};
use serde_json::{Value, json};
use utxo_ownership_proof::{Challenge, OwnershipProof};

use crate::bridge;

pub struct QuoteOnlyWallet;

const NOT_YET: &str = "Swapping is not enabled in this build; only quotes can be fetched";

#[async_trait::async_trait]
impl BitcoinWallet for QuoteOnlyWallet {
    async fn balance(&self) -> Result<Amount> {
        Ok(Amount::ZERO)
    }

    async fn balance_info(&self) -> Result<Balance> {
        Ok(Balance::default())
    }

    async fn new_address(&self) -> Result<Address> {
        bail!(NOT_YET)
    }

    async fn send_to_address(
        &self,
        _address: Address,
        _amount: Amount,
        _spending_fee: Amount,
        _change_override: Option<Address>,
        _max_inputs: Option<usize>,
    ) -> Result<Psbt> {
        bail!(NOT_YET)
    }

    async fn send_to_address_dynamic_fee(
        &self,
        _address: Address,
        _amount: Amount,
        _change_override: Option<Address>,
        _max_inputs: Option<usize>,
    ) -> Result<Psbt> {
        bail!(NOT_YET)
    }

    async fn sweep_balance_to_address_dynamic_fee(&self, _address: Address) -> Result<Psbt> {
        bail!(NOT_YET)
    }

    async fn sign_and_finalize(&self, _psbt: Psbt) -> Result<bitcoin::Transaction> {
        bail!(NOT_YET)
    }

    fn prove_utxo_ownership(&self, _psbt: &Psbt, _challenge: Challenge) -> Result<Vec<OwnershipProof>> {
        bail!(NOT_YET)
    }

    async fn ensure_broadcasted(
        &self,
        _transaction: bitcoin::Transaction,
        _kind: &str,
    ) -> Result<(Txid, Subscription)> {
        bail!(NOT_YET)
    }

    async fn sync(&self) -> Result<()> {
        Ok(())
    }

    async fn health_check(&self) -> Result<()> {
        Ok(())
    }

    async fn subscribe_to(&self, tx: Box<dyn Watchable>) -> Subscription {
        // Never updated: no transaction is ever published by this wallet
        let (_sender, receiver) = tokio::sync::watch::channel(ScriptStatus::Unseen);
        Subscription::new(receiver, self.finality_confirmations(), tx.id())
    }

    async fn status_of_script(&self, _tx: &dyn Watchable) -> Result<ScriptStatus> {
        Ok(ScriptStatus::Unseen)
    }

    async fn get_raw_transaction(
        &self,
        _txid: Txid,
    ) -> Result<Option<std::sync::Arc<bitcoin::Transaction>>> {
        Ok(None)
    }

    async fn max_giveable(
        &self,
        _locking_script_size: usize,
        _max_inputs: Option<usize>,
    ) -> Result<(Amount, Amount)> {
        Ok((Amount::ZERO, Amount::ZERO))
    }

    async fn estimate_fee(
        &self,
        _weight: Weight,
        _transfer_amount: Option<Amount>,
    ) -> Result<Amount> {
        bail!(NOT_YET)
    }

    async fn swap_fee_rate(&self) -> Result<FeeRate> {
        bail!(NOT_YET)
    }

    fn network(&self) -> Network {
        Network::Bitcoin
    }

    fn finality_confirmations(&self) -> u32 {
        1
    }

    async fn wallet_export(&self, _role: &str) -> Result<FullyNodedExport> {
        bail!(NOT_YET)
    }
}

/// The wallet used for real swaps. Coins come from Ashigaru's Badbank account and every signature
/// is Ashigaru's: the engine asks for an unsigned lock transaction paying the swap's shared output,
/// then for its signature, and never holds a key or a coin itself. Watching and broadcasting
/// transactions goes through eigenwallet's Electrum client, connected over Ashigaru's Tor.
pub struct HybridWallet {
    client: Arc<bitcoin_wallet::Client>,
    finality_confirmations: u32,
}

/// Ashigaru answers within this time or the request fails. Signing waits for the user to unlock
/// the wallet, so it gets longer.
const QUICK: Duration = Duration::from_secs(60);
const SIGNING: Duration = Duration::from_secs(15 * 60);

impl HybridWallet {
    pub fn new(client: Arc<bitcoin_wallet::Client>, finality_confirmations: u32) -> Self {
        Self {
            client,
            finality_confirmations,
        }
    }

    /// Ashigaru's own fee rate rather than eigenwallet's estimator, which also asks mempool.space
    async fn fee_rates(&self) -> Result<(FeeRate, FeeRate)> {
        let rates = bridge::ask("fee_rate", json!({}), QUICK).await?;
        let rate = FeeRate::from_sat_per_vb(rates["satPerVb"].as_u64().context("No fee rate")?)
            .context("Fee rate out of range")?;
        let min_relay = FeeRate::from_sat_per_vb(rates["minRelaySatPerVb"].as_u64().unwrap_or(1))
            .context("Minimum relay fee out of range")?;
        Ok((rate, min_relay))
    }

    async fn badbank(&self) -> Result<Value> {
        bridge::ask("wallet_info", json!({}), QUICK).await
    }

    async fn fund(
        &self,
        address: Address,
        amount: Amount,
        fee: Option<Amount>,
        change: Option<Address>,
        max_inputs: Option<usize>,
    ) -> Result<Psbt> {
        let answer = bridge::ask(
            "fund_lock",
            json!({
                "address": address.to_string(),
                "amountSats": amount.to_sat(),
                "feeSats": fee.map(Amount::to_sat),
                "changeAddress": change.map(|a| a.to_string()),
                "maxInputs": max_inputs,
            }),
            QUICK,
        )
        .await?;
        let bytes = hex::decode(answer["psbtHex"].as_str().context("No PSBT returned")?)?;
        let psbt = Psbt::deserialize(&bytes).context("Ashigaru returned an unreadable PSBT")?;
        check_lock_psbt(&psbt, &address, amount, max_inputs)?;
        Ok(psbt)
    }
}

/// `{"proofs": [{"publicKey": <33 byte hex>, "signature": <64 byte compact hex>}, ...]}`, one per input
fn parse_proofs(answer: &Value) -> Result<Vec<OwnershipProof>> {
    answer["proofs"]
        .as_array()
        .context("No proofs returned")?
        .iter()
        .map(|proof| {
            let key = hex::decode(proof["publicKey"].as_str().context("Proof without a public key")?)?;
            let signature = hex::decode(proof["signature"].as_str().context("Proof without a signature")?)?;
            Ok(OwnershipProof {
                public_key: PublicKey::from_slice(&key).context("Unreadable proof public key")?,
                signature: Signature::from_compact(&signature).context("Unreadable proof signature")?,
            })
        })
        .collect()
}

/// The maker checks the lock transaction before taking part (`TxLock::from_psbt`): at most two
/// outputs, exactly one of them paying the agreed amount to the shared output, and (from eigenwallet
/// 5) every input a P2WPKH output the taker can prove it owns, at most `max_inputs` of them.
/// Checking here as well means a mistake on Ashigaru's side is caught before anything is signed.
pub fn check_lock_psbt(
    psbt: &Psbt,
    lock_address: &Address,
    amount: Amount,
    max_inputs: Option<usize>,
) -> Result<()> {
    let inputs = psbt.unsigned_tx.input.len();
    if inputs == 0 {
        bail!("Lock transaction has no inputs");
    }
    if let Some(max) = max_inputs {
        if inputs > max {
            bail!("Lock transaction has {inputs} inputs; at most {max} are allowed");
        }
    }
    if psbt.inputs.len() != inputs {
        bail!("Lock PSBT has {} input maps for {inputs} inputs", psbt.inputs.len());
    }
    for (index, input) in psbt.inputs.iter().enumerate() {
        let Some(utxo) = &input.witness_utxo else {
            bail!("Lock input {index} does not say which output it spends");
        };
        if !utxo.script_pubkey.is_p2wpkh() {
            bail!("Lock input {index} is not a native segwit (P2WPKH) output, which the maker cannot accept");
        }
    }
    let outputs = &psbt.unsigned_tx.output;
    if outputs.len() > 2 {
        bail!("Lock transaction has {} outputs; at most 2 are allowed", outputs.len());
    }
    let lock_script = lock_address.script_pubkey();
    let lock_outputs: Vec<_> = outputs.iter().filter(|o| o.script_pubkey == lock_script).collect();
    match lock_outputs.as_slice() {
        [lock] if lock.value == amount => Ok(()),
        [lock] => bail!("Lock output pays {} instead of {}", lock.value, amount),
        _ => bail!("Lock transaction must pay the lock address exactly once"),
    }
}

#[async_trait::async_trait]
impl BitcoinWallet for HybridWallet {
    async fn balance(&self) -> Result<Amount> {
        let info = self.badbank().await?;
        Ok(Amount::from_sat(info["balanceSats"].as_u64().context("No balance")?))
    }

    async fn balance_info(&self) -> Result<Balance> {
        Ok(Balance {
            confirmed: self.balance().await?,
            ..Default::default()
        })
    }

    async fn new_address(&self) -> Result<Address> {
        let info = self.badbank().await?;
        let address: Address<bitcoin::address::NetworkUnchecked> = info["freshAddress"]
            .as_str()
            .context("No address")?
            .parse()?;
        Ok(address.require_network(Network::Bitcoin)?)
    }

    async fn send_to_address(
        &self,
        address: Address,
        amount: Amount,
        spending_fee: Amount,
        change_override: Option<Address>,
        max_inputs: Option<usize>,
    ) -> Result<Psbt> {
        self.fund(address, amount, Some(spending_fee), change_override, max_inputs).await
    }

    async fn send_to_address_dynamic_fee(
        &self,
        address: Address,
        amount: Amount,
        change_override: Option<Address>,
        max_inputs: Option<usize>,
    ) -> Result<Psbt> {
        self.fund(address, amount, None, change_override, max_inputs).await
    }

    async fn sweep_balance_to_address_dynamic_fee(&self, _address: Address) -> Result<Psbt> {
        bail!("Sweeping Badbank is not something the swap engine may do")
    }

    async fn sign_and_finalize(&self, psbt: Psbt) -> Result<bitcoin::Transaction> {
        let answer = bridge::ask("sign", json!({ "psbtHex": hex::encode(psbt.serialize()) }), SIGNING).await?;
        let bytes = hex::decode(answer["txHex"].as_str().context("No transaction returned")?)?;
        let tx: bitcoin::Transaction = bitcoin::consensus::deserialize(&bytes)
            .context("Ashigaru returned an unreadable transaction")?;
        if tx.compute_txid() != psbt.unsigned_tx.compute_txid() {
            bail!("The signed transaction is not the one that was asked for");
        }
        Ok(tx)
    }

    /// Ashigaru signs the challenge with the key behind each input, in the same wallet unlock that
    /// started the swap. The engine calls this synchronously in the middle of the swap setup, with the
    /// maker waiting, so it blocks this worker thread (the runtime is multi-threaded) until the answer
    /// is back. Proofs are checked here as well, so a mistake on Ashigaru's side is an error the user
    /// can read and not a maker rejecting the setup.
    fn prove_utxo_ownership(&self, psbt: &Psbt, challenge: Challenge) -> Result<Vec<OwnershipProof>> {
        let request = json!({
            "psbtHex": hex::encode(psbt.serialize()),
            "challengeHex": hex::encode(challenge.as_bytes()),
        });
        let handle = tokio::runtime::Handle::try_current().context("Ownership proofs need the engine's runtime")?;
        let answer = tokio::task::block_in_place(|| handle.block_on(bridge::ask("prove", request, QUICK)))?;
        let proofs = parse_proofs(&answer)?;
        utxo_ownership_proof::verify_psbt(psbt, &proofs, challenge)
            .context("Ashigaru's ownership proofs for the lock inputs do not verify")?;
        Ok(proofs)
    }

    async fn ensure_broadcasted(
        &self,
        transaction: bitcoin::Transaction,
        kind: &str,
    ) -> Result<(Txid, Subscription)> {
        let txid = transaction.compute_txid();
        let status = self.client.status_of_script(&transaction, true).await?;
        if !matches!(status, ScriptStatus::InMempool | ScriptStatus::Confirmed(_)) {
            let results = self.client.transaction_broadcast_all(&transaction).await?;
            if !results.iter().any(|r| r.is_ok()) {
                bail!("Bitcoin {kind} transaction {txid} was rejected by every Electrum server");
            }
            tracing::info!(%txid, %kind, "Published Bitcoin transaction");
        }
        let subscription = self.client.subscribe_to(Box::new(transaction), self.finality_confirmations).await;
        Ok((txid, subscription))
    }

    async fn sync(&self) -> Result<()> {
        self.client.update_state(false).await
    }

    async fn health_check(&self) -> Result<()> {
        self.client.update_block_height().await
    }

    async fn subscribe_to(&self, tx: Box<dyn Watchable>) -> Subscription {
        self.client.subscribe_to(tx, self.finality_confirmations).await
    }

    async fn status_of_script(&self, tx: &dyn Watchable) -> Result<ScriptStatus> {
        self.client.status_of_script(tx, true).await
    }

    async fn get_raw_transaction(
        &self,
        txid: Txid,
    ) -> Result<Option<std::sync::Arc<bitcoin::Transaction>>> {
        self.client.get_tx(txid).await
    }

    async fn max_giveable(
        &self,
        locking_script_size: usize,
        max_inputs: Option<usize>,
    ) -> Result<(Amount, Amount)> {
        let answer = bridge::ask(
            "max_giveable",
            json!({ "scriptSize": locking_script_size, "maxInputs": max_inputs }),
            QUICK,
        )
        .await?;
        Ok((
            Amount::from_sat(answer["amountSats"].as_u64().context("No amount")?),
            Amount::from_sat(answer["feeSats"].as_u64().context("No fee")?),
        ))
    }

    async fn estimate_fee(
        &self,
        weight: Weight,
        transfer_amount: Option<Amount>,
    ) -> Result<Amount> {
        let (rate, min_relay) = self.fee_rates().await?;
        bitcoin_wallet::estimate_fee(weight, transfer_amount, rate, min_relay)
    }

    /// The rate the presigned swap transactions are checked against: Ashigaru's own estimate, never
    /// below the relay minimum. The engine accepts the maker's rate within a band around this.
    async fn swap_fee_rate(&self) -> Result<FeeRate> {
        let (rate, min_relay) = self.fee_rates().await?;
        Ok(rate.max(min_relay))
    }

    fn network(&self) -> Network {
        Network::Bitcoin
    }

    fn finality_confirmations(&self) -> u32 {
        self.finality_confirmations
    }

    async fn wallet_export(&self, _role: &str) -> Result<FullyNodedExport> {
        bail!("Badbank is not exported to the swap engine")
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use bitcoin::{OutPoint, ScriptBuf, Sequence, Transaction, TxIn, TxOut, Witness, absolute, transaction};
    use std::str::FromStr;

    fn psbt_paying(outputs: Vec<(ScriptBuf, u64)>) -> Psbt {
        let tx = Transaction {
            version: transaction::Version::TWO,
            lock_time: absolute::LockTime::ZERO,
            input: vec![TxIn {
                previous_output: OutPoint::null(),
                script_sig: ScriptBuf::new(),
                sequence: Sequence::MAX,
                witness: Witness::new(),
            }],
            output: outputs
                .into_iter()
                .map(|(script_pubkey, sats)| TxOut { value: Amount::from_sat(sats), script_pubkey })
                .collect(),
        };
        let mut psbt = Psbt::from_unsigned_tx(tx).unwrap();
        // The coin being spent: a native segwit output, as the maker requires
        psbt.inputs[0].witness_utxo = Some(TxOut { value: Amount::from_sat(100_000), script_pubkey: change_address().script_pubkey() });
        psbt
    }

    fn lock_address() -> Address {
        Address::from_str("bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3")
            .unwrap()
            .require_network(Network::Bitcoin)
            .unwrap()
    }

    fn change_address() -> Address {
        Address::from_str("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4")
            .unwrap()
            .require_network(Network::Bitcoin)
            .unwrap()
    }

    #[test]
    fn lock_with_change_is_accepted() {
        let psbt = psbt_paying(vec![
            (lock_address().script_pubkey(), 50_000),
            (change_address().script_pubkey(), 12_345),
        ]);
        check_lock_psbt(&psbt, &lock_address(), Amount::from_sat(50_000), Some(1)).unwrap();
    }

    #[test]
    fn wrong_amount_extra_outputs_or_missing_lock_are_refused() {
        let lock = lock_address().script_pubkey();
        let change = change_address().script_pubkey();
        let amount = Amount::from_sat(50_000);

        let wrong_amount = psbt_paying(vec![(lock.clone(), 49_999)]);
        assert!(check_lock_psbt(&wrong_amount, &lock_address(), amount, Some(1)).is_err());

        let three_outputs = psbt_paying(vec![(lock.clone(), 50_000), (change.clone(), 1_000), (change.clone(), 1_000)]);
        assert!(check_lock_psbt(&three_outputs, &lock_address(), amount, Some(1)).is_err());

        let paid_twice = psbt_paying(vec![(lock.clone(), 50_000), (lock, 50_000)]);
        assert!(check_lock_psbt(&paid_twice, &lock_address(), amount, Some(1)).is_err());

        let no_lock = psbt_paying(vec![(change, 50_000)]);
        assert!(check_lock_psbt(&no_lock, &lock_address(), amount, Some(1)).is_err());
    }

    #[test]
    fn inputs_the_maker_cannot_take_are_refused() {
        let amount = Amount::from_sat(50_000);
        let lock = lock_address().script_pubkey();

        // Not a P2WPKH output (this is a P2WSH script): the ownership proof cannot be made for it
        let mut not_p2wpkh = psbt_paying(vec![(lock.clone(), 50_000)]);
        not_p2wpkh.inputs[0].witness_utxo = Some(TxOut { value: Amount::from_sat(100_000), script_pubkey: lock_address().script_pubkey() });
        assert!(check_lock_psbt(&not_p2wpkh, &lock_address(), amount, Some(1)).is_err());

        // Which output is being spent is unknown
        let mut unknown = psbt_paying(vec![(lock.clone(), 50_000)]);
        unknown.inputs[0].witness_utxo = None;
        assert!(check_lock_psbt(&unknown, &lock_address(), amount, Some(1)).is_err());

        // Over the cap on inputs
        let ok = psbt_paying(vec![(lock, 50_000)]);
        assert!(check_lock_psbt(&ok, &lock_address(), amount, Some(0)).is_err());
        check_lock_psbt(&ok, &lock_address(), amount, None).unwrap();
    }

    #[test]
    fn proofs_are_read_from_ashigarus_answer() {
        let secret = bitcoin::secp256k1::SecretKey::from_slice(&[7; 32]).unwrap();
        let proof = utxo_ownership_proof::sign(&secret, utxo_ownership_proof::challenge(b"test"));
        let answer = json!({ "proofs": [{
            "publicKey": hex::encode(proof.public_key.serialize()),
            "signature": hex::encode(proof.signature.serialize_compact()),
        }]});
        assert_eq!(parse_proofs(&answer).unwrap(), vec![proof]);
        assert!(parse_proofs(&json!({})).is_err());
        assert!(parse_proofs(&json!({ "proofs": [{ "publicKey": "00", "signature": "00" }] })).is_err());
    }
}
