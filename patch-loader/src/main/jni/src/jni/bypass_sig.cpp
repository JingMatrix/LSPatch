//
// Created by VIP on 2021/4/25.
//

#include "bypass_sig.h"

#include <map>
#include <string>
#include "../src/native_api.h"
#include "elf_util.h"
#include "logging.h"
#include "native_util.h"
#include "patch_loader.h"
#include "utils/hook_helper.hpp"
#include "utils/jni_helper.hpp"
#include "Hook/VMClassLoaderHook.h"
#include <sys/prctl.h>
#include <sys/socket.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <signal.h>
#include <unistd.h>
#include <asm/unistd.h>
#include <sys/ucontext.h>
#include "IO.h"
#include "BoxCore.h"


using lsplant::operator""_sym;

namespace lspd {

    static std::map<std::string, std::string> g_io_rules;
    static bool g_io_hook_enabled = false;

    inline static constexpr auto kLibCName = "libc.so";

    std::unique_ptr<const SandHook::ElfImg> &GetC(bool release = false) {
        static std::unique_ptr<const SandHook::ElfImg> kImg = nullptr;
        if (release) {
            kImg.reset();
        } else if (!kImg) {
            kImg = std::make_unique<SandHook::ElfImg>(kLibCName);
        }
        return kImg;
    }

    inline static auto __openat_ =
            "__openat"_sym.hook->*[]<lsplant::Backup auto backup>(int fd, const char *pathname, int flag,
                                                                  int mode) static -> int {
                if (pathname != nullptr) {
                    auto it = g_io_rules.find(pathname);
                    if (it != g_io_rules.end()) {
                        const auto &redirectPath = it->second;
                        LOGD("Redirect openat from {} to {}", pathname, redirectPath);
                        return backup(fd, redirectPath.c_str(), flag, mode);
                    }
                }
                return backup(fd, pathname, flag, mode);
            };

    bool HookOpenat(const lsplant::HookHandler &handler) { return handler(__openat_); }

    void enable_io_hook() {
        if (g_io_hook_enabled) {
            return;
        }
        auto r = HookOpenat(lsplant::InitInfo{
                .inline_hooker =
                [](auto t, auto r) {
                    void *bk = nullptr;
                    return HookInline(t, r, &bk) == 0 ? bk : nullptr;
                },
                .art_symbol_resolver = [](auto symbol) { return GetC()->getSymbAddress(symbol); },
        });
        if (!r) {
            LOGE("Hook __openat fail");
            return;
        }
        g_io_hook_enabled = true;
        GetC(true);
    }

    LSP_DEF_NATIVE_METHOD(void, SigBypass, enableOpenatHook, jstring origApkPath,
                          jstring cacheApkPath) {
        lsplant::JUTFString str1(env, origApkPath);
        lsplant::JUTFString str2(env, cacheApkPath);
        g_io_rules[str1.get()] = str2.get();
        LOGD("Added IO rule via legacy hook: {} -> {}", str1.get(), str2.get());
        enable_io_hook();
    }

    LSP_DEF_NATIVE_METHOD(void, SigBypass, addIORule, jstring origPath, jstring redirectPath) {
        lsplant::JUTFString orig_path_str(env, origPath);
        lsplant::JUTFString redirect_path_str(env, redirectPath);
        g_io_rules[orig_path_str.get()] = redirect_path_str.get();
        LOGD("Added IO rule: {} -> {}", orig_path_str.get(), redirect_path_str.get());
    }

    LSP_DEF_NATIVE_METHOD(void, SigBypass, enableIO) {
        enable_io_hook();
    }

    LSP_DEF_NATIVE_METHOD(void, SigBypass, hideXposed) {
        VMClassLoaderHook::hideXposed();
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

    LSP_DEF_NATIVE_METHOD(void, SigBypass, initSeccomp) {
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
        LOGD("Init Seccomp Successes");
    }

    static JNINativeMethod gMethods[] = {
            LSP_NATIVE_METHOD(SigBypass, enableOpenatHook, "(Ljava/lang/String;Ljava/lang/String;)V"),
            LSP_NATIVE_METHOD(SigBypass, addIORule, "(Ljava/lang/String;Ljava/lang/String;)V"),
            LSP_NATIVE_METHOD(SigBypass, enableIO, "()V"),
            LSP_NATIVE_METHOD(SigBypass, hideXposed, "()V"),
            LSP_NATIVE_METHOD(SigBypass, initSeccomp, "()V"),
    };

    void RegisterBypass(JNIEnv *env) { REGISTER_LSP_NATIVE_METHODS(SigBypass); }

}  // namespace lspd