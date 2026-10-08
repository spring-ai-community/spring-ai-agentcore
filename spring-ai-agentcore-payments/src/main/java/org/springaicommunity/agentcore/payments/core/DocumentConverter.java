/*
 * Copyright 2025-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springaicommunity.agentcore.payments.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import software.amazon.awssdk.core.document.Document;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Converts between Jackson JSON trees and AWS SDK {@link Document}s, which carry the
 * opaque x402 payloads in the AgentCore Payments API.
 *
 * @author Andrei Shakirin
 */
final class DocumentConverter {

	private DocumentConverter() {
	}

	static Document toDocument(JsonNode node) {
		if (node.isObject()) {
			Map<String, Document> map = new LinkedHashMap<>();
			for (Map.Entry<String, JsonNode> entry : node.properties()) {
				map.put(entry.getKey(), toDocument(entry.getValue()));
			}
			return Document.fromMap(map);
		}
		if (node.isArray()) {
			List<Document> list = new ArrayList<>();
			for (JsonNode element : node) {
				list.add(toDocument(element));
			}
			return Document.fromList(list);
		}
		if (node.isString()) {
			return Document.fromString(node.stringValue());
		}
		if (node.isIntegralNumber()) {
			return Document.fromNumber(node.bigIntegerValue());
		}
		if (node.isNumber()) {
			return Document.fromNumber(node.decimalValue());
		}
		if (node.isBoolean()) {
			return Document.fromBoolean(node.booleanValue());
		}
		return Document.fromNull();
	}

	static JsonNode toJsonNode(Document document) {
		JsonNodeFactory factory = JsonNodeFactory.instance;
		if (document.isMap()) {
			ObjectNode object = factory.objectNode();
			document.asMap().forEach((key, value) -> object.set(key, toJsonNode(value)));
			return object;
		}
		if (document.isList()) {
			ArrayNode array = factory.arrayNode();
			document.asList().forEach((value) -> array.add(toJsonNode(value)));
			return array;
		}
		if (document.isString()) {
			return factory.stringNode(document.asString());
		}
		if (document.isNumber()) {
			BigDecimal number = document.asNumber().bigDecimalValue();
			return (number.scale() <= 0) ? factory.numberNode(number.toBigIntegerExact()) : factory.numberNode(number);
		}
		if (document.isBoolean()) {
			return factory.booleanNode(document.asBoolean());
		}
		return factory.nullNode();
	}

}
