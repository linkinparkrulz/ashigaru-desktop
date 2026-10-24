//! The Bitcoin wallet handed to eigenwallet's taker.
//!
//! eigenwallet funds the BTC lock through the `BitcoinWallet` trait. Ashigaru implements it so the
//! lock is paid straight from Badbank and signed by Ashigaru; no coins ever sit in a wallet of the
//! engine's own. For the first milestone only quotes are fetched, so every funding and chain call
//! is refused - nothing here can move coins yet.

use anyhow::{Result, bail};
use bdk_wallet::Balance;
use bdk_wallet::export::FullyNodedExport;
use bitcoin_wallet::{
    Address, Amount, BitcoinWallet, Network, Psbt, ScriptStatus, Subscription, Txid, Watchable,
    Weight,
};

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
    ) -> Result<Psbt> {
        bail!(NOT_YET)
    }

    async fn send_to_address_dynamic_fee(
        &self,
        _address: Address,
        _amount: Amount,
        _change_override: Option<Address>,
    ) -> Result<Psbt> {
        bail!(NOT_YET)
    }

    async fn sweep_balance_to_address_dynamic_fee(&self, _address: Address) -> Result<Psbt> {
        bail!(NOT_YET)
    }

    async fn sign_and_finalize(&self, _psbt: Psbt) -> Result<bitcoin::Transaction> {
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

    async fn max_giveable(&self, _locking_script_size: usize) -> Result<(Amount, Amount)> {
        Ok((Amount::ZERO, Amount::ZERO))
    }

    async fn estimate_fee(
        &self,
        _weight: Weight,
        _transfer_amount: Option<Amount>,
    ) -> Result<Amount> {
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
