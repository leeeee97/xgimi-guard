package local.z7x.adb;

import android.content.Context;
import android.util.Base64;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * Owns this app's ADB RSA identity (2048-bit), persisted in private storage.
 * Generated once on first use; adbd remembers the approved public key, so the
 * on-screen debug dialog only ever appears once per device.
 *
 * The wire format of the public key follows AOSP adb/crypto/android_pubkey.cpp:
 * a packed little-endian struct
 *   uint32 key_bits_words; uint32 n0inv; uint8 n[256] (LE); uint8 rr[256] (LE); uint32 e
 * where n0inv = -1/n[0] mod 2^32 and rr = 2^4096 mod n, then base64-encoded and
 * suffixed with " name\0" — exactly what adbkey.pub contains.
 */
public final class AdbKeyManager {
    private static final String KEY_FILE = "adbkey";
    /** DER DigestInfo prefix for SHA-1 (RFC 8017 §9.2 notes). */
    private static final byte[] SHA1_DIGEST_INFO_PREFIX = hex("3021300906052b0e03021a05000414");
    private static final String KEY_NAME = "xgimi-guard@local";

    private KeyPair pair;

    private AdbKeyManager() { }

    /** Loads the persisted identity or generates a fresh one. Never returns null. */
    public static synchronized AdbKeyManager load(Context context) {
        AdbKeyManager m = new AdbKeyManager();
        File privFile = new File(context.getFilesDir(), KEY_FILE);
        File pubFile = new File(context.getFilesDir(), KEY_FILE + ".pub");
        if (privFile.exists() && pubFile.exists()) {
            try {
                KeyFactory kf = KeyFactory.getInstance("RSA");
                PrivateKey priv = kf.generatePrivate(
                        new PKCS8EncodedKeySpec(Base64.decode(readText(privFile), Base64.NO_WRAP)));
                PublicKey pub = kf.generatePublic(
                        new X509EncodedKeySpec(Base64.decode(readText(pubFile), Base64.NO_WRAP)));
                m.pair = new KeyPair(pub, priv);
                return m;
            } catch (Exception e) {
                android.util.Log.w("Z7xAdb", "ADB 密钥加载失败，将重新生成", e);
            }
        }
        m.generate(context);
        return m;
    }

    private void generate(Context context) {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            pair = gen.generateKeyPair();
            writeText(new File(context.getFilesDir(), KEY_FILE),
                    Base64.encodeToString(pair.getPrivate().getEncoded(), Base64.NO_WRAP));
            writeText(new File(context.getFilesDir(), KEY_FILE + ".pub"),
                    Base64.encodeToString(pair.getPublic().getEncoded(), Base64.NO_WRAP));
        } catch (Exception e) {
            throw new IllegalStateException("无法生成 ADB 密钥", e);
        }
    }

    /**
     * RSA/SHA-1 PKCS#1 signature over the 20-byte AUTH token.
     * adb treats the token as an already-hashed digest: the signed block is
     * DigestInfo(SHA-1, token), i.e. "SHA-1-PREHASHED". Java's "SHA1withRSA"
     * would hash the token a second time, so we build the DigestInfo ourselves
     * and use raw PKCS#1 v1.5 encryption.
     */
    public byte[] signToken(byte[] token) throws Exception {
        byte[] digestInfo = new byte[SHA1_DIGEST_INFO_PREFIX.length + token.length];
        System.arraycopy(SHA1_DIGEST_INFO_PREFIX, 0, digestInfo, 0, SHA1_DIGEST_INFO_PREFIX.length);
        System.arraycopy(token, 0, digestInfo, SHA1_DIGEST_INFO_PREFIX.length, token.length);
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, pair.getPrivate());
        return cipher.doFinal(digestInfo);
    }

    /** Wire payload for A_AUTH RSAPUBLICKEY: base64(android_pubkey) + " name\0". */
    public byte[] publicKeyPayload() throws Exception {
        RSAPublicKeySpec spec = KeyFactory.getInstance("RSA")
                .getKeySpec(pair.getPublic(), RSAPublicKeySpec.class);
        BigInteger n = spec.getModulus();
        BigInteger e = spec.getPublicExponent();
        BigInteger two32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.modInverse(two32).negate().mod(two32);
        BigInteger rr = BigInteger.TWO.pow(4096).mod(n);

        ByteBuffer buf = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(64);                 // modulus size in 32-bit words (2048/32)
        buf.putInt(n0inv.intValue());   // bit pattern is what matters, negatives are fine
        putLe(buf, n, 256);
        putLe(buf, rr, 256);
        buf.putInt(e.intValue());
        byte[] struct = buf.array();
        String b64 = Base64.encodeToString(struct, Base64.NO_WRAP);
        return (b64 + " " + KEY_NAME + "\0").getBytes("UTF-8");
    }

    /** Appends the value as a zero-padded little-endian byte array of exactly len bytes. */
    private static void putLe(ByteBuffer buf, BigInteger v, int len) {
        byte[] be = v.toByteArray(); // big-endian, possibly with a leading zero byte
        int start = (be.length > 0 && be[0] == 0) ? 1 : 0;
        int content = be.length - start;
        if (content > len) {         // defensive; 2048-bit values never exceed 256 bytes here
            start += content - len;
            content = len;
        }
        byte[] le = new byte[len];
        for (int i = 0; i < content; i++) {
            le[i] = be[be.length - 1 - i];
        }
        buf.put(le);
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String readText(File f) throws Exception {
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
            byte[] b = new byte[(int) f.length()];
            in.readFully(b);
            return new String(b, "UTF-8").trim();
        }
    }

    private static void writeText(File f, String text) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes("UTF-8"));
        }
    }
}
