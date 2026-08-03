package com.hubertstudios.coredsc.cloud;

import com.hubertstudios.coredsc.CoreDSCPlugin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

/** Persistent Ed25519 identity. The private key never leaves the plugin data directory. */
public final class CloudIdentity {
    private static final int MAXIMUM_IDENTITY_BYTES = 8_192;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final UUID instanceId;
    private final PublicKey publicKey;
    private final PrivateKey privateKey;

    private CloudIdentity(UUID instanceId, PublicKey publicKey, PrivateKey privateKey) {
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
        this.privateKey = Objects.requireNonNull(privateKey, "privateKey");
    }

    public static CloudIdentity loadOrCreate(CoreDSCPlugin plugin) throws Exception {
        Objects.requireNonNull(plugin, "plugin");
        Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path identityFile = dataFolder.resolve("state/cloud-identity.properties");
        boolean existed = Files.exists(identityFile, LinkOption.NOFOLLOW_LINKS);
        CloudIdentity identity = loadOrCreate(dataFolder);
        if (!existed) {
            plugin.getLogger().info("[Cloud] Created local Ed25519 instance identity "
                    + identity.instanceId + ". The private key remains in plugins/CoreDSC/state/.");
        }
        return identity;
    }

    static synchronized CloudIdentity loadOrCreate(Path dataFolder) throws Exception {
        Objects.requireNonNull(dataFolder, "dataFolder");
        Path stateDirectory = dataFolder.toAbsolutePath().normalize().resolve("state");
        Path identityFile = stateDirectory.resolve("cloud-identity.properties");
        Files.createDirectories(stateDirectory);
        if (Files.isSymbolicLink(stateDirectory)
                || !Files.isDirectory(stateDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Cloud identity state path is not a real directory");
        }

        if (Files.exists(identityFile, LinkOption.NOFOLLOW_LINKS)) {
            CloudIdentity identity = read(identityFile);
            restrict(identityFile);
            return identity;
        }

        var generator = KeyPairGenerator.getInstance("Ed25519");
        var pair = generator.generateKeyPair();
        var identity = new CloudIdentity(UUID.randomUUID(), pair.getPublic(), pair.getPrivate());
        try {
            identity.write(identityFile);
            return identity;
        } catch (FileAlreadyExistsException race) {
            // A second local initializer won the create-only write. Never replace its private key.
            return read(identityFile);
        }
    }

    public UUID instanceId() {
        return instanceId;
    }

    public String publicKeyBase64() {
        return ENCODER.encodeToString(publicKey.getEncoded());
    }

    public String sign(String canonicalMessage) throws Exception {
        Objects.requireNonNull(canonicalMessage, "canonicalMessage");
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(canonicalMessage.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return ENCODER.encodeToString(signer.sign());
    }

    private static CloudIdentity read(Path file) throws Exception {
        BasicFileAttributes attributes = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw new IOException("Cloud identity path is not a regular file: " + file);
        }
        if (attributes.size() <= 0 || attributes.size() > MAXIMUM_IDENTITY_BYTES) {
            throw new IOException("Cloud identity file has an invalid size: " + file);
        }

        byte[] encoded;
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            encoded = input.readNBytes(MAXIMUM_IDENTITY_BYTES + 1);
        }
        if (encoded.length == 0 || encoded.length > MAXIMUM_IDENTITY_BYTES) {
            Arrays.fill(encoded, (byte) 0);
            throw new IOException("Cloud identity file exceeds the size limit: " + file);
        }

        Properties properties = new Properties();
        try (InputStream input = new ByteArrayInputStream(encoded)) {
            properties.load(input);
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
        String version = properties.getProperty("format", "");
        if (!version.equals("1")) {
            throw new IOException("Unsupported cloud identity format in " + file);
        }
        UUID instanceId = UUID.fromString(required(properties, "instance-id"));
        KeyFactory factory = KeyFactory.getInstance("Ed25519");
        byte[] publicBytes = decodeBounded(properties, "public-key", 128);
        PublicKey publicKey;
        try {
            publicKey = factory.generatePublic(new X509EncodedKeySpec(publicBytes));
        } finally {
            Arrays.fill(publicBytes, (byte) 0);
        }
        byte[] privateBytes = decodeBounded(properties, "private-key", 128);
        PrivateKey privateKey;
        try {
            privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
        } finally {
            Arrays.fill(privateBytes, (byte) 0);
        }
        CloudIdentity identity = new CloudIdentity(instanceId, publicKey, privateKey);

        // Detect truncated/mismatched key files before accepting remote work.
        String probe = "coredsc-identity-self-test:" + instanceId;
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(probe.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (!verifier.verify(DECODER.decode(identity.sign(probe)))) {
            throw new IOException("Cloud identity public/private keys do not match in " + file);
        }
        return identity;
    }

    private void write(Path file) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("format", "1");
        properties.setProperty("instance-id", instanceId.toString());
        properties.setProperty("public-key", ENCODER.encodeToString(publicKey.getEncoded()));
        properties.setProperty("private-key", ENCODER.encodeToString(privateKey.getEncoded()));

        byte[] encoded;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(512)) {
            properties.store(output, "CoreDSC local cloud identity - keep this file private");
            encoded = output.toByteArray();
        }
        if (encoded.length > MAXIMUM_IDENTITY_BYTES) {
            Arrays.fill(encoded, (byte) 0);
            throw new IOException("Generated cloud identity exceeds the size limit");
        }

        boolean created = false;
        try {
            try (OutputStream output = Files.newOutputStream(
                    file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                created = true;
                restrict(file);
                output.write(encoded);
                output.flush();
            }
            restrict(file);
        } catch (Exception error) {
            if (created) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException cleanupError) {
                    error.addSuppressed(cleanupError);
                }
            }
            throw error;
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    private static byte[] decodeBounded(Properties properties, String key, int maximumBytes) throws IOException {
        try {
            byte[] decoded = DECODER.decode(required(properties, key));
            if (decoded.length == 0 || decoded.length > maximumBytes) {
                Arrays.fill(decoded, (byte) 0);
                throw new IOException("Cloud identity has an invalid " + key + " length");
            }
            return decoded;
        } catch (IllegalArgumentException error) {
            throw new IOException("Cloud identity has invalid base64 in " + key, error);
        }
    }

    private static void restrict(Path file) {
        try {
            Files.setPosixFilePermissions(file, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows/non-POSIX hosts use their native ACLs. The file remains
            // inside the server owner's existing private plugin directory.
        }
    }

    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IOException("Cloud identity is missing " + key);
        return value.trim();
    }
}
