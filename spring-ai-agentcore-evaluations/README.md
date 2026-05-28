# Spring AI AgentCore Evaluations

This module provides integration with Amazon Bedrock AgentCore Evaluate API for evaluating agent responses in Spring AI applications.

## Features

- **Built-in Evaluators**: Use pre-built evaluators like `Builtin.Helpfulness`, `Builtin.Correctness`
- **Custom Evaluators**: Support for custom evaluators created via the AgentCore control plane
- **Spring AI Advisor Integration**: Seamlessly integrate with ChatClient via `CallAdvisor` and `StreamAdvisor`
- **Async Evaluation**: Non-blocking evaluation by default
- **Sampling**: Configure evaluation sample rate to control costs
- **Micrometer Metrics**: Built-in metrics for monitoring evaluation scores, latency, and errors
- **Callbacks**: Custom callbacks for evaluation events

## Quick Start

### 1. Add Dependency

```xml
<dependency>
    <groupId>org.springaicommunity.agentcore</groupId>
    <artifactId>spring-ai-agentcore-evaluations</artifactId>
    <version>${spring-ai-agentcore.version}</version>
</dependency>
```

### 2. Configure Properties

```yaml
spring:
  ai:
    agentcore:
      evaluations:
        enabled: true
        region: us-east-1
        evaluator-ids:
          - Builtin.Helpfulness
          - Builtin.Correctness
        async: true
        sample-rate: 1.0
        metrics-enabled: true
```

### 3. Use with ChatClient

```java
@Autowired
private AgentCoreEvaluationAdvisor evaluationAdvisor;

@Autowired
private ChatClient.Builder chatClientBuilder;

public String chat(String userMessage) {
    ChatClient chatClient = chatClientBuilder
        .defaultAdvisors(evaluationAdvisor)
        .build();
    
    return chatClient.prompt()
        .user(userMessage)
        .call()
        .content();
}
```

## Configuration Properties

| Property | Description | Default |
|----------|-------------|---------|
| `spring.ai.agentcore.evaluations.enabled` | Enable/disable evaluations | `false` |
| `spring.ai.agentcore.evaluations.region` | AWS region for AgentCore | `us-east-1` |
| `spring.ai.agentcore.evaluations.evaluator-ids` | List of evaluator IDs | `[Builtin.Helpfulness]` |
| `spring.ai.agentcore.evaluations.async` | Run evaluations asynchronously | `true` |
| `spring.ai.agentcore.evaluations.sample-rate` | Sampling rate (0.0-1.0) | `1.0` |
| `spring.ai.agentcore.evaluations.metrics-enabled` | Enable Micrometer metrics | `true` |

## Built-in Evaluators

AgentCore provides several built-in evaluators:

- `Builtin.Helpfulness` - Evaluates how helpful the response is
- `Builtin.Correctness` - Evaluates factual accuracy
- `Builtin.Harmfulness` - Detects potentially harmful content
- `Builtin.Coherence` - Evaluates response coherence

## Accessing Evaluation Results

Results are stored in the response context:

```java
ChatClientResponse response = chatClient.prompt()
    .user("What is the capital of France?")
    .call();

List<EvaluationResult> results = (List<EvaluationResult>) 
    response.context().get(AgentCoreEvaluationAdvisor.EVALUATION_RESULTS_KEY);

for (EvaluationResult result : results) {
    System.out.println("Evaluator: " + result.evaluatorId());
    System.out.println("Score: " + result.score());
    System.out.println("Label: " + result.label());
    System.out.println("Explanation: " + result.explanation());
}
```

## Custom Callbacks

Register a callback to receive evaluation events:

```java
@Bean
public AgentCoreEvaluationAdvisor evaluationAdvisor(AgentCoreEvaluationClient client) {
    return AgentCoreEvaluationAdvisor.builder(client)
        .evaluatorIds(List.of("Builtin.Helpfulness"))
        .callback(event -> {
            log.info("Evaluation completed for session {}: {} results",
                event.sessionId(), event.results().size());
        })
        .build();
}
```

## Metrics

When Micrometer is on the classpath and metrics are enabled, the following metrics are published:

| Metric | Type | Description |
|--------|------|-------------|
| `agentcore.evaluation.score` | Distribution Summary | Score distribution by evaluator |
| `agentcore.evaluation.count` | Counter | Count of evaluations by evaluator and label |
| `agentcore.evaluation.latency` | Timer | Evaluation API call duration |
| `agentcore.evaluation.errors` | Counter | Failed evaluations by evaluator and error code |

## Manual Usage

For more control, use the client directly:

```java
@Autowired
private AgentCoreEvaluationClient evaluationClient;

public void evaluate(String prompt, String response) {
    SpanEventBuilder spanBuilder = SpanEventBuilder
        .agentInvocation("trace-id", "session-id")
        .promptEvent(prompt)
        .completionEvent(response);
    
    List<Map<String, Object>> spans = List.of(spanBuilder.build());
    
    List<EvaluationResult> results = evaluationClient.evaluate(
        "Builtin.Helpfulness", spans);
    
    for (EvaluationResult result : results) {
        System.out.println("Score: " + result.score());
    }
}
```

## AWS Credentials

The module uses the default AWS credential provider chain. Ensure your application has appropriate IAM permissions for the `bedrock-agentcore:Evaluate` action.

## License

Apache License 2.0
