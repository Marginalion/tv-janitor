package io.github.marginalion.tvjanitor;

import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.Cipher;

/** A tiny ADB client (TCP transport, RSA auth, shell service). No dependencies. */
final class Adb implements Closeable {
    private static final int A_CNXN = 0x4e584e43, A_AUTH = 0x48545541, A_OPEN = 0x4e45504f,
            A_OKAY = 0x59414b4f, A_CLSE = 0x45534c43, A_WRTE = 0x45545257;
    private static final int VERSION = 0x01000000, MAX_DATA = 256 * 1024;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    /** PKCS#1 v1.5 type-1 padding + SHA-1 DigestInfo, for a 2048-bit key and a 20-byte token. */
    private static final byte[] SIG_PREFIX = sigPrefix();

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextId = 1;

    private static final class Msg { int cmd, a0, a1; byte[] data; }

    private Adb(Socket s) throws IOException {
        socket = s;
        in = new DataInputStream(s.getInputStream());
        out = s.getOutputStream();
    }

    /** Connects and authenticates. authTimeoutMs covers the "Allow USB debugging?" prompt on first use. */
    static Adb connect(String host, int port, File keyDir, int authTimeoutMs) throws Exception {
        KeyPair kp = loadOrCreateKey(keyDir);
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 5000);
        s.setSoTimeout(10000);
        Adb adb = new Adb(s);
        try {
            adb.send(A_CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.US_ASCII));
            boolean sentSignature = false;
            while (true) {
                Msg m = adb.read();
                if (m.cmd == A_CNXN) break;
                if (m.cmd != A_AUTH || m.a0 != AUTH_TOKEN) throw new IOException("unexpected " + Integer.toHexString(m.cmd));
                if (!sentSignature) {
                    adb.send(A_AUTH, AUTH_SIGNATURE, 0, sign(kp.getPrivate(), m.data));
                    sentSignature = true;
                } else {
                    // Key not known yet: offer it, the TV shows the prompt.
                    byte[] pub = (Base64.encodeToString(adbPublicKey((RSAPublicKey) kp.getPublic()), Base64.NO_WRAP)
                            + " tvjanitor@tv\0").getBytes(StandardCharsets.US_ASCII);
                    adb.send(A_AUTH, AUTH_RSAPUBLICKEY, 0, pub);
                    s.setSoTimeout(authTimeoutMs);
                }
            }
            s.setSoTimeout(60000);
            return adb;
        } catch (Exception e) {
            adb.close();
            throw e;
        }
    }

    /** Runs a shell command and returns its combined output. */
    String shell(String command) throws IOException {
        int local = nextId++;
        send(A_OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int remote = 0;
        while (true) {
            Msg m = read();
            if (m.a1 != local) continue;
            if (m.cmd == A_OKAY) { remote = m.a0; continue; }
            if (m.cmd == A_WRTE) { buf.write(m.data); send(A_OKAY, local, m.a0, null); continue; }
            if (m.cmd == A_CLSE) { send(A_CLSE, local, m.a0 != 0 ? m.a0 : remote, null); break; }
        }
        return buf.toString("UTF-8");
    }

    @Override public void close() { try { socket.close(); } catch (IOException ignored) {} }

    private void send(int cmd, int a0, int a1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length, sum = 0;
        if (data != null) for (byte b : data) sum += b & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(a0).putInt(a1).putInt(len).putInt(sum).putInt(cmd ^ 0xffffffff);
        out.write(h.array());
        if (len > 0) out.write(data);
        out.flush();
    }

    private Msg read() throws IOException {
        byte[] hb = new byte[24];
        in.readFully(hb);
        ByteBuffer h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt(); m.a0 = h.getInt(); m.a1 = h.getInt();
        int len = h.getInt();
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    private static byte[] sign(PrivateKey key, byte[] token) throws Exception {
        Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key);
        c.update(SIG_PREFIX);
        return c.doFinal(token);
    }

    private static byte[] sigPrefix() {
        byte[] digestInfo = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
        byte[] p = new byte[256 - 20];
        p[0] = 0x00; p[1] = 0x01;
        int ffEnd = p.length - digestInfo.length - 1;
        for (int i = 2; i < ffEnd; i++) p[i] = (byte) 0xff;
        p[ffEnd] = 0x00;
        System.arraycopy(digestInfo, 0, p, ffEnd + 1, digestInfo.length);
        return p;
    }

    private static KeyPair loadOrCreateKey(File dir) throws Exception {
        File priv = new File(dir, "adbkey.der"), pub = new File(dir, "adbkey.pub.der");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv.isFile() && pub.isFile()) {
            return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(readAll(pub))),
                    kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(priv))));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        writeAll(priv, kp.getPrivate().getEncoded());
        writeAll(pub, kp.getPublic().getEncoded());
        return kp;
    }

    /** Android's RSAPublicKey struct: len, n0inv, n[64], rr[64], exponent (all little endian). */
    private static byte[] adbPublicKey(RSAPublicKey key) {
        final int words = 64;
        BigInteger r32 = BigInteger.ZERO.setBit(32);
        BigInteger n = key.getModulus();
        BigInteger rr = BigInteger.ZERO.setBit(words * 32).modPow(BigInteger.valueOf(2), n);
        BigInteger n0inv = n.remainder(r32).modInverse(r32);
        ByteBuffer b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(n0inv.negate().intValue());
        int[] myN = new int[words], myRr = new int[words];
        for (int i = 0; i < words; i++) {
            BigInteger[] q = rr.divideAndRemainder(r32); rr = q[0]; myRr[i] = q[1].intValue();
            q = n.divideAndRemainder(r32); n = q[0]; myN[i] = q[1].intValue();
        }
        for (int v : myN) b.putInt(v);
        for (int v : myRr) b.putInt(v);
        b.putInt(key.getPublicExponent().intValue());
        return b.array();
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream s = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[4096]; int n;
            while ((n = s.read(b)) > 0) o.write(b, 0, n);
            return o.toByteArray();
        }
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        try (OutputStream o = new FileOutputStream(f)) { o.write(data); }
    }
}
