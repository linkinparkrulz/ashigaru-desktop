package com.samourai.wallet.util;

import com.samourai.wallet.bip47.rpc.PaymentCode;
import com.samourai.wallet.segwit.bech32.Bech32;
import com.samourai.wallet.segwit.bech32.Bech32Segwit;
import org.apache.commons.lang3.tuple.Pair;
import org.bitcoinj.core.*;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.uri.BitcoinURI;
import org.bitcoinj.uri.BitcoinURIParseException;
import org.bouncycastle.util.Arrays;
import org.bouncycastle.util.encoders.Base64;
import org.bouncycastle.util.encoders.Hex;

import java.nio.ByteBuffer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FormatsUtilGeneric {

	public static final String URI_BECH32 = "(^bitcoin:(tb|bc)1([qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)(\\?amount\\=([0-9.]+))?(?s).*)|(^bitcoin:(TB|BC)1([QPZRY9X8GF2TVDW0S3JN54KHCE6MUA7L]+)(\\?amount\\=([0-9.]+))?(?s).*)";
	public static final String URI_BECH32_LOWER = "^bitcoin:((tb|bc)1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)(\\?amount\\=([0-9.]+))?(?s).*";

	public static final String HEX_REGEX = "^[0-9A-Fa-f]+$";
	public static final String BASE64_REGEX = "^[0-9A-Za-z\\\\+=/]+$";

	public static final int MAGIC_XPUB = 0x0488B21E;
	public static final int MAGIC_TPUB = 0x043587CF;
	public static final int MAGIC_YPUB = 0x049D7CB2;
	public static final int MAGIC_UPUB = 0x044A5262;
	public static final int MAGIC_ZPUB = 0x04B24746;
	public static final int MAGIC_VPUB = 0x045F1CF6;

	public static final int MAGIC_XPRV = 0x0488ADE4;
	public static final int MAGIC_TPRV = 0x04358394;
	public static final int MAGIC_YPRV = 0x049D7878;
	public static final int MAGIC_UPRV = 0x044A4E28;
	public static final int MAGIC_ZPRV = 0x04B2430C;
	public static final int MAGIC_VPRV = 0x045F18BC;

	public static final String XPUB = "^[xtyu]pub[1-9A-Za-z][^OIl]+$";
	public static final String HEX = "^[0-9A-Fa-f]+$";

	private static FormatsUtilGeneric instance = null;

	public static FormatsUtilGeneric getInstance() {

		if(instance == null) {
			instance = new FormatsUtilGeneric();
		}

		return instance;
	}

	public String validateBitcoinAddress(final String address, final NetworkParameters params) {

		if(isValidBitcoinAddress(address, params)) {
			return address;
		}
		else {
			String addr = getBitcoinAddress(address);
			if(addr != null) {
				return addr;
			}
			else {
				return null;
			}
		}
	}

	public boolean isBitcoinUri(final String s) {

		boolean ret = false;
		BitcoinURI uri = null;

		try {
			uri = new BitcoinURI(s);
			ret = true;
		}
		catch(BitcoinURIParseException bupe) {
			if(s.matches(URI_BECH32))	{
				ret = true;
			}
			else	{
				ret = false;
			}
		}

		return ret;
	}

	public String getBitcoinUri(final String s) {

		String ret = null;
		BitcoinURI uri = null;

		try {
			uri = new BitcoinURI(s);
			ret = uri.toString();
		}
		catch(BitcoinURIParseException bupe) {
			if(s.matches(URI_BECH32))	{
				return s;
			}
			else	{
				ret = null;
			}
		}

		return ret;
	}

	public String getBitcoinAddress(final String s) {

		String ret = null;
		BitcoinURI uri = null;

		try {
			uri = new BitcoinURI(s);
			ret = uri.getAddress().toString();
		}
		catch(BitcoinURIParseException bupe) {
			if(s.toLowerCase().matches(URI_BECH32_LOWER))	{
				Pattern pattern = Pattern.compile(URI_BECH32_LOWER);
				Matcher matcher = pattern.matcher(s.toLowerCase());
				if(matcher.find() && matcher.group(1) != null)    {
					return matcher.group(1);
				}
			}
			else	{
				ret = null;
			}
		}

		return ret;
	}

	public String getBitcoinAmount(final String s) {

		String ret = null;
		BitcoinURI uri = null;

		try {
			uri = new BitcoinURI(s);
			if(uri.getAmount() != null) {
				ret = uri.getAmount().toString();
			}
			else {
				ret = "0.0000";
			}
		}
		catch(BitcoinURIParseException bupe) {
			if(s.toLowerCase().matches(URI_BECH32_LOWER))	{
				Pattern pattern = Pattern.compile(URI_BECH32_LOWER);
				Matcher matcher = pattern.matcher(s.toLowerCase());
				if(matcher.find() && matcher.group(3) != null)    {

					String amt = null;
					int idx = matcher.group(3).indexOf("=");
					if(idx != -1 && idx < matcher.group(3).length())    {
						amt = matcher.group(3).substring(idx + 1);
					}

					if(amt != null)	{
						try	{
							return Long.toString(Math.round(Double.valueOf(amt) * 1e8));
						}
						catch(NumberFormatException nfe)	{
							ret = "0.0000";
						}
					}
					else	{
						ret = "0.0000";
					}

				}
			}
			else	{
				ret = null;
			}
		}

		return ret;
	}

	public boolean isTestNet(NetworkParameters params) {
		return params != null && !(params instanceof MainNetParams);
	}

	public boolean isValidBitcoinAddress(final String address, NetworkParameters params) {

		boolean ret = false;
		Address addr = null;
		boolean isTestNet = isTestNet(params);

		if((!isTestNet && address.toLowerCase().startsWith("bc")) ||
				(isTestNet && address.toLowerCase().startsWith("tb")))	{

			try	{
				Pair<Byte, byte[]> pair = Bech32Segwit.decode(address.substring(0, 2), address);
				if(pair.getLeft() == null || pair.getRight() == null)	{
					;
				}
				else	{
					ret = true;
				}
			}
			catch(Exception e)	{
				e.printStackTrace();
			}

		}
		else	{

			try {
				addr = new Address(params, address);
				if(addr != null) {
					ret = true;
				}
			}
			catch(WrongNetworkException wne) {
				ret = false;
			}
			catch(AddressFormatException afe) {
				ret = false;
			}

		}

		return ret;
	}

	public boolean isValidBech32(final String address) {

		boolean ret = false;

		try	{
			Triple<String, byte[], Integer> triple0 = Bech32.bech32Decode(address);
			if(triple0.getLeft() == null || triple0.getMiddle() == null || triple0.getRight() == null)	{
				ret = false;
			}
			else	{
				Pair<Byte, byte[]> pair1 = Bech32Segwit.decode(address.substring(0, 2), address);
				if(pair1.getLeft() == null || pair1.getRight() == null)	{
					ret = false;
				}
				else	{
					ret = true;
				}
			}
		}
		catch(Exception e)	{
			ret = false;
		}

		return ret;
	}

	public boolean isValidXpub(String xpub){
		return isValidXpub(xpub, MAGIC_XPUB, MAGIC_TPUB, MAGIC_YPUB, MAGIC_UPUB, MAGIC_ZPUB, MAGIC_VPUB);
	}

	public boolean isValidXpubOrZpub(String xpub, NetworkParameters params){
		int[] magic = isTestNet(params) ? new int[]{MAGIC_VPUB,MAGIC_TPUB} : new int[]{MAGIC_ZPUB,MAGIC_XPUB};
		return isValidXpub(xpub, magic);
	}

	private boolean isValidXpub(String xpub, int... versions){

		try {
			byte[] xpubBytes = Base58.decodeChecked(xpub);

			if(xpubBytes.length != 78)	{
				return false;
			}

			ByteBuffer byteBuffer = ByteBuffer.wrap(xpubBytes);
			int version = byteBuffer.getInt();
			if (!Arrays.contains(versions, version)) {
				throw new AddressFormatException("invalid version: " + xpub);
			}
			else	{

				byte[] chain = new byte[32];
				byte[] pub = new byte[33];
				// depth:
				byteBuffer.get();
				// parent fingerprint:
				byteBuffer.getInt();
				// child no.
				byteBuffer.getInt();
				byteBuffer.get(chain);
				byteBuffer.get(pub);

				ByteBuffer pubBytes = ByteBuffer.wrap(pub);
				int firstByte = pubBytes.get();
				if(firstByte == 0x02 || firstByte == 0x03){
					return true;
				}else{
					return false;
				}
			}
		}
		catch(Exception e)	{
			return false;
		}
	}

	public boolean isValidXprv(String xprv){

		try {
			byte[] xprvBytes = Base58.decodeChecked(xprv);

			if(xprvBytes.length != 78)	{
				return false;
			}

			ByteBuffer byteBuffer = ByteBuffer.wrap(xprvBytes);
			int version = byteBuffer.getInt();
			if(version != MAGIC_XPRV && version != MAGIC_TPRV && version != MAGIC_YPRV && version != MAGIC_UPRV && version != MAGIC_ZPRV && version != MAGIC_VPRV)   {
				throw new AddressFormatException("invalid version: " + xprv);
			}
			else	{

				return true;

			}
		}
		catch(Exception e)	{
			return false;
		}
	}

	public String xlatXpub(String xpub, boolean isBIP84) throws AddressFormatException {

		byte[] xpubBytes = Base58.decodeChecked(xpub);

		ByteBuffer bb = ByteBuffer.wrap(xpubBytes);
		int ver = bb.getInt();
		if(ver != MAGIC_XPUB && ver != MAGIC_TPUB && ver != MAGIC_YPUB && ver != MAGIC_UPUB && ver != MAGIC_ZPUB && ver != MAGIC_VPUB)   {
			throw new AddressFormatException("invalid xpub version");
		}

		int xlatVer = 0;
		switch(ver)    {
			case MAGIC_XPUB:
				xlatVer = isBIP84 ? MAGIC_ZPUB : MAGIC_YPUB;
				break;
			case MAGIC_YPUB:
				xlatVer = MAGIC_XPUB;
				break;
			case MAGIC_TPUB:
				xlatVer = isBIP84 ? MAGIC_VPUB : MAGIC_UPUB;
				break;
			case MAGIC_UPUB:
				xlatVer = MAGIC_TPUB;
				break;
			case MAGIC_ZPUB:
				xlatVer = MAGIC_XPUB;
				break;
			case MAGIC_VPUB:
				xlatVer = MAGIC_TPUB;
				break;
		}

		ByteBuffer b = ByteBuffer.allocate(4);
		b.putInt(xlatVer);
		byte[] bVer = b.array();

		System.arraycopy(bVer, 0, xpubBytes, 0, bVer.length);

		// append checksum
		byte[] checksum = java.util.Arrays.copyOfRange(Sha256Hash.hashTwice(xpubBytes), 0, 4);
		byte[] xlatXpub = new byte[xpubBytes.length + checksum.length];
		System.arraycopy(xpubBytes, 0, xlatXpub, 0, xpubBytes.length);
		System.arraycopy(checksum, 0, xlatXpub, xlatXpub.length - 4, checksum.length);

		String ret = Base58.encode(xlatXpub);

		return ret;
	}

	public boolean isValidPaymentCode(String pcode){

		try {
			PaymentCode paymentCode = new PaymentCode(pcode);
			return paymentCode.isValid();
		}
		catch(Exception e)	{
			return false;
		}
	}

	public boolean isValidBIP47OpReturn(String op_return){

		byte[] buf = Hex.decode(op_return);

		if(buf.length == 80 && buf[0] == 0x01 && buf[1] == 0x00 && (buf[2] == 0x02 || buf[2] == 0x03))    {
			return true;
		}
		else    {
			return false;
		}

	}

	public DeterministicKey createMasterPubKeyFromXPub(String xpubstr) throws AddressFormatException {

		if(!isValidXpub(xpubstr))	{
			return null;
		}

		byte[] xpubBytes = Base58.decodeChecked(xpubstr);

		ByteBuffer bb = ByteBuffer.wrap(xpubBytes);

		// magic value
		bb.getInt();

		byte[] chain = new byte[32];
		byte[] pub = new byte[33];
		// depth:
		bb.get();
		// parent fingerprint:
		bb.getInt();
		// child no.
		bb.getInt();
		bb.get(chain);
		bb.get(pub);

		return HDKeyDerivation.createMasterPubKeyFromBytes(pub, chain);
	}

	public byte[] getFingerprintFromXPUB(String xpubstr) throws AddressFormatException {

		if(!isValidXpub(xpubstr))	{
			return null;
		}

		byte[] xpubBytes = Base58.decodeChecked(xpubstr);
		// parent fingerprint:
		byte[] fingerprint = new byte[4];
		System.arraycopy(xpubBytes, 5, fingerprint, 0, fingerprint.length);

		return fingerprint;
	}

	public boolean isHex(String s)   {

		if(s.matches(HEX_REGEX))    {
			return true;
		}
		else    {
			return false;
		}

	}

	public boolean isBase64(String s)   {

		if(s.matches(BASE64_REGEX))    {
			return true;
		}
		else    {
			return false;
		}

	}

	public boolean isPSBT(String s)   {
		final String psbtMagic = "70736274ff";
		if(isHex(s) && s.startsWith(psbtMagic))    {
			return true;
		}
		else if(isBase64(s) && Hex.toHexString(Base64.decode(s)).startsWith(psbtMagic))    {
			return true;
		}
		else if(Z85.getInstance().isZ85(s) && Hex.toHexString(Z85.getInstance().decode(s)).startsWith(psbtMagic))    {
			return true;
		}
		else    {
			return false;
		}

	}

}
