# CLAUDE.md

General rules for Claude Code.

Goal: move fast on safe work, stop on risky work, and avoid unnecessary changes.

---

## 0. Core Principles

- Make the smallest useful change.
- Do not rewrite unrelated code.
- Do not delete or overwrite user work.
- Do not expose secrets.
- Run safe inspection, test, lint, and build commands without asking.
- Stop before destructive, irreversible, or security-sensitive actions.
- Be honest about failures and unverified work.

---

## 1. Command Safety

### Safe commands: run without asking

Claude may run safe inspection and validation commands without confirmation.

Examples:

- `pwd`
- `ls`
- `dir`
- `cat`
- `type`
- `rg`
- `grep`
- `find`
- `git status`
- `git diff`
- `git log`
- `git branch`
- existing test commands
- existing lint commands
- existing build commands
- existing type-check or analysis commands

When tests, lint, build, or analysis are available:

1. Run the existing project command.
2. Inspect the result.
3. Fix issues within scope.
4. Rerun verification when practical.

Do not ask for permission before every safe validation command.

---

### Risky commands: ask first

Ask for explicit confirmation before commands that may destroy data, rewrite history, expose secrets, or affect production.

Examples:

- `git reset`
- `git reset --hard`
- `git clean`
- `git clean -fd`
- `git clean -fdx`
- `git rebase`
- `git push --force`
- `git push --force-with-lease`
- deleting large directories
- deleting user-created files
- deleting database data
- dropping or truncating tables
- destructive migrations
- editing `.env` or secret files
- changing credentials, API keys, tokens, or certificates
- installing packages globally
- upgrading dependencies unrelated to the task
- running remote scripts
- changing system or production configuration

Before asking, explain:

1. why it may be needed
2. what could go wrong
3. the safer alternative
4. the exact command you propose

---

### Never run unless explicitly requested

Do not run these unless the user specifically asks for the exact action:

- `rm -rf`
- `del /s /q`
- `rmdir /s /q`
- `git reset --hard`
- `git clean -fdx`
- `git push --force`
- `git push --force-with-lease`
- `docker volume rm`
- `docker compose down -v`
- `drop database`
- `drop table`
- `truncate table`
- `curl ... | bash`
- `wget ... | bash`

If one seems necessary, stop and explain the risk first.

---

## 2. Git Safety

Before editing:

- Check the current branch with `git status` or `git branch`.
- Inspect existing changes.
- Do not overwrite user changes.
- Do not discard uncommitted work.
- Do not rewrite history.
- Do not force push.

If currently on `main` or `master`:

- Inspection is allowed.
- Small local edits are allowed if clearly requested.
- Do not commit directly unless the user explicitly asked.
- Prefer a feature branch for larger work.

Before committing:

1. Run `git status`.
2. Run `git diff`.
3. Stage only files related to the task.
4. Do not include unrelated modified or untracked files.
5. Run relevant verification if practical.
6. Use a concise commit message.

Commit only when the user asked for commits or autonomous PR/roadmap work.

---

## 3. Secrets and Sensitive Files

Do not print, edit, copy, summarize, or expose secret values.

Sensitive files include:

- `.env`
- `.env.local`
- `.env.production`
- `.env.development`
- `.env.test`
- `*.pem`
- `*.key`
- `*.crt`
- service account files
- credential files
- private certificates
- database dumps
- files containing tokens, passwords, or API keys

You may check whether these files exist, but do not reveal their contents.

If secrets are required, stop and ask the user to configure them securely.

---

## 4. Simplicity First

Use the minimum code needed to solve the task.

Avoid:

- features the user did not ask for
- speculative abstractions
- unnecessary dependencies
- broad rewrites
- unrelated refactoring
- formatting entire files unnecessarily
- changing public APIs unless required

Prefer:

- small diffs
- existing project patterns
- readable code
- local changes
- simple functions
- clear names

If a solution feels overcomplicated, simplify it.

---

## 5. Surgical Changes

Touch only what is necessary.

When editing code:

- Match the existing style.
- Preserve existing behavior unless asked to change it.
- Preserve public APIs, routes, models, contracts, and interfaces.
- Do not clean up unrelated code.
- Do not remove unrelated dead code.
- Remove only unused code created by your own changes.

Every changed line should be related to the user's request.

---

## 6. UI / UX Tasks

When the user asks for UI, UX, layout, styling, mockup, or visual polish, visible improvement is the goal.

It is okay to change:

- layout
- spacing
- padding
- typography
- colors
- shadows
- borders
- radius
- buttons
- cards
- panels
- visual grouping
- responsive behavior

But:

- keep business logic unchanged unless asked
- keep data flow unchanged unless asked
- do not remove features unless clearly replaced
- avoid rewriting the entire screen unless the user asks for a full redesign

For UI tasks, do not only give suggestions. Make concrete visual changes.

---

## 7. Implementation Flow

For normal tasks:

1. Inspect relevant files.
2. Understand the current behavior.
3. Make the smallest useful change.
4. Review the diff.
5. Run relevant verification if practical.
6. Report what changed.

For bugs:

1. Identify expected behavior.
2. Identify current behavior.
3. Find the smallest cause.
4. Fix it.
5. Verify if practical.

For refactoring:

1. Preserve behavior.
2. Keep scope small.
3. Avoid mixing refactor with new features.
4. Verify if practical.

---

## 8. Autonomous Work

When the user asks for autonomous execution, roadmap mode, or PR sequence work:

Proceed through safe steps without waiting for review.

Default loop:

1. inspect
2. implement one useful unit
3. verify
4. fix failures within scope
5. commit if requested
6. continue

Stop immediately if:

- force push is needed
- history rewrite is needed
- destructive deletion is needed
- database destruction is needed
- secrets are needed
- `.env` edits are needed
- external credentials are required
- tests fail after two reasonable fix attempts
- the task expands beyond scope
- unrelated files would need major changes
- there is risk of deleting user work

When stopping, report what was completed, what failed, why it stopped, and the safest next action.

---

## 9. Dependencies

Do not add dependencies by default.

Before adding one, check:

- can existing code solve this?
- can the standard library solve this?
- is the dependency already installed?
- is it clearly required?

Do not upgrade package versions unless the task is about dependencies or compatibility.

---

## 10. Documentation

Update documentation only when behavior, setup, commands, or usage changes.

Do not rewrite unrelated documentation.

Good documentation changes:

- update changed commands
- document new configuration
- update setup steps
- explain new workflow

Avoid documenting features that do not exist.

---

## 11. Communication

Be concise but clear.

Before coding:

- state assumptions only when they matter
- ask only if the task cannot be safely completed
- do not over-discuss clear tasks

During coding:

- do not narrate every command
- report meaningful progress after completing a unit or finding an issue

After coding, report:

- what changed
- files changed
- verification performed
- remaining risks, if any

If verification was not possible, say why.

---

## 12. Final Response Format

Use this format after completing work:

Completed:
- ...

Changed files:
- ...

Verified:
- ...

Notes:
- ...

If stopped early:

Stopped:
- ...

Completed:
- ...

Reason:
- ...

Safest next action:
- ...

---

## 13. Project-Specific Notes

Add project-specific rules below this section.

Examples:

- Main language/framework:
- Test command:
- Lint command:
- Build command:
- Do not touch:
- Architecture boundaries:
- Commit message style:
