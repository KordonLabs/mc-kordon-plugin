package com.kordonlabs.plugin.common.install;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * {@code plugins/Kordon/secret.key}, the plugin's only file: the server key on one line in UTF-8 and nothing else.
 * The folder is created only by {@link #write}; {@link #delete} removes it again when nothing else is left in it.
 *
 * <p>Writes go through a temporary file, created with POSIX {@code rw-------} where supported, and an atomic move.
 * {@link #toString()} and every message never contain the key.
 */
public final class KeyFile {

    public static final String FILE = "secret.key";
    static final String TMP = "secret.key.tmp";
    static final int MAX_BYTES = 1024;

    /** What {@link #read} found. */
    public sealed interface Read permits Absent, Valid, Invalid {
    }

    /** No {@code secret.key}. */
    public record Absent() implements Read {
    }

    /** A valid key; {@link #toString()} shows its prefix only. */
    public record Valid(String token) implements Read {

        @Override
        public String toString() {
            return "Valid[" + Tokens.prefix(token) + "…]";
        }
    }

    /** A file that is not one valid key or cannot be read; {@code why} never contains its content. */
    public record Invalid(String why) implements Read {
    }

    private final Path folder;
    private final Path file;
    private final Path tmp;

    public KeyFile(Path dataFolder) {
        this.folder = dataFolder;
        this.file = dataFolder.resolve(FILE);
        this.tmp = dataFolder.resolve(TMP);
    }

    public Path path() {
        return file;
    }

    /** Reads at most {@value #MAX_BYTES} bytes of strict UTF-8, stripping a BOM and whitespace. Never throws. */
    public Read read() {
        try {
            Files.deleteIfExists(tmp); // left by a write that died between the temp file and the move
        } catch (IOException | RuntimeException ignored) {
            // The next write replaces it anyway.
        }
        try {
            if (!Files.exists(file)) return new Absent();
            if (!Files.isRegularFile(file)) return new Invalid("not a regular file");
            byte[] bytes;
            try (InputStream in = Files.newInputStream(file)) {
                bytes = in.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length > MAX_BYTES) return new Invalid("larger than " + MAX_BYTES + " bytes");
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException e) {
                return new Invalid("not UTF-8");
            }
            if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);
            String token = text.strip();
            return Tokens.valid(token) ? new Valid(token) : new Invalid("not a Kordon key");
        } catch (NoSuchFileException e) {
            return new Absent();
        } catch (IOException | RuntimeException e) {
            return new Invalid(e.getClass().getSimpleName());
        }
    }

    /** Writes the key atomically, creating the folder if needed. The key must pass {@link Tokens#valid}. */
    public void write(String token) throws IOException {
        if (!Tokens.valid(token)) throw new IllegalArgumentException("not a Kordon key");
        Files.createDirectories(folder);
        byte[] bytes = (token + "\n").getBytes(StandardCharsets.UTF_8);
        Files.deleteIfExists(tmp);
        boolean posix = false;
        try {
            posix = Files.getFileStore(folder).supportsFileAttributeView("posix");
        } catch (IOException | RuntimeException ignored) {
            // Unknown: write a plain file.
        }
        if (posix) {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.write(tmp, bytes);
        } else {
            Files.write(tmp, bytes);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // Removed by the next read.
            }
        }
    }

    /** Deletes {@code secret.key}, any leftover temp file and then the folder if empty. True if a key was deleted. */
    public boolean delete() throws IOException {
        boolean deleted = Files.deleteIfExists(file);
        Files.deleteIfExists(tmp);
        removeFolderIfEmpty(folder);
        return deleted;
    }

    /** The file's modification time; null when it is absent or unreadable. */
    public FileTime modified() {
        try {
            return Files.isRegularFile(file) ? Files.getLastModifiedTime(file) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Deletes {@code folder} only when it is an empty directory. Never throws. */
    static void removeFolderIfEmpty(Path folder) {
        try {
            if (folder == null || !Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return;
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
                if (entries.iterator().hasNext()) return;
            }
            Files.delete(folder);
        } catch (IOException | RuntimeException ignored) {
            // Not empty after all, or in use: it stays.
        }
    }

    /** Never contains the key. */
    @Override
    public String toString() {
        return "KeyFile[" + file + "]";
    }
}
