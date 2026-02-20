/*
 * Copyright 2025-2025 the original author or authors.
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

package org.springaicommunity.agentcore.browser;

/**
 * Utility for formatting extracted page content.
 *
 * @author Yuriy Bezsonov
 */
final class PageContentFormatter {

	private PageContentFormatter() {
	}

	/**
	 * Format page title and body text, truncating if necessary.
	 * @param title page title
	 * @param textContent body text
	 * @param maxLength maximum content length before truncation
	 * @return formatted page content
	 */
	static String format(String title, String textContent, int maxLength) {
		if (textContent.length() > maxLength) {
			textContent = textContent.substring(0, maxLength) + "\n... [truncated]";
		}
		return String.format("Title: %s\n\nContent:\n%s", title, textContent);
	}

}
