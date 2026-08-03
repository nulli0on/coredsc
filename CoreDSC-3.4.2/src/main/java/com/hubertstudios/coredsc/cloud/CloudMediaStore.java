package com.hubertstudios.coredsc.cloud;

import com.hubertstudios.coredsc.CoreDSCPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Content-addressed, local-only media used by hosted embed configuration.
 * The cloud transports validated base64 but never receives an arbitrary path
 * and never stores the image after the request finishes.
 */
public final class CloudMediaStore {
    public static final int MAX_MEDIA_BYTES = 512 * 1024;
    private static final Pattern REFERENCE = Pattern.compile(
            "^coredsc-media://([0-9a-f]{64})\\.(png|jpg|webp|gif)$");
    private static final Set<String> MIME_TYPES = Set.of(
            "image/png", "image/jpeg", "image/webp", "image/gif");

    private final Path root;

    public CloudMediaStore(CoreDSCPlugin plugin) {
        this(plugin.getDataFolder().toPath().resolve("cloud-media"));
    }

    CloudMediaStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Map<String, Object> install(Map<String, Object> payload) {
        String mimeType = text(payload.get("mimeType")).toLowerCase(Locale.ROOT);
        String extension = text(payload.get("extension")).toLowerCase(Locale.ROOT);
        String encoded = text(payload.get("dataBase64"));
        if (!MIME_TYPES.contains(mimeType) || !expectedExtension(mimeType).equals(extension)) {
            throw new IllegalArgumentException("Unsupported or mismatched dashboard image type");
        }
        if (encoded.isBlank() || encoded.length() > 750_000) {
            throw new IllegalArgumentException("Dashboard image base64 is empty or too large");
        }

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Dashboard image base64 is invalid", error);
        }
        try {
            if (bytes.length == 0 || bytes.length > MAX_MEDIA_BYTES) {
                throw new IllegalArgumentException("Dashboard image exceeds the 512 KiB local-media limit");
            }
            if (!matchesMagic(bytes, mimeType)) {
                throw new IllegalArgumentException("Dashboard image bytes do not match the declared type");
            }

            String digest = sha256(bytes);
            String fileName = digest + "." + extension;
            Path destination = root.resolve(fileName).normalize();
            if (!destination.getParent().equals(root)) throw new SecurityException("Invalid local media path");

            ensureRoot();
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                requireValidStoredFile(destination, digest, extension);
            } else {
                try {
                    writeAtomic(destination, bytes);
                } catch (FileAlreadyExistsException race) {
                    // Another identical install may have completed between the existence check and move.
                    requireValidStoredFile(destination, digest, extension);
                }
                requireValidStoredFile(destination, digest, extension);
            }

            return Map.of(
                    "reference", "coredsc-media://" + fileName,
                    "sha256", digest,
                    "bytes", bytes.length,
                    "mimeType", mimeType);
        } catch (IOException error) {
            throw new IllegalStateException("Could not store dashboard image locally", error);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    public Optional<MediaFile> resolve(String reference) {
        Matcher matcher = REFERENCE.matcher(reference == null ? "" : reference.trim());
        if (!matcher.matches()) return Optional.empty();
        Path path = root.resolve(matcher.group(1) + "." + matcher.group(2)).normalize();
        if (!path.getParent().equals(root)) return Optional.empty();
        try {
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            if (!isValidStoredFile(path, matcher.group(1), matcher.group(2))) return Optional.empty();
            return Optional.of(new MediaFile(path, "coredsc-" + matcher.group(1).substring(0, 16)
                    + "." + matcher.group(2)));
        } catch (IOException error) {
            return Optional.empty();
        }
    }

    public static boolean isReference(String value) {
        return value != null && REFERENCE.matcher(value.trim()).matches();
    }

    private void ensureRoot() throws IOException {
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Local media root is not a real directory");
        }
    }

    private static void requireValidStoredFile(Path path, String digest, String extension) throws IOException {
        if (!isValidStoredFile(path, digest, extension)) {
            throw new IOException("Existing local media file failed integrity validation");
        }
    }

    private static boolean isValidStoredFile(Path path, String digest, String extension) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) return false;
        if (attributes.size() <= 0 || attributes.size() > MAX_MEDIA_BYTES) return false;

        byte[] bytes;
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_MEDIA_BYTES + 1);
        }
        try {
            return bytes.length > 0
                    && bytes.length <= MAX_MEDIA_BYTES
                    && digest.equals(sha256(bytes))
                    && matchesMagic(bytes, mimeForExtension(extension));
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static void writeAtomic(Path destination, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(destination.getParent(), ".media-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, destination);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean matchesMagic(byte[] bytes, String mimeType) {
        return switch (mimeType) {
            case "image/png" -> bytes.length >= 8
                    && bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47
                    && bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a;
            case "image/jpeg" -> bytes.length >= 4 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8
                    && bytes[bytes.length - 2] == (byte) 0xff && bytes[bytes.length - 1] == (byte) 0xd9;
            case "image/webp" -> bytes.length >= 12 && ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WEBP");
            case "image/gif" -> bytes.length >= 6
                    && (ascii(bytes, 0, "GIF87a") || ascii(bytes, 0, "GIF89a"));
            default -> false;
        };
    }

    private static boolean ascii(byte[] bytes, int offset, String expected) {
        if (bytes.length < offset + expected.length()) return false;
        for (int index = 0; index < expected.length(); index++) {
            if ((bytes[offset + index] & 0xff) != expected.charAt(index)) return false;
        }
        return true;
    }

    private static String expectedExtension(String mimeType) {
        return switch (mimeType) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "";
        };
    }

    private static String mimeForExtension(String extension) {
        return switch (extension) {
            case "png" -> "image/png";
            case "jpg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            default -> "";
        };
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record MediaFile(Path path, String attachmentName) { }
}
