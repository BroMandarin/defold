// Copyright 2020-2023 The Defold Foundation
// Copyright 2014-2020 King
// Copyright 2009-2014 Ragnar Svensson, Christian Murray
// Licensed under the Defold License version 1.0 (the "License"); you may not use
// this file except in compliance with the License.
// 
// You may obtain a copy of the License, together with FAQs at
// https://www.defold.com/license
// 
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

package com.dynamo.bob.util;

import java.util.Locale;

public class StringUtil {

	/**
	 * Using String.toUpperCase() with Locale.ROOT
	 * @param s String to convert to upper-case
	 * @return The upper-case version of the string
	 */
	public static String toUpperCase(String s) {
		return s.toUpperCase(Locale.ROOT);
	}

	/**
	 * Using String.toLowerCase() with Locale.ROOT
	 * @param s String to convert to lower-case
	 * @return The lower-case version of the string
	 */
	public static String toLowerCase(String s) {
		return s.toLowerCase(Locale.ROOT);
	}
}