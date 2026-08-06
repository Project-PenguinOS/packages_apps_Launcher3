/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.launcher3.flowerpot.parser

import com.android.launcher3.flowerpot.FlowerpotFormatException
import com.android.launcher3.flowerpot.rules.Rules

object LineParser {
    fun parse(line: String, version: Int?): Rules? {
        if (line.isBlank()) {
            return Rules.NONE
        }
        return when (line[0]) {
            '#' -> Rules.NONE

            '$' -> Rules.Version(line.rest.toInt())

            ':' -> Rules.IntentAction(line.rest)

            ';' -> Rules.IntentCategory(line.rest)

            '&' -> {
                val parts = line.rest.split("|")
                val ruleName = parts[0]
                val args = if (parts.size > 1) parts.subList(1, parts.size) else emptyList()
                Rules.CodeRule(ruleName, args.toTypedArray())
            }

            else -> if (!line[0].isLetter()) {
                throw FlowerpotFormatException(
                    "Unknown rule identifier '${line[0]}' for version $version",
                )
            } else {
                Rules.Package(line)
            }
        }.apply {
            if (version == null && !(this is Rules.None || this is Rules.Version)) {
                throw FlowerpotFormatException("Version has to be specified before any other rules")
            }
        }
    }

    private inline val String.rest get() = this.substring(1)
}

