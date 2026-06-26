# Contributing

## Development Workflow

### Working on a module

```bash
# Fix style + format for your module
mvn spring-javaformat:apply rewrite:run -pl spring-ai-agentcore-memory

# Run tests for your module
mvn verify -pl spring-ai-agentcore-memory

# If your module depends on local changes in another module
mvn verify -pl spring-ai-agentcore-memory -am
```

### Before pushing

```bash
# Fix all style issues (formatting + rewrite recipes)
mvn spring-javaformat:apply rewrite:run -pl <your-module>

# Verify your module passes
mvn verify -pl <your-module>
```

CI runs the full build across all modules — you don't need to do that locally.

### Adding a new file

All Java files need:
- Apache 2.0 license header (run `mvn license:format` to apply automatically)
- Test classes named `*Tests.java` (plural), integration tests `*IT.java`

### Code style

Style is enforced automatically by three tools:

| Tool | What it checks | How to fix |
|------|---------------|------------|
| `spring-javaformat` | Indentation, braces, spacing | `mvn spring-javaformat:apply` |
| `checkstyle` | Naming, imports, Javadoc, method length, BDDMockito | Fix manually or write a recipe |
| `rewrite` | Import order, `this.` qualifier, lambda style, inner types | `mvn rewrite:run` |

If the build fails on style, run:
```bash
mvn spring-javaformat:apply rewrite:run -pl <module>
```

### Testing conventions

- Use `BDDMockito.given/then` instead of `Mockito.when/verify`
- Use AssertJ `assertThat(...)` instead of JUnit `assertEquals/assertTrue`
- Use `@ExtendWith(MockitoExtension.class)` for mock setup

### Integration tests

Integration tests require AWS credentials and are skipped by default. Run them with:
```bash
AGENTCORE_IT=true mvn verify -pl spring-ai-agentcore-memory
```

To run all integration tests across all modules:
```bash
AGENTCORE_IT=true AGENTCORE_EVAL_PROBE=true mvn verify \
  -pl spring-ai-agentcore-memory,spring-ai-agentcore-browser,spring-ai-agentcore-code-interpreter,spring-ai-agentcore-evaluations
```

#### Required environment variables

| Variable | Description |
|----------|-------------|
| `AGENTCORE_IT=true` | Enables memory, browser, and code-interpreter IT tests |
| `AGENTCORE_EVAL_PROBE=true` | Enables evaluations IT tests |
| `AGENTCORE_MEMORY_MEMORY_ID` | Pinned memory ID for tests |

Strategy IDs (`SEMANTIC_FACTS`, `USER_PREFERENCES`, `SUMMARY`, `EPISODIC`) are
discovered at runtime by the tests themselves from the configured memory.

#### CI integration test job

A separate GitHub Actions workflow (`.github/workflows/integration-tests.yml`) runs
the live AWS integration tests. It is **non-blocking** (does not gate merge) and triggers:

- **Nightly** (cron schedule) — catches regressions automatically
- **Manual dispatch** — on-demand via Actions UI, with optional memory ID override
- **PR label** `run-integration-tests` — opt-in per PR

The workflow uses GitHub OIDC to assume an AWS IAM role (no long-lived secrets).
To set up the AWS side:

1. Create an IAM OIDC provider for `token.actions.githubusercontent.com`
2. Create an IAM role with trust policy allowing the GitHub repo
3. Attach permissions for Bedrock AgentCore (Memory, Browser, Code Interpreter, Evaluations)
4. Set `AWS_INTEGRATION_TEST_ROLE_ARN` as a repository secret
5. Set `AGENTCORE_MEMORY_ID` as a repository variable (pinned memory ID)
6. Optionally set `AWS_REGION` as a repository variable (defaults to `us-east-1`)

## CI

CI runs on every PR and merge queue entry:
1. `mvn clean verify` — compile, checkstyle, license check, tests
2. `mvn rewrite:dryRun` — verifies no rewrite recipes would change anything
3. Examples build — installs library, then builds and tests examples
4. Integration tests (separate workflow, non-blocking) — live AWS round-trips on schedule/dispatch/label

## Project structure

```
build-tools/
├── checkstyle/checkstyle.xml              # Checkstyle ruleset
├── license/apache-2.0-header.txt          # License header template
└── spring-ai-agentcore-rewrite-recipes/   # Custom OpenRewrite recipes (7 recipes, 33 tests)

spring-ai-agentcore-artifact-store/        # Shared artifact storage
spring-ai-agentcore-runtime-starter/       # Runtime contract (invocations, ping, SSE)
spring-ai-agentcore-memory/                # Memory integration (STM + LTM)
spring-ai-agentcore-browser/               # Browser automation tools
spring-ai-agentcore-code-interpreter/      # Code interpreter tools
spring-ai-agentcore-evaluations/           # Evaluation framework
spring-ai-agentcore-common/                # Shared utilities
examples/                                  # Working examples
```
