package org.example.emm

import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.parentOfType

/**
 * 메소드 참조를 찾는 책임을 담당하는 클래스
 */
class MethodReferenceFinder(private val project: Project) {

    // 메소드 참조 정보 수집
    fun findReferencesOf(methodsToMove: Set<PsiMethod>): List<MethodReferenceInfo> {
        val usages = findMethodUsages(methodsToMove)
        val result = mutableListOf<MethodReferenceInfo>()

        for (usage in usages) {
            val methodCall = usage.parentOfType<PsiMethodCallExpression>(false)
            if (methodCall != null) {
                val containingClass = usage.parentOfType<PsiClass>(false) ?: continue
                val method = usage.parentOfType<PsiMethod>(true) ?: continue
                result.add(MethodReferenceInfo(methodCall, containingClass, method))
            }
        }

        return result
    }

    // 메소드 사용처 찾기
    private fun findMethodUsages(methods: Set<PsiMethod>): MutableList<PsiElement> {
        val usages = mutableListOf<PsiElement>()
        val searchScope = GlobalSearchScope.projectScope(project)

        for (method in methods) {
            ReferencesSearch.search(method, searchScope).forEach { reference ->
                usages.add(reference.element)
            }
        }
        return usages
    }

    // 메소드 내부에서 참조하는 외부 클래스 수집
    fun findExternalClassesReferencedInMethods(
        methodsToMove: Set<PsiMethod>,
        originalClass: PsiClass,
        targetClass: PsiClass
    ): Set<PsiClass> {
        val externalClassesToInject = mutableSetOf<PsiClass>()

        for (method in methodsToMove) {
            val body = method.body ?: continue
            body.accept(object : JavaRecursiveElementVisitor() {
                override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                    super.visitMethodCallExpression(expression)
                    val referencedMethod = expression.resolveMethod() ?: return
                    val referencedClass = referencedMethod.containingClass ?: return

                    // 외부 클래스인지 확인
                    if (referencedClass != originalClass && referencedClass != targetClass) {
                        externalClassesToInject.add(referencedClass)
                    }
                }
            })
        }

        return externalClassesToInject
    }
}