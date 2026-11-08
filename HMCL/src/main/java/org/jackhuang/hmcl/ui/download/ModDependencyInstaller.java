/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.ui.download;

import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.addon.RemoteAddonRepository;
import org.jackhuang.hmcl.addon.mod.ModLoaderType;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.download.LibraryAnalyzer;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.DownloadProviders;
import org.jackhuang.hmcl.task.FileDownloadTask;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jackhuang.hmcl.util.TaskCancellationAction;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Downloads an addon together with the dependencies it needs, and with the dependencies those dependencies need.
 *
 * <p>The dependency graph is walked breadth first, so the depth of the graph is not limited. Every dependency is
 * visited once, which also stops dependency cycles. A dependency that is already present in the instance is never
 * downloaded again: the check is done by file name, by the SHA-1 of the file, and by the SHA-1 of every version of
 * the very same project, so a renamed file or an older build of the same mod is recognised as well.</p>
 */
public final class ModDependencyInstaller {

    /**
     * Dependency types that are really needed to run the addon.
     * OPTIONAL / EMBEDDED / INCLUDE are not downloaded, INCOMPATIBLE / BROKEN are useless.
     */
    private static final Set<RemoteAddon.DependencyType> AUTO_INSTALL_TYPES =
            EnumSet.of(RemoteAddon.DependencyType.REQUIRED, RemoteAddon.DependencyType.TOOL);

    private static final String JAR_SUFFIX = ".jar";
    private static final String DISABLED_SUFFIX = ".disabled";

    private ModDependencyInstaller() {
    }

    public static void install(DownloadProvider downloadProvider, HMCLGameRepository repository,
                               @Nullable String instanceId, RemoteAddon rootAddon, RemoteAddon.Version rootVersion) {
        String targetVersion = instanceId != null ? instanceId : repository.getSelectedInstance();
        if (targetVersion == null) {
            Controllers.dialog(i18n("mods.install_dependencies.no_instance"), i18n("install.failed"),
                    MessageDialogPane.MessageType.ERROR);
            return;
        }

        Path runDirectory = repository.hasVersion(targetVersion)
                ? repository.getRunDirectory(targetVersion)
                : repository.getBaseDirectory();
        Path modsDirectory = runDirectory.resolve("mods");
        try {
            Files.createDirectories(modsDirectory);
        } catch (IOException e) {
            LOG.warning("Failed to create directory " + modsDirectory, e);
            Controllers.dialog(i18n("install.failed.downloading"), i18n("install.failed"),
                    MessageDialogPane.MessageType.ERROR);
            return;
        }

        AtomicReference<Resolution> resolutionHolder = new AtomicReference<>();

        Task<?> task = Task.supplyAsync(Schedulers.io(),
                        () -> resolve(downloadProvider, repository, targetVersion, rootAddon, rootVersion, modsDirectory))
                .setName(i18n("mods.install_dependencies.resolving"))
                .thenComposeAsync(Schedulers.io(), resolution -> {
                    resolutionHolder.set(resolution);

                    List<Task<?>> tasks = new ArrayList<>(resolution.installable.size());
                    for (RemoteAddon.Version version : resolution.installable) {
                        Path dest = modsDirectory.resolve(version.file().filename());
                        FileDownloadTask fileTask = new FileDownloadTask(
                                downloadProvider.injectURLWithCandidates(version.file().url()),
                                dest,
                                version.file().getIntegrityCheck());
                        fileTask.setName(version.name());
                        tasks.add(fileTask);
                    }
                    return Task.allOf(tasks);
                })
                .whenComplete(Schedulers.javafx(), (result, exception) -> {
                    if (exception != null) {
                        if (exception instanceof CancellationException) {
                            Controllers.showToast(i18n("message.cancelled"));
                        } else {
                            Controllers.dialog(DownloadProviders.localizeErrorMessage(exception),
                                    i18n("install.failed.downloading"), MessageDialogPane.MessageType.ERROR);
                        }
                        return;
                    }

                    Resolution resolution = resolutionHolder.get();
                    if (resolution != null && !resolution.notes.isEmpty()) {
                        Controllers.dialog(String.join("\n", resolution.notes),
                                i18n("mods.install_dependencies.notes_title"),
                                MessageDialogPane.MessageType.WARNING);
                    } else {
                        Controllers.showToast(i18n("install.success"));
                    }
                });

        Controllers.taskDialog(task, i18n("message.downloading"), TaskCancellationAction.NORMAL);
    }

    /**
     * Walks the dependency graph of {@code rootVersion} and collects every file that still has to be downloaded.
     */
    private static Resolution resolve(DownloadProvider downloadProvider, HMCLGameRepository repository,
                                      String instanceId, RemoteAddon rootAddon, RemoteAddon.Version rootVersion,
                                      Path modsDirectory) throws IOException {
        Resolution resolution = new Resolution();

        InstanceTarget target = inspectInstance(repository, instanceId, rootVersion);
        InstalledFiles installed = InstalledFiles.scan(modsDirectory);

        String rootFileName = rootVersion.file().filename();
        LOG.info("Installing " + rootFileName + " with dependencies into " + modsDirectory
                + " (gameVersion=" + target.gameVersion + ", loaders=" + target.loaders + ")");

        // Lower case file name -> version, keeps the order of discovery and removes duplicates.
        Map<String, RemoteAddon.Version> downloads = new LinkedHashMap<>();
        if (rootFileName != null) {
            // The file the user asked for is always downloaded, exactly like a normal installation.
            downloads.put(rootFileName.toLowerCase(Locale.ROOT), rootVersion);
        }

        Set<String> visited = new HashSet<>();
        visited.add(dependencyKey(rootAddon.repoType(), rootVersion.modid()));

        Deque<Pending> queue = new ArrayDeque<>();
        queue.add(new Pending(rootVersion, 0));

        while (!queue.isEmpty()) {
            Pending pending = queue.removeFirst();
            RemoteAddon.Version current = pending.version();
            int depth = pending.depth() + 1;

            for (RemoteAddon.Dependency dependency : current.dependencies()) {
                if (!AUTO_INSTALL_TYPES.contains(dependency.getType())) {
                    continue;
                }

                RemoteAddonRepository dependencyRepository = dependency.getRemoteModRepository();
                if (dependencyRepository == null || dependency.getId() == null) {
                    continue;
                }

                if (!visited.add(dependencyKey(dependencyRepository.getType(), dependency.getId()))) {
                    // Already visited, this also prevents dependency cycles.
                    continue;
                }

                LOG.info("Resolving dependency " + dependencyRepository.getType() + ":" + dependency.getId()
                        + " at depth " + depth);

                RemoteAddon dependencyAddon;
                try {
                    dependencyAddon = dependency.load(downloadProvider);
                } catch (IOException e) {
                    LOG.warning("Failed to load dependency " + dependency.getId(), e);
                    resolution.notes.add(i18n("mods.install_dependencies.unresolved", dependency.getId()));
                    continue;
                }

                if (dependencyAddon == null || dependencyAddon == RemoteAddon.BROKEN) {
                    resolution.notes.add(i18n("mods.install_dependencies.broken", dependency.getId()));
                    continue;
                }

                List<RemoteAddon.Version> versions;
                try {
                    versions = loadVersions(dependencyAddon, dependencyRepository, downloadProvider);
                } catch (IOException e) {
                    LOG.warning("Failed to list versions of dependency " + dependency.getId(), e);
                    resolution.notes.add(i18n("mods.install_dependencies.unresolved", dependency.getId()));
                    continue;
                }

                boolean isMod = dependencyAddon.repoType() == RemoteAddonRepository.Type.MOD;
                Optional<RemoteAddon.Version> picked = pickVersion(versions, target, isMod);
                if (picked.isEmpty()) {
                    resolution.notes.add(i18n("mods.install_dependencies.no_compatible_version",
                            dependencyAddon.title(), target.gameVersionText()));
                    continue;
                }

                RemoteAddon.Version version = picked.get();
                String fileName = version.file().filename();
                if (fileName == null || version.file().url() == null) {
                    resolution.notes.add(i18n("mods.install_dependencies.unresolved", dependencyAddon.title()));
                    continue;
                }

                // Is this dependency, or another build of it, already in the instance?
                InstalledFiles.Hit installedEntry = installed.find(fileName,
                        version.file().hashes(), projectHashes(versions));
                if (installedEntry != null) {
                    InstalledFiles.Entry present = installedEntry.entry();
                    LOG.info("Skip " + fileName + ", already present as " + present.fileName());
                    if (present.disabled()) {
                        resolution.notes.add(i18n("mods.install_dependencies.skipped_disabled",
                                dependencyAddon.title()));
                    } else if (!installedEntry.exactFile()) {
                        resolution.notes.add(i18n("mods.install_dependencies.skipped_other_version",
                                dependencyAddon.title(), present.fileName()));
                    }
                    // The dependency itself is already there, but what it needs may still be missing,
                    // so its own dependencies have to be walked as well.
                    queue.addLast(new Pending(version, depth));
                    continue;
                }

                String downloadKey = fileName.toLowerCase(Locale.ROOT);
                if (downloads.containsKey(downloadKey)) {
                    // This file is already scheduled (or is the root file), its dependencies are walked there.
                    continue;
                }

                downloads.put(downloadKey, version);
                queue.addLast(new Pending(version, depth));
            }
        }

        resolution.installable.addAll(downloads.values());
        return resolution;
    }

    private static List<RemoteAddon.Version> loadVersions(RemoteAddon addon, RemoteAddonRepository repository,
                                                          DownloadProvider downloadProvider) throws IOException {
        try (Stream<RemoteAddon.Version> stream = addon.data().loadVersions(repository, downloadProvider)) {
            return stream
                    .filter(version -> version != null && version.file() != null)
                    .collect(Collectors.toList());
        }
    }

    /**
     * Every known version of the same project, so an older but differently named build can be recognised.
     */
    private static Set<String> projectHashes(List<RemoteAddon.Version> versions) {
        Set<String> hashes = new HashSet<>();
        for (RemoteAddon.Version version : versions) {
            String sha1 = sha1Of(version);
            if (sha1 != null) {
                hashes.add(sha1);
            }
        }
        return hashes;
    }

    @Nullable
    private static String sha1Of(RemoteAddon.Version version) {
        Map<String, String> hashes = version.file() != null ? version.file().hashes() : null;
        if (hashes == null) {
            return null;
        }
        String sha1 = hashes.get("sha1");
        if (sha1 != null) {
            return sha1.toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static InstanceTarget inspectInstance(HMCLGameRepository repository, String instanceId,
                                                  RemoteAddon.Version rootVersion) {
        String gameVersion = null;
        Set<ModLoaderType> loaders = EnumSet.noneOf(ModLoaderType.class);
        try {
            var game = repository.getResolvedPreservingPatchesVersion(instanceId);
            gameVersion = repository.getGameVersion(game).orElse(null);
            loaders.addAll(LibraryAnalyzer.analyze(game, gameVersion).getModLoaders());
        } catch (Exception e) {
            LOG.warning("Failed to resolve game version of " + instanceId, e);
        }

        if (loaders.isEmpty() && rootVersion.loaders() != null) {
            // The file the user picked is already built for one loader, so it is a reliable hint.
            for (ModLoaderType loader : rootVersion.loaders()) {
                if (loader != null) {
                    loaders.add(loader);
                }
            }
        }

        return new InstanceTarget(gameVersion, loaders);
    }

    private static Optional<RemoteAddon.Version> pickVersion(List<RemoteAddon.Version> versions,
                                                             InstanceTarget target, boolean isMod) {
        Optional<RemoteAddon.Version> exact = bestMatch(versions, target, isMod, false);
        if (exact.isPresent()) {
            return exact;
        }
        // Fallback: e.g. the instance is 1.21.11 but the dependency only declares 1.21.
        return bestMatch(versions, target, isMod, true);
    }

    private static Optional<RemoteAddon.Version> bestMatch(List<RemoteAddon.Version> versions, InstanceTarget target,
                                                           boolean isMod, boolean looseGameVersion) {
        Comparator<RemoteAddon.Version> newestFirst = Comparator
                .comparingInt((RemoteAddon.Version version) -> loaderPreference(version, target.loaders))
                .thenComparingInt(ModDependencyInstaller::versionTypePriority)
                .thenComparing(RemoteAddon.Version::datePublished,
                        Comparator.nullsLast(Comparator.reverseOrder()));

        return versions.stream()
                .filter(version -> version.file().filename() != null && version.file().url() != null)
                .filter(version -> target.gameVersion == null
                        || (looseGameVersion
                        ? hasCompatibleGameVersion(version.gameVersions(), target.gameVersion)
                        : containsGameVersion(version.gameVersions(), target.gameVersion)))
                .filter(version -> isLoaderCompatible(version, target.loaders, isMod))
                .min(newestFirst);
    }

    private static boolean containsGameVersion(@Nullable List<String> declared, String gameVersion) {
        return declared == null || declared.contains(gameVersion);
    }

    private static boolean hasCompatibleGameVersion(@Nullable List<String> declared, String gameVersion) {
        if (declared == null) {
            return false;
        }
        for (String version : declared) {
            if (version != null && isCompatibleGameVersion(version, gameVersion)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCompatibleGameVersion(String declared, String actual) {
        return declared.equals(actual)
                || actual.startsWith(declared + ".")
                || declared.startsWith(actual + ".");
    }

    /**
     * A file is usable only when the loader it was built for is one of the loaders of the instance.
     * When the download source does not report the loaders, the file name is used as a second hint.
     */
    private static boolean isLoaderCompatible(RemoteAddon.Version version, Set<ModLoaderType> targetLoaders,
                                              boolean isMod) {
        if (!isMod || targetLoaders.isEmpty()) {
            return true;
        }

        List<ModLoaderType> loaders = version.loaders();
        if (loaders != null && !loaders.isEmpty()) {
            return !Collections.disjoint(loaders, targetLoaders);
        }

        Optional<ModLoaderType> hint = loaderHintFromFileName(version.file().filename());
        if (hint.isPresent()) {
            boolean compatible = targetLoaders.contains(hint.get());
            if (!compatible) {
                LOG.info("Skip " + version.name() + " (" + version.file().filename()
                        + ") because it is built for " + hint.get() + ", not " + targetLoaders);
            }
            return compatible;
        }

        // Loader unknown, keep the file and let the sort order put it after the known matches.
        return true;
    }

    /**
     * Sort key: 0 for a file known to be built for the instance loader, 1 for a file whose loader is unknown.
     */
    private static int loaderPreference(RemoteAddon.Version version, Set<ModLoaderType> targetLoaders) {
        if (targetLoaders.isEmpty()) {
            return 0;
        }

        List<ModLoaderType> loaders = version.loaders();
        if (loaders != null && !loaders.isEmpty()) {
            return Collections.disjoint(loaders, targetLoaders) ? 2 : 0;
        }

        return 1;
    }

    private static Optional<ModLoaderType> loaderHintFromFileName(@Nullable String fileName) {
        if (fileName == null) {
            return Optional.empty();
        }

        String name = fileName.toLowerCase(Locale.ROOT);
        if (name.contains("neoforge") || name.contains("neo-forge")) {
            return Optional.of(ModLoaderType.NEO_FORGE);
        }
        if (name.contains("legacyfabric") || name.contains("legacy-fabric")) {
            return Optional.of(ModLoaderType.LEGACY_FABRIC);
        }
        if (name.contains("quilt")) {
            return Optional.of(ModLoaderType.QUILT);
        }
        if (name.contains("fabric")) {
            return Optional.of(ModLoaderType.FABRIC);
        }
        if (name.contains("cleanroom")) {
            return Optional.of(ModLoaderType.CLEANROOM);
        }
        if (name.contains("liteloader")) {
            return Optional.of(ModLoaderType.LITE_LOADER);
        }
        if (name.contains("forge")) {
            return Optional.of(ModLoaderType.FORGE);
        }
        return Optional.empty();
    }

    private static int versionTypePriority(RemoteAddon.Version version) {
        RemoteAddon.VersionType type = version.versionType();
        if (type == null) {
            return 3;
        }
        return switch (type) {
            case Release -> 0;
            case Beta -> 1;
            case Alpha -> 2;
        };
    }

    private static String dependencyKey(RemoteAddonRepository.Type type, @Nullable String id) {
        return type + ":" + (id != null ? id : "");
    }

    private record Pending(RemoteAddon.Version version, int depth) {
    }

    private record InstanceTarget(@Nullable String gameVersion, Set<ModLoaderType> loaders) {
        String gameVersionText() {
            return gameVersion != null ? gameVersion : i18n("mods.install_dependencies.unknown_version");
        }
    }

    private static final class Resolution {
        final List<RemoteAddon.Version> installable = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
    }

    /**
     * The files that are already in the {@code mods} directory, together with their SHA-1 computed on demand.
     */
    private static final class InstalledFiles {

        private record Entry(Path path, String fileName, boolean disabled) {
        }

        private record Hit(Entry entry, boolean exactFile) {
        }

        private final List<Entry> entries;
        private final Map<Path, String> sha1Cache = new HashMap<>();

        private InstalledFiles(List<Entry> entries) {
            this.entries = entries;
        }

        static InstalledFiles scan(Path directory) {
            List<Entry> entries = new ArrayList<>();
            if (Files.isDirectory(directory)) {
                try (Stream<Path> stream = Files.list(directory)) {
                    stream.filter(Files::isRegularFile)
                            .forEach(path -> {
                                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                                boolean disabled = name.endsWith(JAR_SUFFIX + DISABLED_SUFFIX);
                                if (!name.endsWith(JAR_SUFFIX) && !disabled) {
                                    return;
                                }
                                entries.add(new Entry(path, name, disabled));
                            });
                } catch (IOException e) {
                    LOG.warning("Failed to list files in " + directory, e);
                }
            }
            return new InstalledFiles(entries);
        }

        /**
         * @return the file that already provides this dependency, or {@code null} when it has to be downloaded.
         */
        @Nullable
        Hit find(String fileName, @Nullable Map<String, String> hashes, Set<String> projectHashes) {
            String name = fileName.toLowerCase(Locale.ROOT);

            for (Entry entry : entries) {
                if (entry.fileName.equals(name)) {
                    return new Hit(entry, true);
                }
            }

            String sha1 = hashes != null ? hashes.get("sha1") : null;
            if (sha1 != null) {
                String expected = sha1.toLowerCase(Locale.ROOT);
                for (Entry entry : entries) {
                    if (expected.equals(sha1Of(entry.path))) {
                        return new Hit(entry, true);
                    }
                }
            }

            if (!projectHashes.isEmpty()) {
                for (Entry entry : entries) {
                    if (projectHashes.contains(sha1Of(entry.path))) {
                        return new Hit(entry, false);
                    }
                }
            }

            return null;
        }

        @Nullable
        private String sha1Of(Path path) {
            if (sha1Cache.containsKey(path)) {
                return sha1Cache.get(path);
            }

            String sha1;
            try {
                sha1 = DigestUtils.digestToString("SHA-1", path);
            } catch (IOException e) {
                LOG.warning("Failed to hash " + path, e);
                sha1 = null;
            }
            sha1Cache.put(path, sha1);
            return sha1;
        }
    }
}
