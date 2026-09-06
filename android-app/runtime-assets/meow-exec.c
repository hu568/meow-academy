/*
 * meow-exec.c —— 喵仓 untrusted_app 域 exec 转发 LD_PRELOAD（0.2.10 包管理器）
 *
 * 背景（AGENTS.md 实测链）：
 *   - untrusted_app + targetSdk >= 29：App 私有目录下的 ELF 与 shebang 脚本都不能直接
 *     execve（SELinux W^X 对 app_data_file 的 execute/execute_no_trans 拒绝），只有系统
 *     ELF（/system/bin/linker64、/system/bin/sh）能 exec；dlopen 私有 .so 允许 →
 *     LD_PRELOAD 自写 .so 可行（DSH node 加载 koffi/pty.node 已证明）。
 *
 * 设计（plan-apt-package-manager.md §4.4，实现补全）：
 *   - hook 全部 exec 家族高层入口：execve / execv / execle / execl / execlp / execlpe /
 *     execvp / execvpe / execveat / fexecve / posix_spawn / posix_spawnp。
 *     之所以 hook 高层入口而不是只 hook execve：libc 内部 execvp→execve 是闭环绑定
 *     不走 PLT，单 hook execve 拦不到 dpkg 的 execvp；而外部程序（dpkg/apt/bash/node）
 *     调用这些入口都经它们自己的 PLT，LD_PRELOAD 全部可拦。
 *   - 转发策略（按目标路径 + 文件魔数）：
 *       目标在 App 私有目录（/data/data/、/data/user/<n>/）时：
 *         '#!'  开头  -> execve("/system/bin/sh",       [sh, path, argv[1..]])
 *                        脚本由 sh 直接吃内容：绕开内核对脚本文件的 exec 检查，
 *                        顺带无视 shebang 里硬编码的 Termux 路径（首行被当注释）。
 *         0x7f 'ELF'  -> execve("/system/bin/linker64", [linker64, path, argv[1..]])
 *                        bionic linker 直接执行模式语义（linker_main.cpp 实证）：
 *                        exe_to_load = argv[1]，目标 argv = linker argv[1..]，
 *                        即目标 argv[0] = path、参数原样传递。
 *       其余路径一律原样放行（系统 ELF 直接 exec；目标不存在时由原调用返回原始
 *       errno）→ interp 自身是系统路径，天然防递归。
 *   - envp 原样传递：LD_PRELOAD 全链继承（apt -> dpkg -> postinst -> 装出的命令）。
 *   - 调试：MEOW_EXEC_DEBUG=1 时向 stderr 打印转发决策。
 *
 * 编译（Termux，构建机本来就是 Termux）：
 *   clang -shared -fPIC -O2 -o meow-exec.so meow-exec.c
 */

#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <spawn.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>

/* ── 真·libc 符号（RTLD_NEXT 惰性解析） ───────────────────────────── */

typedef int (*execve_fn)(const char *, char *const[], char *const[]);
typedef int (*execveat_fn)(int, const char *, char *const[], char *const[], int);
typedef int (*fexecve_fn)(int, char *const[], char *const[]);
typedef int (*spawn_fn)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                        const posix_spawnattr_t *, char *const[], char *const[]);

static execve_fn    g_execve;
static execveat_fn  g_execveat;
static fexecve_fn   g_fexecve;
static spawn_fn     g_posix_spawn;
static spawn_fn     g_posix_spawnp;

/* 喵~ dlsym 一次即缓存；RTLD_NEXT 从本库之后查找，正好落到 bionic libc */
static void *next_sym(void **slot, const char *name) {
  if (!*slot) {
    void *p = dlsym(RTLD_NEXT, name);
    if (p) *slot = p;
  }
  return *slot;
}

/* dlsym 万一失败（不应发生），兜底走裸系统调用，绝不把 exec 链打断 */
static int sys_execve(const char *path, char *const argv[], char *const envp[]) {
  return (int)syscall(__NR_execve, path, argv, envp);
}

static int real_execve_call(const char *path, char *const argv[], char *const envp[]) {
  execve_fn real = (execve_fn)next_sym((void **)&g_execve, "execve");
  return real ? real(path, argv, envp) : sys_execve(path, argv, envp);
}

/* ── 调试 ─────────────────────────────────────────────────────────── */

static int dbg_enabled(void) {
  static int cached = -1;
  if (cached < 0) cached = getenv("MEOW_EXEC_DEBUG") ? 1 : 0;
  return cached;
}

static void dbg(const char *fn, const char *path, const char *interp) {
  if (dbg_enabled())
    fprintf(stderr, "[meow-exec] %s: %s -> %s\n", fn, path ? path : "(null)", interp);
}

/* ── 转发判定 ─────────────────────────────────────────────────────── */

/* App 私有目录判定：/data/data/（主用户别名）与 /data/user/<n>/（含分身用户）。
 * 只转发这两类前缀：系统路径原样放行 = 天然防递归（interp 是 /system/...）。 */
static int is_app_private(const char *p) {
  if (!p || p[0] != '/') return 0;
  if (strncmp(p, "/data/data/", 11) == 0) return 1;
  if (strncmp(p, "/data/user/", 11) == 0 && p[11] >= '0' && p[11] <= '9') return 1;
  return 0;
}

/* 读目标文件前 4 字节；返回读到的字节数，<0 = 打不开（交给原调用报原始 errno） */
static int peek_magic_path(const char *path, unsigned char magic[4]) {
  int fd = open(path, O_RDONLY | O_CLOEXEC);
  if (fd < 0) return -1;
  ssize_t r = read(fd, magic, 4);
  close(fd);
  return r < 0 ? -1 : (int)r;
}

static int magic_is_elf(const unsigned char *m, ssize_t n) {
  return n == 4 && m[0] == 0x7f && m[1] == 'E' && m[2] == 'L' && m[3] == 'F';
}

static int magic_is_shebang(const unsigned char *m, ssize_t n) {
  return n >= 2 && m[0] == '#' && m[1] == '!';
}

/* 转发判定：返回 interp（"/system/bin/sh" 或 "/system/bin/linker64"）或 NULL（放行） */
static const char *redirect_interp(const char *path) {
  if (!is_app_private(path)) return NULL;
  unsigned char magic[4];
  ssize_t n = peek_magic_path(path, magic);
  if (magic_is_shebang(magic, n)) return "/system/bin/sh";
  if (magic_is_elf(magic, n)) return "/system/bin/linker64";
  return NULL;
}

/* 构造转发 argv：[interp, path, argv[1..]]（目标 argv[0] = path，linker/sh 语义见文件头） */
static char **build_forward_argv(const char *interp, const char *path, char *const argv[]) {
  int argc = 0;
  if (argv) while (argv[argc]) argc++;
  char **na = calloc((size_t)argc + 2, sizeof(char *));
  if (!na) { errno = ENOMEM; return NULL; }
  na[0] = (char *)interp;
  na[1] = (char *)path;
  for (int i = 1; i < argc; i++) na[i + 1] = argv[i];
  return na;
}

/* 统一 execve 语义：判定 → 转发或放行（保持 errno 语义） */
static int do_execve(const char *path, char *const argv[], char *const envp[]) {
  const char *interp = redirect_interp(path);
  if (!interp) return real_execve_call(path, argv, envp);
  char **na = build_forward_argv(interp, path, argv);
  if (!na) return -1;  /* errno = ENOMEM */
  dbg("execve", path, interp);
  int r = real_execve_call(interp, na, envp);
  int err = errno;
  free(na);
  errno = err;
  return r;
}

/* PATH 搜索（供 execvp/execvpe/posix_spawnp 的相对名解析）：
 * 取「第一个 stat 命中的普通文件」。注意不能用 access(X_OK)——SELinux 对私有文件的
 * execute 检查会让它永远失败，导致搜索不到；命中后再由 redirect_interp 精判。 */
static int path_lookup(const char *name, char *out, size_t outsz) {
  const char *path = getenv("PATH");
  if (!name || !*name || !path || strchr(name, '/')) return 0;
  const char *p = path;
  while (*p) {
    const char *colon = strchr(p, ':');
    size_t seg = colon ? (size_t)(colon - p) : strlen(p);
    if (seg + strlen(name) + 2 <= outsz) {
      memcpy(out, p, seg);
      out[seg] = '\0';
      strcat(out, "/");
      strcat(out, name);
      struct stat st;
      if (stat(out, &st) == 0 && S_ISREG(st.st_mode)) return 1;
    }
    p = colon ? colon + 1 : p + seg;
  }
  return 0;
}

/* ── hook：execve / execv / execvpe / execvp ─────────────────────── */

int execve(const char *path, char *const argv[], char *const envp[]) {
  return do_execve(path, argv, envp);
}

int execv(const char *path, char *const argv[]) {
  return do_execve(path, argv, environ);
}

int execvpe(const char *file, char *const argv[], char *const envp[]) {
  char resolved[PATH_MAX];
  const char *use = file;
  if (file && !strchr(file, '/') && path_lookup(file, resolved, sizeof resolved))
    use = resolved;  /* 相对名 → 解析成绝对路径再统一判定 */
  return do_execve(use, argv, envp);
}

int execvp(const char *file, char *const argv[]) {
  return execvpe(file, argv, environ);
}

/* ── hook：execle / execl / execlp / execlpe（varargs 组 argv） ───── */

/* 从 varargs 收集 argv（arg0 之后的参数直到 NULL）；is_le 时再取一个 envp */
static char **collect_argv(const char *arg0, va_list ap, char ***envp_slot) {
  va_list apc;
  va_copy(apc, ap);
  size_t n = 0;
  while (va_arg(apc, char *)) n++;
  va_end(apc);
  char **argv = malloc((n + 2) * sizeof(char *));
  if (!argv) {
    if (envp_slot) *envp_slot = NULL;
    va_end(ap);
    errno = ENOMEM;
    return NULL;
  }
  argv[0] = (char *)arg0;
  for (size_t i = 1; i <= n; i++) argv[i] = va_arg(ap, char *);
  argv[n + 1] = NULL;
  if (envp_slot) *envp_slot = va_arg(ap, char **);
  va_end(ap);
  return argv;
}

int execl(const char *path, const char *arg, ...) {
  va_list ap;
  va_start(ap, arg);
  char **argv = collect_argv(arg, ap, NULL);
  if (!argv) return -1;
  int r = do_execve(path, argv, environ);
  int e = errno;
  free(argv);
  errno = e;
  return r;
}

int execle(const char *path, const char *arg, ...) {
  va_list ap;
  va_start(ap, arg);
  char **envp = NULL;
  char **argv = collect_argv(arg, ap, &envp);
  if (!argv) return -1;
  int r = do_execve(path, argv, envp ? envp : environ);
  int e = errno;
  free(argv);
  errno = e;
  return r;
}

int execlp(const char *file, const char *arg, ...) {
  va_list ap;
  va_start(ap, arg);
  char **argv = collect_argv(arg, ap, NULL);
  if (!argv) return -1;
  int r = execvp(file, argv);  /* 走本库 execvp hook（PATH 搜索 + 转发） */
  int e = errno;
  free(argv);
  errno = e;
  return r;
}

int execlpe(const char *file, const char *arg, ...) {
  va_list ap;
  va_start(ap, arg);
  char **envp = NULL;
  char **argv = collect_argv(arg, ap, &envp);
  if (!argv) return -1;
  int r = execvpe(file, argv, envp ? envp : environ);
  int e = errno;
  free(argv);
  errno = e;
  return r;
}

/* ── hook：execveat / fexecve ─────────────────────────────────────── */

int execveat(int dirfd, const char *path, char *const argv[], char *const envp[], int flags) {
  if (path && *path && !(flags & AT_EMPTY_PATH)) {
    if (path[0] == '/') return do_execve(path, argv, envp);  /* 绝对路径：dirfd 被忽略 */
    if (dirfd == AT_FDCWD) {
      /* 相对 cwd：拼绝对路径再判（覆盖 `./tool` 场景）；放行时语义等价 */
      char abspath[PATH_MAX];
      size_t cl;
      if (getcwd(abspath, sizeof abspath) &&
          (cl = strlen(abspath)) + 1 + strlen(path) + 1 <= sizeof abspath) {
        abspath[cl] = '/';
        strcpy(abspath + cl + 1, path);
        return do_execve(abspath, argv, envp);
      }
    }
    /* 相对 dirfd（非 AT_FDCWD）：低频，放行 */
  }
  execveat_fn real = (execveat_fn)next_sym((void **)&g_execveat, "execveat");
  if (real) return real(dirfd, path, argv, envp, flags);
  errno = ENOSYS;
  return -1;
}

int fexecve(int fd, char *const argv[], char *const envp[]) {
  unsigned char magic[4];
  ssize_t r = pread(fd, magic, 4, 0);
  const char *interp = NULL;
  if (magic_is_shebang(magic, r)) interp = "/system/bin/sh";
  else if (magic_is_elf(magic, r)) interp = "/system/bin/linker64";
  if (interp) {
    /* 转发目标用 /proc/self/fd/<fd>：linker64/sh 都可正常打开（fd 已持有引用） */
    char fdpath[64];
    snprintf(fdpath, sizeof fdpath, "/proc/self/fd/%d", fd);
    char **na = build_forward_argv(interp, fdpath, argv);
    if (!na) return -1;
    dbg("fexecve", fdpath, interp);
    int res = real_execve_call(interp, na, envp);
    int err = errno;
    free(na);
    errno = err;
    return res;
  }
  fexecve_fn real = (fexecve_fn)next_sym((void **)&g_fexecve, "fexecve");
  if (real) return real(fd, argv, envp);
  execveat_fn reale = (execveat_fn)next_sym((void **)&g_execveat, "execveat");
  if (reale) return reale(fd, "", argv, envp, AT_EMPTY_PATH);
  errno = ENOSYS;
  return -1;
}

/* ── hook：posix_spawn / posix_spawnp（dpkg 1.19+ 可能走的通道） ──── */
/* 注意 posix_spawn 家族返回「错误码」而非 -1/errno。 */

int posix_spawn(pid_t *pid, const char *path,
                const posix_spawn_file_actions_t *actions,
                const posix_spawnattr_t *attrp,
                char *const argv[], char *const envp[]) {
  spawn_fn real = (spawn_fn)next_sym((void **)&g_posix_spawn, "posix_spawn");
  if (!real) return EIO;
  const char *interp = redirect_interp(path);
  if (!interp) return real(pid, path, actions, attrp, argv, envp);
  char **na = build_forward_argv(interp, path, argv);
  if (!na) return ENOMEM;
  dbg("posix_spawn", path, interp);
  int r = real(pid, interp, actions, attrp, na, envp);
  free(na);
  return r;
}

int posix_spawnp(pid_t *pid, const char *file,
                 const posix_spawn_file_actions_t *actions,
                 const posix_spawnattr_t *attrp,
                 char *const argv[], char *const envp[]) {
  spawn_fn real = (spawn_fn)next_sym((void **)&g_posix_spawnp, "posix_spawnp");
  if (!real) return EIO;
  char resolved[PATH_MAX];
  const char *use = file;
  if (file && !strchr(file, '/') && path_lookup(file, resolved, sizeof resolved))
    use = resolved;
  const char *interp = redirect_interp(use);
  if (!interp) return real(pid, file, actions, attrp, argv, envp);  /* 原样交回保真 */
  spawn_fn spawn_abs = (spawn_fn)next_sym((void **)&g_posix_spawn, "posix_spawn");
  if (!spawn_abs) return EIO;
  char **na = build_forward_argv(interp, use, argv);
  if (!na) return ENOMEM;
  dbg("posix_spawnp", use, interp);
  int r = spawn_abs(pid, interp, actions, attrp, na, envp);
  free(na);
  return r;
}
