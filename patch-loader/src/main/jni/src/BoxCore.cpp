//
// Created by Milk on 4/9/21.
//

#include "BoxCore.h"
#include "Log.h"
#include "IO.h"
#include <jni.h>
#include "JniHook/JniHook.h"
#include "Hook/VMClassLoaderHook.h"
#include "Hook/UnixFileSystemHook.h"
#include "Hook/BinderHook.h"
#include "Hook/RuntimeHook.h"
#include "Utils/HexDump.h"

struct {
    JavaVM *vm;
    jclass NativeCoreClass;
    jmethodID getCallingUidId;
    jmethodID redirectPathString;
    jmethodID redirectPathFile;
    jmethodID loadEmptyDex;
    jmethodID loadEmptyDexL;
    int api_level;
} VMEnv;


JNIEnv *getEnv() {
    JNIEnv *env;
    VMEnv.vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    return env;
}

JNIEnv *ensureEnvCreated() {
    JNIEnv *env = getEnv();
    if (env == NULL) {
        VMEnv.vm->AttachCurrentThread(&env, NULL);
    }
    return env;
}

int BoxCore::getCallingUid(JNIEnv *env, int orig) {
    env = ensureEnvCreated();
    return env->CallStaticIntMethod(VMEnv.NativeCoreClass, VMEnv.getCallingUidId, orig);
}

jstring BoxCore::redirectPathString(JNIEnv *env, jstring path) {
    env = ensureEnvCreated();
    return (jstring) env->CallStaticObjectMethod(VMEnv.NativeCoreClass, VMEnv.redirectPathString, path);
}

jobject BoxCore::redirectPathFile(JNIEnv *env, jobject path) {
    env = ensureEnvCreated();
    return env->CallStaticObjectMethod(VMEnv.NativeCoreClass, VMEnv.redirectPathFile, path);
}

jlongArray BoxCore::loadEmptyDex(JNIEnv *env) {
    env = ensureEnvCreated();
    return (jlongArray) env->CallStaticObjectMethod(VMEnv.NativeCoreClass, VMEnv.loadEmptyDex);
}

int BoxCore::getApiLevel() {
    return VMEnv.api_level;
}

JavaVM *BoxCore::getJavaVM() {
    return VMEnv.vm;
}

void nativeHook(JNIEnv *env) {
    BaseHook::init(env);
    UnixFileSystemHook::init(env);
    VMClassLoaderHook::init(env);
//    RuntimeHook::init(env);
    BinderHook::init(env);
}

void hideXposed(JNIEnv *env, jclass clazz) {
    ALOGD("set hideXposed");
    VMClassLoaderHook::hideXposed();
}

void init(JNIEnv *env, jobject clazz, jint api_level) {
    ALOGD("NativeCore init.");
    VMEnv.api_level = api_level;
    VMEnv.NativeCoreClass = (jclass) env->NewGlobalRef(env->FindClass(VMCORE_CLASS));
    VMEnv.getCallingUidId = env->GetStaticMethodID(VMEnv.NativeCoreClass, "getCallingUid", "(I)I");
    VMEnv.redirectPathString = env->GetStaticMethodID(VMEnv.NativeCoreClass, "redirectPath",
                                                      "(Ljava/lang/String;)Ljava/lang/String;");
    VMEnv.redirectPathFile = env->GetStaticMethodID(VMEnv.NativeCoreClass, "redirectPath",
                                                    "(Ljava/io/File;)Ljava/io/File;");
    VMEnv.loadEmptyDex = env->GetStaticMethodID(VMEnv.NativeCoreClass, "loadEmptyDex",
                                                "()[J");

    JniHook::InitJniHook(env, api_level);
}

void addIORule(JNIEnv *env, jclass clazz, jstring target_path,
               jstring relocate_path) {
    IO::addRule(env->GetStringUTFChars(target_path, JNI_FALSE),
                env->GetStringUTFChars(relocate_path, JNI_FALSE));
}

void enableIO(JNIEnv *env, jclass clazz) {
    IO::init(env);
    nativeHook(env);
}

#define SECMAGIC 0xdeadbeef

#if defined(__aarch64__) // 64-bit architecture
uint64_t OriSyscall(uint64_t num, uint64_t SYSARG_1, uint64_t SYSARG_2, uint64_t SYSARG_3,
                    uint64_t SYSARG_4, uint64_t SYSARG_5, uint64_t SYSARG_6) {
    uint64_t x0;
    __asm__ volatile (
    "mov x8, %1\n\t"
    "mov x0, %2\n\t"
    "mov x1, %3\n\t"
    "mov x2, %4\n\t"
    "mov x3, %5\n\t"
    "mov x4, %6\n\t"
    "mov x5, %7\n\t"
    "svc #0\n\t"
    "mov %0, x0\n\t"
    :"=r"(x0)
    :"r"(num), "r"(SYSARG_1), "r"(SYSARG_2), "r"(SYSARG_3), "r"(SYSARG_4), "r"(SYSARG_5), "r"(SYSARG_6)
    :"x8", "x0", "x1", "x2", "x3", "x4", "x4", "x5"
    );
    return x0;
}
#elif defined(__arm__) // 32-bit architecture
uint32_t OriSyscall(uint32_t num, uint32_t SYSARG_1, uint32_t SYSARG_2, uint32_t SYSARG_3,
                    uint32_t SYSARG_4, uint32_t SYSARG_5, uint32_t SYSARG_6) {
    uint32_t x0;
    __asm__ volatile (
    "mov r7, %1\n\t"
    "mov r0, %2\n\t"
    "mov r1, %3\n\t"
    "mov r2, %4\n\t"
    "mov r3, %5\n\t"
    "mov r4, %6\n\t"
    "mov r5, %7\n\t"
    "svc #0\n\t"
    "mov %0, r0\n\t"
    :"=r"(x0)
    :"r"(num), "r"(SYSARG_1), "r"(SYSARG_2), "r"(SYSARG_3), "r"(SYSARG_4), "r"(SYSARG_5), "r"(SYSARG_6)
    :"r7", "r0", "r1", "r2", "r3", "r4", "r5"
    );
    return x0;
}
#else
#error "Unsupported architecture"
#endif

void sig_callback(int signo, siginfo_t *info, void *data){
    int my_signo = info->si_signo;
    unsigned long syscall_number;
    unsigned long SYSARG_1, SYSARG_2, SYSARG_3, SYSARG_4, SYSARG_5, SYSARG_6;
#if defined(__aarch64__)
    syscall_number = ((ucontext_t *) data)->uc_mcontext.regs[8];
    SYSARG_1 = ((ucontext_t *) data)->uc_mcontext.regs[0];
    SYSARG_2 = ((ucontext_t *) data)->uc_mcontext.regs[1];
    SYSARG_3 = ((ucontext_t *) data)->uc_mcontext.regs[2];
    SYSARG_4 = ((ucontext_t *) data)->uc_mcontext.regs[3];
    SYSARG_5 = ((ucontext_t *) data)->uc_mcontext.regs[4];
    SYSARG_6 = ((ucontext_t *) data)->uc_mcontext.regs[5];
#elif defined(__arm__)
    syscall_number = ((ucontext_t *) data)->uc_mcontext.arm_r7;
    SYSARG_1 = ((ucontext_t *) data)->uc_mcontext.arm_r0;
    SYSARG_2 = ((ucontext_t *) data)->uc_mcontext.arm_r1;
    SYSARG_3 = ((ucontext_t *) data)->uc_mcontext.arm_r2;
    SYSARG_4 = ((ucontext_t *) data)->uc_mcontext.arm_r3;
    SYSARG_5 = ((ucontext_t *) data)->uc_mcontext.arm_r4;
    SYSARG_6 = ((ucontext_t *) data)->uc_mcontext.arm_r5;
#else
#error "Unsupported architecture"
#endif
    switch (syscall_number) {
        case __NR_openat:{
            int fd = (int) SYSARG_1;
            const char *pathname = (const char *) SYSARG_2;
            int flags = (int) SYSARG_3;
            int mode = (int) SYSARG_4;
            ALOGE("测试%s",pathname);
#if defined(__aarch64__)
            ((ucontext_t *) data)->uc_mcontext.regs[0] = (uint64_t)fd;
            ((ucontext_t *) data)->uc_mcontext.regs[1] = (uint64_t)pathname;
            ((ucontext_t *) data)->uc_mcontext.regs[2] = (uint64_t)flags;
            ((ucontext_t *) data)->uc_mcontext.regs[3] = (uint64_t)mode;
#elif defined(__arm__)
            ((ucontext_t *) data)->uc_mcontext.arm_r0 = (uint32_t)fd;
            ((ucontext_t *) data)->uc_mcontext.arm_r1 = (uint32_t)pathname;
            ((ucontext_t *) data)->uc_mcontext.arm_r2 = (uint32_t)flags;
            ((ucontext_t *) data)->uc_mcontext.arm_r3 = (uint32_t)mode;
#endif
#if defined(__aarch64__)
            ((ucontext_t *) data)->uc_mcontext.regs[0] = OriSyscall(__NR_openat, fd, (uint64_t)pathname, flags, mode, SECMAGIC, SECMAGIC);
#elif defined(__arm__)
            ((ucontext_t *) data)->uc_mcontext.arm_r0 = OriSyscall(__NR_openat, fd, (uint32_t)pathname, flags, mode, SECMAGIC, SECMAGIC);
#endif
            break;
        }
        default:
            break;
    }
}

void init_seccomp(JNIEnv *env, jclass clazz) {
    struct sock_filter filter[] = {
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_openat, 0, 2),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[4])),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SECMAGIC, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP)
    };

    struct sock_fprog prog;
    prog.filter = filter;
    prog.len = (unsigned short) (sizeof(filter) / sizeof(filter[0]));

    struct sigaction sa;
    sigset_t sigset;
    sigfillset(&sigset);
    sa.sa_sigaction = sig_callback;
    sa.sa_mask = sigset;
    sa.sa_flags = SA_SIGINFO;

    if (sigaction(SIGSYS, &sa, NULL) == -1) {
        return;
    }
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) == -1) {
        return;
    }
    if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog) == -1) {
        return;
    }
    ALOGE("InitCvmSeccomp Successes");
}
