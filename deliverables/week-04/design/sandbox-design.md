# Sandbox design (lessons 23 and 24)

Covers lesson 23 (principles, industry approaches, design review) and lesson 24 (implementation). **The Sandbox in OryxOS is an application-level policy check inside the JVM. It is not an operating-system sandbox and must not be described as one.** Section 8 states exactly what that does and does not protect.

> As in [memory-design.md](memory-design.md): the lecture text for the industry comparison is not in the repository; section 2 is from general knowledge and marked *(unverified against the lecture)*.

## 1. Threat model

An agent is a program whose next action is chosen by a language model reading untrusted text (user input, web pages, files, memory). Assume the model can be talked into calling any tool it is offered with any arguments. What we want to stop:

| Threat | Example |
|---|---|
| Read or write files outside the agent's area | `../../secrets`, another agent's directory |
| Make the server fetch internal or cloud-metadata URLs (SSRF) | `http://169.254.169.254/...`, `http://127.0.0.1:...` |
| Run arbitrary commands | `shell_exec` with `; rm -rf`, option injection (`git --upload-pack=...`) |
| One agent using another agent's rights | agent B reading agent A's workspace |
| Leaking secrets through error messages | URL with a token echoed back or logged |
| Silent refusals | a denied action that leaves no trace |

Out of scope (see section 8): a malicious or buggy *allowed* program, resource exhaustion, kernel or JVM exploits.

## 2. Industry approaches *(unverified against the lecture)*

| Approach | Isolation strength | Cost | Fit for this course project |
|---|---|---|---|
| Application allow-lists (paths, hosts, commands checked before use) | Weak against a bug in the checker or an allowed program; blind to anything the check does not cover | Cheap, portable, testable | **Chosen for lessons 23/24.** |
| OS facilities: low-privilege user, job objects / cgroups / `rlimit`, seccomp / AppArmor / Landlock | Real enforcement by the kernel | Platform specific, needs setup | Not implemented; recommended outer layer. |
| Containers | Process, filesystem and network namespaces | Needs a container runtime | Recommended deployment boundary; not part of this repo. |
| MicroVMs / gVisor-style user-space kernels | Strongest practical isolation | Heavy | Out of scope. |
| WASM / language sandboxes for tool code | Capability model inside the runtime | Tools must be written for it | Out of scope. |
| Human approval for risky actions | Not technical isolation | Slows the agent | Not implemented. |

Decision (design review outcome): ship an allow-list policy, make it strict and well tested, make every refusal auditable, and say plainly that a real deployment needs an OS or container boundary around it.

## 3. Who decides what is allowed

Two sources, combined by **intersection**:

1. **The Profile declares** what it needs under `settings.sandbox` (`read_roots`, `write_roots`, `hosts`, `commands`, `allow_private_networks`).
2. **The operator sets a ceiling** in `oryxos.sandbox.*` (`application.yaml` or environment). Default: only `.oryxos/workspace` for files, no commands, no hosts.

Effective rights = what the Profile declared that lies inside the ceiling; the rest is dropped with a WARN. A Profile with **no** `settings.sandbox` gets **nothing** (it does not inherit the global default, nor another agent's rights).

How the right reaches the check: `ToolContext` carries the calling `AgentProfile`; each sandboxed tool's first line is `sandbox.forProfile(context.profile())...check...()`, **before any I/O**. There is no thread-local and no global "current agent", so a call cannot pick up another request's rights. `ProfileSandbox.forProfile` derives and caches the effective policy per Profile object. If no Profile is available, every check fails (fail closed).

Parsing is strict (`SandboxSpec.parse`): unknown keys, wrong types, whole-drive roots, UNC roots, `*` or `*.com` host wildcards, path-style command names and argument regexes that accept anything all raise `SandboxConfigException`. **An invalid sandbox section in the operator config or in a Profile stops the application from starting**, with a one-line cause. (Other kinds of invalid Profile are skipped with an error log.)

| Class | Role |
|---|---|
| `core/sandbox/Sandbox`, `SandboxSpec`, `SandboxViolation`, `SandboxConfigException` | contract and parsed declaration |
| `tool/ProfileSandbox` | per-Profile effective policy (intersection) |
| `tool/SandboxPolicy` | the allowed sets; `ceilingFromSpec` for the operator side |
| `tool/AllowListSandbox`, `CommandRule` | path/host/command matching |
| `tool/FileReadTool`, `FileWriteTool`, `HttpGetTool`, `ShellTool`, `notify/NotifyTool` | call the sandbox first |
| `tool/RegistryToolExecutor` | maps `SandboxViolation` to a *denied* tool result; other exceptions to a *failure* |
| `tool/AuditedToolExecutor` | writes the audit row for success, failure and denial |
| `boot/ToolConfiguration` | builds the ceiling and the single `ToolRegistry` |

## 4. File system

Paths are resolved with `toRealPath` (follows symbolic links and Windows junctions) and compared with `Path.startsWith` (path components, not string prefixes, so `/ws/a` does not allow `/ws/ab`). Read and write roots are separate. Also rejected: UNC and device paths (`\\server\share`, `\\?\`, `\\.\`), drive-relative paths (`C:foo`), alternate data streams and any `:` in a name, DOS device names (`CON`, `NUL`, `COM1`...) with any extension, names ending in `.` or space, and the characters `< > | " ? * \`. A path the platform cannot resolve is a denial, and the denial text never echoes the path.

## 5. HTTP and SSRF

Decision is made on the **parsed host**, never on a substring:

- scheme http/https only; user-info (`user:pw@host`) always rejected; hosts case-folded, trailing dot stripped, non-ASCII and malformed hosts rejected;
- exact host entries match exactly (any port); `*.example.org` matches subdomains only (not `example.org`, not `eviltrusted.org`, not `trusted.org.evil.com`);
- **IP literals** pass only when approved exactly; IPv4-mapped IPv6 is judged by the embedded IPv4; disguised forms (`2130706433`, `0x7f.1`, `0177.0.0.1`, `127.1`) are rejected;
- **never allowed, even if listed**: link-local `169.254/16`, `fe80::/10`, AWS `fd00:ec2::254`, Alibaba `100.100.100.200`, `0.0.0.0/8`, multicast;
- **private and loopback ranges** (`10/8`, `172.16/12`, `192.168/16`, CGNAT, ULA, `127/8`, `::1`): a hostname that resolves to one is refused unless both the Profile and the operator set `allow_private_networks`;
- a name is resolved and **every** address is checked; one bad address or a failed lookup refuses the call;
- **no redirects**: the `HttpClient` must be `Redirect.NEVER` (checked at construction); a 3xx is a failure and the `Location` target is never requested (tested);
- invalid URLs are not echoed back (they can carry tokens).

## 6. Shell

No shell string. The rule is "program name plus one regex per argument":

- only a bare program name (no path, no `git status`, no `git; rm`);
- the program must be in the Profile's declaration and inside the operator's ceiling;
- every argument must **fully match** one declared regex, at most `max_args` arguments; a program declared without rules accepts **no** arguments;
- tested: option injection (`--upload-pack=`, `-c core.pager=`), command substitution, redirection, pipes, newlines, `../`;
- 64 KB output limit and a timeout (`destroyForcibly`), child environment reduced to `PATH`/`SystemRoot`.

## 7. Audit

Every tool attempt writes a `tool_invocations` row (`session_id`, `tool_name`, `success`, `error_category`, `error_message`, `duration_ms`, `occurred_at`). A refusal has `success=0`, `error_category='SandboxDenied'` and a short sanitised reason (for example `Path outside allowed WRITE roots`); an unexpected exception is `Tool threw (<ClassName>)`; both go through `SafeErrorText` (URL reduced to scheme and host, `token=`/`api_key=`/`Authorization: Bearer` values replaced, opaque 24+ character strings masked, 200 characters). The exception's own message is never stored. The table can be read through `GET /api/v1/tool-calls`, and the admin console shows it next to scheduled runs.

## 8. Application-level allow-list is not an OS sandbox

What it guarantees: a refused action is not performed, and the decision uses resolved, normalised data and the calling agent's own rights.

What it does **not** do:

- It cannot stop an **allowed** program from doing harm (if `java` is allowed, that program can do anything its OS user can; we only constrain the arguments we pass).
- No CPU, memory, disk, process or file-descriptor limits (shell has only a timeout and an output cap).
- No network isolation: only the target of our own `http_get` is checked, not what a subprocess does.
- No defence against kernel, JVM or library vulnerabilities, or lateral movement after compromise.
- Other processes running as the same OS user can read and write the same files.
- A check-then-use race (TOCTOU) remains: if another process swaps a path component for a link after the check and before the open, the check is stale. No `O_NOFOLLOW`-style atomic open is used.
- **DNS rebinding** is not eliminated: we resolve and check all addresses, then Java's `HttpClient` resolves again; the two answers can differ. Fixing it needs connect-time IP pinning or an egress proxy/firewall.
- Ports are not part of the decision, IPv6 zone ids and IDN are rejected, redirects are not followed.

For untrusted prompts or untrusted users, run OryxOS in a container or a dedicated low-privilege account with a network policy. This repository does not provide that layer. Marking this "isolation risk" in code (for example a flag on a Profile) is not implemented; it is documented here and in [known-limitations.md](known-limitations.md).

## 9. Windows specifics

Symbolic-link creation needs a privilege that this machine's account does not have. The four symlink tests in `PathSecurityTest` are therefore **skipped** (reported as skipped, never silently passed). The same resolution path (`toRealPath`) is exercised by the **junction** escape test, which runs and passes. Re-run the four symlink tests on Linux/macOS or with Windows developer mode.

## 10. Evidence

| Evidence | Where |
|---|---|
| Declaration parsing and validation | `SandboxSpecTest` 9, `SandboxStartupValidationTest` 7 (startup fails with a one-line cause) |
| Paths | `PathSecurityTest` 18 (4 skipped, see section 9) |
| URLs and SSRF | `UriSecurityTest` 20 |
| Intersection and isolation between Profiles | `ProfileSandboxTest` 12, `ProfileSandboxLoadingTest` 5, `AllowListSandboxTest` 6 |
| Shell | `ShellSecurityTest` 10 |
| Enforcement inside the tools and no secret leakage in logs | `SandboxEnforcementTest` 12, `RegistryToolExecutorTest` 4, `ControlledToolsTest` 10, `NotifyTest` 6 |
| Whole system: denial + audit row + file not created | `SandboxIntegrationTest` 10, `ScheduledTaskE2ETest` (scheduled denied write and denied URL) |
| Real JAR | `evidence/live-check.txt` section "Sandbox" (9 attempts, 7 audited refusals compared with the SQLite table); earlier B2 table in `docs/course-progress.md` |
| Mutation check | B2 and B3 records: removing a guard made specific tests fail |

## 11. Limitations

See section 8, plus: no refusal rate-limiting or alerting; the per-Profile policy cache has no eviction (matters when agents can be created at runtime, lesson 29/30); on Linux names containing `:` or `\` are refused too (consistency over convenience); real MCP servers and real webhooks were never exercised under the sandbox.
