package org.example.emm

import com.intellij.psi.PsiClass
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
class MovableMethodFinder {

    /**
     * 주어진 메소드의 의존성을 분석하고, 각 의존성 메소드가 이동 가능한지 여부를 판단합니다.
     * 이동 가능한 메소드는 다음 조건을 만족하는 메소드입니다.
     *
     * - 타겟 메소드는 무조건 이동 가능
     * - 외부 클래스에서 참조되지 않음
     * - 메소드 그룹 내에서 이동 가능한 메소드에 의해서만 참조됨.
     */
    fun findMethodGroupWithCanMove(
        targetMethod: PsiMethod
    ): Map<PsiMethod, Boolean> {
        val methodGroup = createMethodGroup(targetMethod)
        val resultsCache = mapOf(targetMethod to true).toMutableMap()

        return methodGroup.associateWith { method ->
            canMove(method, methodGroup, resultsCache)
        }
    }

    /**
     * 타겟 메소드와 그 메소드에서 직간접적으로 호출되는 모든 내부 메소드들을 찾아 세트로 반환합니다.
     *
     * @param targetMethod 시작점이 되는 메소드
     * @param methodCallGraph 메소드 호출 관계를 표현하는 그래프
     * @return 타겟 메소드와 연관된 모든 메소드들의 집합
     */
    private fun createMethodGroup(
        targetMethod: PsiMethod
    ): Set<PsiMethod> {
        val methodCallGraph = createCallGraph(targetMethod.containingClass!!)
        val reachableFromTarget = mutableSetOf<PsiMethod>()
        reachableFromTarget.add(targetMethod) // 타겟 메소드도 포함

        fun collectReachableMethods(currentMethod: PsiMethod, visited: MutableSet<PsiMethod>) {
            if (currentMethod in visited) return
            visited.add(currentMethod)

            val calledMethods = methodCallGraph[currentMethod].orEmpty()
            for (calledMethod in calledMethods) {
                reachableFromTarget.add(calledMethod)
                collectReachableMethods(calledMethod, visited)
            }
        }

        collectReachableMethods(targetMethod, mutableSetOf())
        return reachableFromTarget
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
     * 메소드가 대상 클래스로 이동 가능한지 확인합니다.
     */
    private fun canMove(
        method: PsiMethod,
        methodGroup: Set<PsiMethod> = emptySet(),
        resultsCache: MutableMap<PsiMethod, Boolean> = mutableMapOf()
    ): Boolean {
        // 결과 캐시에 이미 있으면 저장된 결과 반환
        if (method in resultsCache) {
            return resultsCache[method]!!
        }

        // 순환 참조 감지를 위해 진행 중인 메서드로 표시
        // 임시로 true 설정 (나중에 실제 결과로 덮어씀)
        resultsCache[method] = true

        val sourceClass = method.containingClass ?: run {
            resultsCache[method] = false
            return false
        }

        // 외부 클래스에서 참조되는지 확인
        val references = ReferencesSearch.search(method, GlobalSearchScope.projectScope(method.project)).findAll()

        for (reference in references) {
            val referenceClass = reference.element.parentOfType<PsiClass>(false)
            if (referenceClass != null && referenceClass != sourceClass) {
                resultsCache[method] = false
                return false // 외부 참조가 있으면 이동 불가
            }

            val referenceMethod = reference.element.parentOfType<PsiMethod>(false)
            if (referenceMethod != null && referenceMethod != method) {
                if (referenceMethod !in methodGroup) {
                    resultsCache[method] = false
                    return false // methodGroup 외부에서 참조되면 이동 불가
                }

                // methodGroup 내에서 참조하지만 참조 메소드가 이동 불가능하면 이동 불가
                if (!canMove(referenceMethod, methodGroup, resultsCache)) {
                    resultsCache[method] = false
                    return false
                }
            }
        }

        // 모든 검사를 통과했으므로 이동 가능
        resultsCache[method] = true
        return true
    }

}