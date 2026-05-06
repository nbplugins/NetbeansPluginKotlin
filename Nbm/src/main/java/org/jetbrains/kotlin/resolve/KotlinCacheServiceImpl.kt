package org.jetbrains.kotlin.resolve

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.analyzer.ModuleInfo
import org.jetbrains.kotlin.caches.resolve.KotlinCacheService
import org.jetbrains.kotlin.container.ComponentProvider
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptor
import org.jetbrains.kotlin.diagnostics.netbeans.parser.KotlinParser
import org.jetbrains.kotlin.idea.resolve.ResolutionFacade
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.diagnostics.KotlinSuppressCache
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import com.intellij.openapi.project.Project
import org.netbeans.api.project.Project as NBProject

class KotlinCacheServiceImpl(private val ideaProject: Project, val project: NBProject) : KotlinCacheService {

    override fun getResolutionFacadeByFile(file: PsiFile, platform: TargetPlatform): ResolutionFacade? {
        if (file !is KtFile) return null
        return KotlinSimpleResolutionFacade(ideaProject, listOf(file), project)
    }

    override fun getResolutionFacade(elements: List<KtElement>, platform: TargetPlatform): ResolutionFacade =
            getResolutionFacade(elements)

    override fun getResolutionFacadeByModuleInfo(moduleInfo: ModuleInfo, platform: TargetPlatform): ResolutionFacade? = null

    override fun getSuppressionCache(): KotlinSuppressCache = object : KotlinSuppressCache() {
        override fun getSuppressionAnnotations(annotated: KtAnnotated): List<AnnotationDescriptor> {
            val context = KotlinParser.getAnalysisResult(annotated.containingKtFile, project)
                    ?.analysisResult?.bindingContext ?: return emptyList()
            val descriptor = context.get(BindingContext.DECLARATION_TO_DESCRIPTOR, annotated)
            return if (descriptor != null) descriptor.annotations.toList()
                   else annotated.annotationEntries.mapNotNull { context.get(BindingContext.ANNOTATION, it) }
        }
    }

    override fun getResolutionFacade(elements: List<KtElement>): ResolutionFacade =
            KotlinSimpleResolutionFacade(ideaProject, elements, project)
}

class KotlinSimpleResolutionFacade(
        override val project: Project,
        private val elements: List<KtElement>,
        private val nbProject: NBProject) : ResolutionFacade {
    
    override fun analyze(elements: Collection<KtElement>, bodyResolveMode: BodyResolveMode): BindingContext {
        if (elements.isEmpty()) {
            return BindingContext.EMPTY
        }
        val ktFile = elements.first().containingKtFile
        return KotlinParser.getAnalysisResult(ktFile, nbProject)?.analysisResult?.bindingContext ?: BindingContext.EMPTY
    }

    override fun resolveToDescriptor(declaration: KtDeclaration, bodyResolveMode: BodyResolveMode): DeclarationDescriptor {
        throw UnsupportedOperationException()
    }

    override val moduleDescriptor: ModuleDescriptor
        get() = throw UnsupportedOperationException()

    override fun analyze(element: KtElement, bodyResolveMode: BodyResolveMode): BindingContext {
        val ktFile = element.containingKtFile
        return KotlinParser.getAnalysisResult(ktFile, nbProject)?.analysisResult?.bindingContext ?: BindingContext.EMPTY
    }

    override fun analyzeWithAllCompilerChecks(elements: Collection<KtElement>): AnalysisResult {
        val files = elements.map { it.containingKtFile }.toSet()
        if (files.isEmpty()) throw IllegalStateException("Elements should not be empty")
        return KotlinAnalyzer.analyzeFiles(nbProject, files).analysisResult
    }

    override fun <T : Any> tryGetFrontendService(element: PsiElement, serviceClass: Class<T>): T? {
        val ktFile = (element as? KtElement)?.containingKtFile ?: return null
        return try {
            KotlinAnalyzer.analyzeFiles(nbProject, setOf(ktFile)).componentProvider.getService(serviceClass)
        } catch (e: Exception) { null }
    }

    override fun <T : Any> getFrontendService(element: PsiElement, serviceClass: Class<T>): T {
        throw UnsupportedOperationException()
    }

    override fun <T : Any> getFrontendService(serviceClass: Class<T>): T {
        val files = elements.map { it.containingKtFile }.toSet()
        if (files.isEmpty()) throw IllegalStateException("Elements should not be empty")

        val componentProvider = KotlinAnalyzer.analyzeFiles(nbProject, files).componentProvider

        return componentProvider.getService(serviceClass)
    }

    override fun <T : Any> getFrontendService(moduleDescriptor: ModuleDescriptor, serviceClass: Class<T>): T {
        throw UnsupportedOperationException()
    }

    override fun <T : Any> getIdeService(serviceClass: Class<T>): T {
        throw UnsupportedOperationException()
    }
}

@Suppress("UNCHECKED_CAST") fun <T : Any> ComponentProvider.getService(request: Class<T>): T =
        resolve(request)!!.getValue() as T