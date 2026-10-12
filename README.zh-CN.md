# java-debug

[English](README.md) | 简体中文

一个面向 AI Agent（Claude Code 等）的 **Java 远程断点调试 Skill**。它连接开启了 JDWP 的远程 JVM，由 AI 自主规划并设置断点、等待命中、采集调用栈和局部变量、做表达式求值与单步执行，最后基于**真实采集到的运行时数据**给出根因结论。

核心是自研的 `.agents/skills/java-debug/scripts/jdi-client.jar`：只依赖 JDK 内置的 `com.sun.jdi`，零外部依赖（jar 不随发布，skill 首次运行时由随附源码在本地构建），以常驻 HTTP 服务模式运行，所有操作都是一条 JSON 命令（`curl` 即可驱动）。

---

## 为什么不直接用 jdb

`jdb` 是给人在终端里敲的，交给 AI 驱动时问题很多：输出是要解析的文本、需要 tmux + sleep 去赌时序、默认冻结整个 JVM。本 Skill 针对 AI 驱动的场景重新设计了交互层：

| 维度 | jdb | java-debug |
|------|-----|------------|
| 挂起范围 | `SUSPEND_ALL`，命中即冻结整个 JVM | `SUSPEND_EVENT_THREAD`，只冻结命中的那个线程，其余业务线程照常服务 |
| 交互方式 | 文本输出，需要解析；依赖 tmux + sleep 抢时序 | 结构化 JSON 请求/响应，无 tmux、无 sleep 竞态 |
| 命中信息 | 需要逐条敲 `where` / `locals` | 命中响应自动携带调用栈 + 局部变量 |
| 等待方式 | 轮询 | 事件驱动的阻塞等待（`wait-breakpoint`），带超时 |
| 流程约束 | 无 | 内置工作流：断点计划先经用户确认，结束必须 disconnect，结论只能基于实际观测 |

---

## 安全性

远程调试本身是高危操作（JDWP 没有认证，能连上就能执行任意代码），所以本 Skill 把安全边界尽量做成硬约束，而不是全靠使用者自觉。

**命令端口加固**（`jdi-client` 自身的 15005 端口）

- 在代码里写死只绑定 `127.0.0.1`，没有任何开关能对外开放；跨机器使用只能走 `ssh -L` 隧道
- 校验 `Host` 头只接受 `127.0.0.1` / `localhost` / `[::1]`，带 `Origin` 的请求一律 403，挡掉浏览器跨站 POST 和 DNS rebinding
- POST 必须带 `Content-Type: application/json`（否则 415），请求体上限 1 MB，接收请求有 30s 截止时间，防止慢连接耗尽工作线程
- JSON 解析严格：拒绝嵌套超过 64 层、非法转义、未转义的控制字符、JSON 之后的多余内容

**对目标 JVM 的保护**

- 只冻结命中线程（`SUSPEND_EVENT_THREAD`），不会让整个服务停摆
- 客户端最多保留 64 个命中现场，超出后新命中不再采集、直接放行，并通过 `dropped_events` 提示，防止“等一个、不放、再等一个”把线程池慢慢抽干
- `wait-breakpoint` / `step` 都有超时；超时响应里用 `frozen_threads` 列出“已上报但没人放行”的线程，避免线程被遗忘
- 同时最多 2 个 `step` / `eval` 在执行，始终给 `resume` / `disconnect` 留一个工作线程，一个卡住的 eval 拖不垮命令端口
- `eval` 内置硬拒绝名单：`System.exit`、`Runtime.exit` / `halt` / `exec`、`ProcessBuilder.start`、`Thread.stop` / `suspend`
- 断点命中时只读字段，不调用 `toString()`，所以命中本身不会执行业务代码
- `disconnect` 后目标 JVM 中所有被冻结的线程自动恢复

**流程层面的硬规则**（写在 Skill 里，AI 必须遵守）

- 断点计划必须先给用户看，用户确认后才能连接并设置断点
- 不论正常结束、中途失败还是被用户打断，都必须 `disconnect`
- 禁止 eval 有副作用的方法（写库、发 MQ、改状态、清缓存）
- 连生产环境前必须先确认 JDWP 端口有 IP 白名单或走 SSH 隧道，并提醒用户断点会冻结业务线程
- 认证、支付、用户隐私相关栈帧里的敏感数据（token、身份证号、银行卡号、密码）不得原样贴进对话或结论

> 边界说明：拒绝名单只防手滑，**不是沙箱**，其他有副作用的方法仍然可以调用；64 的上限只限制客户端保留的现场数，**不限制目标端同时被挂起的线程数**，大量并发同时命中仍会瞬间挂起很多线程。所以生产环境要把断点放在冷路径、无锁的位置，并尽快 resume。

---

## 功能

- **断点**：方法入口断点、行断点；类还没加载时自动转为延迟断点；重载方法、同一行的多个可执行位置（含 lambda）都会装上
- **多 ClassLoader / 热部署**：同名类的所有已加载副本都装断点，之后加载的副本自动补装，用 `class_loader_id` 区分命中的是哪一份；某个副本装不上时，`deferred_failures` 会给出原因（方法名错、该行没有字节码、没用 `-g` 编译等）
- **字段树展示**：对象按字段展开到深度 2，从不自动调用 `toString()`；需要时显式 `eval "obj.toString()"`
- **并发命中隔离**：`step` 只返回指定线程的事件，其他线程的命中按原顺序排队，留给 `wait-breakpoint` 取
- **状态读取**：任意栈帧的局部变量、完整调用栈（可限制深度）、线程列表
- **表达式求值**（相当于 IDEA 的 Evaluate Expression）：局部变量、字段访问、实例方法、链式调用、静态方法 / 静态字段、字面量参数；同名类有多份时可用 `class_loader_id` 指定
- **单步执行**：`over` / `into` / `out`，响应自动带上新位置的栈和局部变量；单步途中碰到其他断点的处理与 IDEA 一致
- **热部署友好**：JDK 9+ 目标上的静态调用走 MethodHandle，不会把被调类的 ClassLoader 钉住，旧版本可以正常卸载
- **完善的错误码**：`ConnectFailed`、`Timeout`、`Superseded`、`VmDisconnected`、`Busy`、`AmbiguousClass`、`InvalidArgs` 等，每个都有明确含义和处理方式
- **断线重连**：目标 JVM 重启后发一条 `connect` 即可重连
- **无法直连时**（`--manual`）：生成一份命令清单，由用户在目标机或跳板机上执行，再把输出贴回来分析
- **兼容性**：客户端可运行在 JDK 8（需要加 `tools.jar`）或 JDK 9+；可以调试比客户端版本更老的 JVM

---

## 快速开始

1. 目标 JVM 用 JDWP 参数启动，并用 `-g` 编译（Maven / Gradle 默认就是）：

   ```bash
   # JDK 9+（zsh 下整个参数要加引号）
   java "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005" -jar app.jar
   # JDK 8
   java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005 -jar app.jar
   ```

2. 用 [`npx skills`](https://skills.sh/) 安装 Skill，然后在 Agent 中调用：

   ```bash
   # 全局安装到 Claude Code（~/.claude/skills/java-debug）
   npx skills add Aresxue/java-debug-skill --skill java-debug -g -a claude-code
   # 项目级安装（./.claude/skills/java-debug），随项目提交
   npx skills add Aresxue/java-debug-skill --skill java-debug -a claude-code
   # 其他 Agent，如 Codex / Cursor（不加 -a 则交互选择）
   npx skills add Aresxue/java-debug-skill --skill java-debug -g -a codex -a cursor
   ```

   也可以手动把 `.agents/skills/java-debug/` 拷贝（或软链）到对应 Agent 的 skills 目录。首次运行需要本地有 JDK 来构建 jar（见[构建](#构建)）。

   ```text
   /java-debug 10.0.1.5:5005 --repo /path/to/repo
   /java-debug 10.0.1.5:5005 --bp com.example.OrderService.query --bp com.example.OrderService:123
   ```

   参数：`--bp` 预设断点（可重复）、`--repo` 本地源码路径、`--max-bp` 每轮最多断点数、`--wait-timeout` 等待命中的秒数、`--manual` 只生成命令清单。

3. AI 会依次完成：读代码定位入口 → 给出断点计划等你确认 → 连接并设置断点 → 提示你触发请求 → 采集现场 / 单步 / 求值 → disconnect → 输出结论（执行路径、变量快照、根因、修复建议）。

完整流程、命令参考和错误处理见 [SKILL.md](.agents/skills/java-debug/SKILL.md)。

---

## 目录结构

```text
java-debug/
├── .agents/skills/java-debug/      # Skill 本体（安装时拷贝此目录）
│   ├── SKILL.md                    # Skill 定义：工作流、命令参考、安全约束、错误处理
│   ├── scripts/
│   │   ├── ensure-jdi-client.sh    # 首次使用时构建 scripts/jdi-client.jar（jar 不随发布，已 git 忽略）
│   │   └── jdi-client.jar          # 本地构建产物
│   └── jdi-client/                 # 客户端源码（Maven 工程）
│       ├── build.sh
│       └── src/main/java/cn/ares/jvm/jdi/
│           ├── transport/          # HTTP / stdio 传输，零依赖 JSON
│           ├── command/            # 命令路由与各命令实现
│           ├── connection/         # 连接生命周期、并发配额
│           ├── session/            # JDWP 连接、线程与 ClassLoader 查询
│           ├── breakpoint/         # 断点与延迟断点
│           ├── execution/          # 事件循环、冻结 / 等待 / 恢复 / 单步状态机
│           ├── inspect/            # 栈、局部变量、值格式化
│           └── eval/               # 表达式求值、危险方法拦截、静态调用
```

## 构建

```bash
bash .agents/skills/java-debug/scripts/ensure-jdi-client.sh
```

`scripts/jdi-client.jar` 不存在时，脚本优先用 Maven（`jdi-client/build.sh`）构建；没有 Maven 或 Maven 构建失败时，降级用本地 JDK 的 `javac` + `jar`。需要 JDK（只有 JRE 不行）：JDK 8 会自动加上 `tools.jar`，JDK 9+ 自带 `com.sun.jdi`。产物是 Java 8 字节码。脚本只检查 jar 是否存在，修改源码后需要先删掉 jar 再重新运行。
