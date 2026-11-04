package com.example.kwspotter;

import android.content.Context;
import android.os.Build;
import android.sun.misc.BASE64Encoder;
import android.sun.security.provider.X509Factory;
import android.sun.security.x509.AlgorithmId;
import android.sun.security.x509.CertificateAlgorithmId;
import android.sun.security.x509.CertificateExtensions;
import android.sun.security.x509.CertificateIssuerName;
import android.sun.security.x509.CertificateSerialNumber;
import android.sun.security.x509.CertificateSubjectName;
import android.sun.security.x509.CertificateValidity;
import android.sun.security.x509.CertificateVersion;
import android.sun.security.x509.CertificateX509Key;
import android.sun.security.x509.KeyIdentifier;
import android.sun.security.x509.PrivateKeyUsageExtension;
import android.sun.security.x509.SubjectKeyIdentifierExtension;
import android.sun.security.x509.X500Name;
import android.sun.security.x509.X509CertImpl;
import android.sun.security.x509.X509CertInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.Random;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/**
 * Ключ, которым приложение представляется демону беспроводной отладки.
 * После успешной привязки демон запоминает сертификат, и шестизначный код
 * больше не нужен, пока пользователь не отзовёт устройство в настройках.
 */
public final class KeywordAdbManager extends AbsAdbConnectionManager {

    private static final String PREFS = "adb_session";
    private static final String KEY_PAIRED = "paired";
    private static final long TEN_YEARS_MS = 10L * 365 * 24 * 60 * 60 * 1000;

    private static KeywordAdbManager instance;

    private final PrivateKey privateKey;
    private final Certificate certificate;

    public static synchronized KeywordAdbManager getInstance(@NonNull Context context) throws Exception {
        if (instance == null) {
            instance = new KeywordAdbManager(context.getApplicationContext());
        }
        return instance;
    }

    public static boolean isPaired(@NonNull Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PAIRED, false);
    }

    public static void setPaired(@NonNull Context context, boolean paired) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PAIRED, paired)
                .apply();
    }

    private KeywordAdbManager(@NonNull Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        PrivateKey loadedKey = readPrivateKey(context);
        Certificate loadedCert = readCertificate(context);
        if (loadedKey == null || loadedCert == null) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048, SecureRandom.getInstance("SHA1PRNG"));
            KeyPair keyPair = generator.generateKeyPair();
            PublicKey publicKey = keyPair.getPublic();
            loadedKey = keyPair.getPrivate();
            loadedCert = selfSigned(publicKey, loadedKey);
            writePrivateKey(context, loadedKey);
            writeCertificate(context, loadedCert);
        }
        privateKey = loadedKey;
        certificate = loadedCert;
    }

    @NonNull
    @Override
    protected PrivateKey getPrivateKey() {
        return privateKey;
    }

    @NonNull
    @Override
    protected Certificate getCertificate() {
        return certificate;
    }

    @NonNull
    @Override
    protected String getDeviceName() {
        return "ClearCalls";
    }

    private static Certificate selfSigned(PublicKey publicKey, PrivateKey privateKey) throws Exception {
        String algorithm = "SHA512withRSA";
        Date notBefore = new Date();
        Date notAfter = new Date(System.currentTimeMillis() + TEN_YEARS_MS);

        CertificateExtensions extensions = new CertificateExtensions();
        extensions.set(
                "SubjectKeyIdentifier",
                new SubjectKeyIdentifierExtension(new KeyIdentifier(publicKey).getIdentifier())
        );
        extensions.set("PrivateKeyUsage", new PrivateKeyUsageExtension(notBefore, notAfter));

        X500Name name = new X500Name("CN=ClearCalls");
        X509CertInfo info = new X509CertInfo();
        info.set("version", new CertificateVersion(2));
        info.set("serialNumber", new CertificateSerialNumber(new Random().nextInt() & Integer.MAX_VALUE));
        info.set("algorithmID", new CertificateAlgorithmId(AlgorithmId.get(algorithm)));
        info.set("subject", new CertificateSubjectName(name));
        info.set("key", new CertificateX509Key(publicKey));
        info.set("validity", new CertificateValidity(notBefore, notAfter));
        info.set("issuer", new CertificateIssuerName(name));
        info.set("extensions", extensions);

        X509CertImpl cert = new X509CertImpl(info);
        cert.sign(privateKey, algorithm);
        return cert;
    }

    @Nullable
    private static Certificate readCertificate(Context context) throws IOException, CertificateException {
        File file = new File(context.getFilesDir(), "adb-cert.pem");
        if (!file.exists()) return null;
        try (InputStream in = new FileInputStream(file)) {
            return CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    private static void writeCertificate(Context context, Certificate cert)
            throws CertificateEncodingException, IOException {
        File file = new File(context.getFilesDir(), "adb-cert.pem");
        BASE64Encoder encoder = new BASE64Encoder();
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(X509Factory.BEGIN_CERT.getBytes(StandardCharsets.UTF_8));
            out.write('\n');
            encoder.encode(cert.getEncoded(), out);
            out.write('\n');
            out.write(X509Factory.END_CERT.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Nullable
    private static PrivateKey readPrivateKey(Context context)
            throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        File file = new File(context.getFilesDir(), "adb-private.key");
        if (!file.exists()) return null;
        byte[] encoded = new byte[(int) file.length()];
        try (InputStream in = new FileInputStream(file)) {
            int offset = 0;
            while (offset < encoded.length) {
                int read = in.read(encoded, offset, encoded.length - offset);
                if (read < 0) break;
                offset += read;
            }
        }
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }

    private static void writePrivateKey(Context context, PrivateKey key) throws IOException {
        File file = new File(context.getFilesDir(), "adb-private.key");
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(key.getEncoded());
        }
    }
}
