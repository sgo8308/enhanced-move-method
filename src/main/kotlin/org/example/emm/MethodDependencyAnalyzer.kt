package org.example.emm

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentOfType

/**
 * 메소드 의존성 분석을 담당하는 클래스입니다.
 * 메소드 호출 그래프 생성 및 의존성 분석 기능을 제공합니다.
 */
class MethodDependencyAnalyzer {

    /**
     * 주어진 메소드의 의존성을 분석하고, 각 의존성 메소드가 독점적인지 여부를 반환합니다.
     * 독점적인 메소드는 대상 메소드나 그 의존성 체인에서만 사용되는 메소드입니다.
     */
    fun findMethodDependenciesWithExclusivity(
        method: PsiMethod,
        sourceClass: PsiClass,
        project: Project
    ): Map<PsiMethod, Boolean> {
        // 호출 그래프 구성
        val methodCallGraph = createCallGraph(sourceClass)
        val allSourceMethods = sourceClass.methods.toSet()

        // 1. 먼저 대상 메소드에서 직접 또는 간접적으로 호출하는 모든 메소드 추적
        val reachableFromTarget = mutableSetOf<PsiMethod>()

        fun collectReachableMethods(currentMethod: PsiMethod, visited: MutableSet<PsiMethod>) {
            if (currentMethod in visited) return
            visited.add(currentMethod)

            val calledMethods = methodCallGraph[currentMethod].orEmpty()
            for (calledMethod in calledMethods) {
                reachableFromTarget.add(calledMethod)
                collectReachableMethods(calledMethod, visited)
            }
        }

        collectReachableMethods(method, mutableSetOf())

        // 2. 프로젝트 내에서 각 메소드를 참조하는 곳 찾기
        val methodReferences = mutableMapOf<PsiMethod, List<PsiElement>>()
        for (dependencyMethod in reachableFromTarget) {
            val references = ReferencesSearch.search(
                dependencyMethod,
                GlobalSearchScope.projectScope(project)
            ).findAll().map { it.element }
            methodReferences[dependencyMethod] = references
        }

        // 3. 각 메소드가 이동 대상인지 확인
        val result = mutableMapOf<PsiMethod, Boolean>()

        for (dependencyMethod in reachableFromTarget) {
            val references = methodReferences[dependencyMethod] ?: emptyList()

            // 메소드 참조가 모두 소스 클래스 내부에 있는지 확인
            val externalReferences = references.filter { reference ->
                val containingClass = reference.parentOfType<PsiClass>(false)
                containingClass != null && containingClass != sourceClass
            }

            // 소스 클래스 내 참조들이 모두 이동 대상 메소드에서만 오는지 확인
            val internalReferences = references.filter { reference ->
                val containingClass = reference.parentOfType<PsiClass>(false)
                containingClass == sourceClass
            }

            val nonTargetChainReferences = internalReferences.filter { reference ->
                val containingMethod = reference.parentOfType<PsiMethod>(false)
                containingMethod != null &&
                        containingMethod != method &&
                        !isInMethodChain(containingMethod, method, methodCallGraph)
            }

            // 외부 참조나 이동 대상 체인 외부의 내부 참조가 없는 경우에만 독점적
            val isExclusive = externalReferences.isEmpty() && nonTargetChainReferences.isEmpty()

            result[dependencyMethod] = isExclusive
        }

        return result
    }

    /**
     * 메소드가 다른 메소드의 호출 체인 내에 있는지 확인합니다.
     */
    private fun isInMethodChain(
        method: PsiMethod,
        targetMethod: PsiMethod,
        methodCallGraph: Map<PsiMethod, Set<PsiMethod>>
    ): Boolean {
        val visited = mutableSetOf<PsiMethod>()

        fun search(current: PsiMethod): Boolean {
            if (current == targetMethod) return true
            if (current in visited) return false
            visited.add(current)

            val calledBy = methodCallGraph.entries
                .filter { current in it.value }
                .map { it.key }

            return calledBy.any { search(it) }
        }

        return search(method)
    }

    /**
     * 메소드 호출 그래프를 생성합니다.
     * 각 메소드가 호출하는 다른 메소드들의 맵을 반환합니다.
     */
    private fun createCallGraph(
        sourceClass: PsiClass
    ): MutableMap<PsiMethod, MutableSet<PsiMethod>> {
        val allSourceMethods = sourceClass.methods.toSet()
        val methodCallGraph = mutableMapOf<PsiMethod, MutableSet<PsiMethod>>()

        for (srcMethod in allSourceMethods) {
            methodCallGraph[srcMethod] = mutableSetOf()
            val methodCalls = PsiTreeUtil.findChildrenOfType(srcMethod, PsiMethodCallExpression::class.java)

            for (methodCall in methodCalls) {
                val resolveResult = methodCall.methodExpression.advancedResolve(false)
                val calledMethod = resolveResult.element as? PsiMethod ?: continue
                val calledMethodClass = calledMethod.containingClass ?: continue

                if (calledMethodClass == sourceClass) {
                    methodCallGraph[srcMethod]?.add(calledMethod)
                }
            }
        }

        return methodCallGraph
    }

    /**
     * 메소드 srcMethod가 직접 또는 간접적으로 dependencyMethod를 호출하는지 확인합니다.
     */
    private fun isDependentMethod(
        srcMethod: PsiMethod,
        dependencyMethod: PsiMethod,
        methodCallGraph: Map<PsiMethod, Set<PsiMethod>>
    ): Boolean {
        val visited = mutableSetOf<PsiMethod>()

        fun checkDependency(current: PsiMethod): Boolean {
            if (current in visited) return false
            visited.add(current)

            val calledMethods = methodCallGraph[current].orEmpty()
            if (dependencyMethod in calledMethods) return true

            return calledMethods.any { checkDependency(it) }
        }

        return checkDependency(srcMethod)
    }
}