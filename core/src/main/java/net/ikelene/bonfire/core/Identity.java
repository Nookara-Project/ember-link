package net.ikelene.bonfire.core;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Properties;

public final class Identity {
    public static final String FILE = "identity.properties";
    static final String CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private final PrivateKey privateKey;
    private final byte[] spki;
    private final byte[] pkcs8;
    private final String iid;
    private final String pairingTag;

    private Identity(PrivateKey privateKey, byte[] pkcs8, byte[] spki) {
        this.privateKey = privateKey;
        this.pkcs8 = pkcs8;
        this.spki = spki;
        byte[] h = sha256(spki);
        this.iid = computeIid(h);
        this.pairingTag = computeTag(h);
    }

    public static Identity loadOrCreate(Path dir) throws IOException, GeneralSecurityException {
        Path f = dir.resolve(FILE);
        if (Files.exists(f)) {
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                p.load(r);
            }
            String priv = p.getProperty("private-key", "").trim();
            String pub = p.getProperty("public-key", "").trim();
            if (!priv.isEmpty() && !pub.isEmpty()) {
                byte[] pkcs8 = Base64.getDecoder().decode(priv);
                byte[] spki = Base64.getDecoder().decode(pub);
                KeyFactory kf = KeyFactory.getInstance("Ed25519");
                PrivateKey pk = kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
                kf.generatePublic(new X509EncodedKeySpec(spki));
                Identity id = new Identity(pk, pkcs8, spki);
                if (!id.verify("bonfire-self-check".getBytes(StandardCharsets.UTF_8), id.sign("bonfire-self-check".getBytes(StandardCharsets.UTF_8)))) {
                    throw new GeneralSecurityException("identity.properties keys do not match each other");
                }
                return id;
            }
        }
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Identity id = new Identity(kp.getPrivate(), kp.getPrivate().getEncoded(), kp.getPublic().getEncoded());
        id.save(f);
        return id;
    }

    public static Identity fromSeed(byte[] seed) throws GeneralSecurityException {
        if (seed.length != 32) throw new IllegalArgumentException("seed must be 32 bytes");
        SecureRandom fixed = new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                System.arraycopy(seed, 0, bytes, 0, Math.min(bytes.length, seed.length));
            }
        };
        KeyPairGenerator g = KeyPairGenerator.getInstance("Ed25519");
        g.initialize(255, fixed);
        KeyPair kp = g.generateKeyPair();
        return new Identity(kp.getPrivate(), kp.getPrivate().getEncoded(), kp.getPublic().getEncoded());
    }

    public static void delete(Path dir) throws IOException {
        Files.deleteIfExists(dir.resolve(FILE));
    }

    private void save(Path f) throws IOException {
        Files.createDirectories(f.getParent());
        Properties p = new Properties();
        p.setProperty("private-key", Base64.getEncoder().encodeToString(pkcs8));
        p.setProperty("public-key", Base64.getEncoder().encodeToString(spki));
        StringWriter w = new StringWriter();
        p.store(w, "Ember Link server identity. KEEP PRIVATE. Deleting this file creates a new identity (the server must be linked again).");
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        if (posix) {
            Files.deleteIfExists(f);
            Files.createFile(f, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        Files.writeString(f, w.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    public byte[] sign(byte[] data) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(privateKey);
            s.update(data);
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean verify(byte[] data, byte[] sig) {
        try {
            PublicKey pub = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(pub);
            s.update(data);
            return s.verify(sig);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    public String signHandshake(String nonce) {
        String input = "bonfire-gw-v1|" + nonce + "|" + iid;
        return Base64.getEncoder().encodeToString(sign(input.getBytes(StandardCharsets.UTF_8)));
    }

    public String publicKeyB64() {
        return Base64.getEncoder().encodeToString(spki);
    }

    public String privateKeyB64() {
        return Base64.getEncoder().encodeToString(pkcs8);
    }

    public String iid() {
        return iid;
    }

    public String pairingTag() {
        return pairingTag;
    }

    static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String computeIid(byte[] h) {
        StringBuilder sb = new StringBuilder("g");
        for (int i = 0; i < 8; i++) sb.append(String.format("%02x", h[i] & 0xff));
        return sb.toString();
    }

    static String computeTag(byte[] h) {
        long v = ((h[8] & 0xffL) << 24) | ((h[9] & 0xffL) << 16) | ((h[10] & 0xffL) << 8) | (h[11] & 0xffL);
        long bits = v >>> 2;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            int idx = (int) ((bits >>> (25 - 5 * i)) & 31);
            sb.append(CROCKFORD.charAt(idx));
            if (i == 2) sb.append('-');
        }
        return sb.toString();
    }

    public static String normalizeClaimCode(String raw) {
        if (raw == null) return null;
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
            if (c == '-' || c == ' ') continue;
            if (c == 'O') c = '0';
            if (c == 'I' || c == 'L') c = '1';
            if (CROCKFORD.indexOf(c) < 0) return null;
            sb.append(c);
        }
        return sb.length() == 8 ? sb.toString() : null;
    }
}
