package com.hubertstudios.coredsc.cloud;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Properties;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CloudIdentityTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsOneSigningIdentityAndReloadsIt() throws Exception {
        CloudIdentity first = CloudIdentity.loadOrCreate(temporaryDirectory);
        CloudIdentity second = CloudIdentity.loadOrCreate(temporaryDirectory);

        assertEquals(first.instanceId(), second.instanceId());
        assertEquals(first.publicKeyBase64(), second.publicKeyBase64());
        assertSignature(first, "release-check");
        assertSignature(second, "after-reload");
    }

    @Test
    void concurrentInitializersConvergeOnOneIdentity() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = java.util.stream.IntStream.range(0, 16)
                    .mapToObj(ignored -> executor.submit(() -> CloudIdentity.loadOrCreate(temporaryDirectory)))
                    .toList();
            String publicKey = futures.getFirst().get().publicKeyBase64();
            for (var future : futures) assertEquals(publicKey, future.get().publicKeyBase64());
        }
    }

    @Test
    void rejectsSymlinkAndOversizedIdentityFiles() throws Exception {
        Path state = temporaryDirectory.resolve("state");
        Files.createDirectories(state);
        Path outside = temporaryDirectory.resolve("outside.properties");
        Files.writeString(outside, "format=1\n");
        Files.createSymbolicLink(state.resolve("cloud-identity.properties"), outside);
        assertThrows(Exception.class, () -> CloudIdentity.loadOrCreate(temporaryDirectory));

        Files.delete(state.resolve("cloud-identity.properties"));
        Files.write(state.resolve("cloud-identity.properties"), new byte[8_193]);
        assertThrows(Exception.class, () -> CloudIdentity.loadOrCreate(temporaryDirectory));
    }

    @Test
    void rejectsMismatchedPublicAndPrivateKeys() throws Exception {
        Path firstRoot = temporaryDirectory.resolve("first");
        Path secondRoot = temporaryDirectory.resolve("second");
        CloudIdentity.loadOrCreate(firstRoot);
        CloudIdentity.loadOrCreate(secondRoot);

        Properties first = load(firstRoot);
        Properties second = load(secondRoot);
        first.setProperty("private-key", second.getProperty("private-key"));
        try (var output = Files.newOutputStream(firstRoot.resolve("state/cloud-identity.properties"))) {
            first.store(output, "mismatched test identity");
        }

        assertThrows(Exception.class, () -> CloudIdentity.loadOrCreate(firstRoot));
    }

    private static Properties load(Path root) throws Exception {
        Properties properties = new Properties();
        try (var input = Files.newInputStream(root.resolve("state/cloud-identity.properties"))) {
            properties.load(input);
        }
        return properties;
    }

    private static void assertSignature(CloudIdentity identity, String message) throws Exception {
        byte[] publicBytes = Base64.getUrlDecoder().decode(identity.publicKeyBase64());
        var publicKey = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(publicBytes));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(identity.sign(message))));
    }
}
