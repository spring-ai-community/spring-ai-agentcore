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

## CI

CI runs on every PR and merge queue entry:
1. `mvn clean verify` — compile, checkstyle, license check, tests
2. `mvn rewrite:dryRun` — verifies no rewrite recipes would change anything
3. Examples build — installs library, then builds and tests examples

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
