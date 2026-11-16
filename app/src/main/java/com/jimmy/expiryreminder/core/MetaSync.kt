package com.jimmy.expiryreminder.core

import org.json.JSONArray
import org.json.JSONObject

/** 分类/空间及其颜色、顺序、改名覆盖的快照：随自动同步一起交换，体积很小。 */
data class MetaSnapshot(
    val categories: List<CustomCategory> = emptyList(),
    val categoryColors: Map<String, String> = emptyMap(),
    val categoryLabels: Map<String, String> = emptyMap(),
    val categoryOrder: List<String> = emptyList(),
    val spaces: List<SpaceInfo> = emptyList(),
    val spaceColors: Map<String, String> = emptyMap(),
) {
    fun toJson(): JSONObject {
        val colors = JSONObject()
        for ((k, v) in categoryColors) colors.put(k, v)
        val labels = JSONObject()
        for ((k, v) in categoryLabels) labels.put(k, v)
        val spaceColorJson = JSONObject()
        for ((k, v) in spaceColors) spaceColorJson.put(k, v)
        return JSONObject()
            .put("categories", JSONArray(categories.map { it.toJson() }))
            .put("categoryColors", colors)
            .put("categoryLabels", labels)
            .put("categoryOrder", JSONArray(categoryOrder))
            .put("spaces", JSONArray(spaces.map { it.toJson() }))
            .put("spaceColors", spaceColorJson)
    }

    companion object {
        fun fromJson(raw: JSONObject?): MetaSnapshot {
            if (raw == null) return MetaSnapshot()
            val categories = ArrayList<CustomCategory>()
            raw.optJSONArray("categories")?.let { arr ->
                for (i in 0 until arr.length()) {
                    CustomCategory.fromJson(arr.opt(i))?.let { categories += it }
                }
            }
            val spaces = ArrayList<SpaceInfo>()
            raw.optJSONArray("spaces")?.let { arr ->
                for (i in 0 until arr.length()) {
                    SpaceInfo.fromJson(arr.opt(i))?.let { spaces += it }
                }
            }
            val order = ArrayList<String>()
            raw.optJSONArray("categoryOrder")?.let { arr ->
                for (i in 0 until arr.length()) {
                    (arr.opt(i) as? String)?.takeIf { it.isNotBlank() }?.let { order += it }
                }
            }
            return MetaSnapshot(
                categories = categories,
                categoryColors = readColors(raw.optJSONObject("categoryColors")),
                categoryLabels = readLabels(raw.optJSONObject("categoryLabels")),
                categoryOrder = order,
                spaces = spaces,
                spaceColors = readColors(raw.optJSONObject("spaceColors")),
            )
        }

        private fun readColors(raw: JSONObject?): Map<String, String> {
            if (raw == null) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (key in raw.keys()) {
                val value = raw.optStringOrNull(key) ?: continue
                if (isHexColor(value)) out[key] = value
            }
            return out
        }

        private fun readLabels(raw: JSONObject?): Map<String, String> {
            if (raw == null) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (key in raw.keys()) {
                val value = raw.optStringOrNull(key)?.trim() ?: continue
                if (value.isNotEmpty()) out[key] = value
            }
            return out
        }
    }
}

/** 元数据归并结果：要新增的项、远端 id → 本机 id 的映射，以及合并后的颜色/顺序。 */
data class MetaPlan(
    val addCategories: List<CustomCategory> = emptyList(),
    val addSpaces: List<SpaceInfo> = emptyList(),
    val categoryIdMap: Map<String, String> = emptyMap(),
    val spaceIdMap: Map<String, String> = emptyMap(),
    val categoryColors: Map<String, String> = emptyMap(),
    val categoryLabels: Map<String, String> = emptyMap(),
    val categoryOrder: List<String> = emptyList(),
    val spaceColors: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = addCategories.isEmpty() && addSpaces.isEmpty() &&
            categoryIdMap.isEmpty() && spaceIdMap.isEmpty()
}

/**
 * 分类/空间同步（纯函数，可单测）。
 *
 * 归并规则刻意保守，避免两台设备来回覆盖：
 * 1. 同 id → 保留本机，远端 id 映射到本机 id；
 * 2. 名称（去空格/忽略大小写）相同但 id 不同 → 视为同一项，映射到本机 id，不新增；
 * 3. 本机没有 → 新增远端项（保留其 id），并把远端 id 映射到自身；
 * 4. 颜色 / 改名覆盖：本机优先，只补远端有而本机没有的键。
 */
object MetaSync {

    fun plan(local: MetaSnapshot, remote: MetaSnapshot): MetaPlan {
        val catIds = local.categories.associateBy { it.id }.toMutableMap()
        val catByLabel = local.categories
            .associateByTo(HashMap()) { Similarity.normalizeName(it.label) }
        val catMap = LinkedHashMap<String, String>()
        val addCats = ArrayList<CustomCategory>()

        for (remoteCat in remote.categories) {
            val byId = catIds[remoteCat.id]
            if (byId != null) {
                catMap[remoteCat.id] = byId.id
                continue
            }
            val byLabel = catByLabel[Similarity.normalizeName(remoteCat.label)]
            if (byLabel != null) {
                catMap[remoteCat.id] = byLabel.id
                continue
            }
            // id 撞车但名字不同：给远端项换一个本机 id，避免污染已有记录
            val id = if (catIds.containsKey(remoteCat.id)) "c_${uid()}" else remoteCat.id
            val added = remoteCat.copy(id = id)
            addCats += added
            catIds[id] = added
            catByLabel[Similarity.normalizeName(added.label)] = added
            catMap[remoteCat.id] = id
        }

        val spaceIds = local.spaces.associateBy { it.id }.toMutableMap()
        val spaceByLabel = local.spaces
            .associateByTo(HashMap()) { Similarity.normalizeName(it.label) }
        val spaceMap = LinkedHashMap<String, String>()
        val addSpaces = ArrayList<SpaceInfo>()
        for (remoteSpace in remote.spaces) {
            val byId = spaceIds[remoteSpace.id]
            if (byId != null) {
                spaceMap[remoteSpace.id] = byId.id
                continue
            }
            val byLabel = spaceByLabel[Similarity.normalizeName(remoteSpace.label)]
            if (byLabel != null) {
                spaceMap[remoteSpace.id] = byLabel.id
                continue
            }
            val id = if (spaceIds.containsKey(remoteSpace.id)) "sp_${uid()}" else remoteSpace.id
            val added = remoteSpace.copy(id = id)
            addSpaces += added
            spaceIds[id] = added
            spaceByLabel[Similarity.normalizeName(added.label)] = added
            spaceMap[remoteSpace.id] = id
        }

        val knownCategories = Categories.BUILTIN.map { it.id }.toSet() +
            catIds.keys
        val order = LinkedHashSet<String>()
        for (id in local.categoryOrder) if (id in knownCategories) order.add(id)
        for (id in remote.categoryOrder) {
            val mapped = catMap[id] ?: id
            if (mapped in knownCategories) order.add(mapped)
        }
        for (cat in catIds.values) order.add(cat.id)

        return MetaPlan(
            addCategories = addCats,
            addSpaces = addSpaces,
            categoryIdMap = catMap,
            spaceIdMap = spaceMap,
            categoryColors = mergeColors(local.categoryColors, remote.categoryColors),
            categoryLabels = mergeLabels(local.categoryLabels, remote.categoryLabels),
            categoryOrder = order.toList(),
            spaceColors = mergeColors(local.spaceColors, remote.spaceColors),
        )
    }

    /** 把远端物品上的分类/空间引用改成本机 id；内置分类和空空间不受影响。 */
    fun remap(items: List<ExpiryItem>, plan: MetaPlan): List<ExpiryItem> =
        items.map { item ->
            val category = plan.categoryIdMap[item.category] ?: item.category
            val space = plan.spaceIdMap[item.spaceId] ?: item.spaceId
            if (category == item.category && space == item.spaceId) item
            else item.copy(category = category, spaceId = space)
        }

    private fun mergeColors(local: Map<String, String>, remote: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap(local)
        for ((k, v) in remote) if (k !in out && isHexColor(v)) out[k] = v
        return out
    }

    private fun mergeLabels(local: Map<String, String>, remote: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap(local)
        for ((k, v) in remote) if (k !in out && v.isNotBlank()) out[k] = v.trim()
        return out
    }
}
