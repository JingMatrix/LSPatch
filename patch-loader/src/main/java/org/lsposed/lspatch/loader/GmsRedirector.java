package org.lsposed.lspatch.loader;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;

import org.lsposed.lspatch.loader.util.XLog;
import org.lsposed.lspatch.share.Constants;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Points a patched app's Google Play services traffic at an installed replacement.
 *
 * A patched app is signed with LSPatch's key, so Google Play services rejects it wherever it checks the caller's
 * certificate -- most visibly at Google sign-in. A replacement answers the same requests under its own package, so
 * this rewrites the package and component of every GMS-bound intent, and the authority of every GMS-bound provider
 * call, to it. Which certificate that build then reports for the app is decided by the manifest metadata the
 * patcher wrote, not here.
 *
 * Actions are deliberately left alone. A replacement is a drop-in: it declares Google's own action names under its
 * own package, so rewriting the package is what routes a call, and rewriting the action as well would address a
 * name nothing declares. Push is the one exception, for the reason given on {@link #redirectAction}.
 *
 * Nothing else is assumed about the build. Two kinds exist -- one that renamed Google's namespaces into its own so
 * it can sit beside Google Play services, and one that keeps Google's names because it is meant to replace it
 * outright -- and only the first needs its authorities and push names rewritten. That question is answered from the
 * package name alone, by {@link Constants#renamesGoogleNamespaces}, so no vendor is named here.
 *
 * An intent the app addresses to itself is never rewritten: only Google's own package and {@code gsf} are matched,
 * which matters because the sign-in library opens its own {@code SignInHubActivity} with Google's
 * {@code GOOGLE_SIGN_IN} action, and that activity finishes on any other action.
 */
public class GmsRedirector {
    private static final String TAG = "LSPatch-GmsRedirect";
    private static final String REAL_GMS = Constants.REAL_GMS_PACKAGE_NAME;

    private static String targetGms = null;

    /**
     * Points the app at [gmsReplacementPackage], if that build is installed and the app can see it.
     *
     * The package name is the whole configuration. Whether this build renamed Google's namespaces into its own,
     * and so whether its authorities and push names have to be rewritten to reach it, follows from the name alone;
     * nothing else about the build is assumed and no vendor is named here.
     */
    public static void activate(Context context, String gmsReplacementPackage) {
        if (!Constants.isGmsReplacement(gmsReplacementPackage)) {
            Log.w(TAG, "Not a usable replacement package, GMS redirect disabled: " + gmsReplacementPackage);
            return;
        }
        if (!isInstalled(context, gmsReplacementPackage)) {
            Log.w(TAG, gmsReplacementPackage + " is not installed or not visible to the app"
                    + " (add QUERY_ALL_PACKAGES), GMS redirect disabled");
            return;
        }
        targetGms = gmsReplacementPackage;

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

        Log.i(TAG, "GMS redirect hooks installed");
    }

    /** {@code ConnectionResult.SUCCESS}, named here so the app's own copy of the class is not needed. */
    private static final int PLAY_SERVICES_SUCCESS = 0;

    /** Announced once per process: the check runs constantly, and the reader needs telling only once. */
    private static boolean availabilityAnnounced = false;

    /**
     * Answers the client library's "is Google Play services here?" check on the replacement's behalf.
     *
     * That check is not an intent. It asks the package manager for Google's own package and inspects its
     * version and signing certificate, so the redirect never sees it, and on a device whose Google Play
     * services has been replaced rather than supplemented it fails -- whereupon the library stops before
     * sending anything. It does not merely report the failure: it hands back stub intents, so every API
     * behind it is refused even though the services it would have bound are present and answering.
     *
     * Only armed once the replacement has been found installed, so this reports something that is in fact
     * true: the APIs are available, from a different package. The first time it actually changes an answer it
     * is logged, because an app being told Google Play services is present when Google's own is absent is
     * worth being able to find afterwards.
     */
    static void answerAvailabilityChecks(ClassLoader appClassLoader) {
        if (targetGms == null || appClassLoader == null) return;
        // Four entry points, because the library answers the same question in several places and the APIs
        // behind them do not agree on which to ask: the sign-in client goes through GoogleApiAvailability,
        // the advertising-id client through GooglePlayServicesUtilLight. Hooking one leaves the others
        // refusing.
        for (String name : new String[]{
                "com.google.android.gms.common.GoogleApiAvailability",
                "com.google.android.gms.common.GoogleApiAvailabilityLight",
                "com.google.android.gms.common.GooglePlayServicesUtil",
                "com.google.android.gms.common.GooglePlayServicesUtilLight"}) {
            Class<?> clazz = XposedHelpers.findClassIfExists(name, appClassLoader);
            // Absent whenever the app does not ship the client library, which is the common case.
            if (clazz == null) continue;
            try {
                XposedBridge.hookAllMethods(clazz, "isGooglePlayServicesAvailable", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object result = param.getResult();
                        if (!(result instanceof Integer)) return;
                        int reported = (Integer) result;
                        if (reported == PLAY_SERVICES_SUCCESS) return;
                        param.setResult(PLAY_SERVICES_SUCCESS);
                        announceAvailability(reported);
                    }
                });
                Log.i(TAG, "Answering availability checks on " + name);
            } catch (Throwable t) {
                Log.e(TAG, "Failed to hook " + name + ".isGooglePlayServicesAvailable", t);
            }
        }
    }

    private static void announceAvailability(int reported) {
        synchronized (GmsRedirector.class) {
            if (availabilityAnnounced) return;
            availabilityAnnounced = true;
        }
        Log.i(TAG, "Reported Google Play services as available: the client answered " + reported
                + ", and the calls behind it go to " + targetGms);
    }

    private static boolean isInstalled(Context context, String pkg) {
        try {
            context.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * The package to use in place of [pkg], or null to leave it alone.
     *
     * {@code gsf} maps to the replacement unconditionally, unlike its authorities: a replacement serves the gsf
     * services from inside its own package, so an intent addressed to {@code com.google.android.gsf} reaches a
     * package that is not installed at all, whereas its *authorities* may well be registered under Google's names.
     * The two questions have different answers and are asked separately.
     */
    static String redirectPackage(String pkg) {
        if (REAL_GMS.equals(pkg) || GSF_PACKAGE.equals(pkg)) {
            return targetGms;
        }
        return null;
    }

    private static final String GSF_PACKAGE = "com.google.android.gsf";
    private static final String CHIMERA_AUTHORITY = REAL_GMS + ".chimera";

    /**
     * The authority to use in place of [authority], or null to leave it alone.
     *
     * Only a replacement that renamed its own namespaces serves these under its vendor; one that kept Google's
     * names registers them exactly as Google does, and rewriting them would address something nothing serves.
     * Chimera is left to Google in either case: no replacement provides Dynamite modules, so a rewrite there
     * only breaks a lookup that would otherwise be answered.
     */
    static String redirectAuthority(String authority) {
        if (authority == null) return null;
        if (CHIMERA_AUTHORITY.equals(authority)) return null;
        String vendor = Constants.gmsReplacementVendor(targetGms);
        if (vendor == null) return null;
        if (authority.equals(REAL_GMS)) {
            return targetGms;
        }
        if (authority.startsWith(REAL_GMS + ".")) {
            return targetGms + authority.substring(REAL_GMS.length());
        }
        // The gsf authorities keep their own segment under the replacement's vendor.
        if (authority.startsWith(GSF_PACKAGE)) {
            return vendor + authority.substring("com.google".length());
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

    /**
     * The namespace this replacement's push names live in, or null when it kept Google's.
     *
     * A prefix rather than a list of actions, so that the rule here is the same rule the patcher applied to the
     * manifest: it renames every {@code com.google.android.c2dm.*} string it finds, and anything this side left out
     * of a list would be an action rewritten in one place and not the other.
     */
    private static String c2dmPrefix = null;

    private static void setupC2dmRedirects() {
        c2dmPrefix = Constants.gmsReplacementC2dmPrefix(targetGms);
        if (c2dmPrefix != null) {
            Log.i(TAG, "C2DM names rewritten to " + c2dmPrefix + "*");
        } else {
            Log.i(TAG, "C2DM names left alone: " + targetGms + " keeps Google's C2DM namespace");
        }
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

    /**
     * The action to use in place of [action], or null to leave it alone, which is the usual answer.
     *
     * Only the C2DM family is rewritten. A replacement is a drop-in: it answers Google's own action names under
     * its own package, so rewriting the package is what routes a call to it and rewriting the action as well
     * would send that call to a name nothing declares. Push is the exception, and only because the receiving
     * side cannot be reached under Google's names at all -- the permission guarding them is a signature
     * permission of Google Play services that no other build can hold -- which is why the patcher renames the
     * app's own C2DM names in the manifest to match.
     */
    static String redirectAction(String action) {
        if (action == null || c2dmPrefix == null) return null;
        if (!action.startsWith(Constants.GOOGLE_C2DM_PREFIX)) return null;
        return c2dmPrefix + action.substring(Constants.GOOGLE_C2DM_PREFIX.length());
    }

    /** The Google name for [action] if this rewrote it on the way out, or null when it is not one of ours. */
    static String restoreAction(String action) {
        if (action == null || c2dmPrefix == null) return null;
        if (!action.startsWith(c2dmPrefix)) return null;
        return Constants.GOOGLE_C2DM_PREFIX + action.substring(c2dmPrefix.length());
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
                    // On the hottest path this class hooks, so it is a prefix test and not a scan: the framework
                    // calls getAction on intents that have nothing to do with Google.
                    String restored = restoreAction((String) param.getResult());
                    if (restored != null) param.setResult(restored);
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
                    routeChooseAccountToSystem(intent);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Intent.setComponent", t);
        }
    }

    /**
     * {@code setClass}, {@code setClassName} and {@code readFromParcel} assign the component without going through
     * {@code setComponent}, so the rewrite there never sees them and the component would keep pointing at Google's
     * package. {@code readFromParcel} is how an activity's own launch intent arrives.
     */
    private static void hookIntentSetClass() {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Intent intent = (Intent) param.thisObject;
                ComponentName cn = intent.getComponent();
                if (cn == null) return;
                String redirected = redirectPackage(cn.getPackageName());
                if (redirected != null) {
                    XLog.d(TAG, "Redirecting component from " + cn.getClassName() + "'s package");
                    XposedHelpers.setObjectField(intent, "mComponent", new ComponentName(redirected, cn.getClassName()));
                }
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
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook ContentResolver", t);
        }
    }
}
