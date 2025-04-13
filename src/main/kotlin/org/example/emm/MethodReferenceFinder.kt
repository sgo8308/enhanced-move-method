package org.example.emm

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
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
                val dependentClass = usage.parentOfType<PsiClass>(false) ?: continue

                val qualifierExpression = methodCall.methodExpression.qualifierExpression
                val qualifier = qualifierExpression?.text

                result.add(MethodReferenceInfo(methodCall, dependentClass, qualifier))
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
}