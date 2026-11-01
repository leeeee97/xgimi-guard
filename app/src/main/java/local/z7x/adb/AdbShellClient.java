package local.z7x.adb;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Minimal ADB-protocol client speaking to the device's own adbd on 127.0.0.1:5555.
 * Implements CNXN / AUTH (TOKEN / SIGNATURE / RSAPUBLICKEY) / OPEN / OKAY / WRTE / CLSE.
 *
 * Auth flow (mirrors adb host behaviour and python-adb):
 *   1. CNXN — a secure adbd answers A_AUTH TOKEN with a 20-byte token.
 *   2. Reply A_AUTH SIGNATURE with an RSA SHA-1 PKCS#1 signature over the token.
 *   3. If the device still answers A_AUTH TOKEN, our key is not on its approved
 *      list yet: send A_AUTH RSAPUBLICKEY (android_pubkey format, base64 +
 *      " name\0"). adbd then pops the debug-approval dialog on screen.
 *   4. After the user approves with the remote, adbd answers CNXN and remembers
 *      the key, so later connections skip step 3 entirely.
 */
public final class AdbShellClient implements AutoCloseable {
    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;   // 'AUTH' little-endian
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;
    private static final int PROTOCOL_VERSION = 0x01000000;
    private static final int MAX_PAYLOAD = 4096;
    /** How long to wait for the on-screen approval dialog (remote control). */
    private static final int AUTH_DIALOG_TIMEOUT_MS = 120000;
    private static final int MAX_PUBKEY_ROUNDS = 2;

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private int localId = 1;
    private int remoteId = -1;
    private int maxData = MAX_PAYLOAD;

    private AdbShellClient(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = new DataOutputStream(socket.getOutputStream());
    }

    /**
     * Connects to adbd and performs CNXN, authenticating with the given key
     * manager when the device requires it. Blocks for up to
     * {@link #AUTH_DIALOG_TIMEOUT_MS} when the on-screen approval dialog is
     * pending.
     */
    public static AdbShellClient connect(int port, AdbKeyManager keys) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
        socket.setSoTimeout(15000);
        AdbShellClient client = new AdbShellClient(socket);
        try {
            client.write(A_CNXN, PROTOCOL_VERSION, MAX_PAYLOAD,
                    "host::xgimi-guard\0".getBytes(StandardCharsets.UTF_8));
            Message m = client.read();
            int pubkeyRounds = 0;
            while (m.cmd == A_AUTH) {
                if (m.arg0 != AUTH_TOKEN)
                    throw new IOException("未知 AUTH 类型: arg0=" + m.arg0);
                if (keys == null)
                    throw new IOException("AUTH_REQUIRED: 设备要求认证但无可用密钥");
                client.write(A_AUTH, AUTH_SIGNATURE, 0, keys.signToken(m.data));
                m = client.read();
                if (m.cmd == A_AUTH && m.arg0 == AUTH_TOKEN) {
                    // Signature not on the approved list: publish the public key.
                    // adbd shows the debug dialog; approval is remembered afterwards.
                    if (++pubkeyRounds > MAX_PUBKEY_ROUNDS)
                        throw new IOException("等待授权超时：请在投影仪屏幕弹窗中勾选「始终允许」后重试");
                    socket.setSoTimeout(AUTH_DIALOG_TIMEOUT_MS);
                    client.write(A_AUTH, AUTH_RSAPUBLICKEY, 0, keys.publicKeyPayload());
                    m = client.read();
                    socket.setSoTimeout(15000);
                }
            }
            if (m.cmd != A_CNXN)
                throw new IOException("握手失败: 0x" + Integer.toHexString(m.cmd));
            client.maxData = Math.max(256, m.arg1);
            return client;
        } catch (IOException e) {
            client.close();
            throw e;
        }
    }

    /** Runs "shell:<command>" and returns the combined stdout/stderr text. */
    public String shell(String command) throws IOException {
        byte[] payload = ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8);
        write(A_OPEN, localId, 0, payload);
        Message m = read();
        if (m.cmd == A_CLSE) throw new IOException("adbd 拒绝了 shell 会话");
        if (m.cmd != A_OKAY) throw new IOException("OPEN 失败: 0x" + Integer.toHexString(m.cmd));
        remoteId = m.arg0;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            m = read();
            if (m.cmd == A_WRTE) {
                out.write(m.data);
                write(A_OKAY, localId, remoteId, new byte[0]);
            } else if (m.cmd == A_CLSE) {
                break;
            } else if (m.cmd == A_AUTH) {
                throw new IOException("AUTH_REQUIRED: shell 会话中收到认证请求");
            }
        }
        remoteId = -1;
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static final class Message {
        int cmd; int arg0; int arg1; byte[] data;
    }

    private Message read() throws IOException {
        byte[] head = new byte[24];
        in.readFully(head);
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(head).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int len = b.getInt();
        int checksum = b.getInt();
        int magic = b.getInt();
        if ((m.cmd ^ 0xffffffff) != magic) throw new IOException("adb 消息损坏 (magic 不符)");
        m.data = new byte[len];
        if (len > 0) {
            in.readFully(m.data);
            if (checksum(m.data) != checksum) throw new IOException("adb 数据校验失败");
        }
        return m;
    }

    private void write(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        byte[] head = new byte[24];
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(head).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd); b.putInt(arg0); b.putInt(arg1);
        b.putInt(data.length); b.putInt(checksum(data)); b.putInt(cmd ^ 0xffffffff);
        out.write(head);
        if (data.length > 0) {
            for (int off = 0; off < data.length; off += maxData) {
                out.write(java.util.Arrays.copyOfRange(data, off, Math.min(data.length, off + maxData)));
            }
        }
        out.flush();
    }

    private static int checksum(byte[] data) {
        int sum = 0;
        for (byte v : data) sum += v & 0xff;
        return sum;
    }

    @Override public void close() {
        try { socket.close(); } catch (IOException ignored) { }
    }
}
