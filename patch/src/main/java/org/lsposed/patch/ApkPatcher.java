package org.lsposed.patch;

import static org.lsposed.lspatch.share.Constants.CONFIG_ASSET_PATH;
import static org.lsposed.lspatch.share.Constants.EMBEDDED_MODULES_ASSET_PATH;
import static org.lsposed.lspatch.share.Constants.LOADER_DEX_ASSET_PATH;
import static org.lsposed.lspatch.share.Constants.ORIGINAL_APK_ASSET_PATH;
import static org.lsposed.lspatch.share.Constants.PROXY_APP_COMPONENT_FACTORY;

import com.android.tools.build.apkzlib.sign.SigningExtension;
import com.android.tools.build.apkzlib.sign.SigningOptions;
import com.android.tools.build.apkzlib.zip.AlignmentRules;
import com.android.tools.build.apkzlib.zip.NestedZip;
import com.android.tools.build.apkzlib.zip.StoredEntry;
import com.android.tools.build.apkzlib.zip.ZFile;
import com.android.tools.build.apkzlib.zip.ZFileOptions;
import com.google.gson.Gson;
import com.wind.meditor.core.ManifestEditor;
import com.wind.meditor.property.AttributeItem;
import com.wind.meditor.property.ModificationProperty;
import com.wind.meditor.utils.NodeValue;

import org.apache.commons.io.FilenameUtils;
import org.lsposed.lspatch.share.Constants;
import org.lsposed.lspatch.share.LSPConfig;
import org.lsposed.lspatch.share.PatchConfig;
import org.lsposed.patch.util.ApkSignatureHelper;
import org.lsposed.patch.util.Logger;
import org.lsposed.patch.util.ManifestParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Turns an apk into one that loads Xposed modules, without root.
 *
 * The principle is unchanged and is the whole of why LSPatch works: the original apk is kept intact
 * *inside* the patched one, as a nested zip at {@link Constants#ORIGINAL_APK_ASSET_PATH}, and the
 * manifest is rewritten so Android instantiates LSPatch's own {@code AppComponentFactory} first. At
 * runtime that factory brings the framework up and then hands control to the original application,
 * which loads from the nested copy and so sees itself byte-for-byte as its author shipped it. Every
 * entry that does not need changing is a *link* into the nested copy rather than a second copy of
 * the bytes, which is what keeps a patched apk close to the size of the original.
 *
 * What has changed is the shape. This used to be a single 150-line method on the command-line entry
 * point, which meant the engine could only be driven by parsing argv and every failure mode was a
 * {@code throw} from somewhere in the middle of it. The steps below are the same steps in the same
 * order, named, and reported through {@link Logger#stage} so a caller can show progress without
 * pattern-matching the log text.
 */
public final class ApkPatcher {

    private static final String ANDROID_MANIFEST_XML = "AndroidManifest.xml";

    /**
     * The native library is carried per-architecture under assets, never under {@code lib/}.
     *
     * An x86 device running an arm build through the native bridge would try to load the wrong one
     * out of {@code lib/} and crash; keeping them in assets means the loader picks by itself.
     */
    private static final Set<String> ARCHES = new LinkedHashSet<>(Arrays.asList(
            "armeabi-v7a",
            "arm64-v8a",
            "x86",
            "x86_64"
    ));

    /**
     * Page-aligned entries.
     *
     * The native library and the nested original both have to be mappable straight out of the apk,
     * which requires them to start on a 4 KiB boundary.
     */
    private static final ZFileOptions Z_FILE_OPTIONS = new ZFileOptions().setAlignmentRule(AlignmentRules.compose(
            AlignmentRules.constantForSuffix(".so", 4096),
            AlignmentRules.constantForSuffix(ORIGINAL_APK_ASSET_PATH, 4096)
    ));

    private final Logger logger;
    private final PatchSpec spec;

    public ApkPatcher(Logger logger, PatchSpec spec) {
        this.logger = logger;
        this.spec = spec;
        logger.verbose = spec.verbose();
    }

    /**
     * Patches every apk in the spec and returns what was written, in the order given.
     *
     * A multi-apk app is patched as a set because its splits have to agree: only the apk carrying
     * the application element is rewritten, and the rest are repacked so that they stay installable
     * alongside it.
     */
    public List<File> patch() throws IOException {
        List<File> outputs = new ArrayList<>();
        File outputDir = spec.outputDir();
        if (!outputDir.isDirectory() && !outputDir.mkdirs()) {
            throw new PatchException("Cannot create output directory " + outputDir);
        }
        int index = 0;
        int total = spec.apks().size();
        for (File apk : spec.apks()) {
            index++;
            File src = apk.getAbsoluteFile();
            File output = outputFileFor(src);
            if (output.exists() && !spec.forceOverwrite()) {
                throw new PatchException(output + " exists. Use --force to overwrite");
            }
            logger.i("Processing " + src + " -> " + output);
            patchOne(src, output, index, total);
            outputs.add(output);
        }
        return outputs;
    }

    /**
     * Where a given input apk's result is written.
     *
     * {@link Locale#ROOT}, not the default locale: the version code is formatted into the file name,
     * and a locale with its own digits (Arabic-Indic, say) would produce a name the manager's own
     * suffix matching no longer recognises.
     */
    private File outputFileFor(File src) {
        String name = String.format(
                Locale.ROOT,
                "%s-%d-lspatched.apk",
                FilenameUtils.getBaseName(src.getName()),
                LSPConfig.instance.VERSION_CODE);
        return new File(spec.outputDir(), name).getAbsoluteFile();
    }

    private void patchOne(File srcApkFile, File outputFile, int index, int total) throws IOException {
        if (!srcApkFile.exists()) {
            throw new PatchException("The source apk file does not exist: " + srcApkFile);
        }

        // The output is opened read-write and grown in place, so anything already there would be
        // treated as a partially built apk rather than replaced.
        if (outputFile.exists() && !outputFile.delete()) {
            throw new PatchException("Cannot overwrite " + outputFile);
        }

        logger.d("apk path: " + srcApkFile);
        logger.stage(Logger.Stage.READING, index, total);
        logger.i("Parsing original apk...");

        try (ZFile dstZFile = ZFile.openReadWrite(outputFile, Z_FILE_OPTIONS);
             NestedZip srcZFile = dstZFile.addNestedZip((ignore) -> ORIGINAL_APK_ASSET_PATH, srcApkFile, false)) {

            registerSigner(dstZFile, index, total);

            String originalSignature = readOriginalSignature(srcApkFile);
            ManifestParser.Pair manifest = readManifest(srcZFile);

            // Only the apk carrying the application element gets the loader; a split that declares
            // no appComponentFactory has nothing to hook, so it is repacked and left otherwise
            // untouched. A lone apk is never treated this way, whatever it is called.
            boolean isSplit = spec.apks().size() > 1
                    && srcApkFile.getName().startsWith("split_")
                    && manifest.appComponentFactory == null;
            if (isSplit) {
                packSplit(srcZFile, dstZFile, index, total);
                return;
            }

            PatchConfig config = new PatchConfig(
                    spec.useManager(),
                    spec.debuggable(),
                    spec.manifestOverrides().versionCode,
                    spec.sigBypassLevel(),
                    originalSignature,
                    manifest.appComponentFactory,
                    spec.injectDex(),
                    spec.manifestOverrides().addedPermissions.toArray(new String[0]),
                    spec.manifestOverrides().injectDocumentsProvider,
                    spec.useManager() ? spec.managerPackageName() : null);
            byte[] configBytes = new Gson().toJson(config).getBytes(StandardCharsets.UTF_8);

            rewriteManifest(srcZFile, dstZFile, configBytes, manifest.packageName, manifest.minSdkVersion, index, total);
            injectLoader(srcZFile, dstZFile, index, total);
            if (!spec.useManager()) {
                addLoaderPayload(dstZFile);
                embedModules(dstZFile, index, total);
            }
            linkRemainingEntries(srcZFile, dstZFile);

            dstZFile.realign();

            // Everything after this point -- deflation, realignment, v2 signing -- happens as the
            // zip is closed, and emits nothing for tens of seconds on a large app. A caller showing
            // progress has to know that this is where the silence is.
            logger.stage(Logger.Stage.WRITING, index, total);
            logger.i("Writing apk...");
        }
        logger.stage(Logger.Stage.DONE, index, total);
        logger.i("Done. Output APK: " + outputFile.getAbsolutePath());
    }

    /** Registers the v2 signer, so the finished zip is signed as it is closed. */
    private void registerSigner(ZFile dstZFile, int index, int total) throws IOException {
        logger.stage(Logger.Stage.SIGNING, index, total);
        logger.i(spec.keystore().isBuiltIn()
                ? "Register apk signer with default keystore..."
                : "Register apk signer with custom keystore...");
        try {
            KeyStore keyStore = spec.keystore().load(getClass().getClassLoader());
            KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) keyStore.getEntry(
                    spec.keystore().alias(),
                    new KeyStore.PasswordProtection(spec.keystore().aliasPassword().toCharArray()));
            if (entry == null) {
                throw new PatchException("No key named '" + spec.keystore().alias() + "' in the keystore");
            }
            new SigningExtension(SigningOptions.builder()
                    .setMinSdkVersion(28)
                    .setV2SigningEnabled(true)
                    .setCertificates((X509Certificate[]) entry.getCertificateChain())
                    .setKey(entry.getPrivateKey())
                    .build()).register(dstZFile);
        } catch (PatchException e) {
            throw e;
        } catch (Exception e) {
            throw new PatchException("Failed to register signer", e);
        }
    }

    /**
     * The original app's signature, recorded so the loader can report it back to the app.
     *
     * Only read when signature bypass is on: a patched apk is signed with LSPatch's key, and an app
     * that checks its own signature would otherwise notice. Nothing needs it when the bypass is off.
     */
    private String readOriginalSignature(File srcApkFile) throws IOException {
        if (spec.sigBypassLevel() <= Constants.SIGBYPASS_LV_DISABLE) return null;
        String signature = ApkSignatureHelper.getApkSignInfo(srcApkFile.getAbsolutePath());
        if (signature == null || signature.isEmpty()) {
            throw new PatchException("Could not read the original apk's signature");
        }
        logger.d("Original signature\n" + signature);
        return signature;
    }

    private ManifestParser.Pair readManifest(NestedZip srcZFile) throws IOException {
        StoredEntry manifestEntry = srcZFile.get(ANDROID_MANIFEST_XML);
        if (manifestEntry == null) {
            throw new PatchException("Provided file is not a valid apk");
        }
        try (InputStream is = manifestEntry.open()) {
            ManifestParser.Pair pair = ManifestParser.parseManifestFile(is);
            if (pair == null) {
                throw new PatchException("Failed to parse AndroidManifest.xml");
            }
            logger.d("original appComponentFactory class: " + pair.appComponentFactory);
            logger.d("original minSdkVersion: " + pair.minSdkVersion);
            return pair;
        }
    }

    /** Repacks a split unchanged, dropping only the signatures that no longer apply to it. */
    private void packSplit(NestedZip srcZFile, ZFile dstZFile, int index, int total) throws IOException {
        logger.stage(Logger.Stage.PACKING_SPLIT, index, total);
        logger.i("Packing split apk...");
        for (StoredEntry entry : srcZFile.entries()) {
            String name = entry.getCentralDirectoryHeader().getName();
            if (dstZFile.get(name) != null) continue;
            if (isSignatureEntry(name)) continue;
            srcZFile.addFileLink(name, name);
        }
    }

    /**
     * Points the manifest at LSPatch's component factory and records the patch config in it.
     *
     * The config is written twice on purpose: into the manifest as metadata, where the *manager* can
     * read it from an installed app without opening the apk, and into an asset, where the *loader*
     * reads it at startup without a package manager.
     */
    private void rewriteManifest(
            NestedZip srcZFile, ZFile dstZFile, byte[] configBytes, String packageName, int minSdkVersion,
            int index, int total)
            throws IOException {
        logger.stage(Logger.Stage.REWRITING, index, total);
        logger.i("Patching apk...");
        String metadata = Base64.getEncoder().encodeToString(configBytes);
        StoredEntry manifestEntry = Objects.requireNonNull(srcZFile.get(ANDROID_MANIFEST_XML));
        try (InputStream is = new ByteArrayInputStream(modifyManifestFile(manifestEntry.open(), metadata, packageName, minSdkVersion))) {
            dstZFile.add(ANDROID_MANIFEST_XML, is);
        } catch (IOException e) {
            throw new PatchException("Error when modifying manifest", e);
        }

        logger.i("Adding config...");
        try (InputStream is = new ByteArrayInputStream(configBytes)) {
            dstZFile.add(CONFIG_ASSET_PATH, is);
        } catch (IOException e) {
            throw new PatchException("Error when saving config", e);
        }
    }

    /**
     * Adds the metaloader dex -- the small stub Android runs first.
     *
     * Normally it becomes {@code classes.dex} and the original's dex files are left in the nested
     * copy, untouched. With {@code injectDex} the original's dex files are kept in place and the
     * stub is appended after the last of them instead, because an app whose isolated processes load
     * their own dex (a browser's renderer, say) needs them present in the outer apk.
     */
    private void injectLoader(NestedZip srcZFile, ZFile dstZFile, int index, int total) throws IOException {
        logger.stage(Logger.Stage.INJECTING, index, total);
        logger.i("Adding metaloader dex...");
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(Constants.META_LOADER_DEX_ASSET_PATH)) {
            if (is == null) throw new PatchException("The metaloader dex is missing from this build");
            if (!spec.injectDex()) {
                dstZFile.add("classes.dex", is);
            } else {
                int dexCount = 1;
                for (StoredEntry entry : srcZFile.entries()) {
                    if (isDexEntry(entry.getCentralDirectoryHeader().getName())) dexCount++;
                }
                dstZFile.add("classes" + dexCount + ".dex", is);
            }
        } catch (PatchException e) {
            throw e;
        } catch (IOException e) {
            throw new PatchException("Error when adding dex", e);
        }
    }

    /** The framework itself: the loader dex and its native library, for a manager-free patch. */
    private void addLoaderPayload(ZFile dstZFile) throws IOException {
        logger.i("Adding loader dex...");
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(LOADER_DEX_ASSET_PATH)) {
            if (is == null) throw new PatchException("The loader dex is missing from this build");
            dstZFile.add(LOADER_DEX_ASSET_PATH, is);
        } catch (PatchException e) {
            throw e;
        } catch (IOException e) {
            throw new PatchException("Error when adding assets", e);
        }

        logger.i("Adding native lib...");
        for (String arch : ARCHES) {
            String entryName = "assets/lspatch/so/" + arch + "/liblspatch.so";
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(entryName)) {
                if (is == null) throw new PatchException("Missing native library for " + arch);
                // Stored, not deflated: it is mapped straight out of the apk at runtime.
                dstZFile.add(entryName, is, false);
            } catch (PatchException e) {
                throw e;
            } catch (IOException e) {
                throw new PatchException("Error when adding native lib for " + arch, e);
            }
            logger.d("added " + entryName);
        }
    }

    /**
     * Bakes each module apk in under its own package name.
     *
     * The entry name is the module's package, which is what the loader enumerates by -- so a module
     * appearing twice cannot be represented, and the first one wins. A module that cannot be read is
     * reported and skipped rather than failing the patch, because one bad module apk should not cost
     * the user the whole build.
     */
    private void embedModules(ZFile dstZFile, int index, int total) {
        if (spec.modules().isEmpty()) return;
        logger.stage(Logger.Stage.EMBEDDING, index, total);
        logger.i("Embedding modules...");
        Set<String> embedded = new LinkedHashSet<>();
        for (File module : spec.modules()) {
            try (ZFile apk = ZFile.openReadOnly(module);
                 InputStream fileIs = new java.io.FileInputStream(module);
                 InputStream xmlIs = Objects.requireNonNull(apk.get(ANDROID_MANIFEST_XML)).open()) {
                ManifestParser.Pair manifest = Objects.requireNonNull(ManifestParser.parseManifestFile(xmlIs));
                String packageName = manifest.packageName;
                if (!embedded.add(packageName)) {
                    logger.e("  - " + packageName + " given more than once, keeping the first");
                    continue;
                }
                logger.i("  - " + packageName);
                dstZFile.add(EMBEDDED_MODULES_ASSET_PATH + packageName + ".apk", fileIs);
            } catch (NullPointerException | IOException e) {
                logger.e(module + " does not exist or is not a valid apk file.");
            }
        }
    }

    /**
     * Links every remaining entry to the nested original instead of copying its bytes.
     *
     * This is what keeps a patched apk from being twice the size of the original. The exclusions are
     * the entries the patch has replaced or invalidated: the rewritten manifest, the original's
     * signatures, and -- unless the dex was injected -- the original's dex files, which are loaded
     * from the nested copy rather than the outer apk.
     */
    private void linkRemainingEntries(NestedZip srcZFile, ZFile dstZFile) throws IOException {
        logger.d("Creating nested apk link...");
        for (StoredEntry entry : srcZFile.entries()) {
            String name = entry.getCentralDirectoryHeader().getName();
            if (dstZFile.get(name) != null) continue;
            if (!spec.injectDex() && isDexEntry(name)) continue;
            if (name.equals(ANDROID_MANIFEST_XML)) continue;
            if (isSignatureEntry(name)) continue;
            srcZFile.addFileLink(name, name);
        }
    }

    private static boolean isDexEntry(String name) {
        return name.startsWith("classes") && name.endsWith(".dex");
    }

    private static boolean isSignatureEntry(String name) {
        return name.startsWith("META-INF")
                && (name.endsWith(".SF") || name.endsWith(".MF") || name.endsWith(".RSA"));
    }

    private byte[] modifyManifestFile(InputStream is, String metadata, String packageName, int minSdkVersion)
            throws IOException {
        ModificationProperty property = new ModificationProperty();

        // The loader is built against 28; an app declaring less would be refused the APIs it uses.
        if (minSdkVersion < 28) {
            property.addUsesSdkAttribute(new AttributeItem(NodeValue.UsesSDK.MIN_SDK_VERSION, 28));
        }
        property.addApplicationAttribute(new AttributeItem(NodeValue.Application.DEBUGGABLE, spec.debuggable()));
        property.addApplicationAttribute(new AttributeItem("appComponentFactory", PROXY_APP_COMPONENT_FACTORY));
        property.addMetaData(new ModificationProperty.MetaData("lspatch", metadata));
        applyManifestOverrides(property, packageName);
        // TODO: replace query_all with queries -> manager
        if (spec.useManager()) {
            property.addUsesPermission("android.permission.QUERY_ALL_PACKAGES");
        }

        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try (InputStream in = is) {
            new ManifestEditor(in, os, property).processManifest();
        }
        os.flush();
        return os.toByteArray();
    }

    /**
     * Applies the caller's chosen manifest overrides on top of the loader's own rewrites.
     *
     * Each is set only when the caller asked for it, so an app the user did not want changed keeps
     * every attribute exactly as its author wrote it. A version-code override decides what the
     * installer may replace the app with; a label override replaces the launcher name; a target-SDK
     * override changes the compatibility behaviours the platform applies; the two booleans flip
     * install-time and network policy an app otherwise fixes against a module's needs.
     */
    private void applyManifestOverrides(ModificationProperty property, String packageName) {
        ManifestOverrides o = spec.manifestOverrides();
        if (o.isEmpty()) return;
        if (o.versionCode != null) {
            logger.i("Override versionCode: " + o.versionCode);
            property.addManifestAttribute(new AttributeItem(NodeValue.Manifest.VERSION_CODE, o.versionCode));
        }
        if (o.label != null) {
            logger.i("Override label: " + o.label);
            property.addApplicationAttribute(new AttributeItem(NodeValue.Application.LABEL, o.label));
        }
        if (o.targetSdkVersion != null) {
            logger.i("Override targetSdkVersion: " + o.targetSdkVersion);
            property.addUsesSdkAttribute(new AttributeItem(NodeValue.UsesSDK.TARGET_SDK_VERSION, o.targetSdkVersion));
        }
        if (o.extractNativeLibs != null) {
            logger.i("Override extractNativeLibs: " + o.extractNativeLibs);
            property.addApplicationAttribute(new AttributeItem(NodeValue.Application.EXTRACTNATIVELIBS, o.extractNativeLibs));
        }
        if (o.usesCleartextTraffic != null) {
            logger.i("Override usesCleartextTraffic: " + o.usesCleartextTraffic);
            property.addApplicationAttribute(new AttributeItem("usesCleartextTraffic", o.usesCleartextTraffic));
        }
        // The editor keys uses-permission by name and drops a name the manifest already carries, so
        // handing it one the app declares is harmless -- the added set need not be filtered first.
        for (String permission : o.addedPermissions) {
            logger.i("Add permission: " + permission);
            property.addUsesPermission(permission);
        }
        if (o.injectDocumentsProvider) {
            addDocumentsProvider(property, packageName);
        }
    }

    /**
     * Declares the loader's {@code DocumentsProvider} so the app's private data shows up in the
     * system file picker.
     *
     * The authority is per-package so two patched apps never collide. {@code MANAGE_DOCUMENTS} is the
     * platform-signature permission the Documents UI holds, so gating the provider behind it means
     * only the system can bind it -- access still flows through the user granting a tree, not through
     * any app reaching the authority directly. {@code exported} and {@code grantUriPermissions} are
     * passed as real booleans; a string {@code "true"} would be read back as false and quietly
     * un-export the provider.
     */
    private void addDocumentsProvider(ModificationProperty property, String packageName) {
        String authority = packageName + Constants.DOCUMENTS_PROVIDER_AUTHORITY_SUFFIX;
        logger.i("Add documents provider: " + authority);
        List<AttributeItem> attributes = new ArrayList<>();
        attributes.add(new AttributeItem(NodeValue.Application.NAME, Constants.DOCUMENTS_PROVIDER_CLASS));
        attributes.add(new AttributeItem(NodeValue.Application.Provider.AUTHORITIES, authority));
        attributes.add(new AttributeItem("exported", Boolean.TRUE));
        attributes.add(new AttributeItem("grantUriPermissions", Boolean.TRUE));
        attributes.add(new AttributeItem(NodeValue.Application.Component.PERMISSION, "android.permission.MANAGE_DOCUMENTS"));
        property.addProvider(attributes, "android.content.action.DOCUMENTS_PROVIDER");
    }
}
