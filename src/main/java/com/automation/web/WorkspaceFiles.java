package com.automation.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Local, bounded file management. Hidden files, links, and paths outside the workspace are excluded. */
final class WorkspaceFiles {
    static final int MAX_TEXT_BYTES = 5 * 1024 * 1024;
    private static final Set<String> ROOTS = Set.of("collections", "filters", "reports");
    private final Path root;

    record Entry(String path, String name, boolean directory, long size, String modified) {}
    record Document(String path, String content, String revision) {}

    WorkspaceFiles(Path root) throws IOException {
        this.root = root.toRealPath();
        for (String directory : ROOTS) Files.createDirectories(resolve(directory));
    }

    Path root() { return root; }

    Path resolve(String name) {
        if (name == null || name.length() > 600 || name.contains("\\")) {
            throw new WebException(400, "Choose a path inside collections, filters, or reports.");
        }
        String[] parts = name.split("/", -1);
        if (!ROOTS.contains(parts[0])) throw new WebException(400, "This folder is outside the workspace.");
        Path path = root;
        for (String part : parts) {
            if (!part.matches("[\\p{L}\\p{N}][\\p{L}\\p{N}._ ()-]{0,119}")) {
                throw new WebException(400, "Use folder and file names containing letters, numbers, spaces, dots, hyphens, or underscores.");
            }
            path = path.resolve(part);
            if (Files.isSymbolicLink(path)) throw new WebException(400, "Symbolic links are not available in the web workspace.");
        }
        return path;
    }

    List<Entry> list() throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (String name : List.of("collections", "filters", "reports")) {
            Path base = resolve(name);
            try (var paths = Files.walk(base, 12)) {
                for (Path path : paths.sorted().toList()) {
                    if (entries.size() >= 5000) throw new WebException(413, "The workspace exceeds 5,000 entries. Use a smaller workspace.");
                    String relative = relative(path);
                    try { resolve(relative); } catch (WebException ignored) { continue; }
                    boolean directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
                    if (!directory && !allowedFile(relative)) continue;
                    entries.add(new Entry(relative, path.getFileName().toString(), directory,
                            directory ? 0 : Files.size(path), Files.getLastModifiedTime(path).toInstant().toString()));
                }
            }
        }
        return entries;
    }

    Document read(String name) throws IOException {
        editable(name);
        Path path = resolve(name);
        if (!Files.isRegularFile(path)) throw new WebException(404, "File not found: " + name);
        if (Files.size(path) > MAX_TEXT_BYTES) throw new WebException(413, "Text files must be 5 MB or smaller.");
        byte[] bytes = Files.readAllBytes(path);
        return new Document(name, new String(bytes, StandardCharsets.UTF_8), revision(bytes));
    }

    synchronized Document save(String name, String content, String expectedRevision) throws IOException {
        editable(name);
        Path path = resolve(name);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_BYTES) throw new WebException(413, "Text files must be 5 MB or smaller.");
        if (!Files.isDirectory(path.getParent())) throw new WebException(400, "Create the parent folder first.");
        if (Files.exists(path)) {
            if (!Objects.equals(read(name).revision(), expectedRevision)) {
                throw new WebException(409, "This file changed on disk. Reopen it to load the current version, or save your edits under a new name.");
            }
        } else if (expectedRevision != null) {
            throw new WebException(409, "This file was moved or deleted. Save your edits under a new name.");
        }
        Path temporary = Files.createTempFile(path.getParent(), ".save-", ".tmp");
        try {
            Files.write(temporary, bytes);
            if (expectedRevision == null) Files.move(temporary, path);
            else Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return new Document(name, content, revision(bytes));
    }

    synchronized void mkdir(String name) throws IOException {
        Path path = child(name);
        if (!Files.isDirectory(path.getParent())) throw new WebException(400, "Create the parent folder first.");
        if (Files.exists(path)) throw new WebException(409, "A file or folder already uses that name.");
        Files.createDirectory(path);
    }

    synchronized void move(String from, String to) throws IOException {
        Path source = child(from);
        Path destination = child(to);
        if (!from.split("/")[0].equals(to.split("/")[0])) throw new WebException(400, "Keep files in their collection, filter, or report folder.");
        if (!Files.exists(source)) throw new WebException(404, "The selected file or folder no longer exists.");
        if (Files.exists(destination)) throw new WebException(409, "A file or folder already uses that name.");
        if (destination.startsWith(source)) throw new WebException(400, "A folder cannot be moved inside itself.");
        if (!Files.isDirectory(source) && !allowedFile(to)) throw new WebException(400, "Keep the original file type (.json, .filter, or .xlsx).");
        if (!Files.isDirectory(destination.getParent())) throw new WebException(400, "The destination folder does not exist.");
        Files.move(source, destination);
    }

    synchronized String trash(String name) throws IOException {
        Path source = child(name);
        if (!Files.exists(source)) throw new WebException(404, "The selected file or folder no longer exists.");
        Path trashRoot = internalDirectory(".web-trash");
        Path destination = Files.createDirectory(trashRoot.resolve(UUID.randomUUID().toString())).resolve(source.getFileName());
        Files.move(source, destination);
        return root.relativize(destination).toString();
    }

    Path internalDirectory(String name) throws IOException {
        if (!Set.of(".web-trash", ".web-state").contains(name)) throw new IllegalArgumentException("Unknown internal folder");
        Path path = root.resolve(name);
        if (Files.isSymbolicLink(path)) throw new WebException(400, "Workspace state folders cannot be symbolic links.");
        return Files.createDirectories(path);
    }

    private Path child(String name) {
        Path path = resolve(name);
        if (path.getParent().equals(root)) throw new WebException(400, "The workspace's top-level folders cannot be changed.");
        return path;
    }

    private void editable(String name) {
        resolve(name);
        if (!(name.startsWith("collections/") && name.endsWith(".json"))
                && !(name.startsWith("filters/") && name.endsWith(".filter"))) {
            throw new WebException(400, "Open a .json collection or a .filter report definition.");
        }
    }

    private boolean allowedFile(String name) {
        return name.startsWith("collections/") && name.endsWith(".json")
                || name.startsWith("filters/") && name.endsWith(".filter")
                || name.startsWith("reports/") && name.endsWith(".xlsx");
    }

    String relative(Path path) { return root.relativize(path).toString().replace('\\', '/'); }

    private static String revision(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
