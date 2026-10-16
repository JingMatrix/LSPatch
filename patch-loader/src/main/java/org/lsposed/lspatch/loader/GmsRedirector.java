package org.lsposed.lspatch.loader;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.util.Log;

import org.lsposed.lspatch.loader.util.XLog;
import org.lsposed.lspatch.share.Constants;

import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Points a patched app's Google Play services traffic at an installed MicroG.
 *
 * A patched app is signed with LSPatch's key, so Google Play services rejects it wherever it checks the caller's
 * certificate -- most visibly at Google sign-in. MicroG answers the same requests under its own package, so this
 * rewrites the package, component, provider authority and action of every GMS-bound intent or provider call to the
 * MicroG package found on the device. Which certificate MicroG then reports for the app is decided by the manifest
 * metadata the patcher wrote, not here.
 *
 * Community MicroG builds such as ReVanced GmsCore rename the package to {@code <vendor>.android.gms} and every action
 * along with it, so actions are rewritten too. An intent the app addresses to itself is never rewritten: the Google
 * sign-in library opens its own {@code SignInHubActivity} with {@code com.google.android.gms.auth.GOOGLE_SIGN_IN}, and
 * that activity finishes on any other action.
 */
public class GmsRedirector {
    private static final String TAG = "LSPatch-GmsRedirect";
    private static final String REAL_GMS = Constants.REAL_GMS_PACKAGE_NAME;

    private static final String[] MICROG_PACKAGES = {
            Constants.MICROG_RE_PACKAGE_NAME,  // ReVanced GmsCore
            "org.microg.gms",                  // upstream microG
    };

    private static String targetGms = null;
    private static String selfPackage = null;
    private static String originalSignature;

    public static void activate(Context context, String origSig) {
        originalSignature = origSig;
        selfPackage = context.getPackageName();

        targetGms = findInstalledMicroG(context);
        if (targetGms == null) {
            Log.w(TAG, "No MicroG/GmsCore found! GMS redirect disabled.");
            return;
        }

        Log.i(TAG, "Activating GMS redirect: " + REAL_GMS + " -> " + targetGms);
        setupC2dmRedirects();

        hookIntentSetPackage();
        hookIntentSetAction();
        hookIntentGetAction();
        hookIntentSetComponent();
        hookIntentSetClass();
        hookIntentResolve();
        hookActivityStart();
        hookContentResolverAcquire();
        hookPackageManagerGetPackageInfo(context);

        Log.i(TAG, "GMS redirect hooks installed");
    }

    private static String findInstalledMicroG(Context context) {
        PackageManager pm = context.getPackageManager();
        for (String pkg : MICROG_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0);
                return pkg;
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
        return null;
    }

    static void setTargetGmsForTest(String target, String self) {
        targetGms = target;
        selfPackage = self;
        setupC2dmRedirects();
    }

    static String redirectPackage(String pkg) {
        if (REAL_GMS.equals(pkg) || "com.google.android.gsf".equals(pkg)) {
            return targetGms;
        }
        return null;
    }

    // MicroG does not provide Dynamite/Chimera modules. Redirecting chimera causes
    // provider Authority mismatch and Uri disagreements. Left untouched, real GMS answers it.
    private static final String CHIMERA_AUTHORITY = REAL_GMS + ".chimera";

    static String redirectAuthority(String authority) {
        if (authority == null) return null;
        if (CHIMERA_AUTHORITY.equals(authority)) return null;
        if (authority.startsWith(REAL_GMS + ".")) {
            return targetGms + authority.substring(REAL_GMS.length());
        }
        if (authority.equals(REAL_GMS)) {
            return targetGms;
        }
        if (authority.startsWith("com.google.android.gsf")) {
            return authority.replace("com.google.android.gsf", targetGms);
        }
        return null;
    }

    private static final String CHOOSE_ACCOUNT_ACTION =
            "com.google.android.gms.common.account.CHOOSE_ACCOUNT";
    private static final ComponentName SYSTEM_ACCOUNT_PICKER;
    static {
        ComponentName picker = null;
        try {
            picker = ComponentName.unflattenFromString("android/.accounts.ChooseTypeAndAccountActivity");
        } catch (Throwable ignored) {}
        SYSTEM_ACCOUNT_PICKER = picker;
    }

    // Standard Google C2DM actions that need vendor redirection if targetGms has a custom namespace.
    private static final Map<String, String> c2dmRedirectMap = new HashMap<>();
    private static final String[] C2DM_STANDARD_ACTIONS = {
            "com.google.android.c2dm.intent.REGISTER",
            "com.google.android.c2dm.intent.RECEIVE",
            "com.google.android.c2dm.intent.UNREGISTER",
            "com.google.android.c2dm.intent.REGISTRATION",
    };
    private static final String GMS_ACTION_PREFIX = "com.google.android.gms.";

    private static void setupC2dmRedirects() {
        c2dmRedirectMap.clear();
        if (hasVendorNamespace()) {
            String vendorC2dmPrefix = targetGms.substring(0, targetGms.length() - ".android.gms".length()) + ".android.c2dm";
            for (String standard : C2DM_STANDARD_ACTIONS) {
                c2dmRedirectMap.put(standard, vendorC2dmPrefix + standard.substring("com.google.android.c2dm".length()));
            }
            Log.i(TAG, "C2DM redirect prefix derived: " + vendorC2dmPrefix);
        } else {
            Log.i(TAG, "C2DM redirect disabled for targetGms: " + targetGms);
        }
    }

    /** Whether the MicroG build renamed {@code com.google.android.gms} to {@code <vendor>.android.gms}, actions included. */
    private static boolean hasVendorNamespace() {
        return targetGms != null && !REAL_GMS.equals(targetGms) && targetGms.endsWith(".android.gms");
    }

    static boolean isChooseAccountAction(String action) {
        return CHOOSE_ACCOUNT_ACTION.equals(action)
                || (targetGms != null && (targetGms + ".common.account.CHOOSE_ACCOUNT").equals(action));
    }

    private static void routeChooseAccountToSystem(Intent intent) {
        if (intent == null || SYSTEM_ACCOUNT_PICKER == null || !isChooseAccountAction(intent.getAction())) {
            return;
        }
        if (!SYSTEM_ACCOUNT_PICKER.equals(intent.getComponent()) || intent.getPackage() != null) {
            XLog.d(TAG, "Routing CHOOSE_ACCOUNT directly to the system account picker");
            intent.setComponent(SYSTEM_ACCOUNT_PICKER);
            intent.setPackage(null);
        }
    }

    static String redirectAction(String action) {
        if (action == null) return null;
        if (isChooseAccountAction(action)) return null;
        String redirected = c2dmRedirectMap.get(action);
        if (redirected != null) return redirected;
        if (hasVendorNamespace() && action.startsWith(GMS_ACTION_PREFIX)) {
            return targetGms + "." + action.substring(GMS_ACTION_PREFIX.length());
        }
        return null;
    }

    /** The action {@link #redirectAction} rewrote into {@code action}, or null if it is not a rewritten one. */
    static String originalAction(String action) {
        if (action == null) return null;
        for (Map.Entry<String, String> entry : c2dmRedirectMap.entrySet()) {
            if (entry.getValue().equals(action)) return entry.getKey();
        }
        if (hasVendorNamespace() && action.startsWith(targetGms + ".") && !isChooseAccountAction(action)) {
            return GMS_ACTION_PREFIX + action.substring(targetGms.length() + 1);
        }
        return null;
    }

    static boolean isSelfTargeted(String pkg, ComponentName component) {
        if (selfPackage == null) return false;
        return selfPackage.equals(pkg) || (component != null && selfPackage.equals(component.getPackageName()));
    }

    /**
     * Undoes the action rewrite on an intent addressed to the app itself.
     *
     * The action is usually set before the target -- {@code new Intent(action).setClass(...)} -- so it has already been
     * rewritten by the time the intent turns out to be the app's own. The field is written directly because
     * {@code setAction} is hooked and would rewrite it again.
     */
    private static void restoreSelfTargetedAction(Intent intent) {
        if (intent == null || !isSelfTargeted(intent.getPackage(), intent.getComponent())) return;
        String action = (String) XposedHelpers.getObjectField(intent, "mAction");
        String original = originalAction(action);
        if (original != null) {
            XLog.d(TAG, "Keeping app-internal action: " + action + " -> " + original);
            XposedHelpers.setObjectField(intent, "mAction", original);
        }
    }

    private static void hookIntentSetPackage() {
        try {
            XposedBridge.hookAllMethods(Intent.class, "setPackage", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Intent intent = (Intent) param.thisObject;
                    // Defense for the specific ordering of "setAction(CHOOSE_ACCOUNT)" before "setPackage(...)".
                    // The reverse sequence ("setPackage" before "setAction") is guarded by hookIntentSetAction's
                    // afterHookedMethod and final egress in hookActivityStart.
                    if (isChooseAccountAction(intent.getAction())) {
                        param.args[0] = null;
                        return;
                    }
                    String pkg = (String) param.args[0];
                    String redirected = redirectPackage(pkg);
                    if (redirected != null) param.args[0] = redirected;
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Intent intent = (Intent) param.thisObject;
                    restoreSelfTargetedAction(intent);
                    routeChooseAccountToSystem(intent);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Intent.setPackage", t);
        }
    }

    private static void hookIntentSetAction() {
        try {
            XposedBridge.hookAllMethods(Intent.class, "setAction", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Intent intent = (Intent) param.thisObject;
                    if (isSelfTargeted(intent.getPackage(), intent.getComponent())) return;
                    String action = (String) param.args[0];
                    String redirected = redirectAction(action);
                    if (redirected != null) {
                        XLog.d(TAG, "Redirecting action in setAction: " + action + " -> " + redirected);
                        param.args[0] = redirected;
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    routeChooseAccountToSystem((Intent) param.thisObject);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Intent.setAction", t);
        }
    }

    private static void hookIntentGetAction() {
        try {
            XposedBridge.hookAllMethods(Intent.class, "getAction", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String action = (String) param.getResult();
                    if (action != null && targetGms != null) {
                        for (Map.Entry<String, String> entry : c2dmRedirectMap.entrySet()) {
                            if (entry.getValue().equals(action)) {
                                XLog.d(TAG, "Restoring original action for getAction: " + action + " -> " + entry.getKey());
                                param.setResult(entry.getKey());
                                return;
                            }
                        }
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Intent.getAction", t);
        }
    }

    private static void hookIntentSetComponent() {
        try {
            XposedBridge.hookAllMethods(Intent.class, "setComponent", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    ComponentName cn = (ComponentName) param.args[0];
                    if (cn != null) {
                        String redirected = redirectPackage(cn.getPackageName());
                        if (redirected != null) {
                            param.args[0] = new ComponentName(redirected, cn.getClassName());
                        }
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Intent intent = (Intent) param.thisObject;
                    restoreSelfTargetedAction(intent);
                    routeChooseAccountToSystem(intent);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Intent.setComponent", t);
        }
    }

    /**
     * {@code setClass} and {@code setClassName} assign the component without going through {@code setComponent}, and
     * {@code readFromParcel} -- how an activity's own launch intent arrives -- reads the action through
     * {@code setAction} before it reads the component.
     */
    private static void hookIntentSetClass() {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                restoreSelfTargetedAction((Intent) param.thisObject);
            }
        };
        for (String method : new String[]{"setClass", "setClassName", "readFromParcel"}) {
            try {
                XposedBridge.hookAllMethods(Intent.class, method, hook);
            } catch (Throwable t) {
                Log.e(TAG, "Failed to hook Intent." + method, t);
            }
        }
    }

    private static void hookIntentResolve() {
        try {
            XposedBridge.hookAllConstructors(Intent.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Intent intent = (Intent) param.thisObject;
                    ComponentName cn = intent.getComponent();
                    if (cn != null) {
                        String redirected = redirectPackage(cn.getPackageName());
                        if (redirected != null) {
                            intent.setComponent(new ComponentName(redirected, cn.getClassName()));
                        }
                    }
                    String pkg = intent.getPackage();
                    if (pkg != null) {
                        String redirected = redirectPackage(pkg);
                        if (redirected != null) {
                            intent.setPackage(redirected);
                        }
                    }
                    String action = intent.getAction();
                    if (action != null) {
                        String redirectedAction = redirectAction(action);
                        if (redirectedAction != null) {
                            intent.setAction(redirectedAction);
                        }
                    }
                    restoreSelfTargetedAction(intent);
                    routeChooseAccountToSystem(intent);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Intent constructors", t);
        }
    }

    /**
     * Egress interceptor for direct startActivity / startActivityForResult invocations.
     * Note: Only covers direct caller invocations; indirect deliveries (e.g. wrapped in PendingIntent)
     * are not in this scope and rely on Intent constructor / setter hooks.
     */
    private static void hookActivityStart() {
        try {
            XC_MethodHook egressHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null) return;
                    for (Object arg : param.args) {
                        if (arg instanceof Intent) {
                            restoreSelfTargetedAction((Intent) arg);
                            routeChooseAccountToSystem((Intent) arg);
                            break;
                        }
                    }
                }
            };
            XposedBridge.hookAllMethods(android.content.ContextWrapper.class, "startActivity", egressHook);
            XposedBridge.hookAllMethods(android.app.Activity.class, "startActivityForResult", egressHook);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook startActivity/startActivityForResult", t);
        }
    }

    private static void hookContentResolverAcquire() {
        try {
            for (String method : new String[]{
                    "acquireProvider", "acquireContentProviderClient",
                    "acquireUnstableProvider", "acquireUnstableContentProviderClient"
            }) {
                try {
                    XposedBridge.hookAllMethods(ContentResolver.class, method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args[0] instanceof Uri) {
                                Uri uri = (Uri) param.args[0];
                                String newAuth = redirectAuthority(uri.getAuthority());
                                if (newAuth != null) {
                                    param.args[0] = uri.buildUpon().authority(newAuth).build();
                                }
                            } else if (param.args[0] instanceof String) {
                                String newAuth = redirectAuthority((String) param.args[0]);
                                if (newAuth != null) {
                                    param.args[0] = newAuth;
                                }
                            }
                        }
                    });
                } catch (Throwable ignored) {}
            }

            // A provider call real GMS refuses for the patched signature is retried against MicroG.
            try {
                XposedBridge.hookAllMethods(ContentResolver.class, "call", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        for (int i = 0; i < param.args.length; i++) {
                            if (param.args[i] instanceof Uri) {
                                Uri uri = (Uri) param.args[i];
                                String newAuth = redirectAuthority(uri.getAuthority());
                                if (newAuth != null) {
                                    param.args[i] = uri.buildUpon().authority(newAuth).build();
                                }
                            } else if (param.args[i] instanceof String && i == 0) {
                                String newAuth = redirectAuthority((String) param.args[i]);
                                if (newAuth != null) {
                                    param.args[i] = newAuth;
                                }
                            }
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.getThrowable() instanceof SecurityException) {
                            String msg = param.getThrowable().getMessage();
                            if (msg != null && (msg.contains("GoogleCertificatesRslt") ||
                                    msg.contains("not allowed") ||
                                    msg.contains("Access denied"))) {
                                Log.i(TAG, "GMS rejected call, retrying with MicroG");
                                for (int i = 0; i < param.args.length; i++) {
                                    if (param.args[i] instanceof Uri) {
                                        Uri uri = (Uri) param.args[i];
                                        String authority = uri.getAuthority();
                                        if (authority != null && authority.contains(REAL_GMS)) {
                                            param.args[i] = uri.buildUpon()
                                                    .authority(authority.replace(REAL_GMS, targetGms))
                                                    .build();
                                        }
                                    } else if (param.args[i] instanceof String && i == 0) {
                                        String s = (String) param.args[i];
                                        if (s.contains(REAL_GMS)) {
                                            param.args[i] = s.replace(REAL_GMS, targetGms);
                                        }
                                    }
                                }
                                param.setThrowable(null);
                                param.setResult(null);
                            }
                        }
                    }
                });
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook ContentResolver", t);
        }
    }

    private static void hookPackageManagerGetPackageInfo(Context context) {
        try {
            XposedHelpers.findAndHookMethod(
                    context.getPackageManager().getClass(),
                    "getPackageInfo",
                    String.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            PackageInfo pi = (PackageInfo) param.getResult();
                            if (pi != null && targetGms != null) {
                                if (targetGms.equals(pi.packageName) && (((int) param.args[1]) & PackageManager.GET_SIGNATURES) != 0) {
                                    if (originalSignature != null && !originalSignature.isEmpty()) {
                                        try {
                                            pi.signatures = new Signature[]{new Signature(originalSignature)};
                                        } catch (Throwable ignored) {}
                                    }
                                }
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook PackageManager.getPackageInfo", t);
        }
    }
}
