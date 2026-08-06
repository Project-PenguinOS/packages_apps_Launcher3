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
package com.android.launcher3.flowerpot

import android.content.Context
import com.android.launcher3.flowerpot.parser.FlowerpotReader
import com.android.launcher3.flowerpot.rules.Rules
import com.android.launcher3.model.data.AppInfo
import java.io.InputStream
import java.util.Locale

class Flowerpot(
    private val context: Context,
    val name: String,
    private val loader: Flowerpot.() -> Unit,
) {

    val displayName: String by lazy {
        val id = context.resources.getIdentifier(
            "category_${name.lowercase(Locale.getDefault())}",
            "string",
            context.packageName,
        )
        if (id != 0) context.getString(id) else beautifyName(name)
    }
    private var loaded = false
    val rules: MutableSet<Rules> = mutableSetOf()
    val size get() = rules.size
    lateinit var apps: FlowerpotApps

    fun ensureLoaded() {
        if (!loaded) {
            load()
            loaded = true
        }
    }

    private fun load() {
        loader(this)
        apps = FlowerpotApps(context, this)
    }

    fun categorizeApps(appList: List<AppInfo?>?): Map<String, List<AppInfo>> {
        ensureLoaded()
        apps.updateAppList(appList)
        return apps.categorizedApps.toSortedMap()
    }

    private fun loadFromInputStream(inputStream: InputStream) {
        rules.addAll(FlowerpotReader(inputStream).readRules())
    }

    companion object {
        fun fromAssets(context: Context, path: String, name: String): Flowerpot =
            Flowerpot(context, name) {
                loadFromInputStream(context.assets.open(path))
            }

        const val VERSION_CURRENT = Version.AZALEA

        val SUPPORTED_VERSIONS = arrayOf(VERSION_CURRENT)

        const val ASSETS_PATH = "flowerpot"

        private fun beautifyName(name: String): String =
            name.replace('_', ' ').lowercase(Locale.getDefault())
                .split(" ")
                .joinToString(" ") { word ->
                    word.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                    }
                }
    }

    object Version {
        const val AZALEA = 1
    }

    class Manager private constructor(private val context: Context) {

        private val pots = mutableMapOf<String, Flowerpot>()

        init {
            loadAssets()
        }

        private fun loadAssets() {
            context.assets.list(ASSETS_PATH)?.forEach {
                pots.getOrPut(it) {
                    fromAssets(context, "$ASSETS_PATH/$it", it)
                }
            }
        }

        fun getPot(name: String, forceLoad: Boolean = true) = pots[name]?.apply {
            if (forceLoad) ensureLoaded()
        }

        fun getAllPots() = pots.values

        fun categorizeApps(appList: List<AppInfo?>?): Map<String, List<AppInfo>> {
            val categorizedApps = mutableMapOf<String, MutableList<AppInfo>>()
            val categorizedAppKeys = mutableSetOf<String>()
            val validAppList = appList?.filterNotNull() ?: emptyList()

            pots.values.forEach { pot ->
                pot.categorizeApps(appList).forEach { (category, apps) ->
                    apps.forEach { app ->
                        val key = flowerpotComponentKey(app)
                        if (key !in categorizedAppKeys) {
                            categorizedApps.getOrPut(category) { mutableListOf() }.add(app)
                            categorizedAppKeys.add(key)
                        }
                    }
                }
            }

            validAppList.filter { flowerpotComponentKey(it) !in categorizedAppKeys }
                .takeIf { it.isNotEmpty() }
                ?.let { categorizedApps["Other"] = it.toMutableList() }

            return categorizedApps.toSortedMap()
        }

        companion object {
            @Volatile
            private var instance: Manager? = null

            @JvmStatic
            fun getInstance(context: Context): Manager =
                instance ?: synchronized(this) {
                    instance ?: Manager(context.applicationContext).also { instance = it }
                }
        }
    }
}

private fun flowerpotComponentKey(app: AppInfo): String =
    "${app.targetComponent?.flattenToString() ?: app.targetPackage}#${app.user}"

