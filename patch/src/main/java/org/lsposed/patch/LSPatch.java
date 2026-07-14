package org.lsposed.patch;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;

import org.lsposed.patch.util.JavaLogger;
import org.lsposed.patch.util.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The command-line front end.
 *
 * Its whole job is to turn argv into a {@link PatchSpec} and hand it to {@link ApkPatcher}. It used
 * to be the engine as well -- the parameter fields the argument parser wrote into *were* the
 * patcher's inputs -- which meant the only way to run a patch was to render its settings into
 * command-line flags and parse them back, even from the Android manager, which had them all as
 * typed values already. It also meant an invalid invocation was answered by setting a {@code help}
 * flag and then patching nothing at all, successfully.
 */
public class LSPatch {

    @Parameter(description = "apks")
    private List<String> apkPaths = new ArrayList<>();

    @Parameter(names = {"-h", "--help"}, help = true, order = 0, description = "Print this message")
    private boolean help = false;

    @Parameter(names = {"-o", "--output"}, description = "Output directory")
    private String outputPath = ".";

    @Parameter(names = {"-f", "--force"}, description = "Force overwrite exists output file")
    private boolean forceOverwrite = false;

    @Parameter(names = {"-d", "--debuggable"}, description = "Set app to be debuggable")
    private boolean debuggableFlag = false;

    @Parameter(names = {"-l", "--sigbypasslv"}, description = "Signature bypass level. 0 (disable), 1 (pm), 2 (pm+openat). default 0")
    private int sigbypassLevel = 0;

    @Parameter(names = {"--injectdex"}, description = "Inject directly the loder dex file into the original application package")
    private boolean injectDex = false;

    @Parameter(names = {"-k", "--keystore"}, arity = 4, description = "Set custom signature keystore. Followed by 4 arguments: keystore path, keystore password, keystore alias, keystore alias password")
    private List<String> keystoreArgs = Arrays.asList(null, "123456", "key0", "123456");

    @Parameter(names = {"--manager"}, description = "Use manager (Cannot work with embedding modules)")
    private boolean useManager = false;

    @Parameter(names = {"--manager-package"}, description = "The manager package a manager-mode app binds to at runtime (defaults to the built-in manager). Set this to match a manager reinstalled under a custom package name.")
    private String managerPackageName = null;

    @Parameter(names = {"--version-code"}, description = "Set the patched app's versionCode to this value (e.g. 1, so a later build can be installed over it)")
    private Integer versionCodeOverride = null;

    @Parameter(names = {"-v", "--verbose"}, description = "Verbose output")
    private boolean verbose = false;

    @Parameter(names = {"-m", "--embed"}, description = "Embed provided modules to apk")
    private List<String> modules = new ArrayList<>();

    @Parameter(names = {"--name"}, description = "Override the patched app's launcher label")
    private String labelOverride = null;

    @Parameter(names = {"--target-sdk"}, description = "Override the patched app's targetSdkVersion")
    private Integer targetSdkOverride = null;

    @Parameter(names = {"--extract-libs"}, description = "Force android:extractNativeLibs=true")
    private boolean extractNativeLibs = false;

    @Parameter(names = {"--cleartext"}, description = "Force android:usesCleartextTraffic=true")
    private boolean usesCleartextTraffic = false;

    @Parameter(names = {"--add-permission"}, description = "Add a <uses-permission> to the manifest (repeatable). A bare name is prefixed with android.permission.")
    private List<String> addedPermissions = new ArrayList<>();

    @Parameter(names = {"--documents-provider"}, description = "Inject a DocumentsProvider exposing the app's private data to the system file picker")
    private boolean injectDocumentsProvider = false;

    private final JCommander jCommander;

    public LSPatch(String... args) {
        jCommander = JCommander.newBuilder().addObject(this).build();
        jCommander.parse(args);
    }

    public static void main(String... args) {
        Logger logger = new JavaLogger();
        LSPatch cli;
        try {
            cli = new LSPatch(args);
        } catch (ParameterException e) {
            logger.e(e.getMessage());
            e.getJCommander().usage();
            System.exit(2);
            return;
        }
        if (cli.help) {
            cli.jCommander.usage();
            return;
        }
        try {
            new ApkPatcher(logger, cli.toSpec()).patch();
        } catch (PatchException e) {
            // The message is the whole of what a user can act on; the stack trace is noise unless
            // something unexpected went wrong underneath it.
            logger.e(e.getMessage());
            if (e.getCause() != null) e.getCause().printStackTrace(System.err);
            System.exit(1);
        } catch (IOException e) {
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    /** Translates the parsed arguments into a spec, which validates them. */
    public PatchSpec toSpec() throws PatchException {
        KeystoreSpec keystore = keystoreArgs.get(0) == null
                ? KeystoreSpec.builtIn()
                : KeystoreSpec.of(
                        new File(keystoreArgs.get(0)),
                        keystoreArgs.get(1),
                        keystoreArgs.get(2),
                        keystoreArgs.get(3));
        ManifestOverrides overrides = ManifestOverrides.builder()
                .versionCode(versionCodeOverride)
                .label(labelOverride)
                .targetSdkVersion(targetSdkOverride)
                .extractNativeLibs(extractNativeLibs ? Boolean.TRUE : null)
                .usesCleartextTraffic(usesCleartextTraffic ? Boolean.TRUE : null)
                .permissions(addedPermissions.stream()
                        .map(ManifestOverrides::normalizePermission)
                        .collect(Collectors.toList()))
                .injectDocumentsProvider(injectDocumentsProvider)
                .build();
        return PatchSpec.builder()
                .apks(apkPaths.stream().map(File::new).collect(Collectors.toList()))
                .outputDir(new File(outputPath))
                .useManager(useManager)
                .debuggable(debuggableFlag)
                .sigBypassLevel(sigbypassLevel)
                .injectDex(injectDex)
                .forceOverwrite(forceOverwrite)
                .verbose(verbose)
                .modules(modules.stream().map(File::new).collect(Collectors.toList()))
                .keystore(keystore)
                .manifestOverrides(overrides)
                .managerPackageName(managerPackageName)
                .build();
    }
}
