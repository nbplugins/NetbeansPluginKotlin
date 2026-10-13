/*******************************************************************************
 * Copyright 2026 nbplugins contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *******************************************************************************/
package io.github.nbplugins.kotlin.nbm.hints.intentions

/**
 * Reflection helper for reaching the members IDEA's inspection/intention API declares
 * `protected`.
 *
 * The compiled IDEA classes are consumed from another module, so Kotlin's visibility rules
 * hide their `protected` members — `getPresentation`, `createQuickFix`, `applyFix`. The
 * NetBeans adapter must drive exactly those members to reuse the IDEA behaviour instead of
 * reimplementing it. Looking the member up by name and arity across the class hierarchy
 * also skips the compiler-generated bridge and synthetic methods that share the name.
 */
internal object KaIdeaReflection {

    /**
     * Invokes the first declared method named [name] with [parameterCount] parameters found
     * walking [target]'s class hierarchy from the most-derived class upwards.
     *
     * @param target receiver of the invocation
     * @param name method name to look for
     * @param parameterCount required number of parameters (bridges and synthetics excluded)
     * @param args arguments to pass
     * @return the method's return value, or null when no matching method exists
     */
    fun call(target: Any, name: String, parameterCount: Int, vararg args: Any?): Any? =
        find(target, name, parameterCount)?.let { method ->
            method.isAccessible = true
            method.invoke(target, *args)
        }

    /**
     * Invokes [name] like [call], but fails loudly when no matching method exists.
     *
     * Use this when a missing member means the adapter is broken rather than the action simply
     * not applying. The return value is still nullable: a Kotlin `Unit` function compiles to a
     * `void` method, whose [java.lang.reflect.Method.invoke] result is always null.
     *
     * @param target receiver of the invocation
     * @param name method name to look for
     * @param parameterCount required number of parameters
     * @param args arguments to pass
     * @return the method's return value
     */
    fun callOrFail(target: Any, name: String, parameterCount: Int, vararg args: Any?): Any? {
        val method = find(target, name, parameterCount)
            ?: error("Cannot find $name/${parameterCount}args on ${target.javaClass}")
        method.isAccessible = true
        return method.invoke(target, *args)
    }

    /**
     * Finds the first declared method named [name] taking [parameterCount] parameters.
     *
     * @param target receiver whose class hierarchy is searched
     * @param name method name to look for
     * @param parameterCount required number of parameters
     * @return the method, or null when the hierarchy declares none
     */
    private fun find(target: Any, name: String, parameterCount: Int): java.lang.reflect.Method? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            val method = cls.declaredMethods.firstOrNull { m ->
                m.name == name && m.parameterCount == parameterCount && !m.isSynthetic && !m.isBridge
            }
            if (method != null) return method
            cls = cls.superclass
        }
        return null
    }
}
