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
 * Implements CNXN / OPEN / OKAY / WRTE / CLSE. A_AUTH is intentionally not
 * implemented: XGIMI firmware reports ro.adb.secure=0, and if the device ever
 * asks for authentication the caller surfaces a hint to approve the debug
 * dialog once on screen (the approval is remembered afterwards).
 */
public final class AdbShellClient implements AutoCloseable {
    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x4e545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int PROTOCOL_VERSION = 0x01000001;
    private static final int MAX_PAYLOAD = 4096;

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private int localId = 1;
    private int remoteId = -1;
    private int maxData = MAX_PAYLOAD;

    private AdbShellClient(Socket socket) {
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = new DataOutputStream(socket.getOutputStream());
    }

    /** Connects to adbd on localhost and performs the CNXN handshake. */
    public static AdbShellClient connect(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
        socket.setSoTimeout(15000);
        AdbShellClient client = new AdbShellClient(socket);
        client.write(A_CNXN, PROTOCOL_VERSION, MAX_PAYLOAD, new byte[0]);
        Message m = client.read();
        if (m.cmd == A_AUTH) {
            client.close();
            throw new IOException("AUTH_REQUIRED");
        }
        if (m.cmd != A_CNXN) {
            client.close();
            throw new IOException("握手失败: 0x" + Integer.toHexString(m.cmd));
        }
        client.maxData = Math.max(256, m.arg1);
        return client;
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
                throw new IOException("AUTH_REQUIRED");
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
