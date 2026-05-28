# Spring AI AgentCore Evaluations Example

This example demonstrates how to use the AgentCore Evaluations module to automatically evaluate agent responses.

## Features

- Automatic evaluation of chat responses using multiple built-in evaluators
  (`Builtin.Helpfulness`, `Builtin.Correctness`, `Builtin.Coherence`)
- Evaluation results returned in API response
- Micrometer metrics exposed via Prometheus endpoint

## Prerequisites

- Java 21+
- AWS credentials configured with access to Bedrock and AgentCore
- AgentCore Evaluate API access

## Running the Example

```bash
cd examples/spring-ai-evaluations
mvn spring-boot:run
```

## Testing

Send a chat request:

```bash
curl -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "What is the capital of France?"}'
```

Response includes evaluation results from all configured evaluators:

```json
{
  "content": "The capital of France is Paris.",
  "evaluations": [
    { "evaluatorId": "Builtin.Helpfulness", "score": 0.83, "label": "Very Helpful",       "explanation": "..." },
    { "evaluatorId": "Builtin.Correctness", "score": 1.0,  "label": "Perfectly Correct",  "explanation": "..." },
    { "evaluatorId": "Builtin.Coherence",   "score": 1.0,  "label": "Completely Yes",     "explanation": "..." }
  ]
}
```

## Metrics

View evaluation metrics at:

```bash
curl http://localhost:8080/actuator/prometheus | grep agentcore
```

Available metrics:
- `agentcore_evaluation_score` - Score distribution
- `agentcore_evaluation_count` - Count by evaluator and label
- `agentcore_evaluation_latency` - API call duration
- `agentcore_evaluation_errors` - Error count

## Configuration

Defaults below are the module defaults; see `src/main/resources/application.properties` for what this example overrides (notably `evaluator-ids` is set to three evaluators and `async` to `false`).

| Property | Description | Default |
|----------|-------------|---------|
| `spring.ai.agentcore.evaluations.enabled` | Enable evaluations | `false` |
| `spring.ai.agentcore.evaluations.evaluator-ids` | Evaluators to use | `Builtin.Helpfulness` |
| `spring.ai.agentcore.evaluations.async` | Run async | `true` |
| `spring.ai.agentcore.evaluations.sample-rate` | Sampling rate (0.0-1.0) | `1.0` |

Note: this example sets `async=false` so the controller can include evaluation
results in the HTTP response. Leave `async=true` (the default) when you only
consume results via the callback or metrics.

## Available Evaluators

- `Builtin.Helpfulness` - Evaluates response helpfulness
- `Builtin.Correctness` - Evaluates factual accuracy
- `Builtin.Harmfulness` - Detects harmful content
- `Builtin.Coherence` - Evaluates response coherence
