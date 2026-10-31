/*
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
 */
package io.github.nbplugins.kotlin.nbm.resolve

import com.intellij.codeInsight.Nullability
import com.intellij.codeInsight.NullableNotNullManager
import com.intellij.codeInsight.NullableNotNullManagerImpl
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import java.io.File
import java.nio.file.Path
import org.openide.filesystems.FileUtil
import utils.KotlinTestCase

/**
 * Tests [NullableNotNullManagerImpl], the standalone implementation of the IntelliJ
 * nullability service.
 *
 * The base class ships in the bundled `kotlin-compiler` JAR while no implementation is published
 * anywhere, so before this class existed every `NullableNotNullManager.getNullability(...)` call
 * failed and the J2K engine silently lost annotation-based nullability inference. The tests drive
 * the manager through the Java PSI of `nullability/Annotated.java`.
 */
class NullableNotNullManagerImplTest : KotlinTestCase("NullableNotNullManagerImplTest", "nullability") {

    /** Session over the fixture directory; created on first use. */
    private val session: KotlinAnalysisAPISession by lazy {
        KotlinAnalysisAPISession.createWithJars(
            moduleName = project.projectDirectory.name,
            binaryJars = listOf(annotationsJar()),
            sourceRoots = listOf(FileUtil.toFile(dir).toPath()),
        )
    }

    /** The Java class under test, parsed from the fixture. */
    private val psiClass: PsiClass by lazy {
        val fileObject = dir.getFileObject("Annotated.java")
        val virtualFile = session.fileMap[fileObject.path]
        assertNotNull("Annotated.java must be part of the analysis session", virtualFile)
        val psiFile = PsiManager.getInstance(session.session.project).findFile(virtualFile!!) as PsiJavaFile
        psiFile.classes.single()
    }

    /** The manager registered on the session's project by [KotlinAnalysisAPISession]. */
    private val manager: NullableNotNullManager
        get() = NullableNotNullManager.getInstance(session.session.project)

    /**
     * Finds a method of [psiClass] by name.
     *
     * @param name the method name
     * @return the method declaration
     */
    private fun method(name: String): PsiMethod =
        psiClass.findMethodsByName(name, false).single()

    /**
     * Locates the `org.jetbrains.annotations` JAR on the test classpath.
     *
     * The fixture declares `@Nullable`/`@NotNull` from this artifact; the standalone session only
     * resolves annotations whose classes are on its classpath, and this is the same artifact the
     * plugin bundles at runtime.
     *
     * @return the path of the annotations JAR
     */
    private fun annotationsJar(): Path =
        File(Class.forName("org.jetbrains.annotations.Nullable").protectionDomain.codeSource.location.toURI()).toPath()

    /** The session must register a concrete manager, not leave the abstract service slot empty. */
    fun testServiceIsRegistered() {
        assertNotNull("the analysis session must register a NullableNotNullManager", manager)
        assertTrue(
            "the registered service must be the standalone implementation",
            manager is NullableNotNullManagerImpl,
        )
    }

    /** A `@Nullable` method reports `NULLABLE` through both the instance and the static helper. */
    fun testNullableAnnotation() {
        val target = method("maybeNull")

        assertEquals(Nullability.NULLABLE, manager.findEffectiveNullabilityInfo(target)?.nullability)
        assertEquals(Nullability.NULLABLE, NullableNotNullManager.getNullability(target))
    }

    /** A `@NotNull` method reports `NOT_NULL`. */
    fun testNotNullAnnotation() {
        val target = method("alwaysNotNull")

        assertEquals(Nullability.NOT_NULL, NullableNotNullManager.getNullability(target))
    }

    /** An unannotated reference-typed method has no effective nullability annotation. */
    fun testUnannotatedMethodHasNoNullability() {
        val target = method("unannotated")

        assertNull(
            "an unannotated reference type carries no nullability info",
            manager.findEffectiveNullabilityInfo(target),
        )
        assertEquals(Nullability.UNKNOWN, NullableNotNullManager.getNullability(target))
    }

    /** A primitive return type is never nullable, so no annotation lookup is attempted. */
    fun testPrimitiveTypeIgnoresAnnotations() {
        assertNull(
            "primitive types have no nullability annotation",
            manager.findEffectiveNullabilityInfo(method("primitive")),
        )
    }

    /** A `@Nullable` parameter is reported through the static helper used by the J2K inference. */
    fun testNullableParameter() {
        val parameter = method("withParameter").parameterList.parameters.single()

        assertEquals(Nullability.NULLABLE, NullableNotNullManager.getNullability(parameter))
    }

    /** The default annotation lists must contain the JetBrains annotations the converter emits. */
    fun testDefaultAnnotationLists() {
        assertTrue(
            "org.jetbrains.annotations.Nullable must be nullable by default",
            manager.nullables.contains("org.jetbrains.annotations.Nullable"),
        )
        assertTrue(
            "org.jetbrains.annotations.NotNull must be not-null by default",
            manager.notNulls.contains("org.jetbrains.annotations.NotNull"),
        )
        assertEquals("org.jetbrains.annotations.Nullable", manager.defaultNullable)
        assertEquals("org.jetbrains.annotations.NotNull", manager.defaultNotNull)
    }

    /** Annotation names are resolvable by name, which the base class uses for container lookups. */
    fun testAnnotationNullabilityByName() {
        assertEquals(
            Nullability.NULLABLE,
            manager.getAnnotationNullability("javax.annotation.Nullable").orElse(null),
        )
        assertEquals(
            Nullability.NOT_NULL,
            manager.getAnnotationNullability("javax.annotation.Nonnull").orElse(null),
        )
        assertEquals(
            "an unrelated annotation is not a nullability annotation",
            null,
            manager.getAnnotationNullability("java.lang.Override").orElse(null),
        )
    }

    /** Replacing the lists must be reflected by the getters, which the settings UI relies on. */
    fun testAnnotationListsAreMutable() {
        val original = manager.nullables
        try {
            manager.setNullables("custom.Nullable")

            assertEquals(listOf("custom.Nullable"), manager.nullables)
            assertEquals(Nullability.NULLABLE, manager.getAnnotationNullability("custom.Nullable").orElse(null))
        } finally {
            manager.setNullables(*original.toTypedArray())
        }
    }
}
