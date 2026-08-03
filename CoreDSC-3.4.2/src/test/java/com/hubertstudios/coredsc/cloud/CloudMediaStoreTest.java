package com.hubertstudios.coredsc.cloud;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CloudMediaStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void storesValidatedContentByDigestAndResolvesOnlyGeneratedReferences() throws Exception {
        CloudMediaStore store = new CloudMediaStore(temporaryDirectory.resolve("media"));
        byte[] png = png();
        Map<String, Object> installed = store.install(payload("image/png", "png", png));

        String reference = String.valueOf(installed.get("reference"));
        assertTrue(CloudMediaStore.isReference(reference));
        CloudMediaStore.MediaFile media = store.resolve(reference).orElseThrow();
        assertArrayEquals(png, Files.readAllBytes(media.path()));
        assertTrue(media.attachmentName().endsWith(".png"));

        Map<String, Object> duplicate = store.install(payload("image/png", "png", png));
        assertEquals(reference, duplicate.get("reference"));
        try (var files = Files.list(temporaryDirectory.resolve("media"))) {
            assertEquals(1L, files.count());
        }
    }

    @Test
    void supportsEveryDeclaredImageType() {
        CloudMediaStore store = new CloudMediaStore(temporaryDirectory.resolve("media"));
        List<MediaCase> cases = List.of(
                new MediaCase("image/png", "png", png()),
                new MediaCase("image/jpeg", "jpg", new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9}),
                new MediaCase("image/webp", "webp", "RIFF0000WEBP".getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
                new MediaCase("image/gif", "gif", "GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));

        for (MediaCase mediaCase : cases) {
            Map<String, Object> installed = store.install(payload(
                    mediaCase.mimeType(), mediaCase.extension(), mediaCase.bytes()));
            assertTrue(store.resolve(String.valueOf(installed.get("reference"))).isPresent());
        }
    }

    @Test
    void rejectsInvalidBase64TypeMismatchAndOversizedContent() {
        CloudMediaStore store = new CloudMediaStore(temporaryDirectory.resolve("media"));
        String encoded = Base64.getEncoder().encodeToString(png());

        assertThrows(IllegalArgumentException.class, () -> store.install(Map.of(
                "mimeType", "image/jpeg", "extension", "jpg", "dataBase64", encoded)));
        assertThrows(IllegalArgumentException.class, () -> store.install(Map.of(
                "mimeType", "image/png", "extension", "jpg", "dataBase64", encoded)));
        assertThrows(IllegalArgumentException.class, () -> store.install(Map.of(
                "mimeType", "image/png", "extension", "png", "dataBase64", "%%%")));

        byte[] oversized = new byte[CloudMediaStore.MAX_MEDIA_BYTES + 1];
        System.arraycopy(png(), 0, oversized, 0, png().length);
        assertThrows(IllegalArgumentException.class, () -> store.install(payload("image/png", "png", oversized)));
    }

    @Test
    void rejectsTamperedContentEvenWhenTheReferenceNameLooksValid() throws Exception {
        CloudMediaStore store = new CloudMediaStore(temporaryDirectory.resolve("media"));
        Map<String, Object> installed = store.install(payload("image/png", "png", png()));
        String reference = String.valueOf(installed.get("reference"));
        Path path = store.resolve(reference).orElseThrow().path();

        Files.write(path, new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0, 0, 0, 0});

        assertFalse(store.resolve(reference).isPresent());
        assertThrows(IllegalStateException.class, () -> store.install(payload("image/png", "png", png())));
    }

    @Test
    void rejectsASymlinkAtAContentAddressedDestination() throws Exception {
        Path root = temporaryDirectory.resolve("media");
        Files.createDirectories(root);
        byte[] png = png();
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png));
        Path outside = temporaryDirectory.resolve("outside.png");
        Files.write(outside, png);
        Files.createSymbolicLink(root.resolve(digest + ".png"), outside);

        CloudMediaStore store = new CloudMediaStore(root);
        assertThrows(IllegalStateException.class, () -> store.install(payload("image/png", "png", png)));
        assertFalse(store.resolve("coredsc-media://" + digest + ".png").isPresent());
    }

    @Test
    void concurrentDuplicateInstallsRemainIdempotent() throws Exception {
        CloudMediaStore store = new CloudMediaStore(temporaryDirectory.resolve("media"));
        byte[] png = png();
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int index = 0; index < 24; index++) {
                tasks.add(() -> String.valueOf(store.install(payload("image/png", "png", png)).get("reference")));
            }
            var futures = executor.invokeAll(tasks);
            String expected = futures.getFirst().get();
            for (var future : futures) assertEquals(expected, future.get());
        }
        try (var files = Files.list(temporaryDirectory.resolve("media"))) {
            assertEquals(1L, files.count());
        }
    }

    @Test
    void rejectsPathLikeAndUnknownReferences() {
        CloudMediaStore store = new CloudMediaStore(temporaryDirectory.resolve("media"));
        assertFalse(store.resolve("../../secrets.yml").isPresent());
        assertFalse(store.resolve("coredsc-media://../secrets.yml").isPresent());
        assertFalse(store.resolve("coredsc-media://" + "a".repeat(64) + ".svg").isPresent());
        assertFalse(store.resolve("coredsc-media://" + "a".repeat(64) + ".png").isPresent());
    }

    private static Map<String, Object> payload(String mimeType, String extension, byte[] bytes) {
        return Map.of(
                "mimeType", mimeType,
                "extension", extension,
                "dataBase64", Base64.getEncoder().encodeToString(bytes));
    }

    private static byte[] png() {
        return new byte[] {
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                0x00, 0x01, 0x02, 0x03
        };
    }

    private record MediaCase(String mimeType, String extension, byte[] bytes) { }
}
