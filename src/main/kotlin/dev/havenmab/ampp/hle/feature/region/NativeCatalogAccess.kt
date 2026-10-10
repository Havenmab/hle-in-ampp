package dev.havenmab.ampp.hle.feature.region

import dev.havenmab.ampp.hle.host.HostProfileRegistry
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/*
 * 逐行移植 AM++ 的 AppleCatalogQueryMethod.kt 与 NativeCatalogAccessContract.kt。
 * 唯一改动：`dev.amenhancer.host.applemusic.AppleMusicHostProfiles.catalogQueryRename`
 * 换成插件自己的 [HostProfileRegistry]（数据来自随包发布的 1606 目标表）。
 */

internal object AppleCatalogQueryMethod {
    /**
     * Verified renames of the direct catalog query, keyed by owner class and preferred name.
     *
     * R8 reuses short names between builds, so the same name can describe a different method on
     * another host. Every entry below was established from bytecode rather than from the name,
     * and is consulted only after the preferred name fails the signature check.
     */
    fun resolve(clazz: Class<*>, preferredName: String): Method {
        find(clazz, preferredName)?.let { method ->
            return method.also { it.isAccessible = true }
        }
        val method = HostProfileRegistry.catalogQueryRename(clazz.name, preferredName)
            ?.let { name -> find(clazz, name) }
        return method?.also { it.isAccessible = true }
            ?: throw NoSuchMethodException(
                "${clazz.name}#$preferredName(String,Map,Continuation)",
            )
    }

    private fun find(clazz: Class<*>, name: String): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            val candidates = current.declaredMethods.filter { method ->
                method.name == name &&
                    !Modifier.isStatic(method.modifiers) &&
                    method.returnType == Any::class.java &&
                    method.parameterTypes.let { types ->
                        types.size == 3 &&
                            types[0] == String::class.java &&
                            Map::class.java.isAssignableFrom(types[1]) &&
                            types[2].isInterface &&
                            types[2].name == "kotlin.coroutines.Continuation"
                    }
            }
            if (candidates.size > 1) {
                throw NoSuchMethodException("Ambiguous catalog query: ${current.name}#$name")
            }
            candidates.singleOrNull()?.let { return it }
            current = current.superclass
        }
        return null
    }
}

/** Resolves the holder's uncached API without accepting a same-name static or bridge method. */
internal object NativeCatalogAccessContract {
    fun mediaApi(holderClass: Class<*>, getterName: String): Any {
        val companionTypeName = "${holderClass.name}\$Companion"
        val companionField = holderClass.declaredFields.filter { field ->
            Modifier.isStatic(field.modifiers) && field.type.name == companionTypeName
        }.singleOrNull() ?: error("MediaApiRepositoryHolder companion unavailable or ambiguous")
        val companion = requireNotNull(companionField.apply { isAccessible = true }.get(null))
        val getter = companion.javaClass.declaredMethods.filter { method ->
            method.name == getterName && method.parameterCount == 0 &&
                !Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) &&
                !method.isBridge && !method.isSynthetic &&
                method.returnType != Void.TYPE && !method.returnType.isPrimitive
        }.singleOrNull() ?: error("Apple MediaApi uncached getter unavailable or ambiguous")
        return requireNotNull(getter.apply { isAccessible = true }.invoke(companion)) {
            "Apple MediaApi without HTTP cache unavailable"
        }
    }

    fun storefrontField(mediaApi: Any, fieldName: String): Field {
        val field = generateSequence(mediaApi.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { it.name == fieldName }
            .singleOrNull() ?: error("Apple MediaApi storefront field unavailable or ambiguous")
        check(!Modifier.isStatic(field.modifiers) && field.type == String::class.java) {
            "Apple MediaApi storefront field has unexpected contract"
        }
        return field.apply { isAccessible = true }
    }
}
