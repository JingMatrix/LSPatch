package org.lsposed.patch;

import org.lsposed.lspatch.share.Constants;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One patch job, fully described.
 *
 * The patcher's inputs used to live as mutable fields on the command-line front end, annotated for
 * the argument parser. That made the CLI the only way to express a patch: the Android manager, which
 * has every value in hand as a typed object, had to render them back into a {@code String[]} of
 * flags for the parser to take apart again. Worse, the parser answered an invalid combination by
 * setting a {@code help} flag, after which the patch loop iterated an empty list and returned
 * normally -- so a malformed request reported success and produced nothing.
 *
 * A spec is checked once, when it is built, and cannot be built at all if it is contradictory.
 */
public final class PatchSpec {

    private final List<File> apks;
    private final File outputDir;
    private final boolean useManager;
    private final boolean debuggable;
    private final int sigBypassLevel;
    private final boolean injectDex;
    private final boolean forceOverwrite;
    private final boolean verbose;
    private final List<File> modules;
    private final KeystoreSpec keystore;
    private final ManifestOverrides manifestOverrides;
    private final String managerPackageName;

    private PatchSpec(Builder b) {
        this.apks = Collections.unmodifiableList(new ArrayList<>(b.apks));
        this.outputDir = b.outputDir;
        this.useManager = b.useManager;
        this.debuggable = b.debuggable;
        this.sigBypassLevel = b.sigBypassLevel;
        this.injectDex = b.injectDex;
        this.forceOverwrite = b.forceOverwrite;
        this.verbose = b.verbose;
        this.modules = Collections.unmodifiableList(new ArrayList<>(b.modules));
        this.keystore = b.keystore == null ? KeystoreSpec.builtIn() : b.keystore;
        this.manifestOverrides = b.manifestOverrides == null ? ManifestOverrides.none() : b.manifestOverrides;
        this.managerPackageName = b.managerPackageName;
    }

    public List<File> apks() {
        return apks;
    }

    public File outputDir() {
        return outputDir;
    }

    /**
     * Whether the installed manager serves this app's modules at runtime.
     *
     * Mutually exclusive with {@link #modules()}: a manager-backed app resolves its modules live, so
     * baking any into the apk would produce a set nothing ever reads.
     */
    public boolean useManager() {
        return useManager;
    }

    public boolean debuggable() {
        return debuggable;
    }

    public int sigBypassLevel() {
        return sigBypassLevel;
    }

    public boolean injectDex() {
        return injectDex;
    }

    public boolean forceOverwrite() {
        return forceOverwrite;
    }

    public boolean verbose() {
        return verbose;
    }

    /** The module apks to bake in. Always empty when {@link #useManager()}. */
    public List<File> modules() {
        return modules;
    }

    public KeystoreSpec keystore() {
        return keystore;
    }

    /** Optional manifest attribute overrides; never null (empty when nothing is set). */
    public ManifestOverrides manifestOverrides() {
        return manifestOverrides;
    }

    /**
     * The manager package a manager-mode app should bind to at runtime, or null to accept the
     * built-in default. Lets the manager record its own -- possibly cloaked -- package name so an
     * app patched now keeps reaching the manager after it is reinstalled under a different id.
     */
    public String managerPackageName() {
        return managerPackageName;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private final List<File> apks = new ArrayList<>();
        private final List<File> modules = new ArrayList<>();
        private File outputDir = new File(".");
        private boolean useManager;
        private boolean debuggable;
        private int sigBypassLevel = Constants.SIGBYPASS_LV_DISABLE;
        private boolean injectDex;
        private boolean forceOverwrite;
        private boolean verbose;
        private KeystoreSpec keystore;
        private ManifestOverrides manifestOverrides;
        private String managerPackageName;

        public Builder apk(File apk) {
            this.apks.add(apk);
            return this;
        }

        public Builder apks(List<File> apks) {
            this.apks.addAll(apks);
            return this;
        }

        public Builder outputDir(File outputDir) {
            this.outputDir = outputDir;
            return this;
        }

        public Builder useManager(boolean useManager) {
            this.useManager = useManager;
            return this;
        }

        public Builder debuggable(boolean debuggable) {
            this.debuggable = debuggable;
            return this;
        }

        public Builder sigBypassLevel(int sigBypassLevel) {
            this.sigBypassLevel = sigBypassLevel;
            return this;
        }

        public Builder injectDex(boolean injectDex) {
            this.injectDex = injectDex;
            return this;
        }

        public Builder forceOverwrite(boolean forceOverwrite) {
            this.forceOverwrite = forceOverwrite;
            return this;
        }

        public Builder verbose(boolean verbose) {
            this.verbose = verbose;
            return this;
        }

        public Builder module(File module) {
            this.modules.add(module);
            return this;
        }

        public Builder modules(List<File> modules) {
            this.modules.addAll(modules);
            return this;
        }

        public Builder keystore(KeystoreSpec keystore) {
            this.keystore = keystore;
            return this;
        }

        public Builder manifestOverrides(ManifestOverrides manifestOverrides) {
            this.manifestOverrides = manifestOverrides;
            return this;
        }

        /** The manager package a manager-mode app binds to; null accepts the built-in default. */
        public Builder managerPackageName(String managerPackageName) {
            this.managerPackageName = managerPackageName;
            return this;
        }

        /**
         * @throws PatchException if the request contradicts itself -- refused here, where the caller
         *                        can still be told why, rather than half-executed later.
         */
        public PatchSpec build() throws PatchException {
            if (apks.isEmpty()) {
                throw new PatchException("No apk to patch");
            }
            if (useManager && !modules.isEmpty()) {
                throw new PatchException("Modules cannot be embedded into a manager-backed patch");
            }
            if (sigBypassLevel < Constants.SIGBYPASS_LV_DISABLE || sigBypassLevel >= Constants.SIGBYPASS_LV_MAX) {
                throw new PatchException("Signature bypass level must be between "
                        + Constants.SIGBYPASS_LV_DISABLE + " and " + (Constants.SIGBYPASS_LV_MAX - 1));
            }
            if (outputDir == null) {
                throw new PatchException("No output directory");
            }
            return new PatchSpec(this);
        }
    }
}
