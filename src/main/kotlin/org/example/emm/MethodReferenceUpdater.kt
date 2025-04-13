package org.example.emm

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.*

/**
 * 메소드 참조 업데이트를 담당하는 클래스
 */
class MethodReferenceUpdater(project: Project) {

    private val factory: PsiElementFactory = JavaPsiFacade.getElementFactory(project)
    private val fieldInjector = FieldInjector(project)

    // 메소드 참조 업데이트
    fun updateMethodReferences(
        reference: MethodReferenceInfo,
        targetClass: PsiClass,
        accessModifier: String
    ) {
        // 타겟 클래스 내부에서의 호출인 경우 한정자 없이 직접 호출로 변경
        if (isReferenceInTargetClass(reference.containingClass, targetClass)) {
            updateMethodReferenceToInternalCall(reference.element)
            return
        }

        // 필드 주입이 필요한 경우에만 필드 추가 및 임포트 추가
        fieldInjector.injectFieldIfNeeded(reference.containingClass, targetClass, accessModifier)

        // 메소드 호출 업데이트
        updateMethodReference(reference.element, (targetClass.name)!!.decapitalize())
    }

    // 메소드 참조 업데이트
    fun simpleUpdateMethodReferences(
        reference: MethodReferenceInfo,
        targetClass: PsiClass,
        accessModifier: String
    ) {
        val resolveMethod = reference.element.resolveMethod()
        makeMethodPublic(resolveMethod!!)

        // 메소드 호출 업데이트
        updateMethodReference(reference.element, (targetClass.name)!!.decapitalize())
    }

    private fun makeMethodPublic(method: PsiMethod) {
        val modifierList = method.modifierList
        if (!modifierList.hasModifierProperty(PsiModifier.PUBLIC)) {
            modifierList.setModifierProperty(PsiModifier.PUBLIC, true)
        }
    }

    // 참조가 타겟 클래스 내에 있는지 확인하는 메소드
    private fun isReferenceInTargetClass(referenceClass: PsiClass, targetClass: PsiClass): Boolean {
        // 클래스 이름 기준으로 동일한지 확인
        if (referenceClass.qualifiedName == targetClass.qualifiedName) {
            return true
        }

        // 내부 클래스인 경우도 확인
        val referenceOuterClass = referenceClass.containingClass
        return if (referenceOuterClass != null) {
            isReferenceInTargetClass(referenceOuterClass, targetClass)
        } else {
            false
        }
    }

    // 내부 호출로 메소드 참조 업데이트
    private fun updateMethodReferenceToInternalCall(methodReference: PsiMethodCallExpression) {
        val oldExpr = methodReference.methodExpression
        val methodName = oldExpr.referenceName ?: return

        // 단순 메소드 이름으로 직접 호출 표현식 생성
        val newExprText = methodName
        val newExpr = factory.createExpressionFromText(newExprText, methodReference)

        // 메소드 표현식 업데이트
        oldExpr.replace(newExpr)
    }

    // 메소드 호출 표현식 업데이트
    private fun updateMethodReference(
        methodReference: PsiMethodCallExpression,
        newQualifier: String
    ) {
        val oldExpr = methodReference.methodExpression
        val methodName = oldExpr.referenceName ?: return

        // 새 메소드 호출 표현식 생성
        val newExprText = "$newQualifier.$methodName"
        val newExpr = factory.createExpressionFromText(newExprText, methodReference)

        // 메소드 표현식 업데이트
        oldExpr.replace(newExpr)
    }

    // 문자열의 첫 글자를 소문자로 변환하는 확장 함수
    private fun String.decapitalize(): String {
        return if (isEmpty() || !first().isUpperCase()) this
        else first().lowercaseChar() + substring(1)
    }
}