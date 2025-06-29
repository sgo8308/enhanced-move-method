package org.example.emm

import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiUtil
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

    /**
     * 메소드에서 사용된 클래스 수집
     */
     fun collectUsedClasses(method: PsiMethod): Set<PsiClass> {
        val usedClasses = mutableSetOf<PsiClass>()

        // 반환 타입
        method.returnType?.let { returnType ->
            if (returnType is PsiClassType) {
                PsiUtil.resolveClassInType(returnType)?.let { usedClasses.add(it) }
                returnType.parameters.mapNotNull { parameterType ->
                    PsiUtil.resolveClassInType(parameterType)
                }.forEach { usedClasses.add(it) }
            }
        }

        // 매개변수 타입
        method.parameterList.parameters.forEach { param ->
            val paramType = param.type
            if (paramType is PsiClassType) {
                PsiUtil.resolveClassInType(paramType)?.let { usedClasses.add(it) }
                paramType.parameters.mapNotNull { parameterType ->
                    PsiUtil.resolveClassInType(parameterType)
                }.forEach { usedClasses.add(it) }
            }
        }
        // throws 예외 타입
        method.throwsList.referencedTypes.forEach { type ->
            type.resolve()?.let { usedClasses.add(it) }
        }

        // 메소드 내부에서 사용된 클래스
        method.body?.let { body ->
            body.accept(object : JavaRecursiveElementVisitor() {
                override fun visitReferenceExpression(expression: PsiReferenceExpression) {
                    super.visitReferenceExpression(expression)
                    expression.resolve()?.let { resolved ->
                        if (resolved is PsiClass) {
                            usedClasses.add(resolved)
                        }
                    }
                }

                override fun visitTypeElement(typeElement: PsiTypeElement) {
                    super.visitTypeElement(typeElement)
                    PsiUtil.resolveClassInType(typeElement.type)?.let { usedClasses.add(it) }
                }

                override fun visitNewExpression(expression: PsiNewExpression) {
                    super.visitNewExpression(expression)
                    expression.classReference?.resolve()?.let { resolved ->
                        if (resolved is PsiClass) {
                            usedClasses.add(resolved)
                        }
                    }
                }
            })
        }

        return usedClasses
    }
}